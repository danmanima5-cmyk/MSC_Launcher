import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Persistent proof that an installed profile completed its full download once. */
final class InstalledVersionCache {
    private static final String FORMAT = "msc-launch-ready-v1";
    private static final String MARKER_NAME = ".msc-launch-ready";

    boolean isReady(ResolvedVersion version, Path gameDirectory) {
        try {
            Path marker = markerPath(version, gameDirectory);
            if (!Files.isRegularFile(marker)) {
                return false;
            }
            String expected = signature(version, gameDirectory);
            String stored = Files.readString(marker, StandardCharsets.UTF_8).trim();
            return stored.equals(expected) && essentialFilesPresent(version, gameDirectory);
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    /** Adopts a complete profile installed by an older launcher that has no marker yet. */
    boolean adoptExistingInstall(ResolvedVersion version, Path gameDirectory, ProgressSink progress) {
        try {
            if (!essentialFilesPresent(version, gameDirectory) || !assetObjectsPresent(version)) {
                return false;
            }
            markReady(version, gameDirectory, progress);
            return isReady(version, gameDirectory);
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    void markReady(ResolvedVersion version, Path gameDirectory, ProgressSink progress) {
        Path marker = markerPath(version, gameDirectory);
        Path temporary = marker.resolveSibling(MARKER_NAME + ".tmp");
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(temporary, signature(version, gameDirectory) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ex) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The marker is an optimization; a stale temporary file is harmless.
            }
            progress.log("Не удалось сохранить состояние готовой версии: " + ex.getMessage());
        }
    }

    private boolean essentialFilesPresent(ResolvedVersion version, Path gameDirectory) {
        Map<String, Object> client = version.clientDownload();
        if (!Json.string(client, "url").isBlank()
                && !looksUsable(versionJarPath(gameDirectory, version.rootId()),
                Json.longValue(client, "size", -1))) {
            return false;
        }

        Map<String, Object> assetIndex = version.assetIndex();
        String assetId = Json.string(assetIndex, "id");
        if (!assetId.isBlank()) {
            Path index = ProfileDirectories.assetsInstallDirectory().resolve("indexes")
                    .resolve(assetId + ".json");
            if (!looksUsable(index, Json.longValue(assetIndex, "size", -1))) {
                return false;
            }
        }
        return true;
    }

    private boolean assetObjectsPresent(ResolvedVersion version) throws IOException {
        Map<String, Object> assetIndex = version.assetIndex();
        String assetId = Json.string(assetIndex, "id");
        if (assetId.isBlank()) {
            return true;
        }
        // Old virtual/resource layouts need their derived copies prepared by downloadAssets.
        if ("legacy".equals(version.assets()) || "pre-1.6".equals(version.assets())) {
            return false;
        }
        Path assetsRoot = ProfileDirectories.assetsInstallDirectory();
        Path indexPath = assetsRoot.resolve("indexes").resolve(assetId + ".json");
        Map<String, Object> index = Json.object(Json.parse(
                Files.readString(indexPath, StandardCharsets.UTF_8)));
        if (Json.bool(index, "virtual", false) || Json.bool(index, "map_to_resources", false)) {
            return false;
        }
        for (Object value : Json.object(index, "objects").values()) {
            Map<String, Object> object = Json.object(value);
            String hash = Json.string(object, "hash");
            if (hash.length() < 2) {
                return false;
            }
            Path target = assetsRoot.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
            if (!looksUsable(target, Json.longValue(object, "size", -1))) {
                return false;
            }
        }
        return true;
    }

    static boolean looksUsable(Path file, long expectedSize) {
        try {
            if (!Files.isRegularFile(file)) {
                return false;
            }
            long size = Files.size(file);
            return size > 0 && (expectedSize <= 0 || size == expectedSize);
        } catch (IOException ex) {
            return false;
        }
    }

    private String signature(ResolvedVersion version, Path gameDirectory) throws IOException {
        StringBuilder value = new StringBuilder(FORMAT)
                .append('\n').append(version.id())
                .append('\n').append(version.rootId());
        String current = version.id();
        Set<String> visited = new HashSet<>();
        while (current != null && !current.isBlank() && visited.add(current)) {
            Path jsonPath = versionJsonPath(gameDirectory, current);
            value.append('\n').append(current).append('=').append(Hashing.sha1(jsonPath));
            Map<String, Object> json = Json.object(Json.parse(
                    Files.readString(jsonPath, StandardCharsets.UTF_8)));
            current = Json.string(json, "inheritsFrom");
        }
        if (current != null && !current.isBlank()) {
            throw new IOException("Циклическое наследование профиля " + version.id());
        }
        return value.toString();
    }

    private Path markerPath(ResolvedVersion version, Path gameDirectory) {
        return versionJsonPath(gameDirectory, version.id()).getParent().resolve(MARKER_NAME);
    }

    private Path versionJsonPath(Path gameDirectory, String versionId) {
        return ProfileDirectories.versionsInstallDirectory(gameDirectory, versionId)
                .resolve(versionId).resolve(versionId + ".json");
    }

    private Path versionJarPath(Path gameDirectory, String versionId) {
        return ProfileDirectories.versionsInstallDirectory(gameDirectory, versionId)
                .resolve(versionId).resolve(versionId + ".jar");
    }
}
