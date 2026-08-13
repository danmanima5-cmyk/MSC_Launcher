import java.util.Locale;

enum AppLanguage {
    EN("en", "English"),
    RU("ru", "Русский"),
    UK("uk", "Українська"),
    RO("ro", "Română"),
    ZH("zh", "简体中文"),
    JA("ja", "日本語"),
    KO("ko", "한국어"),
    DE("de", "Deutsch"),
    FR("fr", "Français"),
    IT("it", "Italiano");

    private final String code;
    private final String displayName;

    AppLanguage(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    String code() {
        return code;
    }

    String displayName() {
        return displayName;
    }

    static AppLanguage[] supportedValues() {
        return new AppLanguage[] {EN, RU, FR, IT, JA, RO, ZH, KO};
    }

    static boolean isSupported(AppLanguage language) {
        if (language == null) return false;
        for (AppLanguage supported : supportedValues()) {
            if (supported == language) return true;
        }
        return false;
    }

    static AppLanguage fromCode(String code) {
        if (code != null) {
            for (AppLanguage language : values()) {
                if (isSupported(language)
                        && (language.code.equalsIgnoreCase(code.trim()) || language.name().equalsIgnoreCase(code.trim()))) {
                    return language;
                }
            }
        }
        return defaultLanguage();
    }

    static AppLanguage defaultLanguage() {
        String language = Locale.getDefault().getLanguage();
        return switch (language) {
            case "ru" -> RU;
            case "ro" -> RO;
            case "zh" -> ZH;
            case "ko" -> KO;
            case "ja" -> JA;
            case "fr" -> FR;
            case "it" -> IT;
            default -> EN;
        };
    }

    @Override
    public String toString() {
        return displayName;
    }
}
