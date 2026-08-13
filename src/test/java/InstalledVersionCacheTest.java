import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InstalledVersionCacheTest {
    @TempDir
    Path directory;

    @Test
    void readyMarkerSurvivesRestartAndInvalidatesWhenProfileChanges() throws Exception {
        String id = "1.21.11";
        Path versionDirectory = directory.resolve("versions").resolve(id);
        Files.createDirectories(versionDirectory);
        Files.writeString(versionDirectory.resolve(id + ".json"),
                "{\"id\":\"1.21.11\"}", StandardCharsets.UTF_8);
        Files.write(versionDirectory.resolve(id + ".jar"), new byte[] {1, 2, 3});

        ResolvedVersion version = version(id, 3);
        InstalledVersionCache firstLauncherProcess = new InstalledVersionCache();
        assertFalse(firstLauncherProcess.isReady(version, directory));
        firstLauncherProcess.markReady(version, directory, ProgressSink.NONE);
        assertTrue(firstLauncherProcess.isReady(version, directory));

        InstalledVersionCache restartedLauncher = new InstalledVersionCache();
        assertTrue(restartedLauncher.isReady(version, directory));

        Files.writeString(versionDirectory.resolve(id + ".json"),
                "{\"id\":\"1.21.11\",\"type\":\"release\"}", StandardCharsets.UTF_8);
        assertFalse(restartedLauncher.isReady(version, directory));
    }

    @Test
    void missingOrTruncatedClientForcesRepair() throws Exception {
        String id = "1.21.11";
        Path versionDirectory = directory.resolve("versions").resolve(id);
        Files.createDirectories(versionDirectory);
        Files.writeString(versionDirectory.resolve(id + ".json"),
                "{\"id\":\"1.21.11\"}", StandardCharsets.UTF_8);
        Path client = versionDirectory.resolve(id + ".jar");
        Files.write(client, new byte[] {1, 2, 3});

        InstalledVersionCache cache = new InstalledVersionCache();
        ResolvedVersion version = version(id, 3);
        cache.markReady(version, directory, ProgressSink.NONE);
        assertTrue(cache.isReady(version, directory));

        Files.write(client, new byte[] {1});
        assertFalse(cache.isReady(version, directory));
        Files.delete(client);
        assertFalse(cache.isReady(version, directory));
    }

    @Test
    void adoptsCompleteVersionCreatedBeforeMarkersWereIntroduced() throws Exception {
        String id = "1.21.11";
        Path versionDirectory = directory.resolve("versions").resolve(id);
        Files.createDirectories(versionDirectory);
        Files.writeString(versionDirectory.resolve(id + ".json"),
                "{\"id\":\"1.21.11\"}", StandardCharsets.UTF_8);
        Files.write(versionDirectory.resolve(id + ".jar"), new byte[] {1, 2, 3});

        InstalledVersionCache cache = new InstalledVersionCache();
        ResolvedVersion version = version(id, 3);
        assertFalse(cache.isReady(version, directory));
        assertTrue(cache.adoptExistingInstall(version, directory, ProgressSink.NONE));
        assertTrue(new InstalledVersionCache().isReady(version, directory));
    }

    private ResolvedVersion version(String id, long clientSize) {
        return new ResolvedVersion(id, id, "release", id, "example.Main", Map.of(),
                "java-runtime-delta", 21,
                Map.of("url", "https://example.invalid/client.jar", "size", clientSize),
                List.of(), List.of(), List.of(), "");
    }
}
