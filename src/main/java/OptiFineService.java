import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class OptiFineService {
    private static final String DOWNLOADS_URL = "https://optifine.net/downloads";
    private static final String OPTIFINE_BASE = "https://optifine.net/";

    private final HttpService http = new HttpService();
    private final MinecraftInstaller minecraftInstaller;
    private final ForgeService forgeService;
    private Set<String> cachedSupportedGameVersions;

    OptiFineService(MinecraftInstaller minecraftInstaller, ForgeService forgeService, CurseForgeService curseForgeService) {
        this.minecraftInstaller = minecraftInstaller;
        this.forgeService = forgeService;
    }

    String installStandalone(String gameVersion, LauncherSettings settings, ProgressSink progress) {
        progress.status("Установка OptiFine " + gameVersion);
        OptiFineRelease release = latestFor(gameVersion);
        Path jar = download(release, settings, progress);
        // Run the official installer directly inside the real .minecraft so its hardcoded
        // "versions/<id>" output lands straight in .minecraft/versions — matching where vanilla
        // and standalone OptiFine profiles are meant to live (see
        // ProfileDirectories.storesInGameVersionsFolder) — instead of msc-launcher's own folder.
        launchOfficialInstaller(jar, settings.gameDirectory(), settings, progress);
        // The installer also drops its own OptiFine library jar into .minecraft/libraries —
        // fold it into the launcher's shared libraries folder, where library downloads
        // actually look for it (otherwise the next launch tries and fails to fetch it from
        // Mojang's library CDN, which never hosted OptiFine).
        ProfileDirectories.migrateOptiFineLibraries(settings.gameDirectory(), progress);
        // Safety net for any older leftovers still sitting in msc-launcher/versions.
        ProfileDirectories.migrateOfficialInstallerVersions(progress);
        return "OptiFine installer завершил работу: " + release.fileName();
    }

    String installWithForge(String gameVersion, LauncherSettings settings, ProgressSink progress) {
        OptiFineRelease release = latestWithForgeSupport(gameVersion);
        String forgeMavenVersion = forgeService.resolveForgeForOptiFine(gameVersion, release.forgeText());
        return installWithForge(gameVersion, release, forgeMavenVersion, settings, progress);
    }

    /**
     * Ручной вариант installWithForge: и сборка OptiFine, и сборка Forge выбираются
     * пользователем явно (как в official launcher / MultiMC — просто список версий на выбор),
     * а не автоматическим резолвером. Автоподбор для версий 1.13+ регулярно ставил Forge,
     * несовместимый с конкретной сборкой OptiFine (то слишком старый — не проходил встроенную
     * проверку версии у самого OptiFine, то слишком новый — ломал ASM-трансформеры и ронял игру
     * без сообщения). Раз автоматика ненадёжна для этого диапазона версий, самый честный вариант —
     * отдать выбор пользователю, как это и предлагает интерфейс "Скачать вручную".
     */
    String installWithForgeManual(String gameVersion, OptiFineRelease release, String forgeMavenVersion, LauncherSettings settings, ProgressSink progress) {
        requireForgeCompatibleRelease(gameVersion, release);
        return installWithForge(gameVersion, release, forgeMavenVersion, settings, progress);
    }

    String installWithForgeIntoProfile(String gameVersion, String targetProfile, LauncherSettings settings, ProgressSink progress) {
        OptiFineRelease release = latestWithForgeSupport(gameVersion);
        String forgeMavenVersion = forgeService.resolveForgeForOptiFine(gameVersion, release.forgeText());
        return installWithForgeIntoProfile(gameVersion, release, forgeMavenVersion, targetProfile, settings, progress);
    }

    String installWithForgeManualIntoProfile(String gameVersion, OptiFineRelease release, String forgeMavenVersion,
                                             String targetProfile, LauncherSettings settings, ProgressSink progress) {
        requireForgeCompatibleRelease(gameVersion, release);
        return installWithForgeIntoProfile(gameVersion, release, forgeMavenVersion, targetProfile, settings, progress);
    }

    private String installWithForge(String gameVersion, OptiFineRelease release, String forgeMavenVersion, LauncherSettings settings, ProgressSink progress) {
        requireForgeCompatibleRelease(gameVersion, release);
        progress.status("Установка Forge + OptiFine " + gameVersion);
        prepareVanillaBeforeOptiFine(gameVersion, settings, progress);
        String forgeProfile = forgeService.installForge(gameVersion, forgeMavenVersion, settings, progress);
        String optiFineProfile = createForgeOptiFineProfile(forgeProfile, settings, progress);
        Path optiFineJar = downloadPrepared(release, progress);
        Path modsJar = installAsForgeMod(gameVersion, optiFineProfile, optiFineJar, settings, progress);
        progress.log("OptiFine установлен как Forge mod: " + modsJar);
        return optiFineProfile;
    }

    private String installWithForgeIntoProfile(String gameVersion, OptiFineRelease release, String forgeMavenVersion,
                                               String targetProfile, LauncherSettings settings, ProgressSink progress) {
        requireForgeCompatibleRelease(gameVersion, release);
        progress.status("Установка Forge + OptiFine " + gameVersion);
        prepareVanillaBeforeOptiFine(gameVersion, settings, progress);
        String forgeProfile = forgeService.installForge(gameVersion, forgeMavenVersion, settings, progress);
        Path optiFineJar = downloadPrepared(release, progress);
        Path modsJar = installAsForgeMod(gameVersion, targetProfile, optiFineJar, settings, progress);
        progress.log("OptiFine установлен как Forge mod в инстанс: " + modsJar);
        return forgeProfile;
    }

    /** Список сборок OptiFine для версии игры — для ручного выбора в UI. */
    List<OptiFineRelease> releasesForManualPick(String gameVersion) {
        List<OptiFineRelease> releases = releasesFor(gameVersion);
        if (releases.isEmpty()) {
            throw new LauncherException("OptiFine не найден для Minecraft " + gameVersion);
        }
        return releases;
    }

    OptiFineRelease latestFor(String gameVersion) {
        List<OptiFineRelease> releases = releasesFor(gameVersion);
        if (releases.isEmpty()) {
            throw new LauncherException("OptiFine не найден для Minecraft " + gameVersion);
        }
        return releases.stream()
                .filter(release -> !release.fileName().startsWith("preview_"))
                .findFirst()
                .orElse(releases.get(0));
    }

    /**
     * Как latestFor(), но для сценария Forge + OptiFine: берёт не просто самую свежую
     * сборку OptiFine для версии игры, а самую свежую сборку, у которой в колонке Forge
     * на optifine.net реально указана сборка Forge (не пусто и не "N/A").
     * Это важно, потому что для некоторых версий MC у самой новой сборки OptiFine
     * Forge не указан, хотя у чуть более старой сборки той же версии MC — указан
     * (например 1.14.3/1.14.4 previews). Без этой проверки installWithForge мог
     * упереться в "не указывает совместимую сборку Forge", хотя рабочий вариант был.
     */
    OptiFineRelease latestWithForgeSupport(String gameVersion) {
        List<OptiFineRelease> releases = releasesFor(gameVersion);
        if (releases.isEmpty()) {
            throw new LauncherException("OptiFine не найден для Minecraft " + gameVersion);
        }
        return releases.stream()
                .filter(release -> hasForgeBuild(release.forgeText()))
                .findFirst()
                .orElseThrow(() -> new LauncherException(
                        "Для Minecraft " + gameVersion + " ни одна сборка OptiFine на optifine.net "
                                + "не указывает совместимую сборку Forge (поле Forge = \"N/A\" у всех релизов). "
                                + "Судя по всему, для этой версии Minecraft у OptiFine официально нет Forge-редакции — "
                                + "доступна только отдельная (standalone) установка OptiFine."));
    }

    boolean hasForgeBuild(String forgeText) {
        String required = forgeText == null ? "" : forgeText.replace("Forge", "").trim();
        return !required.isBlank() && !"N/A".equalsIgnoreCase(required);
    }

    Set<String> forgeSupportedGameVersions() {
        LinkedHashSet<String> supported = new LinkedHashSet<>();
        String html = http.getString(DOWNLOADS_URL);
        Matcher headingMatcher = Pattern.compile("<h2>\\s*Minecraft\\s+([^<]+)\\s*</h2>", Pattern.CASE_INSENSITIVE)
                .matcher(html);
        ArrayList<String> versions = new ArrayList<>();
        ArrayList<Integer> starts = new ArrayList<>();
        while (headingMatcher.find()) {
            versions.add(headingMatcher.group(1).trim());
            starts.add(headingMatcher.end());
        }
        for (int i = 0; i < versions.size(); i++) {
            int start = starts.get(i);
            int end = (i + 1 < starts.size()) ? starts.get(i + 1) : html.length();
            String section = html.substring(start, end);
            Matcher rowMatcher = Pattern.compile("<tr\\s+class=['\"]downloadLine[^'\"]*['\"]>(.*?)</tr>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                    .matcher(section);
            while (rowMatcher.find()) {
                if (hasForgeBuild(stripTags(cell(rowMatcher.group(1), "colForge")).trim())) {
                    supported.add(versions.get(i));
                    break;
                }
            }
        }
        return Set.copyOf(supported);
    }

    boolean isForgeCompatibleRelease(String gameVersion, String fileName, ProgressSink progress) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        try {
            for (OptiFineRelease release : releasesFor(gameVersion)) {
                if (release.fileName().equals(fileName)) {
                    return hasForgeBuild(release.forgeText());
                }
            }
        } catch (LauncherException ex) {
            progress.log("Не удалось проверить совместимость OptiFine с Forge: " + ex.getMessage());
        }
        // Conservative offline fallback for the problematic legacy range: 1.13.x is explicitly
        // Forge N/A on optifine.net, while 1.14.4/1.15.2 have Forge-compatible releases.
        if (VersionSort.compareVersions(gameVersion, "1.13") >= 0 && VersionSort.compareVersions(gameVersion, "1.14") < 0) {
            return false;
        }
        return VersionSort.compareVersions(gameVersion, "1.14.4") >= 0;
    }

    private void requireForgeCompatibleRelease(String gameVersion, OptiFineRelease release) {
        if (!hasForgeBuild(release.forgeText())) {
            throw new LauncherException("OptiFine " + release.fileName() + " для Minecraft " + gameVersion
                    + " не поддерживает Forge без OptiForge (на optifine.net: Forge = \"N/A\").");
        }
    }

    Set<String> supportedGameVersions() {
        if (cachedSupportedGameVersions != null) {
            return cachedSupportedGameVersions;
        }
        String html = http.getString(DOWNLOADS_URL);
        Matcher matcher = Pattern.compile("<h2>\\s*Minecraft\\s+([^<]+)\\s*</h2>", Pattern.CASE_INSENSITIVE)
                .matcher(html);
        LinkedHashSet<String> versions = new LinkedHashSet<>();
        while (matcher.find()) {
            String version = matcher.group(1).trim();
            if (!version.isBlank()) {
                versions.add(version);
            }
        }
        cachedSupportedGameVersions = Set.copyOf(versions);
        return cachedSupportedGameVersions;
    }

    List<OptiFineRelease> releasesFor(String gameVersion) {
        String html = http.getString(DOWNLOADS_URL);
        String section = sectionForVersion(html, gameVersion);
        Matcher rowMatcher = Pattern.compile("<tr\\s+class=['\"]downloadLine[^'\"]*['\"]>(.*?)</tr>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(section);
        ArrayList<OptiFineRelease> releases = new ArrayList<>();
        while (rowMatcher.find()) {
            String row = rowMatcher.group(1);
            String title = stripTags(cell(row, "colFile")).trim();
            String mirrorHref = hrefFromCell(row, "colMirror");
            String forgeText = stripTags(cell(row, "colForge")).trim();
            String date = stripTags(cell(row, "colDate")).trim();
            String fileName = fileNameFromHref(mirrorHref);
            if (!fileName.isBlank()) {
                releases.add(new OptiFineRelease(gameVersion, title, fileName, forgeText, date));
            }
        }
        releases.sort(Comparator.comparing(release -> release.fileName().startsWith("preview_")));
        return releases;
    }

    private String sectionForVersion(String html, String gameVersion) {
        Pattern headingPattern = Pattern.compile("<h2>\\s*Minecraft\\s+([^<]+)\\s*</h2>", Pattern.CASE_INSENSITIVE);
        Matcher matcher = headingPattern.matcher(html);
        int start = -1;
        int end = html.length();
        while (matcher.find()) {
            String version = matcher.group(1).trim();
            if (start >= 0) {
                end = matcher.start();
                break;
            }
            if (gameVersion.equals(version)) {
                start = matcher.end();
            }
        }
        if (start < 0) {
            throw new LauncherException("На optifine.net нет раздела Minecraft " + gameVersion);
        }
        return html.substring(start, end);
    }

    /**
     * Полностью устанавливает базовую vanilla-версию до загрузки OptiFine.
     * Официальный установщик OptiFine патчит локальный client.jar и при его
     * отсутствии завершается с "File not found" либо создаёт повреждённый профиль.
     * Полная установка также заранее подготавливает библиотеки и assets, поэтому
     * получившийся OptiFine-профиль можно запускать сразу после закрытия installer'а.
     */
    private void prepareVanillaBeforeOptiFine(String gameVersion, LauncherSettings settings, ProgressSink progress) {
        progress.status("Подготовка vanilla " + gameVersion + " для OptiFine");
        progress.log("Перед установкой OptiFine сначала устанавливается vanilla " + gameVersion + ".");
        minecraftInstaller.installVanilla(gameVersion, settings, progress);
        progress.log("Vanilla " + gameVersion + " готова. Переход к загрузке OptiFine.");
        progress.status("Загрузка OptiFine " + gameVersion);
    }

    Path download(OptiFineRelease release, LauncherSettings settings, ProgressSink progress) {
        prepareVanillaBeforeOptiFine(release.gameVersion(), settings, progress);
        return downloadPrepared(release, progress);
    }

    private Path downloadPrepared(OptiFineRelease release, ProgressSink progress) {
        String url = resolveDownloadUrl(release.fileName());
        Path target = LauncherSettings.settingsDirectory().resolve("installers").resolve("optifine").resolve(release.fileName());
        http.download(url, target, "", -1, progress);
        return target;
    }

    boolean isDownloaded(OptiFineRelease release) {
        Path target = LauncherSettings.settingsDirectory().resolve("installers").resolve("optifine").resolve(release.fileName());
        return Files.isRegularFile(target);
    }

    private String resolveDownloadUrl(String fileName) {
        String adloadUrl = OPTIFINE_BASE + "adloadx?f=" + encode(fileName);
        String page = http.getString(adloadUrl);
        Matcher matcher = Pattern.compile("downloadx\\?f=([^'\"<>]+?)&x=([a-fA-F0-9]+)", Pattern.CASE_INSENSITIVE)
                .matcher(page);
        if (!matcher.find()) {
            throw new LauncherException("OptiFine downloadx-ссылка не найдена для " + fileName);
        }
        String resolvedFileName = decode(matcher.group(1));
        String token = matcher.group(2);
        return OPTIFINE_BASE + "downloadx?f=" + encode(resolvedFileName) + "&x=" + token;
    }

    private Path installAsForgeMod(String gameVersion, String forgeProfile, Path optiFineJar, LauncherSettings settings, ProgressSink progress) {
        Path gameDirectory = settings.gameDirectory();
        Path modsDir = ProfileDirectories.modsDirectory(gameDirectory, forgeProfile);
        try {
            Files.createDirectories(modsDir);
            warnAboutOtherOptiFineMods(gameVersion, modsDir, optiFineJar.getFileName().toString(), progress);
            warnAboutRootModsIgnored(gameDirectory, forgeProfile, progress);
        } catch (IOException ex) {
            throw new LauncherException("Не удалось подготовить папку mods: " + ex.getMessage(), ex);
        }

        if (!requiresModJarExtraction(gameVersion)) {
            // Forge до 1.12.2 включительно использует старый coremod-загрузчик,
            // который прекрасно грузит "сырой" установочный jar OptiFine из mods.
            Path target = modsDir.resolve(optiFineJar.getFileName());
            try {
                removeInvalidOptiFineCopies(gameVersion, modsDir, progress);
                Files.copy(optiFineJar, target, StandardCopyOption.REPLACE_EXISTING);
                progress.log("OptiFine установлен в mods без OptiForge: " + target.getFileName());
                return target;
            } catch (IOException ex) {
                throw new LauncherException("Не удалось установить OptiFine в mods: " + ex.getMessage(), ex);
            }
        }

        if (usesLegacyForgeOptiFineLoading(gameVersion) && isUsableForgeOptiFineJar(optiFineJar, gameVersion)) {
            // Forge 1.13-1.15 не загружает OptiFine как обычный Forge mod из mods:
            // jar без mods.toml будет проигнорирован. Его нужно держать вне mods и
            // подключать через ModLauncher transformation service при запуске.
            Path target = legacyForgeOptiFineDirectory(modsDir.getParent()).resolve(optiFineJar.getFileName());
            try {
                Files.createDirectories(target.getParent());
                removeInvalidOptiFineCopies(gameVersion, modsDir, progress);
                Files.copy(optiFineJar, target, StandardCopyOption.REPLACE_EXISTING);
                progress.log("OptiFine подготовлен для Forge 1.13-1.15 без OptiForge: " + target);
                return target;
            } catch (IOException ex) {
                throw new LauncherException("Не удалось подготовить OptiFine для Forge 1.13-1.15: " + ex.getMessage(), ex);
            }
        }

        // Современные релизы OptiFine (примерно с 1.16+) уже содержат META-INF/mods.toml
        // прямо в том jar, который раздаётся на optifine.net — так же, как всегда работал
        // "сырой" jar для Forge до 1.13. В этом случае ставим точно как на 1.12.2: без
        // открытия окна официального инсталлятора, простым копированием в mods.
        if (isValidForgeModJar(optiFineJar)) {
            removeInvalidOptiFineCopies(gameVersion, modsDir, progress);
            Path target = modsDir.resolve(optiFineJar.getFileName());
            try {
                Files.copy(optiFineJar, target, StandardCopyOption.REPLACE_EXISTING);
                progress.log("OptiFine установлен как Forge mod без окна инсталлятора: " + target.getFileName());
                return target;
            } catch (IOException ex) {
                throw new LauncherException("Не удалось установить OptiFine в mods: " + ex.getMessage(), ex);
            }
        }

        // Остался только случай, когда jar не похож ни на новый Forge-mod jar, ни на
        // legacy OptiFine для Forge 1.13-1.15. Тогда оставляем старый ручной fallback.
        removeInvalidOptiFineCopies(gameVersion, modsDir, progress);
        progress.status("Извлечение OptiFine как Forge-мода " + gameVersion);
        progress.log("Сейчас откроется официальный установщик OptiFine.");
        progress.log("В его окне нажмите \"Extract\" (НЕ \"Install\") и сохраните файл в папку:");
        progress.log(modsDir.toString());
        // Открываем инсталлятор с рабочей директорией = папка этого профиля (родитель modsDir),
        // а не общий gameDirectory: именно эту папку реально сканирует Forge при запуске
        // ЭТОГО профиля (см. ProfileDirectories), поэтому диалог "Extract" по умолчанию
        // должен открываться именно там, а не где-то ещё.
        Path profileDirectory = modsDir.getParent();
        launchOfficialInstaller(optiFineJar, profileDirectory, settings, progress);
        Path extracted = findValidForgeModJar(modsDir);
        if (extracted == null) {
            // Если пользователь не переключил папку вручную в диалоге, валидный jar может
            // лежать прямо в profileDirectory, в общем gameDirectory, либо рядом с самим
            // optiFineJar — подхватываем его оттуда, а не сразу считаем установку проваленной.
            extracted = findAndMoveStrayForgeModJar(profileDirectory, optiFineJar, modsDir, progress);
        }
        if (extracted == null) {
            extracted = findAndMoveStrayForgeModJar(gameDirectory, optiFineJar, modsDir, progress);
        }
        if (extracted == null) {
            throw new LauncherException("OptiFine не был добавлен как мод Forge. В окне установщика "
                    + "нужно нажать именно \"Extract\" и сохранить файл в " + modsDir
                    + ". Кнопка \"Install\" создаёт отдельный профиль, а не мод, и для Forge + OptiFine не подходит.");
        }
        return extracted;
    }

    /** Ищет валидный extracted-jar OptiFine вне modsDir (обычно в корне папки игры) и переносит его в mods. */
    private Path findAndMoveStrayForgeModJar(Path gameDirectory, Path optiFineJar, Path modsDir, ProgressSink progress) {
        List<Path> candidateDirs = List.of(gameDirectory, optiFineJar.getParent());
        for (Path dir : candidateDirs) {
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            try (var stream = Files.list(dir)) {
                Path found = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> !path.equals(optiFineJar))
                        .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                        .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).contains("optifine"))
                        .filter(this::isValidForgeModJar)
                        .max(Comparator.comparingLong(this::lastModifiedMillis))
                        .orElse(null);
                if (found != null) {
                    Path target = modsDir.resolve(found.getFileName());
                    Files.move(found, target, StandardCopyOption.REPLACE_EXISTING);
                    progress.log("Найден extracted OptiFine jar вне mods (" + found + "), перенесён в " + target);
                    return target;
                }
            } catch (IOException ex) {
                progress.log("Не удалось проверить " + dir + " на наличие extracted OptiFine jar: " + ex.getMessage());
            }
        }
        return null;
    }

    /** Начиная с какой версии нужна проверка, что OptiFine jar реально подхватит Forge. */
    boolean requiresModJarExtraction(String gameVersion) {
        Matcher matcher = Pattern.compile("^(\\d+)\\.(\\d+)").matcher(gameVersion == null ? "" : gameVersion);
        if (!matcher.find()) {
            return true; // неизвестный формат версии — безопаснее считать её современной
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        return major > 1 || (major == 1 && minor >= 13);
    }

    private void removeInvalidOptiFineCopies(String gameVersion, Path modsDir, ProgressSink progress) {
        if (!Files.isDirectory(modsDir)) {
            return;
        }
        try (var stream = Files.list(modsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                            .matches("(preview_)?optifine_.*\\.jar"))
                    .filter(path -> !isUsableForgeOptiFineJar(path, gameVersion))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                            progress.log("Удалён невалидный OptiFine jar для Forge: " + path.getFileName());
                        } catch (IOException ex) {
                            progress.log("Не удалось удалить старый OptiFine jar " + path.getFileName() + ": " + ex.getMessage());
                        }
                    });
        } catch (IOException ex) {
            progress.log("Не удалось проверить mods на старые копии OptiFine: " + ex.getMessage());
        }
    }

    private Path findValidForgeModJar(Path modsDir) {
        try (var stream = Files.list(modsDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                    .filter(this::isValidForgeModJar)
                    .max(Comparator.comparingLong(this::lastModifiedMillis))
                    .orElse(null);
        } catch (IOException ex) {
            return null;
        }
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }

    boolean isValidForgeModJar(Path jar) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            return zip.getEntry("META-INF/mods.toml") != null || zip.getEntry("mcmod.info") != null;
        } catch (IOException ex) {
            return false;
        }
    }

    boolean isUsableForgeOptiFineJar(Path jar, String gameVersion) {
        if (isValidForgeModJar(jar)) {
            return true;
        }
        if (!usesLegacyForgeOptiFineLoading(gameVersion)) {
            return false;
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            return zip.getEntry("optifine/OptiFineForgeTweaker.class") != null
                    || zip.getEntry("optifine/OptiFineTweaker.class") != null
                    || zip.getEntry("optifine/OptiFineTransformationService.class") != null
                    || zip.getEntry("META-INF/services/cpw.mods.modlauncher.api.ITransformationService") != null;
        } catch (IOException ex) {
            return false;
        }
    }

    boolean usesLegacyForgeOptiFineLoading(String gameVersion) {
        return VersionSort.compareVersions(gameVersion, "1.13") >= 0
                && VersionSort.compareVersions(gameVersion, "1.16") < 0;
    }

    Path legacyForgeOptiFineDirectory(Path profileDirectory) {
        return profileDirectory.resolve("optifine");
    }

    Path prepareLegacyForgeLaunchJar(Path optiFineJar, Path profileDirectory, ProgressSink progress) {
        Path target = legacyForgeOptiFineDirectory(profileDirectory)
                .resolve(stripJarExtension(optiFineJar.getFileName().toString()) + "-modlauncher.jar");
        try {
            Files.createDirectories(target.getParent());
            if (Files.isRegularFile(target)
                    && Files.getLastModifiedTime(target).toMillis() >= Files.getLastModifiedTime(optiFineJar).toMillis()) {
                return target;
            }
            try (java.util.zip.ZipFile input = new java.util.zip.ZipFile(optiFineJar.toFile());
                 ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
                java.util.Enumeration<? extends ZipEntry> entries = input.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if ("META-INF/services/cpw.mods.modlauncher.api.ITransformationService".equals(entry.getName())) {
                        continue;
                    }
                    output.putNextEntry(copyEntry(entry));
                    if (!entry.isDirectory()) {
                        try (java.io.InputStream in = input.getInputStream(entry)) {
                            in.transferTo(output);
                        }
                    }
                    output.closeEntry();
                }
                output.putNextEntry(new ZipEntry("META-INF/services/cpw.mods.modlauncher.api.ITransformationService"));
                output.write("optifine.OptiFineTransformationService\n".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
            progress.log("Создан launch-jar OptiFine для ModLauncher: " + target.getFileName());
            return target;
        } catch (IOException ex) {
            throw new LauncherException("Не удалось подготовить OptiFine для ModLauncher: " + ex.getMessage(), ex);
        }
    }

    private ZipEntry copyEntry(ZipEntry source) {
        ZipEntry copy = new ZipEntry(source.getName());
        copy.setTime(source.getTime());
        if (source.getMethod() == ZipEntry.STORED) {
            copy.setMethod(ZipEntry.STORED);
            copy.setSize(source.getSize());
            copy.setCompressedSize(source.getCompressedSize());
            copy.setCrc(source.getCrc());
        }
        return copy;
    }

    private String stripJarExtension(String fileName) {
        return fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
    }

    private String createForgeOptiFineProfile(String forgeProfile, LauncherSettings settings, ProgressSink progress) {
        // forgeProfile must be the plain Forge profile id here, never a "-optifine" id itself —
        // otherwise profileId and inheritsFrom below would end up identical, which is a
        // self-referencing version JSON (circular inheritance, crashes on resolve()). Strip a
        // stray "-optifine" suffix defensively so a bug in a caller (e.g. ForgeService picking
        // up an existing Forge+OptiFine profile instead of the plain one) can never write one.
        String baseForgeProfile = forgeProfile.endsWith("-optifine")
                ? forgeProfile.substring(0, forgeProfile.length() - "-optifine".length())
                : forgeProfile;
        String profileId = baseForgeProfile + "-optifine";
        Path profileDir = ProfileDirectories.versionsInstallDirectory().resolve(profileId);
        Path profileJson = profileDir.resolve(profileId + ".json");
        LinkedHashMap<String, Object> json = new LinkedHashMap<>();
        String now = Instant.now().toString();
        json.put("id", profileId);
        json.put("inheritsFrom", baseForgeProfile);
        json.put("time", now);
        json.put("releaseTime", now);
        json.put("type", "release");
        try {
            Files.createDirectories(profileDir);
            Files.writeString(profileJson, Json.stringify(json), StandardCharsets.UTF_8);
            progress.log("Forge + OptiFine профиль сохранён: " + profileId);
            return profileId;
        } catch (IOException ex) {
            throw new LauncherException("Не удалось сохранить Forge + OptiFine профиль: " + ex.getMessage(), ex);
        }
    }

    private void warnAboutOtherOptiFineMods(String gameVersion, Path modsDir, String selectedFileName, ProgressSink progress) throws IOException {
        if (!Files.isDirectory(modsDir)) {
            return;
        }
        try (var stream = Files.list(modsDir)) {
            stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("OptiFine_") || name.startsWith("preview_OptiFine_"))
                    .filter(name -> !name.equals(selectedFileName))
                    .forEach(name -> progress.log("Внимание: в mods уже есть другой OptiFine jar: " + name + ". Для Minecraft " + gameVersion + " это может вызвать конфликт."));
        }
    }

    private void warnAboutRootModsIgnored(Path gameDirectory, String forgeProfile, ProgressSink progress) throws IOException {
        Path rootMods = gameDirectory.resolve("mods");
        if (!ProfileDirectories.usesIsolatedGameDirectory(forgeProfile) || !Files.isDirectory(rootMods)) {
            return;
        }
        try (var stream = Files.list(rootMods)) {
            long rootModsCount = stream.filter(Files::isRegularFile).count();
            if (rootModsCount > 0) {
                progress.log("Forge запускается с отдельной папкой mods для профиля " + forgeProfile
                        + ". Общий каталог .minecraft/mods не будет мешать этой версии.");
            }
        }
    }

    private void launchOfficialInstaller(Path jar, LauncherSettings settings, ProgressSink progress) {
        launchOfficialInstaller(jar, ProfileDirectories.installRoot(), settings, progress);
    }

    private void launchOfficialInstaller(Path jar, Path workingDirectory, LauncherSettings settings, ProgressSink progress) {
        // Официальный installer OptiFine сам пытается дописать себя в launcher_profiles.json
        // (и в launcher_profiles_microsoft_store.json) в своей рабочей директории (cwd). Если
        // этих файлов нет, он показывает "File not found" при нажатии Install/Extract —
        // создаём их заранее именно в workingDirectory (не обязательно совпадает с реальной
        // .minecraft, если это изолированная папка Forge+OptiFine профиля).
        InstallerProfileSupport.ensureLauncherProfiles(workingDirectory, progress);
        try {
            Files.createDirectories(workingDirectory);
        } catch (IOException ex) {
            throw new LauncherException("Не удалось создать папку профиля: " + ex.getMessage(), ex);
        }
        List<String> command = List.of(settings.javaPath().toString(), "-jar", jar.toString());
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDirectory.toFile());
            // Важно: не оставлять stdout/stderr как PIPE без чтения. Официальный installer
            // может напечатать в консоль больше, чем помещается в буфер ОС (обычно 64 КБ),
            // и тогда его процесс блокируется на записи, пока труба не освободится — visually
            // это выглядит так, будто установка "зависает" и завершается, только когда наш
            // лаунчер закрывается (ОС закрывает читающий конец трубы). DISCARD убирает эту блокировку,
            // installer работает полностью независимо от нашего процесса.
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();
            progress.log("Открыт официальный OptiFine installer. В его окне проверьте папку игры и нажмите Install.");
            // Дожидаемся, пока пользователь закроет окно официального installer'а (после Install,
            // Extract или Cancel). Это выполняется в фоновом потоке задачи (не в потоке интерфейса),
            // поэтому окно самого лаунчера не подвисает — а после закрытия installer'а вызывающий код
            // может сразу обновить список установленных версий, без перезапуска лаунчера.
            process.waitFor();
            progress.log("Окно OptiFine installer закрыто.");
        } catch (IOException ex) {
            throw new LauncherException("Не удалось открыть OptiFine installer: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LauncherException("Ожидание завершения OptiFine installer прервано.", ex);
        }
    }

    private String cell(String row, String cssClass) {
        Matcher matcher = Pattern.compile("<td\\s+class=['\"]" + Pattern.quote(cssClass) + "['\"]>(.*?)</td>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(row);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String hrefFromCell(String row, String cssClass) {
        String cell = cell(row, cssClass);
        Matcher matcher = Pattern.compile("href=['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE).matcher(cell);
        return matcher.find() ? matcher.group(1).replace("&amp;", "&") : "";
    }

    private String fileNameFromHref(String href) {
        Matcher matcher = Pattern.compile("[?&]f=([^&'\"]+)").matcher(href);
        return matcher.find() ? decode(matcher.group(1)) : "";
    }

    private String stripTags(String html) {
        return html.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
