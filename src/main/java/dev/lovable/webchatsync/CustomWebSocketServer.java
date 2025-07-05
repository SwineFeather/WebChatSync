package dev.lovable.webchatsync;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.json.JSONObject;
import org.json.JSONException;

public class CustomWebSocketServer extends WebSocketServer {
    private final String token;
    private final WebChatSync plugin;
    private final Set<WebSocket> clients = Collections.synchronizedSet(new HashSet<>());
    private final Set<WebSocket> unauthenticated = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, WebSocket> playerConnections = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, String> userIdToPlayerName = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, Long> lastMessageTimes = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, Long> lastJoinTimes = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, Long> lastLeaveTimes = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, ScheduledFuture<?>> pendingLeaveTasks = Collections.synchronizedMap(new HashMap<>());
    private final String joinMessageFormat;
    private final String leaveMessageFormat;
    private final double rateLimitSeconds;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public CustomWebSocketServer(int port, String token, WebChatSync plugin) {
        super(new InetSocketAddress(port));
        this.token = token;
        this.plugin = plugin;
        this.joinMessageFormat = plugin.getConfig().getString("join-leave.join", "<green>[<aqua>+</aqua>] <blue>[Web] <gold>{PLAYER}");
        this.leaveMessageFormat = plugin.getConfig().getString("join-leave.leave", "<red>[<dark_red>-</dark_red>] <blue>[Web] <gold>{PLAYER}");
        this.rateLimitSeconds = plugin.getConfig().getDouble("chat.rate-limit-seconds", 1.0);
    }

    @Override
    public void onStart() {
        plugin.getLogger().info(String.format("WebSocket server started on port %d", getPort()));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        unauthenticated.add(conn);
        plugin.getLogger().info(String.format("WebSocket client connected (unauthenticated): %s", conn.getRemoteSocketAddress()));
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        final String[] playerNameHolder = {null};
        synchronized (playerConnections) {
            for (Map.Entry<String, WebSocket> entry : playerConnections.entrySet()) {
                if (entry.getValue() == conn) {
                    playerNameHolder[0] = entry.getKey();
                    playerConnections.remove(playerNameHolder[0]);
                    break;
                }
            }
        }
        final String playerName = playerNameHolder[0];
        String userId = null;
        if (playerName != null) {
            for (Map.Entry<String, String> entry : userIdToPlayerName.entrySet()) {
                if (entry.getValue().equals(playerName)) {
                    userId = entry.getKey();
                    break;
                }
            }
            if (userId != null) {
                userIdToPlayerName.remove(userId);
            }
            lastMessageTimes.remove(userId);
        }
        clients.remove(conn);
        unauthenticated.remove(conn);
        if (playerName != null && !playerName.isEmpty() && userId != null) {
            // Rate limit leave messages
            long currentTime = System.currentTimeMillis();
            Long lastLeaveTime = lastLeaveTimes.get(userId);
            if (lastLeaveTime != null && (currentTime - lastLeaveTime) < 5000) {
                plugin.getLogger().info(String.format("Leave rate limit exceeded for %s (userId: %s)", playerName, userId));
                return;
            }
            lastLeaveTimes.put(userId, currentTime);
            // Cancel any existing leave task
            ScheduledFuture<?> existingTask = pendingLeaveTasks.remove(userId);
            if (existingTask != null) {
                existingTask.cancel(false);
                plugin.getLogger().info(String.format("Cancelled pending leave task for %s (userId: %s)", playerName, userId));
            }
            // Schedule new leave task with 10-second delay
            final String finalPlayerName = playerName;
            final String finalUserId = userId;
            ScheduledFuture<?> leaveTask = scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    synchronized (playerConnections) {
                        if (!playerConnections.containsKey(finalPlayerName)) {
                            String leaveMessage = leaveMessageFormat.replace("{PLAYER}", finalPlayerName);
                            Component leaveComponent = MiniMessage.miniMessage().deserialize(leaveMessage);
                            plugin.getServer().sendMessage(leaveComponent);
                            broadcast(new JSONObject()
                                .put("type", "leave")
                                .put("player", finalPlayerName)
                                .put("message", finalPlayerName + " left the chat")
                                .toString());
                            plugin.getLogger().info(String.format("Broadcasted leave message for %s: %s", finalPlayerName, leaveMessage));
                        }
                    }
                    pendingLeaveTasks.remove(finalUserId);
                }
            }, 10, TimeUnit.SECONDS);
            pendingLeaveTasks.put(finalUserId, leaveTask);
        }
        plugin.getLogger().info(String.format("WebSocket client disconnected: %s (code: %d, reason: %s, player: %s, userId: %s)", 
            conn.getRemoteSocketAddress(), code, reason, playerName, userId));
        // Web users cannot appear in Minecraft tablist since they're not actual players
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JSONObject json = new JSONObject(message);
            plugin.getLogger().info(String.format("Received WebSocket message: %s", message));
            if (unauthenticated.contains(conn)) {
                if (!json.has("token") || !token.equals(json.getString("token"))) {
                    conn.close(1008, "Invalid authentication token");
                    plugin.getLogger().warning(String.format("Invalid token from %s", conn.getRemoteSocketAddress()));
                    return;
                }
                final String playerName = json.optString("player", "Unknown").trim();
                String userId = json.optString("userId", "").trim();
                final String finalPlayerName = playerName.isEmpty() ? "Unknown" : playerName;
                if (plugin.getConfig().getStringList("bans.players").contains(finalPlayerName)) {
                    conn.close(1008, "You are banned from web chat");
                    plugin.getLogger().warning(String.format("Banned player attempted to connect: %s", finalPlayerName));
                    return;
                }
                // Rate limit join messages
                long currentTime = System.currentTimeMillis();
                Long lastJoinTime = lastJoinTimes.get(userId);
                if (lastJoinTime != null && (currentTime - lastJoinTime) < 5000) {
                    conn.close(1008, "Join rate limit exceeded");
                    plugin.getLogger().warning(String.format("Join rate limit exceeded for %s (userId: %s)", finalPlayerName, userId));
                    return;
                }
                lastJoinTimes.put(userId, currentTime);
                // Cancel any pending leave task
                ScheduledFuture<?> existingTask = pendingLeaveTasks.remove(userId);
                if (existingTask != null) {
                    existingTask.cancel(false);
                    plugin.getLogger().info(String.format("Cancelled pending leave task for %s (userId: %s) due to reconnect", finalPlayerName, userId));
                }
                synchronized (playerConnections) {
                    if (playerConnections.containsKey(finalPlayerName)) {
                        WebSocket existingConn = playerConnections.get(finalPlayerName);
                        existingConn.close(1008, "Duplicate connection for player");
                        clients.remove(existingConn);
                        plugin.getLogger().info(String.format("Closed duplicate connection for %s (userId: %s) from %s", 
                            finalPlayerName, userId, existingConn.getRemoteSocketAddress()));
                    }
                    playerConnections.put(finalPlayerName, conn);
                    if (!userId.isEmpty()) {
                        userIdToPlayerName.put(userId, finalPlayerName);
                    }
                }
                unauthenticated.remove(conn);
                clients.add(conn);
                if (plugin.getConfig().getBoolean("join-leave.enabled", true)) {
                    String joinMessage = joinMessageFormat.replace("{PLAYER}", finalPlayerName);
                    Component joinComponent = MiniMessage.miniMessage().deserialize(joinMessage);
                    plugin.getServer().sendMessage(joinComponent);
                    broadcast(new JSONObject()
                        .put("type", "join")
                        .put("player", finalPlayerName)
                        .put("message", finalPlayerName + " joined the chat")
                        .toString());
                }
                plugin.getLogger().info(String.format("WebSocket client authenticated: %s as %s (userId: %s)", 
                    conn.getRemoteSocketAddress(), finalPlayerName, userId));
                // Web users cannot appear in Minecraft tablist since they're not actual players
                return;
            }

            String playerName = json.getString("player").trim();
            String userId = json.optString("userId", "").trim();
            if (plugin.getConfig().getStringList("bans.players").contains(playerName)) {
                conn.send("You are banned from web chat");
                plugin.getLogger().warning(String.format("Banned player attempted to send message: %s (userId: %s)", playerName, userId));
                return;
            }

            if (json.has("type")) {
                String type = json.getString("type");
                switch (type) {
                    case "message" -> {
                        // Rate limiting
                        long currentTime = System.currentTimeMillis();
                        Long lastMessageTime = lastMessageTimes.get(userId);
                        if (lastMessageTime != null && (currentTime - lastMessageTime) < (rateLimitSeconds * 1000)) {
                            conn.send("Rate limit exceeded. Please wait before sending another message.");
                            plugin.getLogger().warning(String.format("Rate limit exceeded for %s (userId: %s)", playerName, userId));
                            return;
                        }
                        lastMessageTimes.put(userId, currentTime);

                        String chatMessage = json.getString("message").trim();
                        String role = json.optString("role", "default").trim();
                        if (playerName.isEmpty() || chatMessage.isEmpty()) {
                            conn.send("Player name and message cannot be empty");
                            plugin.getLogger().warning(String.format("Empty player name or message from %s (userId: %s)", playerName, userId));
                            return;
                        }
                        plugin.sendWebMessage(playerName, chatMessage, role);
                    }
                    case "reaction" -> {
                        String messageId = json.getString("messageId");
                        String emoji = json.getString("emoji");
                        plugin.handleReaction(messageId, playerName, emoji);
                    }
                    case "pin" -> {
                        String messageId = json.getString("messageId");
                        boolean pinned = json.getBoolean("pinned");
                        plugin.handlePin(messageId, playerName, pinned);
                    }
                    case "private" -> {
                        String sender = json.getString("sender");
                        String privateMessage = json.getString("message");
                        plugin.sendPrivateMessage(sender, playerName, privateMessage);
                    }
                    default -> {
                        conn.send("Invalid message type. Expected: message, reaction, pin, private");
                        plugin.getLogger().warning(String.format("Invalid message type from %s (userId: %s): %s", playerName, userId, type));
                    }
                }
            } else {
                conn.send("Invalid message format. Expected: {\"type\": \"message\", \"player\": \"name\", \"message\": \"text\"}");
                plugin.getLogger().warning(String.format("Invalid message format from %s", conn.getRemoteSocketAddress()));
            }
        } catch (JSONException e) {
            plugin.getLogger().warning(String.format("Error processing WebSocket message: %s", e.getMessage()));
            conn.send("Error processing message: " + e.getMessage());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        plugin.getLogger().warning(String.format("WebSocket error: %s", ex.getMessage()));
    }

    public void broadcast(String message) {
        synchronized (clients) {
            for (WebSocket client : clients) {
                if (client.isOpen()) {
                    client.send(message);
                }
            }
        }
    }

    public void sendPrivateMessage(String sender, String recipient, String message) {
        synchronized (playerConnections) {
            WebSocket recipientConn = playerConnections.get(recipient);
            if (recipientConn != null && recipientConn.isOpen()) {
                try {
                    recipientConn.send(new JSONObject()
                        .put("type", "private")
                        .put("sender", sender)
                        .put("message", message)
                        .toString());
                    plugin.getLogger().info(String.format("Sent private message from %s to %s: %s", sender, recipient, message));
                } catch (JSONException e) {
                    plugin.getLogger().warning(String.format("Error sending private message to %s: %s", recipient, e.getMessage()));
                }
            } else {
                plugin.getLogger().warning(String.format("Recipient %s not found or not connected for private message", recipient));
            }
        }
    }

    public int getWebUserCount() {
        return playerConnections.size();
    }

    public boolean isPlayerConnectedViaWeb(String playerName) {
        synchronized (playerConnections) {
            return playerConnections.containsKey(playerName);
        }
    }

    public void disconnectPlayer(String playerName) {
        synchronized (playerConnections) {
            WebSocket conn = playerConnections.get(playerName);
            if (conn != null) {
                conn.close(1008, "You have been banned");
                clients.remove(conn);
                playerConnections.remove(playerName);
                String userId = userIdToPlayerName.entrySet().stream()
                    .filter(e -> e.getValue().equals(playerName))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
                if (userId != null) {
                    userIdToPlayerName.remove(userId);
                    lastMessageTimes.remove(userId);
                    lastJoinTimes.remove(userId);
                    lastLeaveTimes.remove(userId);
                    ScheduledFuture<?> existingTask = pendingLeaveTasks.remove(userId);
                    if (existingTask != null) {
                        existingTask.cancel(false);
                        plugin.getLogger().info(String.format("Cancelled pending leave task for banned player: %s (userId: %s)", playerName, userId));
                    }
                }
                plugin.getLogger().info(String.format("Disconnected banned player: %s", playerName));
                // Web users cannot appear in Minecraft tablist since they're not actual players
            }
        }
    }

    @Override
    public void stop() throws InterruptedException {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            throw e;
        }
        super.stop();
    }
}