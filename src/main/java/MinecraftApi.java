import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class MinecraftApi {
    private static final String VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private final HttpService http = new HttpService();
    private VersionManifest cachedManifest;

    VersionManifest fetchManifest() {
        if (cachedManifest != null) {
            return cachedManifest;
        }
        Map<String, Object> root = http.getJsonObject(VERSION_MANIFEST);
        Map<String, Object> latest = Json.object(root, "latest");
        List<VersionInfo> versions = new ArrayList<>();
        for (Object item : Json.list(root, "versions")) {
            Map<String, Object> entry = Json.object(item);
            versions.add(new VersionInfo(
                    Json.string(entry, "id"),
                    Json.string(entry, "type"),
                    Json.string(entry, "url"),
                    Json.string(entry, "releaseTime"),
                    Json.string(entry, "sha1")
            ));
        }
        cachedManifest = new VersionManifest(Json.string(latest, "release"), Json.string(latest, "snapshot"), versions);
        return cachedManifest;
    }

    String fetchVersionJson(String versionId) {
        VersionInfo info = fetchManifest().find(versionId);
        return http.getString(info.url());
    }

    HttpService http() {
        return http;
    }
}
