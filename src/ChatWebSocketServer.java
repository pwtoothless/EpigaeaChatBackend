import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * WebSocket server handling authenticated Discord-style server/channel
 * messaging, server creation, room-code invites, channel management, and WebRTC signaling.
 */
public class ChatWebSocketServer extends WebSocketServer {

    private static final int HISTORY_LIMIT = 100;
    private final ServerState state;
    private final Gson gson = new Gson();

    public ChatWebSocketServer(int port, ServerState state) {
        super(new InetSocketAddress(port));
        this.state = state;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {}

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        ServerState.ConnectedClient client = state.getActiveClients().remove(conn);
        if (client != null) {
            state.removeUserFromVoice(client);
            long serverId = client.activeServerId;
            if (serverId != -1) {
                try {
                    state.broadcastServerUsers(serverId);
                } catch (SQLException ignored) {}
            }
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            if (!json.has("type") || json.get("type").isJsonNull()) {
                state.sendError(conn, "Missing message type");
                return;
            }
            String type = json.get("type").getAsString();

            if ("join".equals(type)) {
                handleJoin(conn, json);
                return;
            }

            ServerState.ConnectedClient client = state.getClient(conn);
            if (client == null) {
                state.sendError(conn, "Not authenticated");
                return;
            }

            switch (type) {
                case "select-server" -> handleSelectServer(client, json);
                case "select-channel" -> handleSelectChannel(client, json);
                case "create-server" -> handleCreateServer(client, json);
                case "join-server" -> handleJoinServer(client, json);
                case "regenerate-invite" -> handleRegenerateInvite(client, json);
                case "create-channel" -> handleCreateChannel(client, json);
                case "delete-channel" -> handleDeleteChannel(client, json);
                case "chat" -> handleChat(client, json);
                case "join-voice" -> handleJoinVoice(client, json);
                case "leave-voice" -> state.removeUserFromVoice(client);
                case "webrtc-offer", "webrtc-answer", "webrtc-ice" -> handleWebRtc(client, json);
                default -> state.sendError(conn, "Unknown message type: " + type);
            }
        } catch (Exception e) {
            state.sendError(conn, e.getMessage() != null ? e.getMessage() : "Invalid request");
        }
    }

    private void handleJoin(WebSocket conn, JsonObject json) throws SQLException {
        String token = json.has("token") && !json.get("token").isJsonNull()
                ? json.get("token").getAsString() : null;
        Authentication.SessionInfo session = state.getAuth().validateToken(token);
        if (session == null) {
            state.sendError(conn, "Invalid or expired session token");
            conn.close();
            return;
        }

        ServerState.ConnectedClient client = new ServerState.ConnectedClient(
                conn, session.userId, session.username, session.isGlobalAdmin);
        state.getActiveClients().put(conn, client);

        DatabaseManager db = state.getDb();
        List<DatabaseManager.ServerRecord> servers = db.getUserServers(client.userId);
        if (!servers.isEmpty()) {
            DatabaseManager.ServerRecord first = servers.get(0);
            client.activeServerId = first.id;
            List<DatabaseManager.ChannelRecord> channels = db.getServerChannels(first.id);
            long firstTextId = findFirstTextChannelId(channels);
            client.activeTextChannelId = firstTextId;

            JsonObject init = buildServerPayload("init", client, first, channels, firstTextId);
            JsonObject userObj = new JsonObject();
            userObj.addProperty("id", client.userId);
            userObj.addProperty("username", client.username);
            userObj.addProperty("isGlobalAdmin", client.isGlobalAdmin);
            init.add("user", userObj);
            init.add("servers", buildServersArray(servers, client.userId));

            state.sendSafe(conn, init.toString());
            state.broadcastServerUsers(first.id);
            state.broadcastServerVoiceList(first.id);
        } else {
            JsonObject init = new JsonObject();
            init.addProperty("type", "init");
            JsonObject userObj = new JsonObject();
            userObj.addProperty("id", client.userId);
            userObj.addProperty("username", client.username);
            userObj.addProperty("isGlobalAdmin", client.isGlobalAdmin);
            init.add("user", userObj);
            init.add("servers", new JsonArray());
            state.sendSafe(conn, init.toString());
        }
    }

    private void handleSelectServer(ServerState.ConnectedClient client, JsonObject json) throws SQLException {
        long serverId = json.get("serverId").getAsLong();
        DatabaseManager db = state.getDb();
        DatabaseManager.ServerRecord srv = db.getServerForUser(serverId, client.userId);
        if (srv == null) {
            state.sendError(client.conn, "You are not a member of that server");
            return;
        }
        long oldServerId = client.activeServerId;
        client.activeServerId = serverId;
        List<DatabaseManager.ChannelRecord> channels = db.getServerChannels(serverId);
        long firstTextId = findFirstTextChannelId(channels);
        client.activeTextChannelId = firstTextId;

        JsonObject payload = buildServerPayload("server-state", client, srv, channels, firstTextId);
        state.sendSafe(client.conn, payload.toString());

        if (oldServerId != -1 && oldServerId != serverId) {
            state.broadcastServerUsers(oldServerId);
        }
        state.broadcastServerUsers(serverId);
        state.broadcastServerVoiceList(serverId);
    }

    private void handleSelectChannel(ServerState.ConnectedClient client, JsonObject json) throws SQLException {
        long channelId = json.get("channelId").getAsLong();
        DatabaseManager db = state.getDb();
        DatabaseManager.ChannelRecord ch = db.getChannelById(channelId);
        if (ch == null || !"TEXT".equals(ch.type)) {
            state.sendError(client.conn, "Text channel not found");
            return;
        }
        if (!db.isServerMember(ch.serverId, client.userId)) {
            state.sendError(client.conn, "You are not a member of this server");
            return;
        }
        client.activeServerId = ch.serverId;
        client.activeTextChannelId = ch.id;

        List<DatabaseManager.MessageRecord> history = db.getChannelMessages(ch.id, client.userId, HISTORY_LIMIT);
        JsonObject out = new JsonObject();
        out.addProperty("type", "channel-history");
        out.addProperty("serverId", ch.serverId);
        out.addProperty("channelId", ch.id);
        out.add("messages", state.messagesToJson(history, ch.serverId));
        state.sendSafe(client.conn, out.toString());
    }

    private void handleCreateServer(ServerState.ConnectedClient client, JsonObject json) throws SQLException {
        String name = json.has("name") && !json.get("name").isJsonNull()
                ? json.get("name").getAsString() : null;
        DatabaseManager db = state.getDb();
        DatabaseManager.ServerRecord created = db.createServer(client.userId, name);
        long oldServerId = client.activeServerId;
        client.activeServerId = created.id;

        List<DatabaseManager.ChannelRecord> channels = db.getServerChannels(created.id);
        long firstTextId = findFirstTextChannelId(channels);
        client.activeTextChannelId = firstTextId;

        JsonObject payload = buildServerPayload("server-state", client, created, channels, firstTextId);
        payload.add("servers", buildServersArray(db.getUserServers(client.userId), client.userId));
        state.sendSafe(client.conn, payload.toString());

        if (oldServerId != -1) {
            state.broadcastServerUsers(oldServerId);
        }
    }

    private void handleJoinServer(ServerState.ConnectedClient client, JsonObject json) throws SQLException {
        String inviteCode = json.has("inviteCode") && !json.get("inviteCode").isJsonNull()
                ? json.get("inviteCode").getAsString() : null;
        DatabaseManager db = state.getDb();
        DatabaseManager.JoinServerResult joined = db.joinServerByCode(client.userId, inviteCode);

        long oldServerId = client.activeServerId;
        client.activeServerId = joined.server.id;
        List<DatabaseManager.ChannelRecord> channels = db.getServerChannels(joined.server.id);
        long firstTextId = findFirstTextChannelId(channels);
        client.activeTextChannelId = firstTextId;

        JsonObject payload = buildServerPayload("server-state", client, joined.server, channels, firstTextId);
        payload.add("servers", buildServersArray(db.getUserServers(client.userId), client.userId));
        payload.addProperty("newlyJoined", joined.newlyJoined);
        state.sendSafe(client.conn, payload.toString());

        if (oldServerId != -1 && oldServerId != joined.server.id) {
            state.broadcastServerUsers(oldServerId);
        }
        if (joined.newlyJoined) {
            state.broadcastServerUpdated(joined.server.id);
        }
        state.broadcastServerUsers(joined.server.id);
        state.broadcastServerVoiceList(joined.server.id);
    }

    private void handleRegenerateInvite(ServerState.ConnectedClient client, JsonObject json) throws SQLException {
        long serverId = json.get("serverId").getAsLong();
        String newCode = state.getDb().regenerateInviteCode(serverId, client.userId);
        JsonObject out = new JsonObject();
        out.addProperty("type", "invite-updated");
        out.addProperty("serverId", serverId);
        out.addProperty("inviteCode", newCode);

        for (ServerState.ConnectedClient c : state.getActiveClients().values()) {
            if (c.activeServerId == serverId && state.getDb().canManageServer(serverId, c.userId)) {
                state.sendSafe(c.conn, out.toString());
            }
        }
    }

    private void handleCreateChannel(ServerState.ConnectedClient client, JsonObject json)
            throws SQLException, DatabaseManager.DuplicateChannelException {
        long serverId = json.get("serverId").getAsLong();
        String name = json.has("name") && !json.get("name").isJsonNull()
                ? json.get("name").getAsString() : null;
        String channelType = json.has("channelType") && !json.get("channelType").isJsonNull()
                ? json.get("channelType").getAsString() : null;

        state.getDb().createChannel(serverId, client.userId, name, channelType);
        state.broadcastServerUpdated(serverId);
    }

    private void handleDeleteChannel(ServerState.ConnectedClient client, JsonObject json) throws SQLException {
        long serverId = json.get("serverId").getAsLong();
        long channelId = json.get("channelId").getAsLong();
        DatabaseManager db = state.getDb();

        DatabaseManager.ChannelRecord deleted = db.deleteChannel(serverId, channelId, client.userId);

        if ("VOICE".equals(deleted.type)) {
            state.getVoiceChannels().remove(channelId);
            for (ServerState.ConnectedClient c : state.getActiveClients().values()) {
                if (c.activeVoiceChannelId == channelId) {
                    c.activeVoiceChannelId = -1;
                    JsonObject kicked = new JsonObject();
                    kicked.addProperty("type", "voice-kicked");
                    kicked.addProperty("channelId", channelId);
                    state.sendSafe(c.conn, kicked.toString());
                }
            }
        } else {
            List<DatabaseManager.ChannelRecord> remaining = db.getServerChannels(serverId);
            long fallbackId = findFirstTextChannelId(remaining);
            for (ServerState.ConnectedClient c : state.getActiveClients().values()) {
                if (c.activeServerId == serverId && c.activeTextChannelId == channelId) {
                    c.activeTextChannelId = fallbackId;
                    if (fallbackId != -1) {
                        List<DatabaseManager.MessageRecord> history = db.getChannelMessages(fallbackId, c.userId, HISTORY_LIMIT);
                        JsonObject histOut = new JsonObject();
                        histOut.addProperty("type", "channel-history");
                        histOut.addProperty("serverId", serverId);
                        histOut.addProperty("channelId", fallbackId);
                        histOut.add("messages", state.messagesToJson(history, serverId));
                        state.sendSafe(c.conn, histOut.toString());
                    }
                }
            }
        }

        state.broadcastServerUpdated(serverId);
    }

    private void handleChat(ServerState.ConnectedClient client, JsonObject json) throws SQLException {
        long channelId = json.has("channelId") && !json.get("channelId").isJsonNull()
                ? json.get("channelId").getAsLong() : client.activeTextChannelId;
        String content = json.has("message") && !json.get("message").isJsonNull()
                ? json.get("message").getAsString() : null;

        DatabaseManager db = state.getDb();
        DatabaseManager.ChannelRecord ch = db.getChannelById(channelId);
        if (ch == null || !"TEXT".equals(ch.type)) {
            state.sendError(client.conn, "Text channel does not exist");
            return;
        }
        DatabaseManager.MessageRecord saved = db.saveMessage(channelId, client.userId, client.username, content);
        state.broadcastChatMessage(ch.serverId, saved);
    }

    private void handleJoinVoice(ServerState.ConnectedClient client, JsonObject json) throws SQLException {
        long channelId = json.get("channelId").getAsLong();
        DatabaseManager db = state.getDb();
        DatabaseManager.ChannelRecord ch = db.getChannelById(channelId);
        if (ch == null || !"VOICE".equals(ch.type)) {
            state.sendError(client.conn, "Voice channel not found");
            return;
        }
        if (!db.isServerMember(ch.serverId, client.userId)) {
            state.sendError(client.conn, "You are not a member of this server");
            return;
        }

        if (client.activeVoiceChannelId == channelId) {
            return;
        }
        state.removeUserFromVoice(client);

        Set<String> participants = state.getVoiceChannels().computeIfAbsent(channelId, k -> ConcurrentHashMap.newKeySet());

        // Notify existing users in this voice channel so they can initiate WebRTC Offer
        JsonObject notify = new JsonObject();
        notify.addProperty("type", "user-joined-voice");
        notify.addProperty("channelId", channelId);
        notify.addProperty("username", client.username);
        String notifyPayload = notify.toString();

        for (ServerState.ConnectedClient other : state.getActiveClients().values()) {
            if (other != client && other.activeVoiceChannelId == channelId) {
                state.sendSafe(other.conn, notifyPayload);
            }
        }

        participants.add(client.username);
        client.activeVoiceChannelId = channelId;
        state.broadcastServerVoiceList(ch.serverId);
    }

    private void handleWebRtc(ServerState.ConnectedClient client, JsonObject json) {
        if (client.activeVoiceChannelId == -1) {
            return;
        }
        String target = json.has("target") && !json.get("target").isJsonNull()
                ? json.get("target").getAsString() : null;
        if (target == null) return;

        json.addProperty("sender", client.username);
        String payload = json.toString();

        for (ServerState.ConnectedClient other : state.getActiveClients().values()) {
            if (other.username.equals(target) && other.activeVoiceChannelId == client.activeVoiceChannelId) {
                state.sendSafe(other.conn, payload);
                break;
            }
        }
    }

    private long findFirstTextChannelId(List<DatabaseManager.ChannelRecord> channels) {
        for (DatabaseManager.ChannelRecord ch : channels) {
            if ("TEXT".equals(ch.type)) {
                return ch.id;
            }
        }
        return -1;
    }

    private JsonArray buildServersArray(List<DatabaseManager.ServerRecord> servers, long userId) throws SQLException {
        JsonArray arr = new JsonArray();
        for (DatabaseManager.ServerRecord s : servers) {
            boolean canManage = state.getDb().canManageServer(s.id, userId);
            arr.add(state.serverToJson(s, canManage));
        }
        return arr;
    }

    private JsonObject buildServerPayload(String type, ServerState.ConnectedClient client,
            DatabaseManager.ServerRecord srv, List<DatabaseManager.ChannelRecord> channels, long activeTextId) throws SQLException {
        DatabaseManager db = state.getDb();
        boolean canManage = db.canManageServer(srv.id, client.userId);

        JsonObject out = new JsonObject();
        out.addProperty("type", type);
        out.add("server", state.serverToJson(srv, canManage));
        out.add("channels", state.channelsToJson(channels));
        out.addProperty("activeChannelId", activeTextId);
        if (activeTextId != -1) {
            List<DatabaseManager.MessageRecord> history = db.getChannelMessages(activeTextId, client.userId, HISTORY_LIMIT);
            out.add("messages", state.messagesToJson(history, srv.id));
        } else {
            out.add("messages", new JsonArray());
        }
        out.add("members", state.membersToJson(db.getServerMembers(srv.id)));
        out.add("onlineUsers", gson.toJsonTree(state.getOnlineUsersInServer(srv.id)));
        out.add("voiceChannels", state.buildVoiceMapForServer(channels));
        return out;
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (ex != null) {
            ex.printStackTrace();
        }
    }

    @Override
    public void onStart() {}
}
