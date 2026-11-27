import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.java_websocket.WebSocket;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Manages live WebSocket sessions, per-server online presence, voice channel
 * participants, and scoped broadcasts backed by DatabaseManager.
 */
public class ServerState {

    public static class ConnectedClient {
        public final WebSocket conn;
        public final long userId;
        public final String username;
        public final boolean isGlobalAdmin;
        public volatile long activeServerId = -1;
        public volatile long activeTextChannelId = -1;
        public volatile long activeVoiceChannelId = -1;

        public ConnectedClient(WebSocket conn, long userId, String username, boolean isGlobalAdmin) {
            this.conn = conn;
            this.userId = userId;
            this.username = username;
            this.isGlobalAdmin = isGlobalAdmin;
        }
    }

    private final DatabaseManager db;
    private final Authentication auth;
    private final Map<WebSocket, ConnectedClient> activeClients = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> voiceChannels = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();

    public ServerState(DatabaseManager db, Authentication auth) {
        this.db = db;
        this.auth = auth;
    }

    public DatabaseManager getDb() {
        return db;
    }

    public Authentication getAuth() {
        return auth;
    }

    public Map<WebSocket, ConnectedClient> getActiveClients() {
        return activeClients;
    }

    public Map<Long, Set<String>> getVoiceChannels() {
        return voiceChannels;
    }

    public ConnectedClient getClient(WebSocket conn) {
        return activeClients.get(conn);
    }

    public void sendSafe(WebSocket conn, String payload) {
        if (conn != null && conn.isOpen()) {
            try {
                conn.send(payload);
            } catch (Exception ignored) {}
        }
    }

    public void sendError(WebSocket conn, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("type", "error");
        err.addProperty("message", message);
        sendSafe(conn, err.toString());
    }

    public JsonObject serverToJson(DatabaseManager.ServerRecord s, boolean canManage) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", s.id);
        obj.addProperty("name", s.name);
        if (s.ownerId != null) {
            obj.addProperty("ownerId", s.ownerId);
        }
        obj.addProperty("isDefault", s.isDefault);
        obj.addProperty("role", s.role);
        obj.addProperty("canManage", canManage);
        if (canManage) {
            obj.addProperty("inviteCode", s.inviteCode);
        }
        return obj;
    }

    public JsonObject channelToJson(DatabaseManager.ChannelRecord ch) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", ch.id);
        obj.addProperty("serverId", ch.serverId);
        obj.addProperty("name", ch.name);
        obj.addProperty("type", ch.type);
        return obj;
    }

    public JsonObject messageToJson(DatabaseManager.MessageRecord m, long serverId) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", m.id);
        obj.addProperty("serverId", serverId);
        obj.addProperty("channelId", m.channelId);
        obj.addProperty("userId", m.userId);
        obj.addProperty("username", m.username);
        obj.addProperty("message", m.content);
        obj.addProperty("timestamp", m.createdAt);
        return obj;
    }

    public JsonArray channelsToJson(List<DatabaseManager.ChannelRecord> channels) {
        JsonArray arr = new JsonArray();
        for (DatabaseManager.ChannelRecord ch : channels) {
            arr.add(channelToJson(ch));
        }
        return arr;
    }

    public JsonArray messagesToJson(List<DatabaseManager.MessageRecord> messages, long serverId) {
        JsonArray arr = new JsonArray();
        for (DatabaseManager.MessageRecord m : messages) {
            arr.add(messageToJson(m, serverId));
        }
        return arr;
    }

    public JsonArray membersToJson(List<DatabaseManager.ServerMemberRecord> members) {
        JsonArray arr = new JsonArray();
        for (DatabaseManager.ServerMemberRecord m : members) {
            JsonObject obj = new JsonObject();
            obj.addProperty("userId", m.userId);
            obj.addProperty("username", m.username);
            obj.addProperty("role", m.role);
            obj.addProperty("isGlobalAdmin", m.isGlobalAdmin);
            arr.add(obj);
        }
        return arr;
    }

    public List<String> getOnlineUsersInServer(long serverId) {
        Set<String> online = new LinkedHashSet<>();
        for (ConnectedClient client : activeClients.values()) {
            try {
                if (client.activeServerId == serverId || db.isServerMember(serverId, client.userId)) {
                    online.add(client.username);
                }
            } catch (SQLException ignored) {}
        }
        return new ArrayList<>(online);
    }

    public JsonObject buildVoiceMapForServer(List<DatabaseManager.ChannelRecord> channels) {
        Map<String, List<String>> map = new HashMap<>();
        for (DatabaseManager.ChannelRecord ch : channels) {
            if ("VOICE".equals(ch.type)) {
                Set<String> participants = voiceChannels.get(ch.id);
                map.put(String.valueOf(ch.id), participants == null ? List.of() : new ArrayList<>(participants));
            }
        }
        return gson.toJsonTree(map).getAsJsonObject();
    }

    public void broadcastServerUsers(long serverId) throws SQLException {
        List<String> online = getOnlineUsersInServer(serverId);
        List<DatabaseManager.ServerMemberRecord> members = db.getServerMembers(serverId);
        JsonObject out = new JsonObject();
        out.addProperty("type", "users");
        out.addProperty("serverId", serverId);
        out.add("list", gson.toJsonTree(online));
        out.add("members", membersToJson(members));
        String payload = out.toString();

        for (ConnectedClient client : activeClients.values()) {
            if (client.activeServerId == serverId) {
                sendSafe(client.conn, payload);
            }
        }
    }

    public void broadcastServerVoiceList(long serverId) throws SQLException {
        List<DatabaseManager.ChannelRecord> channels = db.getServerChannels(serverId);
        JsonObject out = new JsonObject();
        out.addProperty("type", "voice-users");
        out.addProperty("serverId", serverId);
        out.add("channels", buildVoiceMapForServer(channels));
        String payload = out.toString();

        for (ConnectedClient client : activeClients.values()) {
            if (client.activeServerId == serverId) {
                sendSafe(client.conn, payload);
            }
        }
    }

    public void broadcastServerUpdated(long serverId) throws SQLException {
        List<DatabaseManager.ChannelRecord> channels = db.getServerChannels(serverId);
        List<DatabaseManager.ServerMemberRecord> members = db.getServerMembers(serverId);
        List<String> online = getOnlineUsersInServer(serverId);
        JsonObject voiceMap = buildVoiceMapForServer(channels);

        for (ConnectedClient client : activeClients.values()) {
            if (client.activeServerId == serverId) {
                DatabaseManager.ServerRecord srv = db.getServerForUser(serverId, client.userId);
                if (srv == null) continue;
                boolean canManage = db.canManageServer(serverId, client.userId);

                JsonObject out = new JsonObject();
                out.addProperty("type", "server-updated");
                out.add("server", serverToJson(srv, canManage));
                out.add("channels", channelsToJson(channels));
                out.add("members", membersToJson(members));
                out.add("onlineUsers", gson.toJsonTree(online));
                out.add("voiceChannels", voiceMap);
                sendSafe(client.conn, out.toString());
            }
        }
    }

    public void broadcastChatMessage(long serverId, DatabaseManager.MessageRecord msg) {
        JsonObject out = messageToJson(msg, serverId);
        out.addProperty("type", "chat");
        String payload = out.toString();

        for (ConnectedClient client : activeClients.values()) {
            if (client.activeServerId == serverId) {
                sendSafe(client.conn, payload);
            }
        }
    }

    public void removeUserFromVoice(ConnectedClient client) {
        long oldChannelId = client.activeVoiceChannelId;
        if (oldChannelId == -1) return;
        client.activeVoiceChannelId = -1;

        // Only remove username from channel set if no other connection for this user is in the same voice channel
        boolean stillPresent = false;
        for (ConnectedClient other : activeClients.values()) {
            if (other != client && other.userId == client.userId && other.activeVoiceChannelId == oldChannelId) {
                stillPresent = true;
                break;
            }
        }
        if (!stillPresent) {
            Set<String> participants = voiceChannels.get(oldChannelId);
            if (participants != null) {
                participants.remove(client.username);
            }
            JsonObject left = new JsonObject();
            left.addProperty("type", "user-left-voice");
            left.addProperty("channelId", oldChannelId);
            left.addProperty("username", client.username);
            String payload = left.toString();

            for (ConnectedClient other : activeClients.values()) {
                if (other.activeVoiceChannelId == oldChannelId) {
                    sendSafe(other.conn, payload);
                }
            }
        }

        try {
            DatabaseManager.ChannelRecord ch = db.getChannelById(oldChannelId);
            if (ch != null) {
                broadcastServerVoiceList(ch.serverId);
            }
        } catch (SQLException ignored) {}
    }
}
