import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProfileDirectoriesTest {
    @TempDir
    Path directory;

    @Test
    void duplicateProfileIdKeepsParenthesesInCanonicalPath() {
        String id = "NeoForge_1.21.1 (1)";
        Path profile = ProfileDirectories.profileDirectory(id);

        assertEquals(id, profile.getFileName().toString());
        assertEquals(profile, ProfileDirectories.launchGameDirectory(directory, id));
        assertEquals(profile.resolve("mods"), ProfileDirectories.modsDirectory(directory, id));
    }

    @Test
    void legacySanitizedDirectoryIsMergedOnceWithoutDeletingBackup() throws Exception {
        Path canonical = directory.resolve("NeoForge_1.21.1 (1)");
        Path legacy = directory.resolve("NeoForge_1.21.1 _1_");
        Files.createDirectories(canonical.resolve("mods"));
        Files.createDirectories(legacy.resolve("mods"));
        Files.writeString(canonical.resolve("mods/existing.jar"), "canonical");
        Files.writeString(legacy.resolve("mods/downloaded.jar"), "legacy");
        Files.writeString(legacy.resolve("options.txt"), "active legacy settings");

        ProfileDirectories.reconcileLegacySanitizedProfiles(directory, ProgressSink.NONE);

        assertTrue(Files.isRegularFile(canonical.resolve("mods/existing.jar")));
        assertTrue(Files.isRegularFile(canonical.resolve("mods/downloaded.jar")));
        assertEquals("active legacy settings", Files.readString(canonical.resolve("options.txt")));
        assertTrue(Files.isDirectory(legacy), "legacy directory is retained as a backup");

        Files.writeString(legacy.resolve("options.txt"), "stale legacy settings");
        ProfileDirectories.reconcileLegacySanitizedProfiles(directory, ProgressSink.NONE);
        assertEquals("active legacy settings", Files.readString(canonical.resolve("options.txt")),
                "the migration marker must prevent stale backup data from overwriting the profile again");
        assertFalse(Files.exists(canonical.resolve(".msc-profile-path-migrated")));
    }
}
