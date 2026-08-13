import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.io.TempDir;

class GitHubUpdaterTest {
    @TempDir
    Path tempDir;

    @Test
    void recognizesNewerReleaseFromIdeVersion() {
        // CURRENT_VERSION may come either from exploded IDE classes (1.0) or from the
        // release JAR on the Gradle test classpath (project version). Test the comparison
        // contract directly instead of depending on task execution order.
        assertTrue(GitHubUpdater.isNewerVersion("V1.4", "1.0"));
    }

    @Test
    void selectsInstallerFromMixedReleaseRegardlessOfOrder() {
        var jar = asset("MSC-Launcher-2.2.0.jar");
        var exe = asset("MSC-Launcher-2.2.0-Setup.exe");

        assertEquals(exe, GitHubUpdater.selectAsset(List.of(jar, exe),
                GitHubUpdater.PackageType.WINDOWS_INSTALLER));
        assertEquals(exe, GitHubUpdater.selectAsset(List.of(exe, jar),
                GitHubUpdater.PackageType.WINDOWS_INSTALLER));
    }

    @Test
    void selectsStandaloneJarFromMixedRelease() {
        var exe = asset("MSC-Launcher-2.2.0-Setup.exe");
        var jar = asset("MSC-Launcher-2.2.0.jar");

        assertEquals(jar, GitHubUpdater.selectAsset(List.of(exe, jar),
                GitHubUpdater.PackageType.STANDALONE_JAR));
    }

    @Test
    void selectsPublishedMscLauncherJarAndIgnoresInstaller() {
        var exe = asset("MSC-Launcher-2.3.0-Setup.exe");
        var jar = asset("MSC_Launcher.jar");

        assertEquals(jar, GitHubUpdater.selectAsset(List.of(exe, jar),
                GitHubUpdater.PackageType.STANDALONE_JAR));
    }

    @Test
    void keepsStandardAndMetroUpdateAssetsSeparate() {
        var standard = asset("MSC-Launcher-2.3.0-Setup.exe");
        var metro = asset("MSC-Launcher-Metro-2.3.0-Setup.exe");

        assertEquals(standard, GitHubUpdater.selectAsset(List.of(metro, standard),
                GitHubUpdater.PackageType.WINDOWS_INSTALLER, LauncherEdition.STANDARD));
        assertEquals(metro, GitHubUpdater.selectAsset(List.of(standard, metro),
                GitHubUpdater.PackageType.WINDOWS_INSTALLER, LauncherEdition.METRO));
    }

    @Test
    void ignoresDevelopmentJarsAndUnrelatedExecutables() {
        assertEquals(GitHubUpdater.PackageType.OTHER,
                GitHubUpdater.classifyAsset("MSC-Launcher-2.2.0-modern.jar"));
        assertEquals(GitHubUpdater.PackageType.OTHER,
                GitHubUpdater.classifyAsset("helper.exe"));
    }

    @Test
    void comparesReleaseVersionsNumerically() {
        assertTrue(GitHubUpdater.isNewerVersion("v2.10.0", "2.9.9"));
        assertFalse(GitHubUpdater.isNewerVersion("2.2.0", "2.2"));
        assertFalse(GitHubUpdater.isNewerVersion("2.1.9", "2.2.0"));
    }

    @Test
    void readsInstalledVersionFromReleaseManifest() {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Implementation-Version", "3.4.5");

        assertEquals("3.4.5", GitHubUpdater.versionFromManifest(manifest));
        assertEquals("", GitHubUpdater.versionFromManifest(null));
    }

    @Test
    void parsesOnlyValidGithubSha256Digests() {
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertEquals(hash, GitHubUpdater.githubSha256("sha256:" + hash));
        assertEquals(hash, GitHubUpdater.githubSha256(hash.toUpperCase()));
        assertEquals("", GitHubUpdater.githubSha256("sha1:1234"));
    }

    @Test
    void splitsLongReleaseNotesWithoutLosingText() {
        String notes = "Первый абзац с изменениями.\n\n"
                + "Второй длинный абзац с исправлениями и улучшениями.";
        List<String> chunks = GitHubUpdater.splitForTranslation(notes, 35);

        assertEquals(notes, String.join("", chunks));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 35));
    }

    @Test
    void installedWindowsBundleUsesInstallerUpdate() throws Exception {
        Path launcherJar = tempDir.resolve("MSC Launcher/lib/launcher.jar");
        Path javaw = tempDir.resolve("MSC Launcher/runtime/bin/javaw.exe");
        Files.createDirectories(launcherJar.getParent());
        Files.createDirectories(javaw.getParent());
        Files.createFile(launcherJar);
        Files.createFile(javaw);

        assertEquals(GitHubUpdater.PackageType.WINDOWS_INSTALLER,
                GitHubUpdater.packageTypeForCurrentJar(launcherJar, true));
    }

    @Test
    void standaloneJarAndNonWindowsBuildUseJarUpdate() throws Exception {
        Path launcherJar = tempDir.resolve("launcher.jar");
        Files.createFile(launcherJar);

        assertEquals(GitHubUpdater.PackageType.STANDALONE_JAR,
                GitHubUpdater.packageTypeForCurrentJar(launcherJar, true));
        assertEquals(GitHubUpdater.PackageType.STANDALONE_JAR,
                GitHubUpdater.packageTypeForCurrentJar(launcherJar, false));
    }

    @Test
    void extractsJarPathFromJavaCommandWithSpaces() {
        assertEquals("C:\\Apps\\MSC Launcher\\MSC_Launcher.jar",
                GitHubUpdater.firstCommandToken(
                        "\"C:\\Apps\\MSC Launcher\\MSC_Launcher.jar\" msc://profile/vanilla"));
        assertEquals("MSC_Launcher.jar",
                GitHubUpdater.firstCommandToken("MSC_Launcher.jar --updated"));
        assertEquals("", GitHubUpdater.firstCommandToken("   "));
    }

    @Test
    void cleansUpdateCacheAndCanPreserveRunningInstaller() throws Exception {
        String previousTemp = System.getProperty("java.io.tmpdir");
        Path cache = tempDir.resolve("msc-launcher-update");
        Path installer = cache.resolve("MSC-Launcher-Setup.exe");
        Path stale = cache.resolve("old/previous.jar");
        Files.createDirectories(stale.getParent());
        Files.write(installer, new byte[]{'M', 'Z'});
        Files.write(stale, new byte[]{1});

        try {
            System.setProperty("java.io.tmpdir", tempDir.toString());
            GitHubUpdater.cleanupUpdateCacheExcept(installer);

            assertTrue(Files.isRegularFile(installer));
            assertFalse(Files.exists(stale));

            GitHubUpdater.cleanupUpdateCacheExcept(null);
            assertFalse(Files.exists(cache));
        } finally {
            System.setProperty("java.io.tmpdir", previousTemp);
        }
    }

    @Test
    void recognizesObfuscatedLauncherByManifestInsteadOfUpdaterClassName() throws Exception {
        Path obfuscatedJar = tempDir.resolve("MSC_Launcher-obfuscated.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Main");
        try (JarOutputStream output = new JarOutputStream(
                Files.newOutputStream(obfuscatedJar), manifest)) {
            addJarEntry(output, "Main.class");
            addJarEntry(output, "o.class");
        }

        assertTrue(GitHubUpdater.isLauncherJar(obfuscatedJar));
    }

    @Test
    void rejectsJarWithoutLauncherEntryPoint() throws Exception {
        Path unrelatedJar = tempDir.resolve("unrelated.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "OtherMain");
        try (JarOutputStream output = new JarOutputStream(
                Files.newOutputStream(unrelatedJar), manifest)) {
            addJarEntry(output, "OtherMain.class");
        }

        assertFalse(GitHubUpdater.isLauncherJar(unrelatedJar));
    }

    private GitHubUpdater.ReleaseAsset asset(String name) {
        return new GitHubUpdater.ReleaseAsset(name, "https://example.invalid/" + name, 123,
                GitHubUpdater.classifyAsset(name));
    }

    private void addJarEntry(JarOutputStream output, String name) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(0);
        output.closeEntry();
    }
}
