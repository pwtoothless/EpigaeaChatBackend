import java.io.IOException;
import java.net.InetSocketAddress;
import java.sql.SQLException;

import com.sun.net.httpserver.HttpServer;

public class Main {

    private static final int HTTP_PORT = 8082;
    private static final int WS_PORT = 8081;

    public static void main(String[] args) throws IOException, SQLException {
        DatabaseManager db = new DatabaseManager();
        Authentication auth = new Authentication(db);
        ServerState state = new ServerState(db, auth);

        HttpServer httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        httpServer.createContext("/api/login", new LoginHttpHandler(auth));
        httpServer.createContext("/api/register", new RegisterHttpHandler(auth));
        httpServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());

        ChatWebSocketServer webSocketServer = new ChatWebSocketServer(WS_PORT, state);

        httpServer.start();
        webSocketServer.start();

        System.out.println("HTTP Server started on port " + HTTP_PORT);
        System.out.println("WebSocket Server started on port " + WS_PORT);
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            httpServer.stop(0); // Stop immediately (0 seconds delay)
            //executor.shutdownNow(); // Force non-daemon worker threads to close
        }));
    }
}
