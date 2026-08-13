enum ThemeMode {
    LIGHT("theme.light"),
    DARK("theme.dark");

    private final String key;

    ThemeMode(String key) {
        this.key = key;
    }

    String key() {
        return key;
    }

    static ThemeMode fromCode(String code) {
        if (code != null) {
            for (ThemeMode mode : values()) {
                if (mode.name().equalsIgnoreCase(code.trim())) {
                    return mode;
                }
            }
        }
        return DARK;
    }
}
