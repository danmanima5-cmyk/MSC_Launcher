import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JavaRuntimeService {
    private static final String RUNTIME_INDEX_URL = "https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    private final HttpService http = new HttpService();
    private final Map<Path, Integer> detectedJavaMajors = new HashMap<>();

    Path resolveJava(ResolvedVersion version, LauncherSettings settings, ProgressSink progress) {
        int requiredMajor = version.javaMajorVersion();
        if (requiredMajor <= 0) {
            return settings.javaPath();
        }

        String component = javaComponent(version.javaComponent(), requiredMajor);
        Path configured = settings.javaPath();
        int configuredMajor = detectJavaMajor(configured);
        if (configuredMajor == requiredMajor) {
            progress.log("Java runtime: " + configured + " (Java " + configuredMajor + ")");
            return configured;
        }
        if (configuredMajor > 0) {
            progress.log("Configured Java is " + configuredMajor + ", but " + version.id() + " requires Java " + requiredMajor + ".");
        }

        // Explicit path from the "Java installations" settings section takes priority over
        // auto-detected candidates and the automatic Mojang download below.
        Path explicit = settings.javaPathForMajor(requiredMajor);
        if (explicit != null) {
            int explicitMajor = detectJavaMajor(explicit);
            if (explicitMajor == requiredMajor) {
                progress.log("Java runtime (settings, Java " + requiredMajor + "): " + explicit);
                return explicit;
            }
            if (explicitMajor > 0) {
                progress.log("Java " + requiredMajor + " location in settings points to Java " + explicitMajor + "; ignoring it.");
            }
        }

        for (Path candidate : localCandidates(settings, component)) {
            int major = detectJavaMajor(candidate);
            if (major == requiredMajor) {
                progress.log("Java runtime auto-selected: " + candidate + " (Java " + major + ")");
                return candidate;
            }
        }

        // Mojang's current Java 17+ Windows runtimes target Windows 10 or newer. On an older
        // Windows release ProcessBuilder can still create the process, but java.exe exits before
        // the VM starts. From the UI that looked as if the Launch button did absolutely nothing.
        // Keep accepting a compatible Java selected by the user (or found locally), but do not
        // download a runtime which cannot start on this operating system.
        if (isLegacyWindows() && requiredMajor >= 17) {
            throw new LauncherException("Minecraft " + version.id() + " requires Java " + requiredMajor
                    + ", but no compatible Java runtime was found. On Windows 7/8/8.1 the automatic "
                    + "Mojang Java " + requiredMajor + " download is disabled because that runtime "
                    + "requires Windows 10 or newer and exits immediately on this OS. Install a "
                    + "Windows 7/8-compatible Java " + requiredMajor
                    + " build and select its java.exe in launcher settings.");
        }

        if (component.isBlank()) {
            throw new LauncherException("Minecraft " + version.id() + " requires Java " + requiredMajor
                    + ", but a matching Java runtime was not found. Set Java executable to a Java " + requiredMajor + " installation.");
        }

        progress.status("Installing Java " + requiredMajor);
        Path downloaded = installMojangRuntime(component, settings, progress);
        int downloadedMajor = detectJavaMajor(downloaded);
        if (downloadedMajor != requiredMajor) {
            throw new LauncherException("Downloaded runtime " + downloaded + " is Java " + downloadedMajor
                    + ", but " + version.id() + " requires Java " + requiredMajor + ".");
        }
        progress.log("Java runtime installed: " + downloaded + " (Java " + downloadedMajor + ")");
        return downloaded;
    }

    /**
     * Downloads and installs Mojang's recommended runtime for the given Java major version
     * (8, 17, 21 or 25), used by the "Install recommended" button in the Java installations
     * settings section. Independent of any specific Minecraft version.
     */
    Path installRecommended(int major, LauncherSettings settings, ProgressSink progress) {
        String component = javaComponent("", major);
        if (component.isBlank()) {
            throw new LauncherException("No recommended Java " + major + " runtime is available for this platform.");
        }
        progress.status("Installing Java " + major);
        Path installed = installMojangRuntime(component, settings, progress);
        int installedMajor = detectJavaMajor(installed);
        if (installedMajor != major) {
            throw new LauncherException("Downloaded runtime is Java " + installedMajor + ", expected Java " + major + ".");
        }
        return installed;
    }

    /**
     * Looks for an already-installed Java executable matching the given major version among
     * the same local search locations used during automatic version launches (JAVA_HOME,
     * common install directories, the launcher's own downloaded runtimes, ...). Used by the
     * "Detect" button in the Java installations settings section. Returns null if none found.
     */
    Path detectLocal(int major, LauncherSettings settings) {
        String component = javaComponent("", major);
        for (Path candidate : localCandidates(settings, component)) {
            if (detectJavaMajor(candidate) == major) {
                return candidate;
            }
        }
        return null;
    }

    /** Public wrapper so UI code can validate an arbitrary Java executable path. */
    int detectMajor(Path java) {
        return detectJavaMajor(java);
    }

    private Set<Path> localCandidates(LauncherSettings settings, String component) {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        String platform = platform();
        if (!component.isBlank()) {
            candidates.add(javaExecutable(settings.gameDirectory().resolve("runtime").resolve(component).resolve(platform).resolve(component)));
        }
        addJavaHome(candidates, System.getenv("JAVA_HOME"));
        addJavaHome(candidates, System.getenv("JDK_HOME"));

        String userHome = System.getProperty("user.home", "");
        String localAppData = System.getenv("LOCALAPPDATA");
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");

        addJavaHomesUnder(candidates, pathIfPresent(userHome, ".jdks"));
        addJavaHomesUnder(candidates, pathIfPresent(localAppData, "Programs", "Eclipse Adoptium"));
        addJavaHomesUnder(candidates, pathIfPresent(localAppData, "Programs", "Microsoft"));
        addJavaHomesUnder(candidates, pathIfPresent(localAppData, "Programs", "Java"));
        addJavaHomesUnder(candidates, pathIfPresent(programFiles, "Java"));
        addJavaHomesUnder(candidates, pathIfPresent(programFiles, "Eclipse Adoptium"));
        addJavaHomesUnder(candidates, pathIfPresent(programFiles, "Microsoft"));
        addJavaHomesUnder(candidates, pathIfPresent(programFilesX86, "Java"));
        return candidates;
    }

    private void addJavaHome(Set<Path> candidates, String home) {
        if (home == null || home.isBlank()) {
            return;
        }
        candidates.add(javaExecutable(Path.of(home)));
    }

    private void addJavaHomesUnder(Set<Path> candidates, Path parent) {
        if (parent == null || !Files.isDirectory(parent)) {
            return;
        }
        try (var stream = Files.list(parent)) {
            stream.filter(Files::isDirectory)
                    .map(this::javaExecutable)
                    .forEach(candidates::add);
        } catch (IOException ignored) {
            // Missing or unreadable runtime search paths are not fatal.
        }
    }

    private Path pathIfPresent(String first, String... more) {
        if (first == null || first.isBlank()) {
            return null;
        }
        return Path.of(first, more);
    }

    private Path installMojangRuntime(String component, LauncherSettings settings, ProgressSink progress) {
        String platform = platform();
        Path runtimeRoot = settings.gameDirectory().resolve("runtime").resolve(component).resolve(platform).resolve(component);
        Path javaPath = javaExecutable(runtimeRoot);

        Map<String, Object> index = runtimeIndex(settings);
        Map<String, Object> platformRuntimes = Json.object(index, platform);
        var entries = Json.list(platformRuntimes, component);
        if (entries.isEmpty()) {
            throw new LauncherException("Mojang runtime manifest has no " + component + " for " + platform + ".");
        }

        Map<String, Object> selected = Json.object(entries.get(0));
        Map<String, Object> manifestDownload = Json.object(selected, "manifest");
        String manifestUrl = Json.string(manifestDownload, "url");
        Path manifestPath = settings.gameDirectory().resolve("runtime").resolve(component).resolve(platform).resolve(component + ".json");
        http.download(manifestUrl, manifestPath, Json.string(manifestDownload, "sha1"),
                Json.longValue(manifestDownload, "size", -1), progress);

        Map<String, Object> runtimeManifest;
        try {
            runtimeManifest = Json.object(Json.parse(Files.readString(manifestPath, StandardCharsets.UTF_8)));
        } catch (IOException ex) {
            throw new LauncherException("Could not read Java runtime manifest " + manifestPath + ": " + ex.getMessage(), ex);
        }

        Map<String, Object> files = Json.object(runtimeManifest, "files");
        Path normalizedRoot = runtimeRoot.normalize();
        for (Map.Entry<String, Object> entry : files.entrySet()) {
            Map<String, Object> file = Json.object(entry.getValue());
            Path target = normalizedRoot.resolve(entry.getKey().replace("/", java.io.File.separator)).normalize();
            if (!target.startsWith(normalizedRoot)) {
                throw new LauncherException("Invalid Java runtime file path: " + entry.getKey());
            }
            String type = Json.string(file, "type");
            if ("directory".equals(type)) {
                createDirectory(target);
            } else if ("file".equals(type)) {
                downloadRuntimeFile(file, target, progress);
            }
        }
        return javaPath;
    }

    private void downloadRuntimeFile(Map<String, Object> file, Path target, ProgressSink progress) {
        Map<String, Object> raw = Json.object(Json.object(file, "downloads"), "raw");
        if (raw.isEmpty()) {
            return;
        }
        http.download(Json.string(raw, "url"), target, Json.string(raw, "sha1"), Json.longValue(raw, "size", -1), progress);
        if (Json.bool(file, "executable", false) && !isWindows()) {
            target.toFile().setExecutable(true, false);
        }
    }

    private Map<String, Object> runtimeIndex(LauncherSettings settings) {
        Path cache = ProfileDirectories.installRoot().resolve("jre_manifest.json");
        try {
            String json = http.getString(RUNTIME_INDEX_URL);
            Files.createDirectories(cache.getParent());
            Files.writeString(cache, json, StandardCharsets.UTF_8);
            return Json.object(Json.parse(json));
        } catch (RuntimeException | IOException ex) {
            if (Files.isRegularFile(cache)) {
                try {
                    return Json.object(Json.parse(Files.readString(cache, StandardCharsets.UTF_8)));
                } catch (IOException readEx) {
                    throw new LauncherException("Could not read cached Java runtime index: " + readEx.getMessage(), readEx);
                }
            }
            throw new LauncherException("Could not load Java runtime index: " + ex.getMessage(), ex);
        }
    }

    private void createDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException ex) {
            throw new LauncherException("Could not create Java runtime directory " + directory + ": " + ex.getMessage(), ex);
        }
    }

    private int detectJavaMajor(Path java) {
        if (java == null || !Files.isRegularFile(java)) {
            return 0;
        }
        Path key = java.toAbsolutePath().normalize();
        Integer cached = detectedJavaMajors.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(java.toString(), "-version");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return 0;
            }
            int major = parseJavaMajor(output.toString());
            detectedJavaMajors.put(key, major);
            return major;
        } catch (IOException ex) {
            return 0;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    private int parseJavaMajor(String text) {
        Matcher matcher = Pattern.compile("version\\s+\"([^\"]+)\"").matcher(text);
        if (!matcher.find()) {
            return 0;
        }
        String version = matcher.group(1);
        if (version.startsWith("1.")) {
            String[] parts = version.split("\\.");
            return parts.length > 1 ? parseInt(parts[1]) : 0;
        }
        Matcher major = Pattern.compile("^(\\d+)").matcher(version);
        return major.find() ? parseInt(major.group(1)) : 0;
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private String javaComponent(String component, int major) {
        if (component != null && !component.isBlank()) {
            return component;
        }
        return switch (major) {
            case 8 -> "jre-legacy";
            case 16 -> "java-runtime-alpha";
            case 17 -> "java-runtime-gamma";
            case 21 -> "java-runtime-delta";
            case 25 -> "java-runtime-epsilon";
            default -> "";
        };
    }

    private Path javaExecutable(Path javaHome) {
        return javaHome.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
    }

    private String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            if (arch.equals("aarch64") || arch.equals("arm64")) {
                return "windows-arm64";
            }
            return arch.contains("64") ? "windows-x64" : "windows-x86";
        }
        if (os.contains("mac")) {
            return arch.equals("aarch64") || arch.equals("arm64") ? "mac-os-arm64" : "mac-os";
        }
        return arch.contains("64") ? "linux" : "linux-i386";
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private boolean isLegacyWindows() {
        return isLegacyWindows(
                System.getProperty("os.name", ""),
                System.getProperty("os.version", ""));
    }

    /** Recognizes Windows releases older than Windows 10 without misclassifying Windows 11. */
    static boolean isLegacyWindows(String osName, String osVersion) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (!name.contains("win")) {
            return false;
        }
        if (name.contains("windows xp") || name.contains("windows vista")
                || name.contains("windows 7") || name.contains("windows 8")) {
            return true;
        }

        String version = osVersion == null ? "" : osVersion.trim();
        return version.startsWith("5.") || version.startsWith("6.");
    }

}
