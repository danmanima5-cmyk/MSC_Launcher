import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ModPreparedPlanInstallTest {
    @TempDir
    Path directory;

    @Test
    void commitsRootAndAddonOnlyAfterEveryFilePassesPreflight() throws Exception {
        Path cache = Files.createDirectories(directory.resolve("cache"));
        Path mods = Files.createDirectories(directory.resolve("mods"));
        PreparedModInstall root = prepared(cache, "root.jar", "rootmod");
        PreparedModInstall addon = prepared(cache, "addon.jar", "addonmod");
        ModInstallPlan plan = plan(root, addon);

        installer().installPreparedPlanIntoDirectory(
                plan, List.of(root, addon), mods, "test-profile", ProgressSink.NONE);

        assertArrayEquals(Files.readAllBytes(root.cachedFile()),
                Files.readAllBytes(mods.resolve("root.jar")));
        assertArrayEquals(Files.readAllBytes(addon.cachedFile()),
                Files.readAllBytes(mods.resolve("addon.jar")));
    }

    @Test
    void failedPreflightLeavesExistingRootUntouchedAndCleansStaging() throws Exception {
        Path cache = Files.createDirectories(directory.resolve("cache"));
        Path mods = Files.createDirectories(directory.resolve("mods"));
        PreparedModInstall root = prepared(cache, "root.jar", "rootmod");
        PreparedModInstall addon = prepared(cache, "addon.jar", "addonmod");
        byte[] original = "existing root".getBytes(StandardCharsets.UTF_8);
        Files.write(mods.resolve("root.jar"), original);
        Files.createDirectory(mods.resolve("addon.jar"));

        assertThrows(LauncherException.class, () -> installer().installPreparedPlanIntoDirectory(
                plan(root, addon), List.of(root, addon), mods, "test-profile", ProgressSink.NONE));

        assertArrayEquals(original, Files.readAllBytes(mods.resolve("root.jar")));
        try (var files = Files.list(mods)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void duplicateProvidedModIdIsRejectedBeforeProfileWrites() throws Exception {
        Path cache = Files.createDirectories(directory.resolve("cache"));
        Path mods = Files.createDirectories(directory.resolve("mods"));
        PreparedModInstall root = prepared(cache, "root.jar", "sharedid");
        PreparedModInstall addon = prepared(cache, "addon.jar", "sharedid");

        assertThrows(LauncherException.class, () -> installer().installPreparedPlanIntoDirectory(
                plan(root, addon), List.of(root, addon), mods, "test-profile", ProgressSink.NONE));

        assertFalse(Files.exists(mods.resolve("root.jar")));
        assertFalse(Files.exists(mods.resolve("addon.jar")));
        try (var files = Files.list(mods)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void safetyPreflightRejectsWrongLoaderWithoutWritingProfile() throws Exception {
        Path cache = Files.createDirectories(directory.resolve("cache"));
        Path mods = Files.createDirectories(directory.resolve("mods"));
        PreparedModInstall prepared = prepared(cache, "forge-addon.jar", "forge_addon");
        ModFileRelease forgeOnly = new ModFileRelease(
                prepared.release().id(), prepared.release().versionName(),
                prepared.release().versionNumber(), prepared.release().fileName(),
                prepared.release().releaseType(), prepared.release().publishedAt(),
                List.of("1.20.1"), List.of("forge"), prepared.release().downloadUrl(),
                prepared.release().sha1(), prepared.release().size(), List.of());
        PreparedModInstall forgePrepared = new PreparedModInstall(forgeOnly, prepared.cachedFile());
        ModInstallPlan plan = new ModInstallPlan(List.of(
                new ModInstallEntry(project("forge-addon"), forgeOnly)), false);

        assertThrows(LauncherException.class, () -> installer().validatePreparedPlan(
                plan, List.of(forgePrepared), mods, "1.20.1", "fabric"));

        try (var files = Files.list(mods)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void safetyPreflightRejectsDuplicateInstalledModIdWithoutReplacingAnything() throws Exception {
        Path cache = Files.createDirectories(directory.resolve("cache"));
        Path mods = Files.createDirectories(directory.resolve("mods"));
        PreparedModInstall candidate = prepared(cache, "new-addon.jar", "duplicate_id");
        Path installed = mods.resolve("already-installed.jar");
        Files.copy(candidate.cachedFile(), installed);
        byte[] original = Files.readAllBytes(installed);
        ModInstallPlan plan = new ModInstallPlan(List.of(
                new ModInstallEntry(project("new-addon"), candidate.release())), false);

        assertThrows(LauncherException.class, () -> installer().validatePreparedPlan(
                plan, List.of(candidate), mods, "1.20.1", "fabric"));

        assertArrayEquals(original, Files.readAllBytes(installed));
        assertFalse(Files.exists(mods.resolve("new-addon.jar")));
    }

    @Test
    void safetyPreflightDoesNotTreatManifestBreaksAsUnconditionalConflict() throws Exception {
        Path cache = Files.createDirectories(directory.resolve("cache"));
        Path mods = Files.createDirectories(directory.resolve("mods"));
        PreparedModInstall installedMod = prepared(cache, "installed-source.jar", "old_renderer");
        Files.copy(installedMod.cachedFile(), mods.resolve("old-renderer.jar"));
        PreparedModInstall dangerous = prepared(
                cache, "dangerous-addon.jar", "dangerous_addon", "old_renderer");
        ModInstallPlan plan = new ModInstallPlan(List.of(
                new ModInstallEntry(project("dangerous-addon"), dangerous.release())), false);

        installer().validatePreparedPlan(plan, List.of(dangerous), mods, "1.20.1", "fabric");

        assertFalse(Files.exists(mods.resolve("dangerous-addon.jar")));
        assertTrue(Files.isRegularFile(mods.resolve("old-renderer.jar")));
    }

    @Test
    void safetyPreflightDoesNotTreatInstalledManifestBreaksAsUnconditionalConflict() throws Exception {
        Path cache = Files.createDirectories(directory.resolve("cache"));
        Path mods = Files.createDirectories(directory.resolve("mods"));
        PreparedModInstall installedMod = prepared(
                cache, "protective-source.jar", "protective_mod", "unsafe_addon");
        Files.copy(installedMod.cachedFile(), mods.resolve("protective-mod.jar"));
        PreparedModInstall unsafeAddon = prepared(
                cache, "unsafe-addon.jar", "unsafe_addon");
        ModInstallPlan plan = new ModInstallPlan(List.of(
                new ModInstallEntry(project("unsafe-addon"), unsafeAddon.release())), false);

        installer().validatePreparedPlan(plan, List.of(unsafeAddon), mods, "1.20.1", "fabric");

        assertFalse(Files.exists(mods.resolve("unsafe-addon.jar")));
        assertTrue(Files.isRegularFile(mods.resolve("protective-mod.jar")));
    }

    private ModContentInstaller installer() {
        return new ModContentInstaller(null, null, null, null, null);
    }

    private ModInstallPlan plan(PreparedModInstall root, PreparedModInstall addon) {
        return new ModInstallPlan(List.of(
                new ModInstallEntry(project("root"), root.release()),
                new ModInstallEntry(project("addon"), addon.release())), false);
    }

    private PreparedModInstall prepared(Path cache, String fileName, String modId) throws Exception {
        return prepared(cache, fileName, modId, "");
    }

    private PreparedModInstall prepared(Path cache, String fileName, String modId,
                                        String breaksModId) throws Exception {
        Path jar = cache.resolve(fileName);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            String breaks = breaksModId == null || breaksModId.isBlank() ? ""
                    : ",\"breaks\":{\"" + breaksModId + "\":\"*\"}";
            zip.write(("{\"schemaVersion\":1,\"id\":\"" + modId
                    + "\",\"version\":\"1.0\"" + breaks + "}")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        ModFileRelease release = new ModFileRelease(
                modId + "-version", modId, "1.0", fileName, "release", "",
                List.of("1.20.1"), List.of("fabric"), "https://example.invalid/" + fileName,
                Hashing.sha1(jar), Files.size(jar), List.of());
        return new PreparedModInstall(release, jar);
    }

    private ModrinthProject project(String id) {
        return new ModrinthProject(id, id, id, "", "mod", "", 0, "", List.of(), "");
    }
}
