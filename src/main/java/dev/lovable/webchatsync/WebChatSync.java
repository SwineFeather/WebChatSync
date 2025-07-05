package dev.lovable.webchatsync;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.NamedTextColor;
import me.clip.placeholderapi.PlaceholderAPI;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import com.palmergames.bukkit.towny.event.NewTownEvent;

public class WebChatSync extends JavaPlugin implements Listener {
    private CustomWebSocketServer webSocketServer;
    private String chatFormat;
    private String webPrefix;
    private String playerColor;
    private String messageColor;
    private String privateColor;
    private String joinMessageFormat;
    private String leaveMessageFormat;
    private boolean joinLeaveEnabled;
    private boolean tablistEnabled;
    private String tablistWebPrefix;
    private String helpMessage;
    private String webUsersMessage;
    private String muteMessage;
    private int muteDurationDefault;
    private boolean eventsEnabled;
    private String deathMessage;
    private String townCreatedMessage;
    private boolean debug;
    private boolean placeholderApiEnabled;
    private List<String> placeholderList;
    private final Map<String, Long> mutedPlayers = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        startWebSocketServer();
        if (tablistEnabled) {
            updateTablist();
        }
    }

    @Override
    public void onDisable() {
        if (webSocketServer != null) {
            try {
                webSocketServer.stop();
            } catch (InterruptedException e) {
                getLogger().log(Level.WARNING, "Error stopping WebSocket server: {0}", e.getMessage());
            }
        }
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        chatFormat = config.getString("chat.format", "{prefix} {player}: {message}");
        webPrefix = config.getString("chat.web-prefix", "<blue>[Web]");
        playerColor = config.getString("chat.player-color", "<aqua>");
        messageColor = config.getString("chat.message-color", "<white>");
        privateColor = config.getString("chat.private-color", "<light_purple>");
        tablistEnabled = config.getBoolean("tablist.enabled", true);
        tablistWebPrefix = config.getString("tablist.web-prefix", "<blue>[Web] ");
        helpMessage = config.getString("help.message", "<gold>WebChatSync Commands:\n<gray>/webchatsync [reload|ban|unban|mute|unmute] [player] - Manage plugin\n<gray>/webpm <player> <message> - Send private message\n<gray>/webusers - Show web user count\n<gray>/help - Show this message");
        webUsersMessage = config.getString("webusers.message", "<gray>{count} web users online");
        muteMessage = config.getString("mute.message", "<red>{player} has been muted for {duration} minutes");
        muteDurationDefault = config.getInt("mute.duration-default", 60);
        eventsEnabled = config.getBoolean("events.enabled", true);
        deathMessage = config.getString("events.death", "<red>{PLAYER} died!");
        townCreatedMessage = config.getString("events.town-created", "<green>{PLAYER} created a new town: {TOWN}!");
        debug = config.getBoolean("debug", false);
        placeholderApiEnabled = config.getBoolean("placeholderapi.enabled", false) && 
                               getServer().getPluginManager().getPlugin("PlaceholderAPI") != null;
        placeholderList = config.getStringList("placeholderapi.placeholders");
    }

    private void startWebSocketServer() {
        FileConfiguration config = getConfig();
        int port = config.getInt("websocket.port", 8082);
        String token = config.getString("websocket.token", "your-secure-token-here");
        try {
            webSocketServer = new CustomWebSocketServer(port, token, this);
            webSocketServer.start();
            getLogger().info(String.format("WebSocket server started on port %d", port));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to start WebSocket server: {0}", e.getMessage());
        }
    }

    public void updateTablist() {
        if (!tablistEnabled || webSocketServer == null) return;
        for (Player player : getServer().getOnlinePlayers()) {
            // Only add web prefix to players who are connected via web
            if (webSocketServer.isPlayerConnectedViaWeb(player.getName())) {
                Component displayName = Component.text(tablistWebPrefix + player.getName());
                player.playerListName(displayName);
            } else {
                // Reset to normal display name for in-game players
                player.playerListName(Component.text(player.getName()));
            }
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        String message = event.getMessage();
        if (isMuted(playerName)) {
            event.getPlayer().sendMessage(Component.text("You are muted and cannot send messages.").color(NamedTextColor.RED));
            event.setCancelled(true);
            return;
        }
        // For in-game players, use a different format without web prefix
        String inGameFormat = "{player}: {message}";
        String format = inGameFormat
            .replace("{player}", playerColor + playerName)
            .replace("{message}", messageColor + message);
        Component chatComponent = MiniMessage.miniMessage().deserialize(format);
        event.setCancelled(true);
        getServer().sendMessage(chatComponent);
        JSONObject json = new JSONObject();
        try {
            json.put("type", "chat")
                .put("player", playerName)
                .put("message", message);
            if (placeholderApiEnabled) {
                JSONObject placeholders = new JSONObject();
                for (String placeholder : placeholderList) {
                    String value = PlaceholderAPI.setPlaceholders(player, "%" + placeholder + "%");
                    placeholders.put(placeholder, value != null ? value : "null");
                }
                json.put("placeholders", placeholders);
            }
            if (webSocketServer != null) {
                webSocketServer.broadcast(json.toString());
            }
            if (debug) {
                getLogger().info(String.format("Broadcasted to WebSocket: %s", format));
            }
        } catch (JSONException e) {
            getLogger().warning(String.format("Error processing chat message: %s", e.getMessage()));
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (tablistEnabled) {
            Player player = event.getPlayer();
            // Don't add web prefix on join - it will be handled by updateTablist when web users connect
            player.playerListName(Component.text(player.getName()));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (tablistEnabled) {
            Player player = event.getPlayer();
            // Reset to normal display name when player quits
            player.playerListName(Component.text(player.getName()));
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!eventsEnabled) return;
        String playerName = event.getEntity().getName();
        String message = deathMessage.replace("{PLAYER}", playerName);
        Component deathComponent = MiniMessage.miniMessage().deserialize(message);
        getServer().sendMessage(deathComponent);
        if (webSocketServer != null) {
            try {
                webSocketServer.broadcast(new JSONObject()
                    .put("type", "death")
                    .put("player", playerName)
                    .put("message", playerName + " died!")
                    .toString());
            } catch (JSONException e) {
                getLogger().warning(String.format("Error broadcasting death event: %s", e.getMessage()));
            }
        }
    }

    @EventHandler
    public void onNewTown(NewTownEvent event) {
        if (!eventsEnabled) return;
        String playerName = event.getTown().getMayor().getName();
        String townName = event.getTown().getName();
        String message = townCreatedMessage.replace("{PLAYER}", playerName).replace("{TOWN}", townName);
        Component townComponent = MiniMessage.miniMessage().deserialize(message);
        getServer().sendMessage(townComponent);
        if (webSocketServer != null) {
            try {
                webSocketServer.broadcast(new JSONObject()
                    .put("type", "town-created")
                    .put("player", playerName)
                    .put("town", townName)
                    .put("message", playerName + " created a new town: " + townName)
                    .toString());
            } catch (JSONException e) {
                getLogger().warning(String.format("Error broadcasting town creation event: %s", e.getMessage()));
            }
        }
    }

    public void sendWebMessage(String playerName, String message, String role) {
        if (isMuted(playerName)) {
            if (webSocketServer != null) {
                webSocketServer.sendPrivateMessage("Server", playerName, "You are muted and cannot send messages.");
            }
            return;
        }
        String prefix = getConfig().getString("chat.role-prefixes." + role, webPrefix);
        String format = chatFormat
            .replace("{prefix}", prefix)
            .replace("{player}", playerColor + playerName)
            .replace("{message}", messageColor + message);
        Component messageComponent = MiniMessage.miniMessage().deserialize(format);
        getServer().sendMessage(messageComponent);
        if (debug) {
            getLogger().info(String.format("Received from WebSocket: %s", MiniMessage.miniMessage().serialize(messageComponent)));
        }
    }

    public void sendPrivateMessage(String sender, String recipient, String message) {
        if (isMuted(recipient)) {
            if (webSocketServer != null) {
                webSocketServer.sendPrivateMessage("Server", recipient, "You are muted and cannot receive messages.");
            }
            return;
        }
        String format = privateColor + "[PM from " + sender + "] " + message;
        Component messageComponent = MiniMessage.miniMessage().deserialize(format);
        if (webSocketServer != null) {
            webSocketServer.sendPrivateMessage(sender, recipient, message);
            getServer().getOnlinePlayers().stream()
                .filter(p -> p.getName().equals(recipient))
                .forEach(p -> p.sendMessage(messageComponent));
        }
        if (debug) {
            getLogger().info(String.format("Sent private message from %s to %s: %s", sender, recipient, message));
        }
    }

    public void handleReaction(String messageId, String playerName, String emoji) {
        Component formattedMessage = MiniMessage.miniMessage().deserialize(
            "<gray>" + playerName + " reacted with " + emoji
        );
        getServer().sendMessage(formattedMessage);
        if (webSocketServer != null) {
            try {
                webSocketServer.broadcast(new JSONObject()
                    .put("type", "reaction")
                    .put("messageId", messageId)
                    .put("player", playerName)
                    .put("emoji", emoji)
                    .toString());
            } catch (JSONException e) {
                getLogger().warning(String.format("Error broadcasting reaction: %s", e.getMessage()));
            }
        }
    }

    public void handlePin(String messageId, String playerName, boolean pinned) {
        Component formattedMessage = MiniMessage.miniMessage().deserialize(
            "<gray>" + playerName + (pinned ? " pinned" : " unpinned") + " a message"
        );
        getServer().sendMessage(formattedMessage);
        if (webSocketServer != null) {
            try {
                webSocketServer.broadcast(new JSONObject()
                    .put("type", "pin")
                    .put("messageId", messageId)
                    .put("player", playerName)
                    .put("pinned", pinned)
                    .toString());
            } catch (JSONException e) {
                getLogger().warning(String.format("Error broadcasting pin: %s", e.getMessage()));
            }
        }
    }

    public boolean isMuted(String playerName) {
        Long muteEndTime = mutedPlayers.get(playerName);
        if (muteEndTime == null) return false;
        if (System.currentTimeMillis() >= muteEndTime) {
            mutedPlayers.remove(playerName);
            getConfig().set("bans.muted", new ArrayList<>(mutedPlayers.keySet()));
            saveConfig();
            return false;
        }
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("webchatsync")) {
            if (!sender.hasPermission("webchatsync.admin")) {
                sender.sendMessage(Component.text("You don't have permission to use this command.").color(NamedTextColor.RED));
                return true;
            }
            if (args.length == 0) {
                sender.sendMessage(Component.text("Usage: /webchatsync [reload|ban|unban|mute|unmute|help] [player]").color(NamedTextColor.YELLOW));
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "reload" -> {
                    reloadConfig();
                    loadConfig();
                    sender.sendMessage(Component.text("WebChatSync configuration reloaded.").color(NamedTextColor.GREEN));
                    return true;
                }
                case "ban" -> {
                    if (args.length < 2) {
                        sender.sendMessage(Component.text("Usage: /webchatsync ban <player>").color(NamedTextColor.RED));
                        return true;
                    }
                    String player = args[1];
                    List<String> bannedPlayers = getConfig().getStringList("bans.players");
                    if (!bannedPlayers.contains(player)) {
                        bannedPlayers.add(player);
                        getConfig().set("bans.players", bannedPlayers);
                        saveConfig();
                        sender.sendMessage(Component.text("Banned web user: " + player).color(NamedTextColor.GREEN));
                        if (webSocketServer != null) {
                            webSocketServer.disconnectPlayer(player);
                        }
                    } else {
                        sender.sendMessage(Component.text(player + " is already banned.").color(NamedTextColor.YELLOW));
                    }
                    return true;
                }
                case "unban" -> {
                    if (args.length < 2) {
                        sender.sendMessage(Component.text("Usage: /webchatsync unban <player>").color(NamedTextColor.RED));
                        return true;
                    }
                    String player = args[1];
                    List<String> bannedPlayers = getConfig().getStringList("bans.players");
                    if (bannedPlayers.remove(player)) {
                        getConfig().set("bans.players", bannedPlayers);
                        saveConfig();
                        sender.sendMessage(Component.text("Unbanned web user: " + player).color(NamedTextColor.GREEN));
                    } else {
                        sender.sendMessage(Component.text(player + " is not banned.").color(NamedTextColor.YELLOW));
                    }
                    return true;
                }
                case "mute" -> {
                    if (args.length < 2) {
                        sender.sendMessage(Component.text("Usage: /webchatsync mute <player> [duration]").color(NamedTextColor.RED));
                        return true;
                    }
                    String player = args[1];
                    int duration = args.length > 2 ? Integer.parseInt(args[2]) : muteDurationDefault;
                    long muteEndTime = System.currentTimeMillis() + (duration * 60 * 1000L);
                    mutedPlayers.put(player, muteEndTime);
                    getConfig().set("bans.muted", new ArrayList<>(mutedPlayers.keySet()));
                    saveConfig();
                    String message = muteMessage.replace("{player}", player).replace("{duration}", String.valueOf(duration));
                    Component muteComponent = MiniMessage.miniMessage().deserialize(message);
                    getServer().sendMessage(muteComponent);
                    sender.sendMessage(Component.text("Muted web user: " + player + " for " + duration + " minutes").color(NamedTextColor.GREEN));
                    return true;
                }
                case "unmute" -> {
                    if (args.length < 2) {
                        sender.sendMessage(Component.text("Usage: /webchatsync unmute <player>").color(NamedTextColor.RED));
                        return true;
                    }
                    String player = args[1];
                    if (mutedPlayers.remove(player) != null) {
                        getConfig().set("bans.muted", new ArrayList<>(mutedPlayers.keySet()));
                        saveConfig();
                        sender.sendMessage(Component.text("Unmuted web user: " + player).color(NamedTextColor.GREEN));
                    } else {
                        sender.sendMessage(Component.text(player + " is not muted.").color(NamedTextColor.YELLOW));
                    }
                    return true;
                }
                case "help" -> {
                    Component helpComponent = MiniMessage.miniMessage().deserialize(helpMessage);
                    sender.sendMessage(helpComponent);
                    return true;
                }
                default -> {
                    sender.sendMessage(Component.text("Usage: /webchatsync [reload|ban|unban|mute|unmute|help] [player]").color(NamedTextColor.YELLOW));
                    return true;
                }
            }
        }
        if (command.getName().equalsIgnoreCase("webpm")) {
            if (!sender.hasPermission("webchatsync.pm")) {
                sender.sendMessage(Component.text("You don't have permission to use this command.").color(NamedTextColor.RED));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(Component.text("Usage: /webpm <player> <message>").color(NamedTextColor.RED));
                return true;
            }
            String recipient = args[0];
            String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            if (webSocketServer != null) {
                sendPrivateMessage(sender.getName(), recipient, message);
                sender.sendMessage(Component.text("Sent private message to " + recipient + ": " + message).color(NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text("WebSocket server is not running.").color(NamedTextColor.RED));
            }
            return true;
        }
        if (command.getName().equalsIgnoreCase("webusers")) {
            if (!sender.hasPermission("webchatsync.users")) {
                sender.sendMessage(Component.text("You don't have permission to use this command.").color(NamedTextColor.RED));
                return true;
            }
            int count = webSocketServer != null ? webSocketServer.getWebUserCount() : 0;
            String message = webUsersMessage.replace("{count}", String.valueOf(count));
            Component usersComponent = MiniMessage.miniMessage().deserialize(message);
            sender.sendMessage(usersComponent);
            return true;
        }
        return false;
    }
}