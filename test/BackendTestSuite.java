import java.io.File;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

public class BackendTestSuite {

    private static int passed = 0;

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(msg + " | expected=" + expected + ", actual=" + actual);
        }
    }

    private static File tempDbFile() throws Exception {
        File dir = Files.createTempDirectory("epigaea-test-").toFile();
        dir.deleteOnExit();
        return new File(dir, "test.db");
    }

    public static void main(String[] args) throws Exception {
        testSeedingAndAuth();
        testServersInvitesAndPermissions();
        testPersistenceAcrossRestart();
        testConcurrencyAndRaceConditions();
        testLiveHttpAndWebSocketFlow();
        System.out.println("ALL " + passed + " BACKEND TEST GROUPS PASSED!");
    }

    private static void testSeedingAndAuth() throws Exception {
        File dbFile = tempDbFile();
        try (DatabaseManager db = new DatabaseManager(dbFile.getAbsolutePath())) {
            Authentication auth = new Authentication(db, "SECRET-123");

            // Standard registration
            Authentication.AuthResult r1 = auth.register("Alice", "pass123", "");
            assertTrue(r1.success && r1.statusCode == 201, "Alice should register");
            assertTrue(!r1.session.isGlobalAdmin, "Alice should not be global admin");

            // Case-insensitive duplicate rejection
            Authentication.AuthResult dup = auth.register("alice", "other", "");
            assertTrue(!dup.success && dup.statusCode == 409, "Duplicate username must return 409");

            // Invalid username / empty password
            assertEquals(400, auth.register("a", "pass", "").statusCode, "Short username rejected");
            assertEquals(400, auth.register("Bad User!", "pass", "").statusCode, "Invalid chars rejected");
            assertEquals(400, auth.register("Bob", "", "").statusCode, "Empty password rejected");

            // Wrong admin secret rejected
            Authentication.AuthResult badAdmin = auth.register("Mallory", "pass", "WRONG");
            assertTrue(!badAdmin.success && badAdmin.statusCode == 403, "Wrong admin code must return 403");

            // Valid admin secret creates Global Admin
            Authentication.AuthResult adminReg = auth.register("RootAdmin", "rootpass", "SECRET-123");
            assertTrue(adminReg.success && adminReg.session.isGlobalAdmin, "Valid admin code creates global admin");

            // Login checks
            assertEquals(401, auth.login("Alice", "wrong").statusCode, "Wrong password rejected");
            Authentication.AuthResult loginOk = auth.login("Alice", "pass123");
            assertTrue(loginOk.success && loginOk.token != null, "Valid login returns token");
            assertTrue(auth.validateToken(loginOk.token) != null, "Token validates");
            assertTrue(auth.validateToken("bogus-token") == null, "Bogus token rejected");

            // Auto-joined default server
            List<DatabaseManager.ServerRecord> servers = db.getUserServers(r1.session.userId);
            assertEquals(1, servers.size(), "New user auto-joins default server");
            assertEquals("Epigaea Home", servers.get(0).name, "Default server name");
        }
        passed++;
        System.out.println("[PASS] testSeedingAndAuth");
    }

    private static void testServersInvitesAndPermissions() throws Exception {
        File dbFile = tempDbFile();
        try (DatabaseManager db = new DatabaseManager(dbFile.getAbsolutePath())) {
            Authentication auth = new Authentication(db, "SECRET-123");
            long aliceId = auth.register("Alice", "p1", "").session.userId;
            long bobId = auth.register("Bob", "p2", "").session.userId;
            long adminId = auth.register("SuperAdmin", "p3", "SECRET-123").session.userId;

            // Anyone can create a server
            DatabaseManager.ServerRecord srv = db.createServer(aliceId, "Gaming Hub");
            assertEquals("OWNER", srv.role, "Creator is OWNER");
            assertEquals(8, srv.inviteCode.length(), "8-char invite code");

            List<DatabaseManager.ChannelRecord> chs = db.getServerChannels(srv.id);
            assertEquals(2, chs.size(), "Default text + voice channel created");

            // Bob joins via invite code
            DatabaseManager.JoinServerResult j1 = db.joinServerByCode(bobId, srv.inviteCode.toLowerCase());
            assertTrue(j1.newlyJoined, "Bob newly joined");
            DatabaseManager.JoinServerResult j2 = db.joinServerByCode(bobId, srv.inviteCode);
            assertTrue(!j2.newlyJoined, "Re-joining is idempotent");

            // Invalid invite code
            boolean invalidThrew = false;
            try { db.joinServerByCode(bobId, "NOPE9999"); } catch (IllegalArgumentException e) { invalidThrew = true; }
            assertTrue(invalidThrew, "Invalid invite code throws");

            // Non-owner Bob cannot add/delete channels or regenerate invite
            boolean bobBlocked = false;
            try { db.createChannel(srv.id, bobId, "secret", "TEXT"); } catch (SecurityException e) { bobBlocked = true; }
            assertTrue(bobBlocked, "Non-owner blocked from creating channel");

            boolean bobRegenBlocked = false;
            try { db.regenerateInviteCode(srv.id, bobId); } catch (SecurityException e) { bobRegenBlocked = true; }
            assertTrue(bobRegenBlocked, "Non-owner blocked from regenerating invite");

            // Owner Alice can add/delete channels & regenerate invite
            DatabaseManager.ChannelRecord announcements = db.createChannel(srv.id, aliceId, "announcements", "TEXT");
            DatabaseManager.ChannelRecord lounge = db.createChannel(srv.id, aliceId, "Lounge", "VOICE");
            assertEquals(4, db.getServerChannels(srv.id).size(), "4 channels exist");

            // Duplicate channel name+type rejected, same name different type allowed
            boolean dupChan = false;
            try { db.createChannel(srv.id, aliceId, "announcements", "TEXT"); } catch (DatabaseManager.DuplicateChannelException e) { dupChan = true; }
            assertTrue(dupChan, "Duplicate channel rejected");
            db.createChannel(srv.id, aliceId, "announcements", "VOICE");

            // Global Admin can manage channels on any server (including Alice's & default)
            db.deleteChannel(srv.id, announcements.id, adminId);
            db.deleteChannel(srv.id, lounge.id, adminId);

            // Cannot delete last TEXT channel
            List<DatabaseManager.ChannelRecord> remaining = db.getServerChannels(srv.id);
            long lastTextId = remaining.stream().filter(c -> "TEXT".equals(c.type)).findFirst().get().id;
            boolean lastTextBlocked = false;
            try { db.deleteChannel(srv.id, lastTextId, aliceId); } catch (IllegalStateException e) { lastTextBlocked = true; }
            assertTrue(lastTextBlocked, "Cannot delete last text channel");

            // Invite regeneration invalidates old code
            String oldCode = srv.inviteCode;
            String newCode = db.regenerateInviteCode(srv.id, aliceId);
            assertTrue(!oldCode.equals(newCode), "New invite code differs");
            boolean oldCodeInvalid = false;
            try { db.joinServerByCode(adminId, oldCode); } catch (IllegalArgumentException e) { oldCodeInvalid = true; }
            assertTrue(oldCodeInvalid, "Old invite code invalidated");
            assertTrue(db.joinServerByCode(adminId, newCode).newlyJoined, "New invite code works");
        }
        passed++;
        System.out.println("[PASS] testServersInvitesAndPermissions");
    }

    private static void testPersistenceAcrossRestart() throws Exception {
        File dbFile = tempDbFile();
        long channelId;
        long aliceId;
        try (DatabaseManager db = new DatabaseManager(dbFile.getAbsolutePath())) {
            Authentication auth = new Authentication(db, "SECRET-123");
            aliceId = auth.register("Alice", "pw", "").session.userId;
            DatabaseManager.ServerRecord srv = db.createServer(aliceId, "PersistLand");
            channelId = db.getServerChannels(srv.id).stream().filter(c -> "TEXT".equals(c.type)).findFirst().get().id;
            db.saveMessage(channelId, aliceId, "Alice", "Hello 1");
            db.saveMessage(channelId, aliceId, "Alice", "Hello 2");
        }

        // Reopen DB file
        try (DatabaseManager db2 = new DatabaseManager(dbFile.getAbsolutePath())) {
            Authentication auth2 = new Authentication(db2, "SECRET-123");
            assertTrue(auth2.login("Alice", "pw").success, "User persists across restart");
            List<DatabaseManager.MessageRecord> msgs = db2.getChannelMessages(channelId, aliceId, 50);
            assertEquals(2, msgs.size(), "Messages persist across restart");
            assertEquals("Hello 1", msgs.get(0).content, "Chronological order 1");
            assertEquals("Hello 2", msgs.get(1).content, "Chronological order 2");
        }
        passed++;
        System.out.println("[PASS] testPersistenceAcrossRestart");
    }

    private static void testConcurrencyAndRaceConditions() throws Exception {
        File dbFile = tempDbFile();
        try (DatabaseManager db = new DatabaseManager(dbFile.getAbsolutePath())) {
            Authentication auth = new Authentication(db, "SECRET-123");
            ExecutorService pool = Executors.newFixedThreadPool(12);

            // 6a: Concurrent duplicate registration race
            int regThreads = 10;
            CountDownLatch regLatch = new CountDownLatch(1);
            AtomicInteger regSuccess = new AtomicInteger();
            AtomicInteger regConflict = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < regThreads; i++) {
                futures.add(pool.submit(() -> {
                    regLatch.await();
                    Authentication.AuthResult res = auth.register("RaceUser", "pw", "");
                    if (res.success) regSuccess.incrementAndGet();
                    else if (res.statusCode == 409) regConflict.incrementAndGet();
                    return null;
                }));
            }
            regLatch.countDown();
            for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
            assertEquals(1, regSuccess.get(), "Exactly 1 concurrent registration succeeds");
            assertEquals(regThreads - 1, regConflict.get(), "All others get 409 conflict");

            // 6b: Concurrent message writes (8 threads x 25 msgs = 200)
            long userId = db.findUserByUsername("RaceUser").id;
            DatabaseManager.ServerRecord srv = db.createServer(userId, "ConcurrencyServer");
            long chanId = db.getServerChannels(srv.id).stream().filter(c -> "TEXT".equals(c.type)).findFirst().get().id;

            int msgThreads = 8;
            int perThread = 25;
            CountDownLatch msgLatch = new CountDownLatch(1);
            futures.clear();
            for (int t = 0; t < msgThreads; t++) {
                final int tid = t;
                futures.add(pool.submit(() -> {
                    msgLatch.await();
                    for (int m = 0; m < perThread; m++) {
                        db.saveMessage(chanId, userId, "RaceUser", "T" + tid + "-M" + m);
                    }
                    return null;
                }));
            }
            msgLatch.countDown();
            for (Future<?> f : futures) f.get(15, TimeUnit.SECONDS);
            List<DatabaseManager.MessageRecord> allMsgs = db.getChannelMessages(chanId, userId, 300);
            assertEquals(msgThreads * perThread, allMsgs.size(), "All 200 concurrent messages saved");

            // 6c: Concurrent delete channel vs save message race
            DatabaseManager.ChannelRecord tempChan = db.createChannel(srv.id, userId, "ephemeral", "TEXT");
            CountDownLatch raceLatch = new CountDownLatch(1);
            Future<String> writer = pool.submit(() -> {
                raceLatch.await();
                for (int i = 0; i < 20; i++) {
                    try {
                        db.saveMessage(tempChan.id, userId, "RaceUser", "race-" + i);
                    } catch (IllegalArgumentException expectedAfterDelete) {
                        return "cleanly-rejected";
                    }
                }
                return "all-before-delete";
            });
            Future<String> deleter = pool.submit(() -> {
                raceLatch.await();
                db.deleteChannel(srv.id, tempChan.id, userId);
                return "deleted";
            });
            raceLatch.countDown();
            assertEquals("deleted", deleter.get(5, TimeUnit.SECONDS), "Channel deleted cleanly");
            String writerOutcome = writer.get(5, TimeUnit.SECONDS);
            assertTrue("cleanly-rejected".equals(writerOutcome) || "all-before-delete".equals(writerOutcome),
                    "Writer handled channel delete race cleanly");

            pool.shutdown();
        }
        passed++;
        System.out.println("[PASS] testConcurrencyAndRaceConditions");
    }

    private static void testLiveHttpAndWebSocketFlow() throws Exception {
        File dbFile = tempDbFile();
        int httpPort = 18082;
        int wsPort = 18081;

        try (DatabaseManager db = new DatabaseManager(dbFile.getAbsolutePath())) {
            Authentication auth = new Authentication(db, "SECRET-123");
            ServerState state = new ServerState(db, auth);

            HttpServer http = HttpServer.create(new InetSocketAddress(httpPort), 0);
            http.createContext("/api/login", new LoginHttpHandler(auth));
            http.createContext("/api/register", new RegisterHttpHandler(auth));
            http.setExecutor(Executors.newCachedThreadPool());
            http.start();

            ChatWebSocketServer ws = new ChatWebSocketServer(wsPort, state);
            ws.start();

            try {
                HttpClient client = HttpClient.newHttpClient();

                // Register Alice (standard) and AdminUser (global admin)
                HttpResponse<String> regAlice = client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + httpPort + "/api/register"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"Alice\",\"password\":\"p1\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(201, regAlice.statusCode(), "HTTP register Alice");

                HttpResponse<String> regBob = client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + httpPort + "/api/register"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"Bob\",\"password\":\"p2\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(201, regBob.statusCode(), "HTTP register Bob");

                // Login both
                String aliceToken = JsonParser.parseString(client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + httpPort + "/api/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"Alice\",\"password\":\"p1\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject().get("token").getAsString();

                String bobToken = JsonParser.parseString(client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + httpPort + "/api/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"Bob\",\"password\":\"p2\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject().get("token").getAsString();

                BlockingQueue<JsonObject> aliceMsgs = new LinkedBlockingQueue<>();
                BlockingQueue<JsonObject> bobMsgs = new LinkedBlockingQueue<>();

                WebSocketClient wsAlice = connectWs(wsPort, aliceMsgs);
                WebSocketClient wsBob = connectWs(wsPort, bobMsgs);

                // Unauthenticated chat rejected
                wsAlice.send("{\"type\":\"chat\",\"message\":\"unauth\"}");
                JsonObject err = waitForType(aliceMsgs, "error");
                assertEquals("Not authenticated", err.get("message").getAsString(), "Unauth rejected");

                // Authenticate both
                wsAlice.send("{\"type\":\"join\",\"token\":\"" + aliceToken + "\"}");
                JsonObject aliceInit = waitForType(aliceMsgs, "init");
                assertEquals("Alice", aliceInit.getAsJsonObject("user").get("username").getAsString(), "Alice init");

                wsBob.send("{\"type\":\"join\",\"token\":\"" + bobToken + "\"}");
                waitForType(bobMsgs, "init");

                // Alice creates a server
                wsAlice.send("{\"type\":\"create-server\",\"name\":\"Live Room\"}");
                JsonObject srvState = waitForType(aliceMsgs, "server-state");
                long newServerId = srvState.getAsJsonObject("server").get("id").getAsLong();
                String inviteCode = srvState.getAsJsonObject("server").get("inviteCode").getAsString();
                long textChanId = srvState.getAsJsonArray("channels").get(0).getAsJsonObject().get("id").getAsLong();

                // Bob joins via invite code
                wsBob.send("{\"type\":\"join-server\",\"inviteCode\":\"" + inviteCode + "\"}");
                JsonObject bobJoined = waitForType(bobMsgs, "server-state");
                assertEquals(newServerId, bobJoined.getAsJsonObject("server").get("id").getAsLong(), "Bob joined server");

                // Alice sends a message, Bob receives it
                wsAlice.send("{\"type\":\"chat\",\"channelId\":" + textChanId + ",\"message\":\"Hi Bob!\"}");
                JsonObject bobChat = waitForType(bobMsgs, "chat");
                assertEquals("Hi Bob!", bobChat.get("message").getAsString(), "Bob received chat");

                // Bob (non-owner) tries to create a channel -> rejected
                wsBob.send("{\"type\":\"create-channel\",\"serverId\":" + newServerId + ",\"name\":\"hack\",\"channelType\":\"TEXT\"}");
                JsonObject bobErr = waitForType(bobMsgs, "error");
                assertTrue(bobErr.get("message").getAsString().contains("owner"), "Non-owner channel create rejected");

                // Alice (owner) creates a text channel -> both get server-updated
                wsAlice.send("{\"type\":\"create-channel\",\"serverId\":" + newServerId + ",\"name\":\"dev\",\"channelType\":\"TEXT\"}");
                JsonObject updated = waitForType(bobMsgs, "server-updated");
                JsonArray chs = updated.getAsJsonArray("channels");
                assertTrue(chs.size() == 3, "Bob sees 3 channels after owner adds dev");

                wsAlice.closeBlocking();
                wsBob.closeBlocking();
            } finally {
                ws.stop(500);
                http.stop(0);
            }
        }
        passed++;
        System.out.println("[PASS] testLiveHttpAndWebSocketFlow");
    }

    private static WebSocketClient connectWs(int port, BlockingQueue<JsonObject> queue) throws Exception {
        WebSocketClient client = new WebSocketClient(URI.create("ws://localhost:" + port)) {
            @Override public void onOpen(ServerHandshake handshakedata) {}
            @Override public void onMessage(String message) {
                queue.offer(JsonParser.parseString(message).getAsJsonObject());
            }
            @Override public void onClose(int code, String reason, boolean remote) {}
            @Override public void onError(Exception ex) {}
        };
        assertTrue(client.connectBlocking(5, TimeUnit.SECONDS), "WS connected");
        return client;
    }

    private static JsonObject waitForType(BlockingQueue<JsonObject> queue, String type) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            JsonObject msg = queue.poll(500, TimeUnit.MILLISECONDS);
            if (msg != null && type.equals(msg.get("type").getAsString())) {
                return msg;
            }
        }
        throw new AssertionError("Timed out waiting for WS message type: " + type);
    }
}
