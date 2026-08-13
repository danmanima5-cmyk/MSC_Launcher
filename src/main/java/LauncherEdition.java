import java.util.Locale;

/** Runtime identity for separately packaged launcher editions. */
enum LauncherEdition {
    STANDARD("standard", "MSC Launcher", "msc-launcher-data", null, false),
    METRO("metro", "MSC Launcher Metro", "msc-launcher-metro-data", "metro", true);

    static final String EDITION_PROPERTY = "msc.launcher.edition";

    private final String id;
    private final String displayName;
    private final String dataDirectoryName;
    private final String forcedUiMode;
    private final boolean fullScreen;

    LauncherEdition(String id, String displayName, String dataDirectoryName,
                    String forcedUiMode, boolean fullScreen) {
        this.id = id;
        this.displayName = displayName;
        this.dataDirectoryName = dataDirectoryName;
        this.forcedUiMode = forcedUiMode;
        this.fullScreen = fullScreen;
    }

    static LauncherEdition current() {
        String configured = System.getProperty(EDITION_PROPERTY, "").trim().toLowerCase(Locale.ROOT);
        return "metro".equals(configured) ? METRO : STANDARD;
    }

    String id() {
        return id;
    }

    String displayName() {
        return displayName;
    }

    String dataDirectoryName() {
        return dataDirectoryName;
    }

    String applyUiMode(String configuredMode) {
        return forcedUiMode == null ? configuredMode : forcedUiMode;
    }

    boolean locksUiMode() {
        return forcedUiMode != null;
    }

    boolean usesFullScreenShell() {
        return fullScreen;
    }

    boolean acceptsReleaseAsset(String fileName) {
        String normalized = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        boolean metroAsset = normalized.contains("metro");
        return this == METRO ? metroAsset : !metroAsset;
    }
}
