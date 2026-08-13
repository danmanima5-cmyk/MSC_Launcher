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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class MicrosoftAuthService {
    private static final String AUTHORIZE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
    private static final String DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MINECRAFT_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String ENTITLEMENTS_URL = "https://api.minecraftservices.com/entitlements/mcstore";
    private static final String PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final String PROMPT = "select_account";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String clientId;
    private final Window authWindowOwner;
    private final HttpService http = new HttpService();

    MicrosoftAuthService(String clientId) {
        this(clientId, null);
    }

    MicrosoftAuthService(String clientId, Window authWindowOwner) {
        this.clientId = clientId;
        this.authWindowOwner = authWindowOwner;
    }

    Account login(String redirectUri, ProgressSink progress) {
        URI redirect = URI.create(redirectUri);
        String state = UUID.randomUUID().toString();
        String codeVerifier = createCodeVerifier();
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
                    + "&response_type=code"
                    + "&redirect_uri=" + encode(redirectUri)
                    + "&scope=" + encode(SCOPE)
                    + "&state=" + encode(state)
                    + "&prompt=" + encode(PROMPT)
                    + "&code_challenge=" + encode(codeChallenge(codeVerifier))
                    + "&code_challenge_method=S256";
            progress.status("Ожидание Microsoft входа в окне лаунчера");
            openBrowser(authUrl, callback, browserClose);

            CallbackRequest request = waitForCallback(callback);
            try {
                Map<String, String> params = request.params();
                if (params.containsKey("error")) {
                    throw new LauncherException("Microsoft вход не выполнен: "
                            + params.getOrDefault("error_description", params.get("error")));
                }
                String code = params.getOrDefault("code", "");
                if (code.isBlank()) {
                    throw new LauncherException("Microsoft не вернул authorization code.");
                }
                Account account = exchangeCode(code, redirectUri, codeVerifier, progress);
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

    DeviceCode requestDeviceCode() {
        String body = http.postForm(DEVICE_CODE_URL, Map.of(
                "client_id", clientId,
                "scope", SCOPE
        ));
        Map<String, Object> json = Json.object(Json.parse(body));
        return new DeviceCode(
                Json.string(json, "device_code"),
                Json.string(json, "user_code"),
                Json.string(json, "verification_uri"),
                Json.string(json, "message"),
                (int) Json.longValue(json, "expires_in", 900),
                (int) Json.longValue(json, "interval", 5)
        );
    }

    Account completeDeviceLogin(DeviceCode deviceCode, ProgressSink progress) {
        Instant deadline = Instant.now().plusSeconds(deviceCode.expiresIn());
        int interval = Math.max(1, deviceCode.interval());
        while (Instant.now().isBefore(deadline)) {
            progress.status("Ожидание Microsoft авторизации");
            try {
                Thread.sleep(interval * 1000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new LauncherException("Microsoft вход прерван.", ex);
            }
            try {
                String tokenBody = http.postForm(TOKEN_URL, Map.of(
                        "client_id", clientId,
                        "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                        "device_code", deviceCode.deviceCode()
                ));
                Map<String, Object> token = Json.object(Json.parse(tokenBody));
                return completeMinecraftLogin(
                        Json.string(token, "access_token"),
                        Json.string(token, "refresh_token"),
                        Json.longValue(token, "expires_in", 3600),
                        progress
                );
            } catch (LauncherException ex) {
                String message = ex.getMessage();
                if (message != null && message.contains("authorization_pending")) {
                    continue;
                }
                if (message != null && message.contains("slow_down")) {
                    interval += 5;
                    continue;
                }
                throw ex;
            }
        }
        throw new LauncherException("Время ожидания Microsoft авторизации истекло.");
    }

    Account refresh(Account account, ProgressSink progress) {
        return refresh(account, progress, false);
    }

    Account refresh(Account account, ProgressSink progress, boolean force) {
        if (account.type() != Account.Type.MICROSOFT || account.refreshToken().isBlank()) {
            return account;
        }
        if (!force && Instant.now().isBefore(account.expiresAt().minusSeconds(120))) {
            return account;
        }
        String body = http.postForm(TOKEN_URL, Map.of(
                "client_id", clientId,
                "grant_type", "refresh_token",
                "refresh_token", account.refreshToken(),
                "scope", SCOPE
        ));
        Map<String, Object> token = Json.object(Json.parse(body));
        String refreshToken = Json.string(token, "refresh_token");
        if (refreshToken.isBlank()) {
            refreshToken = account.refreshToken();
        }
        return completeMinecraftLogin(
                Json.string(token, "access_token"),
                refreshToken,
                Json.longValue(token, "expires_in", 3600),
                progress
        );
    }

    private Account exchangeCode(String code, String redirectUri, String codeVerifier, ProgressSink progress) {
        progress.status("Обмен Microsoft authorization code");
        String body = http.postForm(TOKEN_URL, Map.of(
                "client_id", clientId,
                "code", code,
                "grant_type", "authorization_code",
                "redirect_uri", redirectUri,
                "code_verifier", codeVerifier,
                "scope", SCOPE
        ));
        Map<String, Object> token = Json.object(Json.parse(body));
        return completeMinecraftLogin(
                Json.string(token, "access_token"),
                Json.string(token, "refresh_token"),
                Json.longValue(token, "expires_in", 3600),
                progress
        );
    }

    private Account completeMinecraftLogin(String microsoftAccessToken, String refreshToken, long microsoftExpiresIn, ProgressSink progress) {
        progress.status("Xbox Live авторизация");
        Map<String, Object> xblBody = HttpService.map(
                "Properties", HttpService.map(
                        "AuthMethod", "RPS",
                        "SiteName", "user.auth.xboxlive.com",
                        "RpsTicket", "d=" + microsoftAccessToken
                ),
                "RelyingParty", "http://auth.xboxlive.com",
                "TokenType", "JWT"
        );
        Map<String, Object> xbl = Json.object(Json.parse(http.postJson(XBL_AUTH_URL, xblBody)));
        String xblToken = Json.string(xbl, "Token");
        String uhs = userHash(xbl);

        progress.status("XSTS авторизация");
        Map<String, Object> xstsBody = HttpService.map(
                "Properties", HttpService.map(
                        "SandboxId", "RETAIL",
                        "UserTokens", List.of(xblToken)
                ),
                "RelyingParty", "rp://api.minecraftservices.com/",
                "TokenType", "JWT"
        );
        Map<String, Object> xsts = Json.object(Json.parse(http.postJson(XSTS_AUTH_URL, xstsBody)));
        String xstsToken = Json.string(xsts, "Token");

        progress.status("Minecraft Services авторизация");
        Map<String, Object> mcLoginBody = HttpService.map("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        Map<String, Object> mcLogin = Json.object(Json.parse(http.postJson(MINECRAFT_LOGIN_URL, mcLoginBody)));
        String mcAccessToken = Json.string(mcLogin, "access_token");
        long mcExpiresIn = Json.longValue(mcLogin, "expires_in", microsoftExpiresIn);

        progress.status("Проверка лицензии Minecraft");
        Map<String, Object> entitlements = http.getAuthorizedJsonObject(ENTITLEMENTS_URL, mcAccessToken);
        if (Json.list(entitlements, "items").isEmpty()) {
            throw new LauncherException("На этом Microsoft-аккаунте не найдена лицензия Minecraft Java Edition.");
        }

        Map<String, Object> profile = http.getAuthorizedJsonObject(PROFILE_URL, mcAccessToken);
        String id = Json.string(profile, "id");
        String name = Json.string(profile, "name");
        if (id.isBlank() || name.isBlank()) {
            throw new LauncherException("Minecraft profile не найден для аккаунта.");
        }
        return new Account(Account.Type.MICROSOFT, name, id, mcAccessToken, refreshToken, Instant.now().plusSeconds(mcExpiresIn), uhs);
    }

    private String userHash(Map<String, Object> response) {
        Map<String, Object> displayClaims = Json.object(response, "DisplayClaims");
        List<Object> xui = Json.list(displayClaims, "xui");
        if (xui.isEmpty()) {
            throw new LauncherException("Xbox Live не вернул user hash.");
        }
        return Json.string(Json.object(xui.get(0)), "uhs");
    }

    private String createCodeVerifier() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return base64Url(bytes);
    }

    private String codeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return base64Url(digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception ex) {
            throw new LauncherException("Не удалось подготовить Microsoft PKCE challenge: " + ex.getMessage(), ex);
        }
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
            throw new LauncherException("Не удалось запустить Microsoft callback на " + host + ":" + port + ": " + ex.getMessage(), ex);
        }
    }

    private void handleCallback(HttpExchange exchange, String expectedState, CompletableFuture<CallbackRequest> callback) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        CallbackRequest request = new CallbackRequest(params);
        if (!expectedState.equals(params.get("state"))) {
            request.respond(errorPage("Некорректный state в Microsoft callback."));
        } else if (!callback.complete(request)) {
            request.respond(errorPage("Этот Microsoft callback уже обработан."));
        }
        writeHtml(exchange, request.awaitResponse());
    }

    private CallbackRequest waitForCallback(CompletableFuture<CallbackRequest> callback) {
        try {
            return callback.get(180, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            throw new LauncherException("Истекло время ожидания Microsoft входа.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LauncherException("Microsoft вход прерван.", ex);
        } catch (Exception ex) {
            if (ex.getCause() instanceof LauncherException launcherException) {
                throw launcherException;
            }
            throw new LauncherException("Microsoft вход не выполнен: " + ex.getMessage(), ex);
        }
    }

    private String successPage(Account account) {
        return """
                <!doctype html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <title>MSC Launcher - Microsoft</title>
                    <style>
                        body { font-family: Arial, sans-serif; max-width: 720px; margin: 48px auto; padding: 0 24px; line-height: 1.5; color: #222; }
                        .status { padding: 16px; border: 1px solid #77b255; background: #f0fff0; }
                    </style>
                </head>
                <body>
                    <h1>Microsoft account connected</h1>
                    <div class="status">
                        <b>Account:</b> %s<br>
                        <b>Launcher:</b> MSC Launcher
                    </div>
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
                    <title>MSC Launcher - Microsoft</title>
                    <style>
                        body { font-family: Arial, sans-serif; max-width: 720px; margin: 48px auto; padding: 0 24px; line-height: 1.5; color: #222; }
                        .status { padding: 16px; border: 1px solid #cc4444; background: #fff2f2; }
                    </style>
                </head>
                <body>
                    <h1>Microsoft login failed</h1>
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

    private void openBrowser(String url, CompletableFuture<CallbackRequest> callback,
                             CompletableFuture<Void> browserClose) {
        try {
            AuthBrowserWindow.open(
                    authWindowOwner,
                    "Вход в Microsoft",
                    url,
                    browserClose,
                    () -> callback.completeExceptionally(new LauncherException("Microsoft вход отменен."))
            );
        } catch (Throwable javaFxUnavailable) {
            // JavaFX is optional in current desktop JREs.  Do not abort OAuth when
            // the embedded WebView cannot be linked (for example NoClassDefFoundError:
            // javafx/embed/swing/JFXPanel); the loopback callback works equally well
            // from the user's normal browser.
            try {
                BrowserUtil.openUrl(url);
            } catch (RuntimeException browserError) {
                browserError.addSuppressed(javaFxUnavailable);
                callback.completeExceptionally(new LauncherException(
                        "Не удалось открыть страницу Microsoft в системном браузере: "
                                + browserError.getMessage(), browserError));
            }
        }
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
                        <!doctype html><html><head><meta charset="utf-8"><title>MSC Launcher - Microsoft</title></head>
                        <body><h1>Microsoft login interrupted</h1><p>Return to MSC Launcher and try again.</p></body></html>
                        """;
            } catch (Exception ex) {
                return """
                        <!doctype html><html><head><meta charset="utf-8"><title>MSC Launcher - Microsoft</title></head>
                        <body><h1>Microsoft login is still processing</h1><p>Return to MSC Launcher.</p></body></html>
                        """;
            }
        }
    }
}
