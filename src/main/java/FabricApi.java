import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FabricApi {
    private static final String BASE = "https://meta.fabricmc.net/v2/versions/loader/";
    private static final String GAME_VERSIONS = "https://meta.fabricmc.net/v2/versions/game";
    private final HttpService http = new HttpService();
    private Set<String> cachedGameVersions;

    String latestLoaderFor(String gameVersion) {
        Object json = http.getJson(BASE + encode(gameVersion));
        List<Object> loaders = Json.list(json);
        if (loaders.isEmpty()) {
            throw new LauncherException("Fabric Loader не найден для Minecraft " + gameVersion);
        }
        for (Object item : loaders) {
            Map<String, Object> entry = Json.object(item);
            Map<String, Object> loader = Json.object(entry, "loader");
            if (Json.bool(loader, "stable", false)) {
                return Json.string(loader, "version");
            }
        }
        Map<String, Object> firstLoader = Json.object(Json.object(loaders.get(0)), "loader");
        return Json.string(firstLoader, "version");
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

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
