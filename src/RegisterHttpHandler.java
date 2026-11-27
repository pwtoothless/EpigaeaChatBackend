import java.io.IOException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * HTTP handler for POST /api/register.
 */
public class RegisterHttpHandler implements HttpHandler {

    private final Authentication auth;

    public RegisterHttpHandler(Authentication auth) {
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
            String adminCode = json.has("adminCode") && !json.get("adminCode").isJsonNull()
                    ? json.get("adminCode").getAsString() : null;

            Authentication.AuthResult result = auth.register(username, password, adminCode);
            if (result.success) {
                JsonObject resp = new JsonObject();
                resp.addProperty("success", true);
                resp.addProperty("message", result.message);
                resp.addProperty("username", result.session.username);
                resp.addProperty("isGlobalAdmin", result.session.isGlobalAdmin);
                HttpHelper.sendJson(exchange, result.statusCode, resp);
            } else {
                HttpHelper.sendError(exchange, result.statusCode, result.message);
            }
        } catch (Exception e) {
            HttpHelper.sendError(exchange, 400, "Invalid JSON request");
        }
    }
}
