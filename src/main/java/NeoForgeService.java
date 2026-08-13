import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NeoForgeService {
    private static final String METADATA_URL = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    private static final String MAVEN_BASE = "https://maven.neoforged.net/releases/net/neoforged/neoforge/";
    private static final Pattern VERSION_PREFIX = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\..*)?$");

    private final HttpService http = new HttpService();
    private final MinecraftInstaller minecraftInstaller;
    private List<String> cachedVersions;
    private Set<String> cachedSupportedGameVersions;

    NeoForgeService(MinecraftInstaller minecraftInstaller) {
        this.minecraftInstaller = minecraftInstaller;
    }

    String installLatestNeoForge(String gameVersion, LauncherSettings settings, ProgressSink progress) {
        String neoForgeVersion = latestNeoForgeFor(gameVersion);
        return installNeoForge(gameVersion, neoForgeVersion, settings, progress);
    }

    String installNeoForge(String gameVersion, String neoForgeVersion, LauncherSettings settings, ProgressSink progress) {
        progress.status("Установка NeoForge " + neoForgeVersion);
        minecraftInstaller.ensureVanillaMetadata(gameVersion, settings, progress);

        // Если профиль neoforge-<version> уже установлен, пропустить скачивание.
        List<String> installedNow = minecraftInstaller.listInstalledVersions(settings.gameDirectory());
        String expected = "neoforge-" + neoForgeVersion;
        if (installedNow.contains(expected)) {
            progress.log("NeoForge уже установлен, пропуск загрузки: " + expected);
            return expected;
        }
        // Широкая проверка: любой NeoForge профиль с этой версией
        String neoForgeVersionLower = neoForgeVersion.toLowerCase(java.util.Locale.ROOT);
        for (String id : installedNow) {
            String idLower = id.toLowerCase(java.util.Locale.ROOT);
            if (idLower.contains("neoforge") && idLower.contains(neoForgeVersionLower)) {
                progress.log("NeoForge уже установлен (широкая проверка), пропуск: " + id);
                return id;
            }
        }

        Set<String> before = new HashSet<>(installedNow);
        Path installerJar = downloadInstaller(neoForgeVersion, progress);
        runInstaller(installerJar, settings, progress);
        List<String> after = minecraftInstaller.listInstalledVersions(settings.gameDirectory());

        String detected = detectInstalledProfile(neoForgeVersion, before, after);
        progress.log("NeoForge профиль: " + detected);
        return detected;
    }

    String latestNeoForgeFor(String gameVersion) {
        // Тот же аргумент, что и в ForgeService.latestForgeFor: кэш живёт всю сессию лаунчера,
        // а запрос "последней версии" должен всегда отражать реальное текущее состояние
        // maven-репозитория NeoForge, а не то, что было там при первом обращении.
        cachedVersions = null;
        return versions().stream()
                .filter(version -> gameVersion.equals(gameVersionFor(version)))
                .max(VersionSort::compareVersions)
                .orElseThrow(() -> new LauncherException("NeoForge не найден для Minecraft " + gameVersion));
    }

    Set<String> supportedGameVersions() {
        if (cachedSupportedGameVersions != null) {
            return cachedSupportedGameVersions;
        }
        HashSet<String> supported = new HashSet<>();
        for (String version : versions()) {
            String gameVersion = gameVersionFor(version);
            if (!gameVersion.isBlank()) {
                supported.add(gameVersion);
            }
        }
        cachedSupportedGameVersions = Set.copyOf(supported);
        return cachedSupportedGameVersions;
    }

    private List<String> versions() {
        if (cachedVersions != null) {
            return cachedVersions;
        }
        String metadata = http.getString(METADATA_URL);
        Matcher matcher = Pattern.compile("<version>([^<]+)</version>").matcher(metadata);
        ArrayList<String> versions = new ArrayList<>();
        while (matcher.find()) {
            versions.add(matcher.group(1).trim());
        }
        if (versions.isEmpty()) {
            throw new LauncherException("NeoForge metadata не содержит версий.");
        }
        cachedVersions = List.copyOf(versions);
        return cachedVersions;
    }

    private String gameVersionFor(String neoForgeVersion) {
        Matcher matcher = VERSION_PREFIX.matcher(neoForgeVersion);
        if (!matcher.matches()) {
            return "";
        }
        String major = matcher.group(1);
        String minor = matcher.group(2);
        if ("0".equals(major)) {
            return "";
        }
        return "0".equals(minor) ? "1." + major : "1." + major + "." + minor;
    }

    private Path downloadInstaller(String neoForgeVersion, ProgressSink progress) {
        String fileName = "neoforge-" + neoForgeVersion + "-installer.jar";
        String url = MAVEN_BASE + neoForgeVersion + "/" + fileName;
        Path target = LauncherSettings.settingsDirectory().resolve("installers").resolve("neoforge").resolve(fileName);
        http.download(url, target, "", -1, progress);
        return target;
    }

    private void runInstaller(Path installerJar, LauncherSettings settings, ProgressSink progress) {
        Path installTarget = ProfileDirectories.installRoot();
        List<String> command = List.of(
                settings.javaPath().toString(),
                "-jar",
                installerJar.toString(),
                "--installClient",
                installTarget.toString()
        );
        progress.log("NeoForge installer: " + String.join(" ", command));
        try {
            Files.createDirectories(installTarget);
            InstallerProfileSupport.ensureLauncherProfiles(installTarget, progress);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    progress.log("[NeoForge] " + line);
                }
            }
            int code = process.waitFor();
            if (code != 0) {
                throw new LauncherException("NeoForge installer завершился с кодом " + code);
            }
            // The installer always writes its own hardcoded versions/<id> folder under
            // installTarget — fold that into msc-launcher/Profiles, our canonical location.
            ProfileDirectories.migrateOfficialInstallerVersions(progress);
        } catch (IOException ex) {
            throw new LauncherException("Не удалось запустить NeoForge installer: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LauncherException("Установка NeoForge прервана.", ex);
        }
    }

    private String detectInstalledProfile(String neoForgeVersion, Set<String> before, List<String> after) {
        String expected = "neoforge-" + neoForgeVersion;
        if (after.contains(expected)) {
            return expected;
        }
        return after.stream()
                .filter(id -> !before.contains(id))
                .filter(id -> id.toLowerCase(java.util.Locale.ROOT).contains("neoforge"))
                .findFirst()
                .orElse(expected);
    }
}