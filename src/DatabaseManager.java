import java.io.File;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-safe SQLite persistence manager for users, servers, channels,
 * memberships, invite codes, and message history.
 */
public class DatabaseManager implements AutoCloseable {

    private static final String INVITE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int INVITE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String dbPath;
    private final Object dbLock = new Object();
    private Connection conn;

    public static class DuplicateUsernameException extends Exception {
        public DuplicateUsernameException(String message) { super(message); }
    }

    public static class DuplicateChannelException extends Exception {
        public DuplicateChannelException(String message) { super(message); }
    }

    public static class UserRecord {
        public final long id;
        public final String username;
        public final String passwordHash;
        public final String passwordSalt;
        public final boolean isGlobalAdmin;
        public final long createdAt;

        public UserRecord(long id, String username, String passwordHash, String passwordSalt, boolean isGlobalAdmin, long createdAt) {
            this.id = id;
            this.username = username;
            this.passwordHash = passwordHash;
            this.passwordSalt = passwordSalt;
            this.isGlobalAdmin = isGlobalAdmin;
            this.createdAt = createdAt;
        }
    }

    public static class ServerRecord {
        public final long id;
        public final String name;
        public final Long ownerId;
        public final String inviteCode;
        public final boolean isDefault;
        public final long createdAt;
        public final String role;

        public ServerRecord(long id, String name, Long ownerId, String inviteCode, boolean isDefault, long createdAt, String role) {
            this.id = id;
            this.name = name;
            this.ownerId = ownerId;
            this.inviteCode = inviteCode;
            this.isDefault = isDefault;
            this.createdAt = createdAt;
            this.role = role;
        }
    }

    public static class ChannelRecord {
        public final long id;
        public final long serverId;
        public final String name;
        public final String type;
        public final long createdAt;

        public ChannelRecord(long id, long serverId, String name, String type, long createdAt) {
            this.id = id;
            this.serverId = serverId;
            this.name = name;
            this.type = type;
            this.createdAt = createdAt;
        }
    }

    public static class MessageRecord {
        public final long id;
        public final long channelId;
        public final long userId;
        public final String username;
        public final String content;
        public final long createdAt;

        public MessageRecord(long id, long channelId, long userId, String username, String content, long createdAt) {
            this.id = id;
            this.channelId = channelId;
            this.userId = userId;
            this.username = username;
            this.content = content;
            this.createdAt = createdAt;
        }
    }

    public static class ServerMemberRecord {
        public final long userId;
        public final String username;
        public final String role;
        public final boolean isGlobalAdmin;

        public ServerMemberRecord(long userId, String username, String role, boolean isGlobalAdmin) {
            this.userId = userId;
            this.username = username;
            this.role = role;
            this.isGlobalAdmin = isGlobalAdmin;
        }
    }

    public static class JoinServerResult {
        public final ServerRecord server;
        public final boolean newlyJoined;

        public JoinServerResult(ServerRecord server, boolean newlyJoined) {
            this.server = server;
            this.newlyJoined = newlyJoined;
        }
    }

    public DatabaseManager() throws SQLException {
        this(resolveDefaultDbPath());
    }

    public DatabaseManager(String dbPath) throws SQLException {
        this.dbPath = dbPath;
        File file = new File(dbPath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        synchronized (dbLock) {
            openConnection();
            initSchemaAndSeed();
        }
    }

    private static String resolveDefaultDbPath() {
        String envPath = System.getenv("DB_PATH");
        return (envPath != null && !envPath.isBlank()) ? envPath.trim() : "./data/epigaea.db";
    }

    private void openConnection() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            return;
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA busy_timeout=5000;");
            st.execute("PRAGMA foreign_keys=ON;");
        }
    }

    private Connection getConnection() throws SQLException {
        if (conn == null || conn.isClosed()) {
            openConnection();
        }
        return conn;
    }

    private void initSchemaAndSeed() throws SQLException {
        Connection c = getConnection();
        try (Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT UNIQUE NOT NULL COLLATE NOCASE,
                    password_hash TEXT NOT NULL,
                    password_salt TEXT NOT NULL,
                    is_global_admin INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL
                );
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS servers (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    owner_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
                    invite_code TEXT UNIQUE NOT NULL,
                    is_default INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL
                );
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS server_members (
                    server_id INTEGER NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
                    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    role TEXT NOT NULL DEFAULT 'MEMBER',
                    joined_at INTEGER NOT NULL,
                    PRIMARY KEY (server_id, user_id)
                );
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS channels (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    server_id INTEGER NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
                    name TEXT NOT NULL COLLATE NOCASE,
                    type TEXT NOT NULL CHECK(type IN ('TEXT', 'VOICE')),
                    created_at INTEGER NOT NULL,
                    UNIQUE(server_id, name, type)
                );
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    channel_id INTEGER NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
                    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    username TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                );
            """);

            st.execute("CREATE INDEX IF NOT EXISTS idx_messages_channel_id ON messages(channel_id, id DESC);");
        }

        // Seed default "Epigaea Home" server if none exists
        long defaultServerId = -1;
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM servers WHERE is_default = 1 LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                defaultServerId = rs.getLong("id");
            }
        }

        if (defaultServerId == -1) {
            long now = System.currentTimeMillis();
            c.setAutoCommit(false);
            try {
                String inviteCode = generateUniqueInviteCode(c);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO servers (name, owner_id, invite_code, is_default, created_at) VALUES (?, NULL, ?, 1, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, "Epigaea Home");
                    ps.setString(2, inviteCode);
                    ps.setLong(3, now);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) {
                            defaultServerId = keys.getLong(1);
                        }
                    }
                }
                insertChannelInternal(c, defaultServerId, "general", "TEXT", now);
                insertChannelInternal(c, defaultServerId, "General", "VOICE", now);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private String generateRandomCode() {
        StringBuilder sb = new StringBuilder(INVITE_LENGTH);
        for (int i = 0; i < INVITE_LENGTH; i++) {
            sb.append(INVITE_CHARS.charAt(RANDOM.nextInt(INVITE_CHARS.length())));
        }
        return sb.toString();
    }

    private String generateUniqueInviteCode(Connection c) throws SQLException {
        for (int attempt = 0; attempt < 20; attempt++) {
            String code = generateRandomCode();
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM servers WHERE invite_code = ?")) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return code;
                    }
                }
            }
        }
        throw new SQLException("Failed to generate unique invite code");
    }

    private ChannelRecord insertChannelInternal(Connection c, long serverId, String name, String type, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO channels (server_id, name, type, created_at) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, serverId);
            ps.setString(2, name);
            ps.setString(3, type);
            ps.setLong(4, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                long id = keys.next() ? keys.getLong(1) : -1;
                return new ChannelRecord(id, serverId, name, type, now);
            }
        }
    }

    public UserRecord createUser(String username, String passwordHash, String passwordSalt, boolean isGlobalAdmin)
            throws SQLException, DuplicateUsernameException {
        synchronized (dbLock) {
            Connection c = getConnection();
            long now = System.currentTimeMillis();
            c.setAutoCommit(false);
            try {
                long userId;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO users (username, password_hash, password_salt, is_global_admin, created_at) VALUES (?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, username);
                    ps.setString(2, passwordHash);
                    ps.setString(3, passwordSalt);
                    ps.setInt(4, isGlobalAdmin ? 1 : 0);
                    ps.setLong(5, now);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("Failed to retrieve user id");
                        }
                        userId = keys.getLong(1);
                    }
                } catch (SQLException e) {
                    if (e.getMessage() != null && e.getMessage().toLowerCase().contains("unique")) {
                        throw new DuplicateUsernameException("Username already exists");
                    }
                    throw e;
                }

                // Auto-join default server
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT OR IGNORE INTO server_members (server_id, user_id, role, joined_at) " +
                        "SELECT id, ?, 'MEMBER', ? FROM servers WHERE is_default = 1")) {
                    ps.setLong(1, userId);
                    ps.setLong(2, now);
                    ps.executeUpdate();
                }

                c.commit();
                return new UserRecord(userId, username, passwordHash, passwordSalt, isGlobalAdmin, now);
            } catch (SQLException | DuplicateUsernameException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public UserRecord findUserByUsername(String username) throws SQLException {
        synchronized (dbLock) {
            Connection c = getConnection();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, username, password_hash, password_salt, is_global_admin, created_at FROM users WHERE username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return new UserRecord(
                            rs.getLong("id"),
                            rs.getString("username"),
                            rs.getString("password_hash"),
                            rs.getString("password_salt"),
                            rs.getInt("is_global_admin") == 1,
                            rs.getLong("created_at")
                    );
                }
            }
        }
    }

    public UserRecord findUserById(long userId) throws SQLException {
        synchronized (dbLock) {
            Connection c = getConnection();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, username, password_hash, password_salt, is_global_admin, created_at FROM users WHERE id = ?")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return new UserRecord(
                            rs.getLong("id"),
                            rs.getString("username"),
                            rs.getString("password_hash"),
                            rs.getString("password_salt"),
                            rs.getInt("is_global_admin") == 1,
                            rs.getLong("created_at")
                    );
                }
            }
        }
    }

    public List<ServerRecord> getUserServers(long userId) throws SQLException {
        synchronized (dbLock) {
            Connection c = getConnection();
            List<ServerRecord> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT s.id, s.name, s.owner_id, s.invite_code, s.is_default, s.created_at, sm.role " +
                    "FROM servers s JOIN server_members sm ON s.id = sm.server_id " +
                    "WHERE sm.user_id = ? ORDER BY s.is_default DESC, s.id ASC")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long rawOwner = rs.getLong("owner_id");
                        Long ownerId = rs.wasNull() ? null : rawOwner;
                        list.add(new ServerRecord(
                                rs.getLong("id"),
                                rs.getString("name"),
                                ownerId,
                                rs.getString("invite_code"),
                                rs.getInt("is_default") == 1,
                                rs.getLong("created_at"),
                                rs.getString("role")
                        ));
                    }
                }
            }
            return list;
        }
    }

    public ServerRecord getServerForUser(long serverId, long userId) throws SQLException {
        synchronized (dbLock) {
            Connection c = getConnection();
            return getServerForUserInternal(c, serverId, userId);
        }
    }

    private ServerRecord getServerForUserInternal(Connection c, long serverId, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT s.id, s.name, s.owner_id, s.invite_code, s.is_default, s.created_at, sm.role " +
                "FROM servers s JOIN server_members sm ON s.id = sm.server_id " +
                "WHERE s.id = ? AND sm.user_id = ?")) {
            ps.setLong(1, serverId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long rawOwner = rs.getLong("owner_id");
                Long ownerId = rs.wasNull() ? null : rawOwner;
                return new ServerRecord(
                        rs.getLong("id"),
                        rs.getString("name"),
                        ownerId,
                        rs.getString("invite_code"),
                        rs.getInt("is_default") == 1,
                        rs.getLong("created_at"),
                        rs.getString("role")
                );
            }
        }
    }

    public boolean isServerMember(long serverId, long userId) throws SQLException {
        synchronized (dbLock) {
            Connection c = getConnection();
            return isServerMemberInternal(c, serverId, userId);
        }
    }

    private boolean isServerMemberInternal(Connection c, long serverId, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM server_members WHERE server_id = ? AND user_id = ?")) {
            ps.setLong(1, serverId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean canManageServer(long serverId, long userId) throws SQLException {
        synchronized (dbLock) {
            Connection c = getConnection();
            return canManageServerInternal(c, serverId, userId);
        }
    }

    private boolean canManageServerInternal(Connection c, long serverId, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT is_global_admin FROM users WHERE id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt("is_global_admin") == 1) {
                    return true;
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT s.owner_id, sm.role FROM servers s " +
                "LEFT JOIN server_members sm ON s.id = sm.server_id AND sm.user_id = ? " +
                "WHERE s.id = ?")) {
            ps.setLong(1, userId);
            ps.setLong(2, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                long ownerId = rs.getLong("owner_id");
                if (!rs.wasNull() && ownerId == userId) return true;
                String role = rs.getString("role");
                return "OWNER".equalsIgnoreCase(role) || "ADMIN".equalsIgnoreCase(role);
            }
        }
    }

    public ServerRecord createServer(long ownerId, String name) throws SQLException {
        if (name == null || name.trim().isEmpty() || name.trim().length() > 50) {
            throw new IllegalArgumentException("Server name must be 1 to 50 characters");
        }
        String cleanName = name.trim();
        synchronized (dbLock) {
            Connection c = getConnection();
            long now = System.currentTimeMillis();
            c.setAutoCommit(false);
            try {
                String inviteCode = generateUniqueInviteCode(c);
                long serverId;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO servers (name, owner_id, invite_code, is_default, created_at) VALUES (?, ?, ?, 0, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, cleanName);
                    ps.setLong(2, ownerId);
                    ps.setString(3, inviteCode);
                    ps.setLong(4, now);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Failed to create server");
                        serverId = keys.getLong(1);
                    }
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO server_members (server_id, user_id, role, joined_at) VALUES (?, ?, 'OWNER', ?)")) {
                    ps.setLong(1, serverId);
                    ps.setLong(2, ownerId);
                    ps.setLong(3, now);
                    ps.executeUpdate();
                }

                insertChannelInternal(c, serverId, "general", "TEXT", now);
                insertChannelInternal(c, serverId, "General", "VOICE", now);

                c.commit();
                return new ServerRecord(serverId, cleanName, ownerId, inviteCode, false, now, "OWNER");
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public JoinServerResult joinServerByCode(long userId, String inviteCode) throws SQLException {
        if (inviteCode == null || inviteCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Invite code is required");
        }
        String normalized = inviteCode.trim().toUpperCase();
        synchronized (dbLock) {
            Connection c = getConnection();
            long serverId;
            String name;
            Long ownerId;
            boolean isDefault;
            long createdAt;

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, name, owner_id, invite_code, is_default, created_at FROM servers WHERE invite_code = ?")) {
                ps.setString(1, normalized);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Invalid invite room code");
                    }
                    serverId = rs.getLong("id");
                    name = rs.getString("name");
                    long rawOwner = rs.getLong("owner_id");
                    ownerId = rs.wasNull() ? null : rawOwner;
                    isDefault = rs.getInt("is_default") == 1;
                    createdAt = rs.getLong("created_at");
                }
            }

            ServerRecord existing = getServerForUserInternal(c, serverId, userId);
            if (existing != null) {
                return new JoinServerResult(existing, false);
            }

            long now = System.currentTimeMillis();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO server_members (server_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', ?)")) {
                ps.setLong(1, serverId);
                ps.setLong(2, userId);
                ps.setLong(3, now);
                ps.executeUpdate();
            }

            return new JoinServerResult(
                    new ServerRecord(serverId, name, ownerId, normalized, isDefault, createdAt, "MEMBER"),
                    true
            );
        }
    }

    public String regenerateInviteCode(long serverId, long actorUserId) throws SQLException {
        synchronized (dbLock) {
            Connection c = getConnection();
            if (!canManageServerInternal(c, serverId, actorUserId)) {
                throw new SecurityException("Only the server owner or a global admin can regenerate invite codes");
            }
            String newCode = generateUniqueInviteCode(c);
            try (PreparedStatement ps = c.prepareStatement("UPDATE servers SET invite_code = ? WHERE id = ?")) {
                ps.setString(1, newCode);
                ps.setLong(2, serverId);
                int updated = ps.executeUpdate();
                if (updated == 0) {
                    throw new IllegalArgumentException("Server not found");
                }
            }
            return newCode;
        }
    }

    public List<ChannelRecord> getServerChannels(long serverId) throws SQLException {
        synchronized (dbLock) {
            Connection c = getConnection();
            List<ChannelRecord> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, server_id, name, type, created_at FROM channels WHERE server_id = ? ORDER BY type ASC, id ASC")) {
                ps.setLong(1, serverId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new ChannelRecord(
                                rs.getLong("id"),
                                rs.getLong("server_id"),
                                rs.getString("name"),
                                rs.getString("type"),
                                rs.getLong("created_at")
                        ));
                    }
                }
            }
            return list;
        }
    }

    public ChannelRecord getChannelById(long channelId) throws SQLException {
        synchronized (dbLock) {
            Connection c = getConnection();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, server_id, name, type, created_at FROM channels WHERE id = ?")) {
                ps.setLong(1, channelId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return new ChannelRecord(
                            rs.getLong("id"),
                            rs.getLong("server_id"),
                            rs.getString("name"),
                            rs.getString("type"),
                            rs.getLong("created_at")
                    );
                }
            }
        }
    }

    public List<ServerMemberRecord> getServerMembers(long serverId) throws SQLException {
        synchronized (dbLock) {
            Connection c = getConnection();
            List<ServerMemberRecord> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT u.id, u.username, sm.role, u.is_global_admin " +
                    "FROM server_members sm JOIN users u ON sm.user_id = u.id " +
                    "WHERE sm.server_id = ? ORDER BY u.username ASC")) {
                ps.setLong(1, serverId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new ServerMemberRecord(
                                rs.getLong("id"),
                                rs.getString("username"),
                                rs.getString("role"),
                                rs.getInt("is_global_admin") == 1
                        ));
                    }
                }
            }
            return list;
        }
    }

    public ChannelRecord createChannel(long serverId, long actorUserId, String name, String type)
            throws SQLException, DuplicateChannelException {
        if (name == null || name.trim().isEmpty() || name.trim().length() > 32) {
            throw new IllegalArgumentException("Channel name must be 1 to 32 characters");
        }
        String cleanName = name.trim();
        String normalizedType = type == null ? "" : type.trim().toUpperCase();
        if (!"TEXT".equals(normalizedType) && !"VOICE".equals(normalizedType)) {
            throw new IllegalArgumentException("Channel type must be TEXT or VOICE");
        }

        synchronized (dbLock) {
            Connection c = getConnection();
            if (!canManageServerInternal(c, serverId, actorUserId)) {
                throw new SecurityException("Only the server owner or a global admin can add channels");
            }
            try {
                return insertChannelInternal(c, serverId, cleanName, normalizedType, System.currentTimeMillis());
            } catch (SQLException e) {
                if (e.getMessage() != null && e.getMessage().toLowerCase().contains("unique")) {
                    throw new DuplicateChannelException("A " + normalizedType.toLowerCase() + " channel with that name already exists");
                }
                throw e;
            }
        }
    }

    public ChannelRecord deleteChannel(long serverId, long channelId, long actorUserId) throws SQLException {
        synchronized (dbLock) {
            Connection c = getConnection();
            if (!canManageServerInternal(c, serverId, actorUserId)) {
                throw new SecurityException("Only the server owner or a global admin can delete channels");
            }

            ChannelRecord target = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, server_id, name, type, created_at FROM channels WHERE id = ? AND server_id = ?")) {
                ps.setLong(1, channelId);
                ps.setLong(2, serverId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        target = new ChannelRecord(
                                rs.getLong("id"),
                                rs.getLong("server_id"),
                                rs.getString("name"),
                                rs.getString("type"),
                                rs.getLong("created_at")
                        );
                    }
                }
            }
            if (target == null) {
                throw new IllegalArgumentException("Channel not found in this server");
            }

            if ("TEXT".equals(target.type)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM channels WHERE server_id = ? AND type = 'TEXT'")) {
                    ps.setLong(1, serverId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getInt(1) <= 1) {
                            throw new IllegalStateException("Cannot delete the last text channel in a server");
                        }
                    }
                }
            }

            try (PreparedStatement ps = c.prepareStatement("DELETE FROM channels WHERE id = ? AND server_id = ?")) {
                ps.setLong(1, channelId);
                ps.setLong(2, serverId);
                ps.executeUpdate();
            }
            return target;
        }
    }

    public MessageRecord saveMessage(long channelId, long userId, String username, String content) throws SQLException {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("Message cannot be empty");
        }
        synchronized (dbLock) {
            Connection c = getConnection();
            long serverId = -1;
            String type = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT server_id, type FROM channels WHERE id = ?")) {
                ps.setLong(1, channelId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        serverId = rs.getLong("server_id");
                        type = rs.getString("type");
                    }
                }
            }
            if (serverId == -1 || !"TEXT".equals(type)) {
                throw new IllegalArgumentException("Text channel does not exist");
            }
            if (!isServerMemberInternal(c, serverId, userId)) {
                throw new SecurityException("You are not a member of this server");
            }

            long now = System.currentTimeMillis();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO messages (channel_id, user_id, username, content, created_at) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, channelId);
                ps.setLong(2, userId);
                ps.setString(3, username);
                ps.setString(4, content);
                ps.setLong(5, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    long msgId = keys.next() ? keys.getLong(1) : -1;
                    return new MessageRecord(msgId, channelId, userId, username, content, now);
                }
            }
        }
    }

    public List<MessageRecord> getChannelMessages(long channelId, long userId, int limit) throws SQLException {
        synchronized (dbLock) {
            Connection c = getConnection();
            long serverId = -1;
            try (PreparedStatement ps = c.prepareStatement("SELECT server_id FROM channels WHERE id = ?")) {
                ps.setLong(1, channelId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        serverId = rs.getLong("server_id");
                    }
                }
            }
            if (serverId == -1) {
                throw new IllegalArgumentException("Channel not found");
            }
            if (!isServerMemberInternal(c, serverId, userId)) {
                throw new SecurityException("You are not a member of this server");
            }

            List<MessageRecord> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, channel_id, user_id, username, content, created_at " +
                    "FROM messages WHERE channel_id = ? ORDER BY id DESC LIMIT ?")) {
                ps.setLong(1, channelId);
                ps.setInt(2, Math.max(1, Math.min(limit, 500)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new MessageRecord(
                                rs.getLong("id"),
                                rs.getLong("channel_id"),
                                rs.getLong("user_id"),
                                rs.getString("username"),
                                rs.getString("content"),
                                rs.getLong("created_at")
                        ));
                    }
                }
            }
            Collections.reverse(list);
            return list;
        }
    }

    @Override
    public void close() throws SQLException {
        synchronized (dbLock) {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        }
    }
}
