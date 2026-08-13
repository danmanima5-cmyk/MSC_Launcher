import java.util.List;

record VersionManifest(String latestRelease, String latestSnapshot, List<VersionInfo> versions) {
    VersionInfo find(String id) {
        return versions.stream()
                .filter(version -> version.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new LauncherException("Версия не найдена в Minecraft API: " + id));
    }
}
