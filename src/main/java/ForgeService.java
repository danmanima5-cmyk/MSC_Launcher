import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ForgeService {
    private static final String METADATA_URL = "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
    private static final String MAVEN_BASE = "https://maven.minecraftforge.net/net/minecraftforge/forge/";

    private final HttpService http = new HttpService();
    private final MinecraftInstaller minecraftInstaller;
    private List<String> cachedVersions;
    private Set<String> cachedSupportedGameVersions;

    ForgeService(MinecraftInstaller minecraftInstaller) {
        this.minecraftInstaller = minecraftInstaller;
    }

    String installLatestForge(String gameVersion, LauncherSettings settings, ProgressSink progress) {
        String forgeMavenVersion = latestForgeFor(gameVersion);
        return installForge(gameVersion, forgeMavenVersion, settings, progress);
    }

    String installForge(String gameVersion, String forgeMavenVersion, LauncherSettings settings, ProgressSink progress) {
        progress.status("Установка Forge " + forgeMavenVersion);
        minecraftInstaller.ensureVanillaMetadata(gameVersion, settings, progress);

        // Быстрая проверка: есть ли уже профиль для этого Forge — если да, пропустить скачивание/установку.
        List<String> installedNow = minecraftInstaller.listInstalledVersions(settings.gameDirectory());
        String forgeNumber = forgeMavenVersion.startsWith(gameVersion + "-") ? forgeMavenVersion.substring((gameVersion + "-").length()) : forgeMavenVersion;
        List<String> expected = List.of(
                gameVersion + "-forge-" + forgeNumber,
                gameVersion + "-Forge" + forgeNumber,
                "forge-" + forgeMavenVersion
        );
        for (String id : expected) {
            if (installedNow.contains(id)) {
                progress.log("Forge уже установлен, пропуск загрузки: " + id);
                return id;
            }
        }
        // Широкая проверка: любая установленная версия для этой версии MC содержащая "forge".
        // ВАЖНО: должна находить именно "голый" Forge-профиль, а не Forge+OptiFine
        // ("...-optifine") или NeoForge ("...neoforge...") — иначе OptiFineService потом
        // сохранит профиль Forge+OptiFine с inheritsFrom, указывающим на самого себя
        // (цикл наследования version JSON).
        String gameVersionLower = gameVersion.toLowerCase(java.util.Locale.ROOT);
        String forgeNumberLower = forgeNumber.toLowerCase(java.util.Locale.ROOT);
        for (String id : installedNow) {
            String idLower = id.toLowerCase(java.util.Locale.ROOT);
            if (idLower.contains("optifine") || idLower.contains("neoforge")) {
                continue;
            }
            if (idLower.contains(gameVersionLower) && idLower.contains("forge") && idLower.contains(forgeNumberLower)) {
                progress.log("Forge уже установлен (широкая проверка), пропуск загрузки: " + id);
                return id;
            }
        }
        // Ещё шире: если точная версия forge совпадает, любое имя профиля подойдёт
        // (кроме всё тех же Forge+OptiFine/NeoForge профилей — см. комментарий выше).
        for (String id : installedNow) {
            String idLower = id.toLowerCase(java.util.Locale.ROOT);
            if (idLower.contains("optifine") || idLower.contains("neoforge")) {
                continue;
            }
            if (idLower.startsWith(gameVersionLower) && idLower.contains("forge")) {
                // Проверим, что это именно та же версия forge — сравниваем полный номер сборки,
                // а не только последний сегмент после точки (иначе "36.2.42" ложно совпадёт
                // с уже установленным "36.1.42", т.к. оба заканчиваются на "42").
                if (idLower.contains(forgeNumberLower)) {
                    progress.log("Forge уже установлен (по версии MC+forge), пропуск загрузки: " + id);
                    return id;
                }
            }
        }

        Set<String> before = new HashSet<>(installedNow);
        Path installerJar = downloadInstaller(forgeMavenVersion, progress);
        if (installerSupportsClientInstall(installerJar, settings, progress)) {
            runInstaller(installerJar, settings, progress);
            List<String> after = minecraftInstaller.listInstalledVersions(settings.gameDirectory());

            String detected = detectInstalledProfile(gameVersion, forgeMavenVersion, before, after);
            progress.log("Forge профиль: " + detected);
            return detected;
        }

        String detected = installLegacyClient(installerJar, settings, progress);
        progress.log("Forge профиль: " + detected);
        return detected;
    }

    String latestForgeFor(String gameVersion) {
        // Всегда сбрасываем кэш перед резолвом "последней" версии: cachedVersions живёт всё
        // время работы лаунчера, и если Forge выкатил новый билд уже после того, как список
        // версий был впервые загружен в этой сессии, "latest" молча продолжал бы указывать
        // на устаревший билд. Здесь пользователь явно просит именно самую свежую версию —
        // поэтому бьём по сети заново, а не доверяем памяти.
        cachedVersions = null;
        return versions().stream()
                .filter(version -> version.startsWith(gameVersion + "-"))
                .max(VersionSort::compareVersions)
                .orElseThrow(() -> new LauncherException("Forge не найден для Minecraft " + gameVersion));
    }

    Set<String> supportedGameVersions() {
        if (cachedSupportedGameVersions != null) {
            return cachedSupportedGameVersions;
        }
        HashSet<String> supported = new HashSet<>();
        for (String version : versions()) {
            int separator = version.indexOf('-');
            if (separator > 0) {
                supported.add(version.substring(0, separator));
            }
        }
        cachedSupportedGameVersions = Set.copyOf(supported);
        return cachedSupportedGameVersions;
    }

    /** Все известные сборки Forge для версии игры, самые новые первыми — для ручного выбора в UI. */
    List<String> allBuildsFor(String gameVersion) {
        return versions().stream()
                .filter(version -> version.startsWith(gameVersion + "-"))
                .sorted(VersionSort.latestFirst())
                .toList();
    }

    String resolveForgeForOptiFine(String gameVersion, String optiFineForgeText) {
        String required = optiFineForgeText == null ? "" : optiFineForgeText.replace("Forge", "").trim();
        if (required.isBlank() || "N/A".equalsIgnoreCase(required)) {
            throw new LauncherException("Выбранная сборка OptiFine для Minecraft " + gameVersion
                    + " не указывает совместимую сборку Forge (Forge = \"N/A\" на optifine.net). "
                    + "Для этой сборки доступна только отдельная (standalone) установка OptiFine.");
        }
        if (required.startsWith("#")) {
            String build = required.substring(1);
            return versions().stream()
                    .filter(version -> version.startsWith(gameVersion + "-"))
                    .filter(version -> matchesForgeBuild(version, gameVersion, build))
                    .max(VersionSort::compareVersions)
                    .orElseThrow(() -> new LauncherException("Forge build #" + build + " не найден для Minecraft " + gameVersion));
        }
        // ВАЖНО: раньше здесь при точном совпадении сразу возвращалась именно та сборка
        // Forge, что указана в колонке "Forge" на optifine.net. Для 1.6–1.12.2 это нормально
        // (Forge там фактически заморожен), но начиная с 1.13 колонка почти всегда указывает
        // старую сборку, с которой автор просто тестировал конкретный билд OptiFine, а не
        // "минимум" — поэтому пин строго на указанную сборку не годится.
        //
        // НО: самую новую из существующих сборок брать тоже нельзя — Forge между билдами той
        // же версии MC периодически меняет внутренности ModLauncher/Mixin, а ASM-трансформеры
        // OptiFine рассчитаны на конкретный диапазон. Слишком новый Forge может привести к
        // тихому нативному краху JVM без исключения и без окна (игра просто закрывается) —
        // это ровно то, что ловит эта версия фикса, если раньше пытались взять .max().
        //
        // Поэтому берём МИНИМАЛЬНУЮ сборку Forge, которая ещё удовлетворяет требованию
        // (т.е. ближайшую сверху к указанной на optifine.net), а не самую
        // новую из существующих — и только если такой нет, падаем на старую точную/суффиксную
        // эвристику (актуально для легаси-версий, где формат "Forge" на сайте не числовой).
        Optional<String> closestSatisfying = versions().stream()
                .filter(version -> version.startsWith(gameVersion + "-"))
                .filter(version -> VersionSort.compareVersions(forgeVersionPart(version, gameVersion), required) >= 0)
                .min(VersionSort::compareVersions);
        if (closestSatisfying.isPresent()) {
            return closestSatisfying.get();
        }
        String exact = gameVersion + "-" + required;
        if (versions().contains(exact)) {
            return exact;
        }
        return versions().stream()
                .filter(version -> version.startsWith(gameVersion + "-"))
                .filter(version -> matchesForgeRequirement(version, gameVersion, required))
                .max(VersionSort::compareVersions)
                .orElseThrow(() -> new LauncherException("Forge " + required + " не найден для Minecraft " + gameVersion));
    }

    private boolean matchesForgeBuild(String mavenVersion, String gameVersion, String build) {
        String forgeVersion = forgeVersionPart(mavenVersion, gameVersion);
        int qualifierIndex = forgeVersion.indexOf('-');
        String numericForgeVersion = qualifierIndex >= 0 ? forgeVersion.substring(0, qualifierIndex) : forgeVersion;
        return numericForgeVersion.equals(build)
                || numericForgeVersion.endsWith("." + build)
                || numericForgeVersion.endsWith("-" + build);
    }

    private boolean matchesForgeRequirement(String mavenVersion, String gameVersion, String required) {
        String forgeVersion = forgeVersionPart(mavenVersion, gameVersion);
        return forgeVersion.equals(required)
                || forgeVersion.startsWith(required + "-")
                || forgeVersion.endsWith("." + required)
                || forgeVersion.endsWith("-" + required);
    }

    private String forgeVersionPart(String mavenVersion, String gameVersion) {
        String prefix = gameVersion + "-";
        return mavenVersion.startsWith(prefix) ? mavenVersion.substring(prefix.length()) : mavenVersion;
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
            throw new LauncherException("Forge metadata не содержит версий.");
        }
        cachedVersions = List.copyOf(versions);
        return cachedVersions;
    }

    private Path downloadInstaller(String forgeMavenVersion, ProgressSink progress) {
        String fileName = "forge-" + forgeMavenVersion + "-installer.jar";
        String url = MAVEN_BASE + forgeMavenVersion + "/" + fileName;
        Path target = LauncherSettings.settingsDirectory().resolve("installers").resolve("forge").resolve(fileName);
        http.download(url, target, "", -1, progress);
        return target;
    }

    private boolean installerSupportsClientInstall(Path installerJar, LauncherSettings settings, ProgressSink progress) {
        List<String> command = List.of(settings.javaPath().toString(), "-jar", installerJar.toString(), "--help");
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int code = process.waitFor();
            if (code != 0) {
                progress.log("Не удалось проверить Forge installer " + installerJar.getFileName() + ": код " + code);
                return true;
            }
            return output.toString().contains("--installClient");
        } catch (IOException ex) {
            throw new LauncherException("Не удалось проверить Forge installer: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LauncherException("Проверка Forge installer прервана.", ex);
        }
    }

    private String installLegacyClient(Path installerJar, LauncherSettings settings, ProgressSink progress) {
        progress.log("Forge installer " + installerJar.getFileName() + " использует legacy-формат без --installClient.");
        try (JarFile jar = new JarFile(installerJar.toFile())) {
            Map<String, Object> profile = readInstallProfile(jar, installerJar);
            Map<String, Object> install = Json.object(profile, "install");
            Map<String, Object> versionInfo = Json.object(profile, "versionInfo");
            if (versionInfo.isEmpty()) {
                throw new LauncherException("Legacy Forge installer не содержит versionInfo: " + installerJar.getFileName());
            }

            String profileId = Json.string(versionInfo, "id");
            if (profileId.isBlank()) {
                profileId = Json.string(install, "target");
            }
            if (profileId.isBlank()) {
                throw new LauncherException("Legacy Forge installer не указывает id профиля: " + installerJar.getFileName());
            }

            Path profileDir = ProfileDirectories.versionsInstallDirectory().resolve(profileId);
            Path profileJson = profileDir.resolve(profileId + ".json");
            Files.createDirectories(profileDir);
            Files.writeString(profileJson, Json.stringify(versionInfo), StandardCharsets.UTF_8);

            copyBundledForgeLibrary(jar, install, settings.gameDirectory(), progress);
            progress.log("Legacy Forge профиль сохранён: " + profileId);
            return profileId;
        } catch (IOException ex) {
            throw new LauncherException("Не удалось установить legacy Forge client-профиль: " + ex.getMessage(), ex);
        }
    }

    private Map<String, Object> readInstallProfile(JarFile jar, Path installerJar) throws IOException {
        JarEntry entry = jar.getJarEntry("install_profile.json");
        if (entry == null) {
            throw new LauncherException("Forge installer не поддерживает --installClient и не содержит install_profile.json: " + installerJar.getFileName());
        }
        try (var in = jar.getInputStream(entry)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Json.object(Json.parse(text));
        }
    }

    private void copyBundledForgeLibrary(JarFile jar, Map<String, Object> install, Path gameDirectory, ProgressSink progress) throws IOException {
        String filePath = Json.string(install, "filePath");
        String artifact = Json.string(install, "path");
        if (filePath.isBlank() || artifact.isBlank()) {
            progress.log("Legacy Forge installer не содержит bundled library; библиотеки будут загружены при запуске.");
            return;
        }

        JarEntry payload = jar.getJarEntry(filePath);
        if (payload == null && filePath.startsWith("/")) {
            payload = jar.getJarEntry(filePath.substring(1));
        }
        if (payload == null) {
            throw new LauncherException("В Forge installer не найден bundled jar: " + filePath);
        }

        Path target = ProfileDirectories.librariesInstallDirectory().resolve(mavenArtifactPath(artifact).replace("/", java.io.File.separator));
        Files.createDirectories(target.getParent());
        try (var in = jar.getInputStream(payload)) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        progress.log("Forge library сохранена: " + target.getFileName());
    }

    private String mavenArtifactPath(String name) {
        String extension = "jar";
        int extensionMarker = name.indexOf('@');
        if (extensionMarker >= 0) {
            extension = name.substring(extensionMarker + 1);
            name = name.substring(0, extensionMarker);
        }
        String[] parts = name.split(":");
        if (parts.length < 3) {
            throw new LauncherException("Некорректный Maven artifact в Forge installer: " + name);
        }
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length >= 4 ? "-" + parts[3] : "";
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + "." + extension;
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
        progress.log("Forge installer: " + String.join(" ", command));
        try {
            Files.createDirectories(installTarget);
            InstallerProfileSupport.ensureLauncherProfiles(installTarget, progress);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    progress.log("[Forge] " + line);
                }
            }
            int code = process.waitFor();
            if (code != 0) {
                throw new LauncherException("Forge installer завершился с кодом " + code);
            }
            // The installer always writes its own hardcoded versions/<id> folder under
            // installTarget — fold that into msc-launcher/Profiles, our canonical location.
            ProfileDirectories.migrateOfficialInstallerVersions(progress);
        } catch (IOException ex) {
            throw new LauncherException("Не удалось запустить Forge installer: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LauncherException("Установка Forge прервана.", ex);
        }
    }

    private String detectInstalledProfile(String gameVersion, String forgeMavenVersion, Set<String> before, List<String> after) {
        String forgeNumber = forgeMavenVersion.substring((gameVersion + "-").length());
        List<String> expected = List.of(
                gameVersion + "-forge-" + forgeNumber,
                gameVersion + "-Forge" + forgeNumber,
                "forge-" + forgeMavenVersion
        );
        for (String id : expected) {
            if (after.contains(id)) {
                return id;
            }
        }
        return after.stream()
                .filter(id -> !before.contains(id))
                .filter(id -> id.toLowerCase().contains("forge"))
                .findFirst()
                .orElse(expected.get(0));
    }
}