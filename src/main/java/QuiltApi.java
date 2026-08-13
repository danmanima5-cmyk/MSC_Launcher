import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class QuiltApi {
    private static final String BASE = "https://meta.quiltmc.org/v3/versions/loader/";
    private static final String GAME_VERSIONS = "https://meta.quiltmc.org/v3/versions/game";
    private final HttpService http = new HttpService();
    private Set<String> cachedGameVersions;

    String latestLoaderFor(String gameVersion) {
        List<Object> loaders = Json.list(http.getJson(BASE + encode(gameVersion)));
        if (loaders.isEmpty()) {
            throw new LauncherException("Quilt Loader не найден для Minecraft " + gameVersion);
        }

        String firstVersion = "";
        for (Object item : loaders) {
            Map<String, Object> entry = Json.object(item);
            Map<String, Object> loader = Json.object(entry, "loader");
            String version = Json.string(loader.isEmpty() ? entry : loader, "version");
            if (version.isBlank()) {
                continue;
            }
            if (firstVersion.isBlank()) {
                firstVersion = version;
            }
            boolean stable = Json.bool(loader.isEmpty() ? entry : loader, "stable", !isBeta(version));
            if (stable && !isBeta(version)) {
                return version;
            }
        }

        if (!firstVersion.isBlank()) {
            return firstVersion;
        }
        throw new LauncherException("Quilt Loader не найден для Minecraft " + gameVersion);
    }

    String fetchProfileJson(String gameVersion, String loaderVersion) {
        return http.getString(BASE + encode(gameVersion) + "/" + encode(loaderVersion) + "/profile/json");
    }

    Set<String> supportedGameVersions() {
        if (cachedGameVersions != null) {
            return cachedGameVersions;
        }
        LinkedHashSet<String> versions = new LinkedHashSet<>();
        for (Object item : Json.list(http.getJson(GAME_VERSIONS))) {
            String version = Json.string(Json.object(item), "version");
            if (!version.isBlank()) {
                versions.add(version);
            }
        }
        cachedGameVersions = Set.copyOf(versions);
        return cachedGameVersions;
    }

    private boolean isBeta(String version) {
        return version.toLowerCase(Locale.ROOT).contains("beta");
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
