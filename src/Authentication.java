import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Handles user registration, PBKDF2 password hashing/verification,
 * global admin secret validation, and WebSocket session tokens.
 */
public class Authentication {

    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 65536;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DatabaseManager db;
    private final String adminSecret;
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    public static class SessionInfo {
        public final long userId;
        public final String username;
        public final boolean isGlobalAdmin;
        public final long createdAt;

        public SessionInfo(long userId, String username, boolean isGlobalAdmin, long createdAt) {
            this.userId = userId;
            this.username = username;
            this.isGlobalAdmin = isGlobalAdmin;
            this.createdAt = createdAt;
        }
    }

    public static class AuthResult {
        public final boolean success;
        public final int statusCode;
        public final String message;
        public final String token;
        public final SessionInfo session;

        private AuthResult(boolean success, int statusCode, String message, String token, SessionInfo session) {
            this.success = success;
            this.statusCode = statusCode;
            this.message = message;
            this.token = token;
            this.session = session;
        }

        public static AuthResult ok(String message, String token, SessionInfo session) {
            return new AuthResult(true, 200, message, token, session);
        }

        public static AuthResult created(String message, SessionInfo session) {
            return new AuthResult(true, 201, message, null, session);
        }

        public static AuthResult fail(int statusCode, String message) {
            return new AuthResult(false, statusCode, message, null, null);
        }
    }

    public Authentication(DatabaseManager db) {
        this(db, resolveAdminSecret());
    }

    public Authentication(DatabaseManager db, String adminSecret) {
        this.db = db;
        this.adminSecret = adminSecret;
    }

    private static String resolveAdminSecret() {
        String env = System.getenv("ADMIN_SECRET");
        return (env != null && !env.isBlank()) ? env.trim() : "EPIGAEA-ADMIN";
    }

    public AuthResult register(String rawUsername, String rawPassword, String rawAdminCode) {
        if (rawUsername == null || rawPassword == null) {
            return AuthResult.fail(400, "Username and password are required");
        }
        String username = rawUsername.trim();
        if (username.length() < 2 || username.length() > 24 || !username.matches("^[a-zA-Z0-9_.-]+$")) {
            return AuthResult.fail(400, "Username must be 2-24 characters (letters, numbers, _, ., -)");
        }
        if (rawPassword.isEmpty()) {
            return AuthResult.fail(400, "Password cannot be empty");
        }

        boolean isGlobalAdmin = false;
        if (rawAdminCode != null && !rawAdminCode.trim().isEmpty()) {
            if (!MessageDigest.isEqual(
                    rawAdminCode.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    adminSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                return AuthResult.fail(403, "Invalid admin secret code");
            }
            isGlobalAdmin = true;
        }

        byte[] saltBytes = new byte[SALT_BYTES];
        RANDOM.nextBytes(saltBytes);
        String saltB64 = Base64.getEncoder().encodeToString(saltBytes);
        String hashB64 = hashPassword(rawPassword, saltBytes);

        try {
            DatabaseManager.UserRecord user = db.createUser(username, hashB64, saltB64, isGlobalAdmin);
            SessionInfo info = new SessionInfo(user.id, user.username, user.isGlobalAdmin, user.createdAt);
            return AuthResult.created("Account created", info);
        } catch (DatabaseManager.DuplicateUsernameException e) {
            return AuthResult.fail(409, "Username already exists");
        } catch (SQLException e) {
            e.printStackTrace();
            return AuthResult.fail(500, "Database error during registration");
        }
    }

    public AuthResult login(String rawUsername, String rawPassword) {
        if (rawUsername == null || rawPassword == null || rawUsername.trim().isEmpty() || rawPassword.isEmpty()) {
            return AuthResult.fail(400, "Username and password are required");
        }
        String username = rawUsername.trim();
        try {
            DatabaseManager.UserRecord user = db.findUserByUsername(username);
            if (user == null) {
                return AuthResult.fail(401, "Wrong username or password");
            }
            byte[] saltBytes = Base64.getDecoder().decode(user.passwordSalt);
            String candidateHash = hashPassword(rawPassword, saltBytes);
            if (!MessageDigest.isEqual(
                    candidateHash.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    user.passwordHash.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                return AuthResult.fail(401, "Wrong username or password");
            }

            byte[] tokenBytes = new byte[32];
            RANDOM.nextBytes(tokenBytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
            SessionInfo session = new SessionInfo(user.id, user.username, user.isGlobalAdmin, System.currentTimeMillis());
            sessions.put(token, session);
            return AuthResult.ok("Login successful", token, session);
        } catch (SQLException e) {
            e.printStackTrace();
            return AuthResult.fail(500, "Database error during login");
        }
    }

    public SessionInfo validateToken(String token) {
        if (token == null || token.isBlank()) return null;
        return sessions.get(token);
    }

    private static String hashPassword(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_BITS);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = skf.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 unavailable", e);
        }
    }
}
