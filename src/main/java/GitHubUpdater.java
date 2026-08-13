import javax.swing.SwingWorker;
import javax.swing.JOptionPane;
import java.awt.Frame;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * GitHub Releases updater.
 *
 * <p>A release may contain both the Windows installer and a standalone jar. Assets are
 * deliberately selected by name and installation type instead of taking assets[0], because
 * GitHub does not promise a useful asset order.</p>
 */
final class GitHubUpdater {
    // Release builds take their version from the JAR manifest written by Gradle. Keeping a
    // second hard-coded release version here made legitimate GitHub releases look older.
    static final String CURRENT_VERSION = detectCurrentVersion();
    static final String RELEASES_URL =
            "https://github.com/danmanima5-cmyk/MSC_Launcher/releases/latest";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/danmanima5-cmyk/MSC_Launcher/releases/latest";

    private GitHubUpdater() {
    }

    private static String detectCurrentVersion() {
        Package launcherPackage = GitHubUpdater.class.getPackage();
        if (launcherPackage != null && !isBlank(launcherPackage.getImplementationVersion())) {
            return normalizeVersion(launcherPackage.getImplementationVersion());
        }
        try {
            Path location = codeLocation();
            if (Files.isRegularFile(location)) {
                try (JarFile jar = new JarFile(location.toFile())) {
                    String version = versionFromManifest(jar.getManifest());
                    if (!isBlank(version)) {
                        return normalizeVersion(version);
                    }
                }
            }
        } catch (Exception ignored) {
            // Classes launched by Gradle/IDE do not have the release JAR manifest.
        }
        // An IDE/Gradle launch uses an exploded classes directory and therefore has no
        // Implementation-Version. It must not pretend to be the current release: doing so
        // hides perfectly valid GitHub releases while developing and testing the updater.
        return "2.1";
    }

    static String versionFromManifest(Manifest manifest) {
        return manifest == null ? ""
                : manifest.getMainAttributes().getValue("Implementation-Version");
    }

    static void checkNow(Frame parentFrame) {
        check(parentFrame, true, LauncherSettings.load().language());
    }

    static void checkNow(Frame parentFrame, AppLanguage language) {
        check(parentFrame, true, language);
    }

    /** Quiet startup check: network errors and an up-to-date result do not interrupt the user. */
    static void checkAutomatically(Frame parentFrame) {
        check(parentFrame, false, LauncherSettings.load().language());
    }

    static void checkAutomatically(Frame parentFrame, AppLanguage language) {
        check(parentFrame, false, language);
    }

    private static void check(Frame parentFrame, boolean userInitiated, AppLanguage language) {
        new SwingWorker<UpdateRelease, Void>() {
            @Override
            protected UpdateRelease doInBackground() {
                HttpService http = new HttpService();
                UpdateRelease release = fetchLatestRelease(http, detectPackageType());
                String translated = translateReleaseNotes(http, release.description(), language);
                return new UpdateRelease(release.version(), release.pageUrl(), release.asset(), translated);
            }

            @Override
            protected void done() {
                try {
                    UpdateRelease release = get();
                    if (!isNewerVersion(release.version(), CURRENT_VERSION)) {
                        if (userInitiated) {
                            showInformation(parentFrame,
                                    "Обновления не найдены",
                                    "У вас уже установлена актуальная версия MSC Launcher "
                                            + CURRENT_VERSION + ".");
                        }
                        return;
                    }
                    offerUpdate(parentFrame, release);
                } catch (Exception ex) {
                    if (userInitiated) {
                        showError(parentFrame,
                                "Ошибка проверки обновлений",
                                "Не удалось проверить обновления.\n\n" + rootMessage(ex)
                                        + "\n\nРелиз можно открыть вручную:\n" + RELEASES_URL);
                    }
                }
            }
        }.execute();
    }

    private static void offerUpdate(Frame parentFrame, UpdateRelease release) {
        ReleaseAsset asset = release.asset();
        if (asset == null) {
            confirm(parentFrame,
                    "Доступно обновление",
                    "Доступна версия " + release.version() + ", но в релизе нет подходящего файла "
                            + "для этой сборки.\n\nОткрыть страницу релиза?",
                    null, "Открыть релиз", accepted -> {
                        if (accepted) {
                            BrowserUtil.openUrl(release.pageUrl());
                        }
                    });
            return;
        }

        String kind = asset.kind() == PackageType.WINDOWS_INSTALLER
                ? "установщик Windows (.exe)" : "standalone JAR";
        String message = "Доступна новая версия MSC Launcher " + release.version() + ".\n"
                + "Будет скачан " + kind + ": " + asset.name() + "\n\n"
                + "Скачать и установить обновление?";
        String notes = isBlank(release.description())
                ? "Описание релиза не указано." : release.description();
        confirm(parentFrame, "Доступно обновление", message, notes,
                "Скачать и установить", accepted -> {
                    if (accepted) {
                        downloadAndInstall(parentFrame, release);
                    }
                });
    }

    private static void downloadAndInstall(Frame parentFrame, UpdateRelease release) {
        new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() throws Exception {
                ReleaseAsset asset = release.asset();
                Path updateDir = updateCacheDirectory();
                Files.createDirectories(updateDir);
                Path target = updateDir.resolve(safeFileName(asset.name()));
                Path partial = target.resolveSibling(target.getFileName() + ".part");
                Files.deleteIfExists(partial);
                new HttpService().download(asset.downloadUrl(), partial, "", asset.size(), ProgressSink.NONE);
                if (!Files.isRegularFile(partial) || (asset.size() > 0 && Files.size(partial) != asset.size())) {
                    Files.deleteIfExists(partial);
                    throw new IOException("Размер скачанного файла не совпадает с данными GitHub.");
                }
                validateDownloadedAsset(partial, asset.kind());
                if (!isBlank(asset.sha256()) && !asset.sha256().equalsIgnoreCase(Hashing.sha256(partial))) {
                    Files.deleteIfExists(partial);
                    throw new IOException("Контрольная сумма скачанного релиза не совпадает с GitHub.");
                }
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
                return target;
            }

            @Override
            protected void done() {
                try {
                    Path downloaded = get();
                    if (release.asset().kind() == PackageType.WINDOWS_INSTALLER) {
                        launchInstallerWithCleanup(downloaded);
                    } else {
                        installStandaloneJar(downloaded);
                    }
                    System.exit(0);
                } catch (Exception ex) {
                    showError(parentFrame,
                            "Ошибка обновления",
                            "Не удалось установить обновление.\n\n" + rootMessage(ex)
                                    + "\n\nСкачайте нужный файл вручную:\n" + release.pageUrl());
                }
            }
        }.execute();
    }

    /**
     * JavaFX is optional during an IDE launch. The fallback must live outside
     * JavaFxUpdaterDialogs because the JVM may be unable to link that class at all.
     */
    private static void showInformation(Frame owner, String title, String message) {
        try {
            JavaFxUpdaterDialogs.showInformation(owner, title, message);
        } catch (LinkageError unavailableJavaFx) {
            JOptionPane.showMessageDialog(owner, message, title, JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private static void showError(Frame owner, String title, String message) {
        try {
            JavaFxUpdaterDialogs.showError(owner, title, message);
        } catch (LinkageError unavailableJavaFx) {
            JOptionPane.showMessageDialog(owner, message, title, JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void confirm(Frame owner, String title, String message, String details,
                                String acceptText, java.util.function.Consumer<Boolean> handler) {
        try {
            JavaFxUpdaterDialogs.confirm(owner, title, message, details, acceptText, handler);
        } catch (LinkageError unavailableJavaFx) {
            Object content = isBlank(details) ? message : new Object[]{message, details};
            int selected = JOptionPane.showConfirmDialog(owner, content, title,
                    JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
            handler.accept(selected == JOptionPane.YES_OPTION);
        }
    }

    private static void installStandaloneJar(Path downloaded) throws Exception {
        Path current = findCurrentJar();
        if (current == null) {
            // An IDE/Gradle launch has an exploded classes directory instead of a current JAR.
            // Install the downloaded standalone build into a stable per-user location and
            // start it, rather than failing after the complete download.
            Path target = standaloneFallbackTarget();
            Files.createDirectories(target.getParent());
            Files.copy(downloaded, target, StandardCopyOption.REPLACE_EXISTING);
            launchJar(target);
            cleanupUpdateCacheExcept(null);
            return;
        }
        Path staged = current.resolveSibling(current.getFileName() + ".update");
        Files.copy(downloaded, staged, StandardCopyOption.REPLACE_EXISTING);

        if (isWindows()) {
            Path script = Files.createTempFile("msc-launcher-update-", ".cmd");
            String java = Paths.get(System.getProperty("java.home"), "bin", "javaw.exe").toString();
            String body = "@echo off\r\n"
                    + "ping 127.0.0.1 -n 3 > nul\r\n"
                    + "move /Y \"" + staged + "\" \"" + current + "\" > nul\r\n"
                    + "start \"\" \"" + java + "\" -jar \"" + current + "\"\r\n"
                    + "del \"%~f0\"\r\n";
            Files.write(script, body.getBytes(StandardCharsets.UTF_8));
            new ProcessBuilder("cmd.exe", "/c", "start", "", "/min", script.toString()).start();
            cleanupUpdateCacheExcept(null);
        } else {
            Files.move(staged, current, StandardCopyOption.REPLACE_EXISTING);
            String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            new ProcessBuilder(java, "-jar", current.toString()).start();
            cleanupUpdateCacheExcept(null);
        }
    }

    private static void launchInstallerWithCleanup(Path installer) throws IOException {
        if (!isWindows()) {
            new ProcessBuilder(installer.toString()).start();
            return;
        }

        // The installer executable cannot be deleted while Windows is running it. A detached
        // helper waits for completion, removes the final cached file and then deletes itself.
        cleanupUpdateCacheExcept(installer);
        Path cache = updateCacheDirectory().toAbsolutePath().normalize();
        Path script = Files.createTempFile("msc-launcher-installer-cleanup-", ".cmd");
        String body = "@echo off\r\n"
                + "start \"\" /wait \"" + installer.toAbsolutePath().normalize() + "\" /SP-\r\n"
                + "del /F /Q \"" + installer.toAbsolutePath().normalize() + "\" > nul 2>&1\r\n"
                + "rmdir /S /Q \"" + cache + "\" > nul 2>&1\r\n"
                + "del /F /Q \"%~f0\"\r\n";
        Files.write(script, body.getBytes(StandardCharsets.UTF_8));
        new ProcessBuilder("cmd.exe", "/c", "start", "", "/min", script.toString()).start();
    }

    private static Path updateCacheDirectory() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "msc-launcher-update")
                .toAbsolutePath().normalize();
    }

    /** Best-effort cleanup, optionally preserving the installer that is still about to run. */
    static void cleanupUpdateCacheExcept(Path preserved) {
        Path cache = updateCacheDirectory();
        Path keep = preserved == null ? null : preserved.toAbsolutePath().normalize();
        if (!Files.isDirectory(cache)) {
            return;
        }
        // Never perform recursive cleanup unless the resolved target is exactly our own
        // fixed child directory under java.io.tmpdir.
        Path expectedParent = Paths.get(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath().normalize();
        if (!expectedParent.equals(cache.getParent())
                || !"msc-launcher-update".equals(String.valueOf(cache.getFileName()))) {
            return;
        }

        List<Path> paths = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(cache)) {
            stream.forEach(paths::add);
        } catch (IOException ignored) {
            return;
        }
        paths.sort(Comparator.reverseOrder());
        for (Path path : paths) {
            if (keep != null && (path.equals(keep) || keep.startsWith(path))) {
                continue;
            }
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Antivirus/indexer locks are temporary; the next successful update retries.
            }
        }
    }

    private static void launchJar(Path jar) throws IOException {
        String executable = isWindows() ? "javaw.exe" : "java";
        Path java = Paths.get(System.getProperty("java.home"), "bin", executable);
        new ProcessBuilder(java.toString(), "-jar", jar.toString())
                .directory(jar.toAbsolutePath().getParent().toFile())
                .start();
    }

    static Path standaloneFallbackTarget() {
        if (isWindows()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (!isBlank(localAppData)) {
                return Paths.get(localAppData, "MSC Launcher", "standalone", "MSC_Launcher.jar");
            }
        }
        return Paths.get(System.getProperty("user.home"), ".msc-launcher", "MSC_Launcher.jar");
    }

    private static Path findCurrentJar() throws Exception {
        Path location = codeLocation();
        if (isLauncherJar(location)) {
            return location;
        }

        Path explicit = candidatePath(System.getProperty("msc.launcher.jar", ""));
        if (isLauncherJar(explicit)) {
            return explicit;
        }

        Path commandJar = candidatePath(firstCommandToken(System.getProperty("sun.java.command", "")));
        if (isLauncherJar(commandJar)) {
            return commandJar;
        }

        String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (isBlank(entry)) {
                continue;
            }
            Path candidate = candidatePath(entry);
            if (isLauncherJar(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path candidatePath(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return Paths.get(value).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static String firstCommandToken(String command) {
        if (isBlank(command)) {
            return "";
        }
        String trimmed = command.trim();
        if (trimmed.charAt(0) == '"') {
            int closingQuote = trimmed.indexOf('"', 1);
            return closingQuote < 0 ? trimmed.substring(1) : trimmed.substring(1, closingQuote);
        }
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    /**
     * Identifies the running launcher by its executable manifest instead of an implementation
     * class name. ProGuard is allowed to rename helper classes, while the declared entry point
     * must remain stable for {@code java -jar} to work.
     */
    static boolean isLauncherJar(Path candidate) {
        if (!Files.isRegularFile(candidate)
                || !candidate.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return false;
        }
        try (JarFile jar = new JarFile(candidate.toFile())) {
            return hasLauncherEntryPoint(jar, null);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean hasLauncherEntryPoint(JarFile jar, String requiredMainClass)
            throws IOException {
        java.util.jar.Manifest manifest = jar.getManifest();
        if (manifest == null) {
            return false;
        }
        String mainClass = manifest.getMainAttributes().getValue("Main-Class");
        if (isBlank(mainClass)
                || (requiredMainClass != null && !requiredMainClass.equals(mainClass))) {
            return false;
        }
        if (!"Main".equals(mainClass) && !"MetroMain".equals(mainClass)) {
            return false;
        }
        return jar.getJarEntry(mainClass.replace('.', '/') + ".class") != null;
    }

    private static void validateDownloadedAsset(Path downloaded, PackageType kind) throws IOException {
        if (kind == PackageType.STANDALONE_JAR) {
            try (JarFile jar = new JarFile(downloaded.toFile())) {
                String expectedMain = LauncherEdition.current() == LauncherEdition.METRO
                        ? "MetroMain" : "Main";
                if (!hasLauncherEntryPoint(jar, expectedMain)) {
                    throw new IOException("Скачанный JAR не является сборкой MSC Launcher.");
                }
            } catch (java.util.zip.ZipException ex) {
                throw new IOException("Скачанный файл повреждён и не является JAR.", ex);
            }
            return;
        }
        if (kind == PackageType.WINDOWS_INSTALLER) {
            try (java.io.InputStream input = Files.newInputStream(downloaded)) {
                if (input.read() != 'M' || input.read() != 'Z') {
                    throw new IOException("Скачанный файл не является установщиком Windows.");
                }
            }
        }
    }

    static UpdateRelease fetchLatestRelease(HttpService http, PackageType packageType) {
        Map<String, Object> json = http.getJsonObject(LATEST_RELEASE_API);
        String version = normalizeVersion(Json.string(json, "tag_name"));
        String pageUrl = Json.string(json, "html_url");
        String description = Json.string(json, "body");
        if (isBlank(pageUrl)) {
            pageUrl = RELEASES_URL;
        }
        List<ReleaseAsset> assets = new ArrayList<>();
        for (Object raw : Json.list(json, "assets")) {
            Map<String, Object> item = Json.object(raw);
            assets.add(new ReleaseAsset(
                    Json.string(item, "name"),
                    Json.string(item, "browser_download_url"),
                    Json.longValue(item, "size", -1),
                    classifyAsset(Json.string(item, "name")),
                    githubSha256(Json.string(item, "digest"))));
        }
        return new UpdateRelease(version, pageUrl,
                selectAsset(assets, packageType, LauncherEdition.current()), description);
    }

    /**
     * Translates GitHub's release body to the launcher's UI language. Translation is best effort:
     * a temporary translation-service failure must never prevent an update from being offered.
     */
    static String translateReleaseNotes(HttpService http, String source, AppLanguage language) {
        if (isBlank(source) || language == null || language == AppLanguage.EN) {
            return source == null ? "" : source;
        }
        try {
            List<String> chunks = splitForTranslation(source, 1800);
            StringBuilder translated = new StringBuilder();
            for (String chunk : chunks) {
                String url = "https://translate.googleapis.com/translate_a/single"
                        + "?client=gtx&sl=auto&tl=" + language.code() + "&dt=t&q="
                        + urlEncode(chunk);
                Object response = Json.parse(http.getString(url));
                List<Object> root = Json.list(response);
                List<Object> sentences = root.isEmpty()
                        ? Collections.<Object>emptyList() : Json.list(root.get(0));
                for (Object sentence : sentences) {
                    List<Object> fields = Json.list(sentence);
                    if (!fields.isEmpty()) {
                        translated.append(Json.string(fields.get(0)));
                    }
                }
            }
            return translated.isEmpty() ? source : translated.toString();
        } catch (RuntimeException ex) {
            return source;
        }
    }

    static List<String> splitForTranslation(String text, int maxLength) {
        List<String> chunks = new ArrayList<>();
        String remaining = text == null ? "" : text;
        while (!remaining.isEmpty()) {
            int end = Math.min(maxLength, remaining.length());
            if (end < remaining.length()) {
                int paragraph = remaining.lastIndexOf("\n\n", end);
                int line = remaining.lastIndexOf('\n', end);
                int space = remaining.lastIndexOf(' ', end);
                int split = paragraph > maxLength / 2 ? paragraph + 2
                        : line > maxLength / 2 ? line + 1
                        : space > maxLength / 2 ? space + 1 : end;
                end = split;
            }
            chunks.add(remaining.substring(0, end));
            remaining = remaining.substring(end);
        }
        return chunks;
    }

    static ReleaseAsset selectAsset(List<ReleaseAsset> assets, PackageType wanted) {
        return selectAsset(assets, wanted, LauncherEdition.STANDARD);
    }

    static ReleaseAsset selectAsset(List<ReleaseAsset> assets, PackageType wanted,
                                    LauncherEdition edition) {
        return assets.stream()
                .filter(asset -> asset.kind() == wanted)
                .filter(asset -> !isBlank(asset.downloadUrl()))
                .filter(asset -> edition.acceptsReleaseAsset(asset.name()))
                .sorted((left, right) -> Integer.compare(
                        assetScore(right, wanted), assetScore(left, wanted)))
                .findFirst().orElse(null);
    }

    private static int assetScore(ReleaseAsset asset, PackageType wanted) {
        String name = asset.name().toLowerCase(Locale.ROOT);
        int score = 0;
        if (name.contains("msc") && name.contains("launcher")) score += 10;
        if (wanted == PackageType.WINDOWS_INSTALLER && name.contains("setup")) score += 20;
        if (name.contains(CURRENT_VERSION)) score += 5;
        return score;
    }

    static PackageType classifyAsset(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe") && (name.contains("setup") || name.contains("installer"))) {
            return PackageType.WINDOWS_INSTALLER;
        }
        if (name.endsWith(".jar") && !name.contains("sources") && !name.contains("javadoc")
                && !name.contains("modern") && !name.contains("unshaded")) {
            return PackageType.STANDALONE_JAR;
        }
        return PackageType.OTHER;
    }

    static boolean isNewerVersion(String candidate, String current) {
        int[] left = versionParts(candidate);
        int[] right = versionParts(current);
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    private static int[] versionParts(String version) {
        String[] parts = normalizeVersion(version).split("[.-]");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*$", ""));
            } catch (NumberFormatException ignored) {
                result[i] = 0;
            }
        }
        return result;
    }

    private static String normalizeVersion(String version) {
        String normalized = version == null ? "" : version.trim();
        return normalized.matches("^[vV].*") ? normalized.substring(1) : normalized;
    }

    static PackageType detectPackageType() {
        try {
            return packageTypeForCurrentJar(findCurrentJar(), isWindows());
        } catch (Exception ignored) {
            // A directly launched JAR remains the safest fallback when the
            // installation layout cannot be determined.
            return PackageType.STANDALONE_JAR;
        }
    }

    /** Installed bundles must be upgraded as a unit, including their Java runtime. */
    static PackageType packageTypeForCurrentJar(Path currentJar, boolean windows) {
        if (!windows || currentJar == null) {
            return PackageType.STANDALONE_JAR;
        }
        Path libDir = currentJar.toAbsolutePath().normalize().getParent();
        Path appDir = libDir == null ? null : libDir.getParent();
        boolean bundledLayout = libDir != null && appDir != null
                && "lib".equalsIgnoreCase(String.valueOf(libDir.getFileName()))
                && Files.isRegularFile(appDir.resolve("runtime").resolve("bin").resolve("javaw.exe"));
        return bundledLayout ? PackageType.WINDOWS_INSTALLER : PackageType.STANDALONE_JAR;
    }

    private static Path codeLocation() throws Exception {
        URI uri = GitHubUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        return Paths.get(uri).toAbsolutePath().normalize();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static String githubSha256(String digest) {
        String value = digest == null ? "" : digest.trim();
        if (value.regionMatches(true, 0, "sha256:", 0, 7)) {
            value = value.substring(7);
        }
        return value.matches("(?i)[0-9a-f]{64}") ? value.toLowerCase(Locale.ROOT) : "";
    }

    private static String safeFileName(String name) {
        String safe = Paths.get(name == null ? "update.bin" : name).getFileName().toString();
        return isBlank(safe) ? "update.bin" : safe;
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    enum PackageType { WINDOWS_INSTALLER, STANDALONE_JAR, OTHER }

    static final class ReleaseAsset {
        private final String name;
        private final String downloadUrl;
        private final long size;
        private final PackageType kind;
        private final String sha256;

        ReleaseAsset(String name, String downloadUrl, long size, PackageType kind) {
            this(name, downloadUrl, size, kind, "");
        }

        ReleaseAsset(String name, String downloadUrl, long size, PackageType kind, String sha256) {
            this.name = name;
            this.downloadUrl = downloadUrl;
            this.size = size;
            this.kind = kind;
            this.sha256 = sha256 == null ? "" : sha256;
        }

        String name() {
            return name;
        }

        String downloadUrl() {
            return downloadUrl;
        }

        long size() {
            return size;
        }

        PackageType kind() {
            return kind;
        }

        String sha256() {
            return sha256;
        }
    }

    static final class UpdateRelease {
        private final String version;
        private final String pageUrl;
        private final ReleaseAsset asset;
        private final String description;

        UpdateRelease(String version, String pageUrl, ReleaseAsset asset, String description) {
            this.version = version;
            this.pageUrl = pageUrl;
            this.asset = asset;
            this.description = description;
        }

        String version() {
            return version;
        }

        String pageUrl() {
            return pageUrl;
        }

        ReleaseAsset asset() {
            return asset;
        }

        String description() {
            return description;
        }
    }
}
