import java.io.IOException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * HTTP handler for POST /api/login.
 */
public class LoginHttpHandler implements HttpHandler {

    private final Authentication auth;

    public LoginHttpHandler(Authentication auth) {
        this.auth = auth;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        HttpHelper.addCorsHeaders(exchange);

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        try {
            String body = HttpHelper.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            String username = json.has("username") && !json.get("username").isJsonNull()
                    ? json.get("username").getAsString() : null;
            String password = json.has("password") && !json.get("password").isJsonNull()
                    ? json.get("password").getAsString() : null;

            Authentication.AuthResult result = auth.login(username, password);
            if (result.success) {
                JsonObject resp = new JsonObject();
                resp.addProperty("success", true);
                resp.addProperty("token", result.token);
                resp.addProperty("username", result.session.username);
                resp.addProperty("userId", result.session.userId);
                resp.addProperty("isGlobalAdmin", result.session.isGlobalAdmin);
                HttpHelper.sendJson(exchange, 200, resp);
            } else {
                HttpHelper.sendError(exchange, result.statusCode, result.message);
            }
        } catch (Exception e) {
            HttpHelper.sendError(exchange, 400, "Invalid JSON request");
        }
    }
}
