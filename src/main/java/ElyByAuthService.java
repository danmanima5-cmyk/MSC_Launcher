import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Window;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ElyByAuthService {
    private static final String AUTHORIZE_URL = "https://account.ely.by/oauth2/v1";
    private static final String TOKEN_URL = "https://account.ely.by/api/oauth2/v1/token";
    private static final String ACCOUNT_INFO_URL = "https://account.ely.by/api/account/v1/info";
    private static final String SCOPE = "account_info offline_access minecraft_server_session";
    private static final String PROMPT = "select_account,consent";

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final Window authWindowOwner;
    private final HttpService http = new HttpService();

    ElyByAuthService(String clientId, String clientSecret, String redirectUri) {
        this(clientId, clientSecret, redirectUri, null);
    }

    ElyByAuthService(String clientId, String clientSecret, String redirectUri, Window authWindowOwner) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.authWindowOwner = authWindowOwner;
    }

    Account login(ProgressSink progress) {
        URI redirect = URI.create(redirectUri);
        String state = UUID.randomUUID().toString();
        CompletableFuture<CallbackRequest> callback = new CompletableFuture<>();
        CompletableFuture<Void> browserClose = new CompletableFuture<>();
        browserClose.whenComplete((ignored, error) -> {
            if (error != null && !callback.isDone()) {
                callback.completeExceptionally(error);
            }
        });
        HttpServer server = startCallbackServer(redirect, state, callback);
        try {
            String authUrl = AUTHORIZE_URL
                    + "?client_id=" + encode(clientId)
                    + "&redirect_uri=" + encode(redirectUri)
                    + "&response_type=code"
                    + "&scope=" + encode(SCOPE)
                    + "&state=" + encode(state)
                    + "&prompt=" + encode(PROMPT);
            progress.status("Ely.by вход в окне лаунчера");
            openBrowser(authUrl, callback, browserClose);

            CallbackRequest request = waitForCallback(callback);
            try {
                Map<String, String> params = request.params();
                if (params.containsKey("error")) {
                    throw new LauncherException("Ely.by authorization failed: " + params.getOrDefault("error_message", params.get("error")));
                }
                String code = params.getOrDefault("code", "");
                if (code.isBlank()) {
                    throw new LauncherException("Ely.by did not return an authorization code.");
                }
                Account account = exchangeCode(code, progress);
                request.respond(successPage(account));
                return account;
            } catch (RuntimeException ex) {
                request.respond(errorPage(ex.getMessage()));
                throw ex;
            }
        } finally {
            browserClose.complete(null);
            server.stop(0);
        }
    }

    Account refresh(Account account, ProgressSink progress) {
        if (account.type() != Account.Type.ELY_BY || account.refreshToken().isBlank()) {
            return account;
        }
        if (Instant.now().isBefore(account.expiresAt().minusSeconds(120))) {
            return account;
        }
        progress.status("Refreshing Ely.by token");
        String body = http.postForm(TOKEN_URL, Map.of(
                "client_id", clientId,
                "client_secret", clientSecret,
                "grant_type", "refresh_token",
                "refresh_token", account.refreshToken(),
                "scope", SCOPE
        ));
        Map<String, Object> token = Json.object(Json.parse(body));
        String accessToken = Json.string(token, "access_token");
        String refreshToken = Json.string(token, "refresh_token");
        if (refreshToken.isBlank()) {
            refreshToken = account.refreshToken();
        }
        return loadAccount(accessToken, refreshToken, Json.longValue(token, "expires_in", 86400), progress);
    }

    private Account exchangeCode(String code, ProgressSink progress) {
        progress.status("Ely.by token exchange");
        String body = http.postForm(TOKEN_URL, Map.of(
                "client_id", clientId,
                "client_secret", clientSecret,
                "redirect_uri", redirectUri,
                "grant_type", "authorization_code",
                "code", code
        ));
        Map<String, Object> token = Json.object(Json.parse(body));
        return loadAccount(
                Json.string(token, "access_token"),
                Json.string(token, "refresh_token"),
                Json.longValue(token, "expires_in", 86400),
                progress
        );
    }

    private Account loadAccount(String accessToken, String refreshToken, long expiresIn, ProgressSink progress) {
        if (accessToken.isBlank()) {
            throw new LauncherException("Ely.by did not return an access token.");
        }
        progress.status("Ely.by profile");
        Map<String, Object> info = http.getAuthorizedJsonObject(ACCOUNT_INFO_URL, accessToken);
        String username = Json.string(info, "username");
        String uuid = normalizeUuid(Json.string(info, "uuid"));
        if (username.isBlank() || uuid.isBlank()) {
            throw new LauncherException("Ely.by profile is incomplete.");
        }
        return new Account(Account.Type.ELY_BY, username, uuid, accessToken, refreshToken, Instant.now().plusSeconds(expiresIn), "");
    }

    private HttpServer startCallbackServer(URI redirect, String expectedState, CompletableFuture<CallbackRequest> callback) {
        String host = redirect.getHost() == null || redirect.getHost().isBlank() ? "localhost" : redirect.getHost();
        int port = redirect.getPort() <= 0 ? 80 : redirect.getPort();
        String path = redirect.getPath() == null || redirect.getPath().isBlank() ? "/" : redirect.getPath();
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext(path, exchange -> handleCallback(exchange, expectedState, callback));
            server.start();
            return server;
        } catch (IOException ex) {
            throw new LauncherException("Could not start local Ely.by callback server on " + host + ":" + port + ": " + ex.getMessage(), ex);
        }
    }

    private void handleCallback(HttpExchange exchange, String expectedState, CompletableFuture<CallbackRequest> callback) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        CallbackRequest request = new CallbackRequest(params);
        if (!expectedState.equals(params.get("state"))) {
            request.respond(errorPage("Invalid Ely.by login state."));
        } else if (!callback.complete(request)) {
            request.respond(errorPage("This Ely.by login request was already handled."));
        }
        writeHtml(exchange, request.awaitResponse());
    }

    private CallbackRequest waitForCallback(CompletableFuture<CallbackRequest> callback) {
        try {
            return callback.get(180, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            throw new LauncherException("Timed out waiting for Ely.by authorization.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LauncherException("Ely.by authorization was interrupted.", ex);
        } catch (Exception ex) {
            if (ex.getCause() instanceof LauncherException launcherException) {
                throw launcherException;
            }
            throw new LauncherException("Ely.by authorization failed: " + ex.getMessage(), ex);
        }
    }

    private String successPage(Account account) {
        return """
                <!doctype html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <title>MSC Launcher - Ely.by</title>
                    <style>
                        body { font-family: Arial, sans-serif; max-width: 720px; margin: 48px auto; padding: 0 24px; line-height: 1.5; color: #222; }
                        .status { padding: 16px; border: 1px solid #77b255; background: #f0fff0; }
                        a { color: #175fa8; }
                    </style>
                </head>
                <body>
                    <h1>Ely.by account connected</h1>
                    <div class="status">
                        <b>Account:</b> %s<br>
                        <b>Launcher:</b> MSC Launcher
                    </div>
                    <p>Minecraft will use this Ely.by account. Skins are loaded in game through Ely.by authlib-injector when you launch Minecraft from MSC Launcher.</p>
                    <p><a href="https://dev.ely.by/skins/add">Open Ely.by Skin System</a></p>
                    <p>You can close this tab and return to MSC Launcher.</p>
                </body>
                </html>
                """.formatted(escapeHtml(account.username()));
    }

    private String errorPage(String message) {
        return """
                <!doctype html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <title>MSC Launcher - Ely.by</title>
                    <style>
                        body { font-family: Arial, sans-serif; max-width: 720px; margin: 48px auto; padding: 0 24px; line-height: 1.5; color: #222; }
                        .status { padding: 16px; border: 1px solid #cc4444; background: #fff2f2; }
                    </style>
                </head>
                <body>
                    <h1>Ely.by login failed</h1>
                    <div class="status">%s</div>
                    <p>Return to MSC Launcher and try again.</p>
                </body>
                </html>
                """.formatted(escapeHtml(message == null || message.isBlank() ? "Unknown error." : message));
    }

    private void writeHtml(HttpExchange exchange, String html) throws IOException {
        byte[] response = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }

    private String escapeHtml(String value) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }

    private void openBrowser(String url, CompletableFuture<CallbackRequest> callback,
                             CompletableFuture<Void> browserClose) {
        AuthBrowserWindow.open(
                authWindowOwner,
                "Ely.by вход",
                url,
                browserClose,
                () -> callback.completeExceptionally(new LauncherException("Ely.by вход отменен."))
        );
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isBlank()) {
            return params;
        }
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            String key = separator < 0 ? part : part.substring(0, separator);
            String value = separator < 0 ? "" : part.substring(separator + 1);
            params.put(decode(key), decode(value));
        }
        return params;
    }

    private String normalizeUuid(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "").trim();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static final class CallbackRequest {
        private final Map<String, String> params;
        private final CompletableFuture<String> response = new CompletableFuture<>();

        CallbackRequest(Map<String, String> params) {
            this.params = params;
        }

        Map<String, String> params() {
            return params;
        }

        void respond(String html) {
            response.complete(html);
        }

        String awaitResponse() {
            try {
                return response.get(30, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return """
                        <!doctype html><html><head><meta charset="utf-8"><title>MSC Launcher - Ely.by</title></head>
                        <body><h1>Ely.by login interrupted</h1><p>Return to MSC Launcher and try again.</p></body></html>
                        """;
            } catch (Exception ex) {
                return """
                        <!doctype html><html><head><meta charset="utf-8"><title>MSC Launcher - Ely.by</title></head>
                        <body><h1>Ely.by login is still processing</h1><p>Return to MSC Launcher.</p></body></html>
                        """;
            }
        }
    }
}
