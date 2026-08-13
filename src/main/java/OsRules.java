import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class OsRules {
    private OsRules() {
    }

    static String currentOsName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "osx";
        }
        return "linux";
    }

    static String archBits() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("64") || arch.equals("aarch64") ? "64" : "32";
    }

    static boolean matches(Map<String, Object> osRule) {
        if (osRule.isEmpty()) {
            return true;
        }
        String name = Json.string(osRule, "name");
        if (!name.isBlank() && !name.equals(currentOsName())) {
            return false;
        }
        String arch = Json.string(osRule, "arch");
        if (!arch.isBlank()) {
            String currentArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            if (!Pattern.compile(arch).matcher(currentArch).find()) {
                return false;
            }
        }
        String version = Json.string(osRule, "version");
        if (!version.isBlank()) {
            String currentVersion = System.getProperty("os.version", "");
            return Pattern.compile(version).matcher(currentVersion).find();
        }
        return true;
    }
}
