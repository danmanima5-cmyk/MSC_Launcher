import java.nio.file.Path;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

final class AuthlibInjectorService {
    private static final String VERSION = "1.2.5";
    private static final String SHA1 = "1eca6aa7faf7ac6e3211862afa6e43fe2eedd07b";
    private static final String DOWNLOAD_URL = "https://repo1.maven.org/maven2/org/glavo/hmcl/authlib-injector/"
            + VERSION + "/authlib-injector-" + VERSION + ".jar";
    private static final String ELY_BY_PROFILE_URL = "https://authserver.ely.by/api/users/profiles/minecraft/";

    private final HttpService http = new HttpService();

    Path ensureInstalled(ProgressSink progress) {
        Path target = LauncherSettings.settingsDirectory()
                .resolve("authlib-injector")
                .resolve("authlib-injector-" + VERSION + ".jar");
        http.download(DOWNLOAD_URL, target, SHA1, -1, progress);
        return target;
    }

    Optional<String> resolveElyByUuid(String username, ProgressSink progress) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        try {
            String encodedName = URLEncoder.encode(username.trim(), StandardCharsets.UTF_8).replace("+", "%20");
            Map<String, Object> profile = http.getJsonObject(ELY_BY_PROFILE_URL + encodedName);
            String uuid = Json.string(profile, "id").replace("-", "").trim();
            return uuid.isBlank() ? Optional.empty() : Optional.of(uuid);
        } catch (LauncherException ex) {
            progress.log("Ely.by profile not found for " + username + ": " + ex.getMessage());
            return Optional.empty();
        }
    }
}
