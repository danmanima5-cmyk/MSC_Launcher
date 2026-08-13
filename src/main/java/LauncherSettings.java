import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Properties;

final class LauncherSettings {
    private static final String DATA_DIRECTORY_NAME = "msc-launcher-data";
    private static final String LEGACY_DATA_DIRECTORY_NAME = "msc-launcher";
    private static final String LEGACY_MIGRATION_MARKER = ".legacy-data-migrated";
    private static final String LEGACY_MIGRATION_LOCK = ".legacy-data-migration.lock";
    private static final String[] LEGACY_PERSISTENT_ENTRIES = {
            "settings.properties",
            "accounts.properties",
            "account.properties",
            "active-skin.properties",
            "updater.properties",
            "launcher-log.txt",
            "jre_manifest.json",
            "Profiles",
            "versions",
            "saves_old",
            "world-backups",
            "libraries",
            "assets",
            "resources",
            "installers",
            "authlib-injector",
            "skins",
            "instance-icons",
            "cache",
            "msc-launcher"
    };

    // Постоянные данные не должны находиться рядом с EXE/JAR лаунчера: установщик обновления
    // может очищать или заменять каталог приложения. На Windows используем отдельный
    // %APPDATA%\msc-launcher-data; старые данные из %APPDATA%\msc-launcher копируются туда
    // один раз при первом запуске этой версии.
    private static final Path SETTINGS_DIR = initializeSettingsDir();
    private static final Path SETTINGS_FILE = SETTINGS_DIR.resolve("settings.properties");
    private static final String DEFAULT_MICROSOFT_CLIENT_ID = "a332a5f6-c4dc-45b6-9fe3-d881490252b2";
    private static final String DEFAULT_MICROSOFT_REDIRECT_URI = "http://localhost:46521";
    private static final String UNSUPPORTED_LEGACY_MICROSOFT_CLIENT_ID = "f8cdef31-a31e-4b4a-93e4-5f571e91255a";
    private static final String DEFAULT_ELY_BY_CLIENT_ID = "msc-launcher";
    private static final String DEFAULT_ELY_BY_CLIENT_SECRET = "u4_2xCSvqU7OJ00nGtTECcMm4rvQPma0kTIdk3_udDjr8KNmDj6J-jVbCF3TTUTJ";
    private static final String DEFAULT_ELY_BY_REDIRECT_URI = "http://localhost:27485/elyby/callback";
    static final String DEFAULT_METRO_THEME_COLOR = "purple";

    private Path gameDirectory;
    private Path javaPath;
    /** Optional explicit Java executable paths per major version, configured from the
     *  "Java installations" settings section (Java 8 / 17 / 21 / 25). Null means "not set" —
     *  the launcher falls back to auto-detection / auto-download for that major version. */
    private Path javaPath8;
    private Path javaPath17;
    private Path javaPath21;
    private Path javaPath25;
    private int minMemoryMb;
    private int maxMemoryMb;
    private String username;
    private String microsoftClientId;
    private String microsoftRedirectUri;
    private String elyByClientId;
    private String elyByClientSecret;
    private String elyByRedirectUri;
    private String wallpaperPath;
    private String updateManifestUrl;
    private boolean showReleaseVersions;
    private boolean showSnapshotVersions;
    private boolean showOldBetaVersions;
    private boolean showOldAlphaVersions;
    private boolean showVanillaVersions;
    private boolean showFabricVersions;
    private boolean showForgeVersions;
    private boolean showNeoForgeVersions;
    private boolean showQuiltVersions;
    private boolean showOptiFineVersions;
    private boolean showForgeOptiFineVersions;
    private boolean hideLauncherAfterLaunch;
    private boolean autoUpdateModLoaders;
    private boolean hideServerBanner;
    /** Whether Modern UI shows the wallpaper (bundled Resources/wallpaper.png, or a custom one)
     *  behind its flat screens (Home, Library, Backups, ...) instead of the plain solid background. */
    private boolean modernWallpaperEnabled;
    private String modernAccentColor;
    private boolean metroWallpaperEnabled;
    private AppLanguage language;
    private ThemeMode theme;
    /** UI style: "msc" = classic MSC Launcher, "modrinth" = Modrinth-inspired modern UI */
    private String uiMode;
    /** Custom directory where world backups are stored. Empty = default inside settings dir. */
    private String backupDirectory;
    /** Semicolon-separated list of "profileId|worldName" pairs tracked for backup monitoring. */
    private String trackedBackupWorlds;
    /** Where the Modern UI global sidebar is docked: LEFT, RIGHT, TOP or BOTTOM. */
    private String sidebarPosition;
    /** Comma-separated custom order of the (draggable) sidebar nav items, e.g. "2,0,1,3,4". */
    private String sidebarOrder;
    /** Semicolon-separated Metro tile layout entries: index,x,y,w,h. */
    private String metroTileLayout;
    /** Whether screen transition animations are enabled for each UI mode. */
    private boolean classicTransitionsEnabled;
    private boolean modernTransitionsEnabled;
    private boolean metroTransitionsEnabled;
    /** Whether Metro UI paints the glossy Aero overlay. */
    private boolean metroAeroEnabled;
    /** Named Metro UI base color used for the flat theme and Aero background. */
    private String metroThemeColor;
    /** Сколько дней не показывать оповещение об обновлении лаунчера после нажатия "Отменить" (1–30). */
    private int updateSnoozeDays;
    /** Показывать ли вообще оповещения о новой версии лаунчера. Ручная проверка обновлений
     *  (кнопка в Настройках) работает независимо от этого флага. */
    private boolean updateNotificationsEnabled;

    private LauncherSettings(Path gameDirectory, Path javaPath, int minMemoryMb, int maxMemoryMb, String username,
                             String microsoftClientId, String microsoftRedirectUri, String elyByClientId,
                             String elyByClientSecret, String elyByRedirectUri, String wallpaperPath,
                             String updateManifestUrl,
                             boolean showReleaseVersions, boolean showSnapshotVersions, boolean showOldBetaVersions,
                             boolean showOldAlphaVersions, boolean showVanillaVersions, boolean showFabricVersions,
                             boolean showForgeVersions, boolean showOptiFineVersions, boolean showForgeOptiFineVersions,
                             boolean showNeoForgeVersions, boolean showQuiltVersions, boolean hideLauncherAfterLaunch,
                             boolean autoUpdateModLoaders, AppLanguage language, ThemeMode theme) {
        this.gameDirectory = gameDirectory;
        this.javaPath = javaPath;
        this.minMemoryMb = minMemoryMb;
        this.maxMemoryMb = maxMemoryMb;
        this.username = username;
        this.microsoftClientId = microsoftClientId;
        this.microsoftRedirectUri = microsoftRedirectUri;
        this.elyByClientId = elyByClientId;
        this.elyByClientSecret = elyByClientSecret;
        this.elyByRedirectUri = elyByRedirectUri;
        this.wallpaperPath = wallpaperPath;
        this.updateManifestUrl = updateManifestUrl;
        this.showReleaseVersions = showReleaseVersions;
        this.showSnapshotVersions = showSnapshotVersions;
        this.showOldBetaVersions = showOldBetaVersions;
        this.showOldAlphaVersions = showOldAlphaVersions;
        this.showVanillaVersions = showVanillaVersions;
        this.showFabricVersions = showFabricVersions;
        this.showForgeVersions = showForgeVersions;
        this.showNeoForgeVersions = showNeoForgeVersions;
        this.showQuiltVersions = showQuiltVersions;
        this.showOptiFineVersions = showOptiFineVersions;
        this.showForgeOptiFineVersions = showForgeOptiFineVersions;
        this.hideLauncherAfterLaunch = hideLauncherAfterLaunch;
        this.autoUpdateModLoaders = autoUpdateModLoaders;
        this.language = normalizeLanguage(language);
        this.theme = theme;
        this.uiMode = "msc";
        this.backupDirectory = "";
        this.trackedBackupWorlds = "";
        this.sidebarPosition = "LEFT";
        this.sidebarOrder = "";
        this.metroTileLayout = "";
        this.classicTransitionsEnabled = true;
        this.modernTransitionsEnabled = true;
        this.metroTransitionsEnabled = true;
        this.metroAeroEnabled = true;
        this.metroThemeColor = DEFAULT_METRO_THEME_COLOR;
        this.updateSnoozeDays = 7;
        this.updateNotificationsEnabled = true;
    }

    static LauncherSettings load() {
        Properties properties = new Properties();
        if (Files.isRegularFile(SETTINGS_FILE)) {
            try (InputStream in = Files.newInputStream(SETTINGS_FILE)) {
                properties.load(in);
            } catch (IOException ignored) {
                // Defaults below keep the launcher usable when the settings file is damaged.
            }
        }

        Path gameDir = Path.of(properties.getProperty("gameDirectory", defaultGameDirectory().toString()));
        Path javaPath = Path.of(properties.getProperty("javaPath", defaultJavaPath().toString()));
        // Первый запуск (нет сохранённых значений) — подбираем Xms/Xmx исходя из
        // реального объёма ОЗУ этого компьютера, а не фиксированных чисел,
        // одинаковых для всех пользователей.
        int min = parseInt(properties.getProperty("minMemoryMb"), SystemMemoryInfo.suggestedDefaultMinMemoryMb());
        int max = parseInt(properties.getProperty("maxMemoryMb"), SystemMemoryInfo.suggestedDefaultMaxMemoryMb());
        String username = properties.getProperty("username", "Player").trim();
        String clientId = properties.getProperty("microsoftClientId", DEFAULT_MICROSOFT_CLIENT_ID).trim();
        if (clientId.isBlank() || UNSUPPORTED_LEGACY_MICROSOFT_CLIENT_ID.equalsIgnoreCase(clientId)) {
            clientId = DEFAULT_MICROSOFT_CLIENT_ID;
        }
        String microsoftRedirectUri = properties.getProperty("microsoftRedirectUri", DEFAULT_MICROSOFT_REDIRECT_URI).trim();
        String elyByClientId = properties.getProperty("elyByClientId", DEFAULT_ELY_BY_CLIENT_ID).trim();
        String elyByClientSecret = properties.getProperty("elyByClientSecret", DEFAULT_ELY_BY_CLIENT_SECRET).trim();
        if ("msc-launcher1".equals(elyByClientId)) {
            elyByClientId = DEFAULT_ELY_BY_CLIENT_ID;
            elyByClientSecret = DEFAULT_ELY_BY_CLIENT_SECRET;
        }
        String elyByRedirectUri = properties.getProperty("elyByRedirectUri", DEFAULT_ELY_BY_REDIRECT_URI).trim();
        String wallpaperPath = properties.getProperty("wallpaperPath", "").trim();
        String updateManifestUrl = properties.getProperty("updateManifestUrl", "").trim();
        boolean showReleaseVersions = parseBoolean(properties.getProperty("showReleaseVersions"), true);
        boolean showSnapshotVersions = parseBoolean(properties.getProperty("showSnapshotVersions"), true);
        boolean showOldBetaVersions = parseBoolean(properties.getProperty("showOldBetaVersions"), true);
        boolean showOldAlphaVersions = parseBoolean(properties.getProperty("showOldAlphaVersions"), true);
        boolean showVanillaVersions = parseBoolean(properties.getProperty("showVanillaVersions"), true);
        boolean showFabricVersions = parseBoolean(properties.getProperty("showFabricVersions"), true);
        boolean showForgeVersions = parseBoolean(properties.getProperty("showForgeVersions"), true);
        boolean showNeoForgeVersions = parseBoolean(properties.getProperty("showNeoForgeVersions"), true);
        boolean showQuiltVersions = parseBoolean(properties.getProperty("showQuiltVersions"), true);
        boolean showOptiFineVersions = parseBoolean(properties.getProperty("showOptiFineVersions"), true);
        boolean showForgeOptiFineVersions = parseBoolean(properties.getProperty("showForgeOptiFineVersions"), true);
        boolean hideLauncherAfterLaunch = parseBoolean(properties.getProperty("hideLauncherAfterLaunch"), false);
        // Нажатие "Играть" не должно менять установленный профиль. Обновление loader'ов
        // выполняется только пользователем через явную установку нужной версии.
        boolean autoUpdateModLoaders = false;
        AppLanguage language = AppLanguage.fromCode(properties.getProperty("language"));
        ThemeMode theme = ThemeMode.fromCode(properties.getProperty("theme"));
        String uiMode = LauncherEdition.current().applyUiMode(
                properties.getProperty("uiMode", "msc").trim());
        LauncherSettings s = new LauncherSettings(gameDir, javaPath, min, max, username.isBlank() ? "Player" : username,
                clientId,
                microsoftRedirectUri.isBlank() ? DEFAULT_MICROSOFT_REDIRECT_URI : microsoftRedirectUri,
                elyByClientId, elyByClientSecret,
                elyByRedirectUri.isBlank() ? DEFAULT_ELY_BY_REDIRECT_URI : elyByRedirectUri,
                wallpaperPath,
                updateManifestUrl,
                showReleaseVersions, showSnapshotVersions, showOldBetaVersions, showOldAlphaVersions,
                showVanillaVersions, showFabricVersions, showForgeVersions, showOptiFineVersions,
                showForgeOptiFineVersions, showNeoForgeVersions, showQuiltVersions, hideLauncherAfterLaunch,
                autoUpdateModLoaders, language, theme);
        s.uiMode = switch (uiMode) {
            case "metro" -> "metro";
            case "modrinth", "modrinth-dark", "modrinth-light", "frutiger" -> "modrinth-dark";
            case "modrinth-green" -> "modrinth-dark"; // legacy: green theme removed, map to dark
            default -> "msc";
        };
        s.backupDirectory = properties.getProperty("backupDirectory", "").trim();
        s.trackedBackupWorlds = properties.getProperty("trackedBackupWorlds", "").trim();
        s.hideServerBanner = parseBoolean(properties.getProperty("hideServerBanner"), false);
        s.modernWallpaperEnabled = parseBoolean(properties.getProperty("modernWallpaperEnabled"), true);
        s.modernAccentColor = properties.getProperty("modernAccentColor", "blue").trim().toLowerCase(Locale.ROOT);
        s.metroWallpaperEnabled = parseBoolean(properties.getProperty("metroWallpaperEnabled"), false);
        s.sidebarPosition = properties.getProperty("sidebarPosition", "LEFT").trim().toUpperCase(Locale.ROOT);
        s.sidebarOrder = properties.getProperty("sidebarOrder", "").trim();
        s.metroTileLayout = properties.getProperty("metroTileLayout", "").trim();
        s.classicTransitionsEnabled = parseBoolean(properties.getProperty("classicTransitionsEnabled"), true);
        s.modernTransitionsEnabled = parseBoolean(properties.getProperty("modernTransitionsEnabled"), true);
        s.metroTransitionsEnabled = parseBoolean(properties.getProperty("metroTransitionsEnabled"), true);
        s.metroAeroEnabled = parseBoolean(properties.getProperty("metroAeroEnabled"), true);
        s.metroThemeColor = normalizeMetroThemeColor(properties.getProperty("metroThemeColor", DEFAULT_METRO_THEME_COLOR));
        int snoozeDays = parseInt(properties.getProperty("updateSnoozeDays"), 7);
        s.updateSnoozeDays = Math.max(1, Math.min(30, snoozeDays));
        s.updateNotificationsEnabled = parseBoolean(properties.getProperty("updateNotificationsEnabled"), true);
        s.javaPath8 = parseOptionalPath(properties.getProperty("javaPath8", ""));
        s.javaPath17 = parseOptionalPath(properties.getProperty("javaPath17", ""));
        s.javaPath21 = parseOptionalPath(properties.getProperty("javaPath21", ""));
        s.javaPath25 = parseOptionalPath(properties.getProperty("javaPath25", ""));
        return s;
    }

    private static Path parseOptionalPath(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Path.of(text.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    void save() {
        Properties properties = new Properties();
        properties.setProperty("gameDirectory", gameDirectory.toString());
        properties.setProperty("javaPath", javaPath.toString());
        properties.setProperty("minMemoryMb", Integer.toString(minMemoryMb));
        properties.setProperty("maxMemoryMb", Integer.toString(maxMemoryMb));
        properties.setProperty("username", username);
        properties.setProperty("microsoftClientId", microsoftClientId);
        properties.setProperty("microsoftRedirectUri", microsoftRedirectUri);
        properties.setProperty("elyByClientId", elyByClientId);
        properties.setProperty("elyByClientSecret", elyByClientSecret);
        properties.setProperty("elyByRedirectUri", elyByRedirectUri);
        properties.setProperty("wallpaperPath", wallpaperPath);
        properties.setProperty("updateManifestUrl", updateManifestUrl == null ? "" : updateManifestUrl);
        properties.setProperty("showReleaseVersions", Boolean.toString(showReleaseVersions));
        properties.setProperty("showSnapshotVersions", Boolean.toString(showSnapshotVersions));
        properties.setProperty("showOldBetaVersions", Boolean.toString(showOldBetaVersions));
        properties.setProperty("showOldAlphaVersions", Boolean.toString(showOldAlphaVersions));
        properties.setProperty("showVanillaVersions", Boolean.toString(showVanillaVersions));
        properties.setProperty("showFabricVersions", Boolean.toString(showFabricVersions));
        properties.setProperty("showForgeVersions", Boolean.toString(showForgeVersions));
        properties.setProperty("showNeoForgeVersions", Boolean.toString(showNeoForgeVersions));
        properties.setProperty("showQuiltVersions", Boolean.toString(showQuiltVersions));
        properties.setProperty("showOptiFineVersions", Boolean.toString(showOptiFineVersions));
        properties.setProperty("showForgeOptiFineVersions", Boolean.toString(showForgeOptiFineVersions));
        properties.setProperty("hideLauncherAfterLaunch", Boolean.toString(hideLauncherAfterLaunch));
        properties.setProperty("autoUpdateModLoaders", "false");
        properties.setProperty("language", language().code());
        properties.setProperty("theme", theme.name());
        properties.setProperty("uiMode", uiMode == null ? "msc" : uiMode);
        properties.setProperty("backupDirectory", backupDirectory == null ? "" : backupDirectory);
        properties.setProperty("trackedBackupWorlds", trackedBackupWorlds == null ? "" : trackedBackupWorlds);
        properties.setProperty("hideServerBanner", Boolean.toString(hideServerBanner));
        properties.setProperty("modernWallpaperEnabled", Boolean.toString(modernWallpaperEnabled));
        properties.setProperty("modernAccentColor", modernAccentColor == null ? "blue" : modernAccentColor);
        properties.setProperty("metroWallpaperEnabled", Boolean.toString(metroWallpaperEnabled));
        properties.setProperty("sidebarPosition", sidebarPosition == null ? "LEFT" : sidebarPosition);
        properties.setProperty("sidebarOrder", sidebarOrder == null ? "" : sidebarOrder);
        properties.setProperty("metroTileLayout", metroTileLayout == null ? "" : metroTileLayout);
        properties.setProperty("classicTransitionsEnabled", Boolean.toString(classicTransitionsEnabled));
        properties.setProperty("modernTransitionsEnabled", Boolean.toString(modernTransitionsEnabled));
        properties.setProperty("metroTransitionsEnabled", Boolean.toString(metroTransitionsEnabled));
        properties.setProperty("metroAeroEnabled", Boolean.toString(metroAeroEnabled));
        properties.setProperty("metroThemeColor", normalizeMetroThemeColor(metroThemeColor));
        properties.setProperty("updateSnoozeDays", Integer.toString(updateSnoozeDays));
        properties.setProperty("updateNotificationsEnabled", Boolean.toString(updateNotificationsEnabled));
        properties.setProperty("javaPath8", javaPath8 == null ? "" : javaPath8.toString());
        properties.setProperty("javaPath17", javaPath17 == null ? "" : javaPath17.toString());
        properties.setProperty("javaPath21", javaPath21 == null ? "" : javaPath21.toString());
        properties.setProperty("javaPath25", javaPath25 == null ? "" : javaPath25.toString());
        try {
            Files.createDirectories(SETTINGS_DIR);
            try (OutputStream out = Files.newOutputStream(SETTINGS_FILE)) {
                properties.store(out, "MSC Launcher settings");
            }
        } catch (IOException ex) {
            throw new LauncherException("Не удалось сохранить настройки: " + ex.getMessage(), ex);
        }
    }

    static Path settingsDirectory() {
        return SETTINGS_DIR;
    }

    private static Path initializeSettingsDir() {
        Path directory = resolveSettingsDir();
        migrateLegacyData(resolveLegacySettingsDir(), directory);
        return directory;
    }

    private static Path resolveSettingsDir() {
        return resolvePlatformDataDirectory(LauncherEdition.current().dataDirectoryName());
    }

    private static Path resolveLegacySettingsDir() {
        // The separate Metro edition starts with a one-time copy of the normal
        // edition's data. The normal edition keeps its original migration path.
        String sourceName = LauncherEdition.current() == LauncherEdition.METRO
                ? DATA_DIRECTORY_NAME : LEGACY_DATA_DIRECTORY_NAME;
        return resolvePlatformDataDirectory(sourceName);
    }

    private static Path resolvePlatformDataDirectory(String directoryName) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path roaming = appData == null || appData.isBlank()
                    ? Path.of(home, "AppData", "Roaming")
                    : Path.of(appData);
            return roaming.resolve(directoryName);
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", directoryName);
        }

        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        Path dataHome = xdgDataHome == null || xdgDataHome.isBlank()
                ? Path.of(home, ".local", "share")
                : Path.of(xdgDataHome);
        return dataHome.resolve(directoryName);
    }

    /**
     * Copies only launcher-owned persistent entries out of the old directory. We deliberately
     * do not copy arbitrary files: on some Windows installations that directory also contains
     * the installed EXE, runtime and uninstaller. Existing files in the new directory always
     * win, so an interrupted migration can safely be retried without rolling back newer saves.
     */
    private static void migrateLegacyData(Path legacyDirectory, Path dataDirectory) {
        Path marker = dataDirectory.resolve(LEGACY_MIGRATION_MARKER);
        if (Files.exists(marker) || !Files.isDirectory(legacyDirectory)
                || legacyDirectory.toAbsolutePath().normalize().equals(dataDirectory.toAbsolutePath().normalize())) {
            return;
        }

        try {
            Files.createDirectories(dataDirectory);
            Path lockPath = dataDirectory.resolve(LEGACY_MIGRATION_LOCK);
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock migrationLock;
                try {
                    migrationLock = channel.tryLock();
                } catch (OverlappingFileLockException ex) {
                    migrationLock = null;
                }
                if (migrationLock == null) {
                    System.err.println("MSC Launcher: перенос пользовательских данных уже выполняется другим экземпляром.");
                    return;
                }
                try (FileLock acquiredLock = migrationLock) {
                    // Another process may have completed the migration after the initial marker check.
                    if (Files.exists(marker)) {
                        return;
                    }
                    migrateLegacyDataLocked(legacyDirectory, dataDirectory, marker);
                }
            }
        } catch (IOException | SecurityException ex) {
            System.err.println("MSC Launcher: не удалось перенести пользовательские данные: " + ex.getMessage());
        }
    }

    private static void migrateLegacyDataLocked(Path legacyDirectory, Path dataDirectory, Path marker) {
        boolean complete = true;
        try {
            for (String entryName : LEGACY_PERSISTENT_ENTRIES) {
                Path source = legacyDirectory.resolve(entryName);
                Path target = dataDirectory.resolve(entryName);
                if ("Profiles".equals(entryName) || "saves_old".equals(entryName)
                        || "world-backups".equals(entryName)) {
                    complete &= copyLegacyProfileCollection(source, target, dataDirectory, entryName);
                } else {
                    complete &= copyMissingTree(source, target);
                }
            }
            if (complete) {
                Files.writeString(marker,
                        "Persistent launcher data was copied from " + legacyDirectory.toAbsolutePath().normalize());
            }
        } catch (IOException | SecurityException ex) {
            complete = false;
            System.err.println("MSC Launcher: не удалось перенести пользовательские данные: " + ex.getMessage());
        }
        if (!complete) {
            System.err.println("MSC Launcher: перенос пользовательских данных будет повторен при следующем запуске.");
        }
    }

    /**
     * Migrates profile-like directory trees without ever merging two independently existing
     * profiles with the same id. A destination created by this migration is marked as such so
     * an interrupted copy can resume into it. A genuine name collision is copied under
     * legacy-recovery instead, where its worlds stay available for manual recovery but the
     * launcher will not list it as a second/crossed installed version.
     */
    private static boolean copyLegacyProfileCollection(Path sourceRoot, Path targetRoot,
                                                       Path dataDirectory, String collectionName) {
        if (!Files.exists(sourceRoot)) {
            return true;
        }
        if (!Files.isDirectory(sourceRoot)) {
            return copyMissingTree(sourceRoot, targetRoot);
        }

        boolean complete = true;
        try {
            Files.createDirectories(targetRoot);
            try (var children = Files.list(sourceRoot)) {
                for (Path source : children.toList()) {
                    String name = source.getFileName().toString();
                    Path target = targetRoot.resolve(name);
                    if (!Files.isDirectory(source) || Files.isSymbolicLink(source)) {
                        complete &= copyMissingTree(source, target);
                        continue;
                    }

                    Path stateDirectory = dataDirectory.resolve(".legacy-migration")
                            .resolve(collectionName).resolve(name);
                    Path doneMarker = stateDirectory.resolve("done");
                    if (Files.isRegularFile(doneMarker)) {
                        continue;
                    }

                    Path primaryMarker = stateDirectory.resolve("use-primary-destination");
                    if (!Files.exists(target) && !Files.exists(primaryMarker)) {
                        Files.createDirectories(stateDirectory);
                        Files.writeString(primaryMarker, target.toAbsolutePath().normalize().toString());
                    }

                    boolean usePrimary = Files.isRegularFile(primaryMarker);
                    Path destination = usePrimary
                            ? target
                            : dataDirectory.resolve("legacy-recovery").resolve(collectionName).resolve(name);
                    boolean copied = copyMissingTree(source, destination);
                    complete &= copied;
                    if (copied) {
                        Files.createDirectories(stateDirectory);
                        Files.writeString(doneMarker, destination.toAbsolutePath().normalize().toString());
                    }
                }
            }
            return complete;
        } catch (IOException | SecurityException ex) {
            System.err.println("MSC Launcher: не удалось перенести " + sourceRoot + ": " + ex.getMessage());
            return false;
        }
    }

    private static boolean copyMissingTree(Path source, Path target) {
        if (!Files.exists(source)) {
            return true;
        }
        if (Files.isSymbolicLink(source)) {
            return true;
        }
        try {
            if (Files.isDirectory(source)) {
                Files.createDirectories(target);
                boolean complete = true;
                try (var children = Files.list(source)) {
                    for (Path child : children.toList()) {
                        complete &= copyMissingTree(child, target.resolve(child.getFileName().toString()));
                    }
                }
                return complete;
            }

            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
            return true;
        } catch (IOException | SecurityException ex) {
            if (sameLengthRegularFiles(source, target)) {
                // Another launcher instance may have completed this immutable/cache file first.
                return true;
            }
            System.err.println("MSC Launcher: не удалось скопировать " + source + ": " + ex.getMessage());
            return false;
        }
    }

    private static boolean sameLengthRegularFiles(Path source, Path target) {
        try {
            return Files.isRegularFile(source) && Files.isRegularFile(target)
                    && Files.size(source) == Files.size(target);
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    Path gameDirectory() {
        return gameDirectory;
    }

    void setGameDirectory(Path gameDirectory) {
        this.gameDirectory = gameDirectory;
    }

    Path javaPath() {
        return javaPath;
    }

    void setJavaPath(Path javaPath) {
        this.javaPath = javaPath;
    }

    /** Explicit Java executable configured for this major version in "Java installations", or
     *  null if the user hasn't set one (auto-detect/auto-download will be used instead). */
    Path javaPathForMajor(int major) {
        return switch (major) {
            case 8 -> javaPath8;
            case 17 -> javaPath17;
            case 21 -> javaPath21;
            case 25 -> javaPath25;
            default -> null;
        };
    }

    void setJavaPathForMajor(int major, Path path) {
        switch (major) {
            case 8 -> javaPath8 = path;
            case 17 -> javaPath17 = path;
            case 21 -> javaPath21 = path;
            case 25 -> javaPath25 = path;
            default -> { /* unsupported major, ignore */ }
        }
    }

    Path javaPath8() {
        return javaPath8;
    }

    void setJavaPath8(Path path) {
        this.javaPath8 = path;
    }

    Path javaPath17() {
        return javaPath17;
    }

    void setJavaPath17(Path path) {
        this.javaPath17 = path;
    }

    Path javaPath21() {
        return javaPath21;
    }

    void setJavaPath21(Path path) {
        this.javaPath21 = path;
    }

    Path javaPath25() {
        return javaPath25;
    }

    void setJavaPath25(Path path) {
        this.javaPath25 = path;
    }

    int minMemoryMb() {
        return minMemoryMb;
    }

    void setMinMemoryMb(int minMemoryMb) {
        this.minMemoryMb = minMemoryMb;
    }

    int maxMemoryMb() {
        return maxMemoryMb;
    }

    void setMaxMemoryMb(int maxMemoryMb) {
        this.maxMemoryMb = maxMemoryMb;
    }

    String username() {
        return username;
    }

    void setUsername(String username) {
        this.username = username == null || username.isBlank() ? "Player" : username;
    }

    String microsoftClientId() {
        return microsoftClientId;
    }

    void setMicrosoftClientId(String microsoftClientId) {
        this.microsoftClientId = microsoftClientId == null ? "" : microsoftClientId;
    }

    String microsoftRedirectUri() {
        return microsoftRedirectUri;
    }

    void setMicrosoftRedirectUri(String microsoftRedirectUri) {
        this.microsoftRedirectUri = microsoftRedirectUri == null || microsoftRedirectUri.isBlank()
                ? DEFAULT_MICROSOFT_REDIRECT_URI
                : microsoftRedirectUri;
    }

    String elyByClientId() {
        return elyByClientId;
    }

    void setElyByClientId(String elyByClientId) {
        this.elyByClientId = elyByClientId == null ? "" : elyByClientId;
    }

    String elyByClientSecret() {
        return elyByClientSecret;
    }

    void setElyByClientSecret(String elyByClientSecret) {
        this.elyByClientSecret = elyByClientSecret == null ? "" : elyByClientSecret;
    }

    String elyByRedirectUri() {
        return elyByRedirectUri;
    }

    void setElyByRedirectUri(String elyByRedirectUri) {
        this.elyByRedirectUri = elyByRedirectUri == null || elyByRedirectUri.isBlank() ? DEFAULT_ELY_BY_REDIRECT_URI : elyByRedirectUri;
    }

    String wallpaperPath() {
        return wallpaperPath;
    }

    void setWallpaperPath(String wallpaperPath) {
        this.wallpaperPath = wallpaperPath == null ? "" : wallpaperPath.trim();
    }

    String updateManifestUrl() {
        return updateManifestUrl;
    }

    void setUpdateManifestUrl(String updateManifestUrl) {
        this.updateManifestUrl = updateManifestUrl == null ? "" : updateManifestUrl.trim();
    }

    boolean showReleaseVersions() {
        return showReleaseVersions;
    }

    void setShowReleaseVersions(boolean showReleaseVersions) {
        this.showReleaseVersions = showReleaseVersions;
    }

    boolean showSnapshotVersions() {
        return showSnapshotVersions;
    }

    void setShowSnapshotVersions(boolean showSnapshotVersions) {
        this.showSnapshotVersions = showSnapshotVersions;
    }

    boolean showOldBetaVersions() {
        return showOldBetaVersions;
    }

    void setShowOldBetaVersions(boolean showOldBetaVersions) {
        this.showOldBetaVersions = showOldBetaVersions;
    }

    boolean showOldAlphaVersions() {
        return showOldAlphaVersions;
    }

    void setShowOldAlphaVersions(boolean showOldAlphaVersions) {
        this.showOldAlphaVersions = showOldAlphaVersions;
    }

    boolean showVanillaVersions() {
        return showVanillaVersions;
    }

    void setShowVanillaVersions(boolean showVanillaVersions) {
        this.showVanillaVersions = showVanillaVersions;
    }

    boolean showFabricVersions() {
        return showFabricVersions;
    }

    void setShowFabricVersions(boolean showFabricVersions) {
        this.showFabricVersions = showFabricVersions;
    }

    boolean showForgeVersions() {
        return showForgeVersions;
    }

    void setShowForgeVersions(boolean showForgeVersions) {
        this.showForgeVersions = showForgeVersions;
    }

    boolean showNeoForgeVersions() {
        return showNeoForgeVersions;
    }

    void setShowNeoForgeVersions(boolean showNeoForgeVersions) {
        this.showNeoForgeVersions = showNeoForgeVersions;
    }

    boolean showQuiltVersions() {
        return showQuiltVersions;
    }

    void setShowQuiltVersions(boolean showQuiltVersions) {
        this.showQuiltVersions = showQuiltVersions;
    }

    boolean showOptiFineVersions() {
        return showOptiFineVersions;
    }

    void setShowOptiFineVersions(boolean showOptiFineVersions) {
        this.showOptiFineVersions = showOptiFineVersions;
    }

    boolean showForgeOptiFineVersions() {
        return showForgeOptiFineVersions;
    }

    void setShowForgeOptiFineVersions(boolean showForgeOptiFineVersions) {
        this.showForgeOptiFineVersions = showForgeOptiFineVersions;
    }

    boolean hideLauncherAfterLaunch() {
        return hideLauncherAfterLaunch;
    }

    void setHideLauncherAfterLaunch(boolean hideLauncherAfterLaunch) {
        this.hideLauncherAfterLaunch = hideLauncherAfterLaunch;
    }

    boolean autoUpdateModLoaders() {
        return false;
    }

    void setAutoUpdateModLoaders(boolean autoUpdateModLoaders) {
        this.autoUpdateModLoaders = false;
    }

    AppLanguage language() {
        language = normalizeLanguage(language);
        return language;
    }

    void setLanguage(AppLanguage language) {
        this.language = normalizeLanguage(language);
    }

    private static AppLanguage normalizeLanguage(AppLanguage language) {
        return AppLanguage.isSupported(language) ? language : AppLanguage.defaultLanguage();
    }

    ThemeMode theme() {
        return theme;
    }

    void setTheme(ThemeMode theme) {
        this.theme = theme == null ? ThemeMode.DARK : theme;
    }

    String uiMode() {
        return uiMode == null ? "msc" : uiMode;
    }

    void setUiMode(String mode) {
        mode = LauncherEdition.current().applyUiMode(mode);
        if (mode == null) { this.uiMode = "msc"; return; }
        // Accept legacy modern modes, but keep Modern UI dark-only.
        switch (mode) {
            case "metro" -> this.uiMode = "metro";
            case "modrinth", "modrinth-dark", "modrinth-light", "frutiger" -> this.uiMode = "modrinth-dark";
            // Legacy green theme removed: treat as modrinth-dark
            case "modrinth-green" -> this.uiMode = "modrinth-dark";
            default -> this.uiMode = "msc";
        }
    }

    String backupDirectory() {
        return backupDirectory == null ? "" : backupDirectory;
    }

    void setBackupDirectory(String dir) {
        this.backupDirectory = dir == null ? "" : dir.trim();
    }

    /** Returns set of tracked "profileId|worldName" entries. */
    java.util.Set<String> trackedBackupWorldSet() {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        if (trackedBackupWorlds != null && !trackedBackupWorlds.isBlank()) {
            for (String part : trackedBackupWorlds.split(";")) {
                String t = part.trim();
                if (!t.isEmpty()) set.add(t);
            }
        }
        return set;
    }

    void setTrackedBackupWorldSet(java.util.Set<String> set) {
        this.trackedBackupWorlds = set == null ? "" : String.join(";", set);
    }

    private static int parseInt(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String text, boolean fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(text.trim());
    }

    private static Path defaultJavaPath() {
        String executable = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private static Path defaultGameDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Path.of(appData == null || appData.isBlank() ? home : appData, ".minecraft");
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "minecraft");
        }
        return Path.of(home, ".minecraft");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    boolean hideServerBanner() {
        return hideServerBanner;
    }

    void setHideServerBanner(boolean hideServerBanner) {
        this.hideServerBanner = hideServerBanner;
    }

    boolean modernWallpaperEnabled() {
        return modernWallpaperEnabled;
    }

    void setModernWallpaperEnabled(boolean modernWallpaperEnabled) {
        this.modernWallpaperEnabled = modernWallpaperEnabled;
    }

    String modernAccentColor() {
        return modernAccentColor == null || modernAccentColor.isBlank() ? "blue" : modernAccentColor;
    }

    void setModernAccentColor(String color) {
        modernAccentColor = color == null || color.isBlank() ? "blue" : color.trim().toLowerCase(Locale.ROOT);
    }

    boolean metroWallpaperEnabled() {
        return metroWallpaperEnabled;
    }

    void setMetroWallpaperEnabled(boolean enabled) {
        metroWallpaperEnabled = enabled;
    }

    /** LEFT, RIGHT, TOP или BOTTOM — где сейчас закреплена боковая панель Modern UI. */
    String sidebarPosition() {
        return (sidebarPosition == null || sidebarPosition.isBlank()) ? "LEFT" : sidebarPosition;
    }

    void setSidebarPosition(String position) {
        this.sidebarPosition = (position == null || position.isBlank()) ? "LEFT" : position.trim().toUpperCase(Locale.ROOT);
    }

    /** Пользовательский порядок пунктов боковой панели (кроме "Настроек"), например "2,0,1,3,4". */
    String sidebarOrder() {
        return sidebarOrder == null ? "" : sidebarOrder;
    }

    void setSidebarOrder(String order) {
        this.sidebarOrder = order == null ? "" : order.trim();
    }

    String metroTileLayout() {
        return metroTileLayout == null ? "" : metroTileLayout;
    }

    void setMetroTileLayout(String layout) {
        this.metroTileLayout = layout == null ? "" : layout.trim();
    }

    boolean classicTransitionsEnabled() {
        return classicTransitionsEnabled;
    }

    void setClassicTransitionsEnabled(boolean enabled) {
        this.classicTransitionsEnabled = enabled;
    }

    boolean modernTransitionsEnabled() {
        return modernTransitionsEnabled;
    }

    void setModernTransitionsEnabled(boolean enabled) {
        this.modernTransitionsEnabled = enabled;
    }

    boolean metroTransitionsEnabled() {
        return metroTransitionsEnabled;
    }

    void setMetroTransitionsEnabled(boolean enabled) {
        this.metroTransitionsEnabled = enabled;
    }

    boolean metroAeroEnabled() {
        return metroAeroEnabled;
    }

    void setMetroAeroEnabled(boolean enabled) {
        this.metroAeroEnabled = enabled;
    }

    String metroThemeColor() {
        return normalizeMetroThemeColor(metroThemeColor);
    }

    void setMetroThemeColor(String color) {
        this.metroThemeColor = normalizeMetroThemeColor(color);
    }

    private static String normalizeMetroThemeColor(String color) {
        if (color == null) {
            return DEFAULT_METRO_THEME_COLOR;
        }
        return switch (color.trim().toLowerCase(Locale.ROOT)) {
            case "blue" -> "blue";
            case "teal" -> "teal";
            case "green" -> "green";
            case "red" -> "red";
            case "graphite" -> "graphite";
            default -> DEFAULT_METRO_THEME_COLOR;
        };
    }

    /** Сколько дней (1–30) не показывать оповещение об обновлении после нажатия "Отменить". */
    int updateSnoozeDays() {
        return updateSnoozeDays <= 0 ? 7 : updateSnoozeDays;
    }

    void setUpdateSnoozeDays(int days) {
        this.updateSnoozeDays = Math.max(1, Math.min(30, days));
    }

    /** Показывать ли автоматическое оповещение о новой версии при запуске лаунчера. */
    boolean updateNotificationsEnabled() {
        return updateNotificationsEnabled;
    }

    void setUpdateNotificationsEnabled(boolean enabled) {
        this.updateNotificationsEnabled = enabled;
    }
}
