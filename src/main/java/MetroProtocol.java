import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Validates commands sent by the Windows 8.1 tile shell. */
final class MetroProtocol {
    private static final Set<String> SECTIONS = Set.of(
            "main", "play", "library", "mods", "skins", "backups", "settings");

    private MetroProtocol() {
    }

    static String section(String rawUri) {
        if (rawUri == null || rawUri.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(rawUri.trim());
            if (!"msc-launcher".equalsIgnoreCase(uri.getScheme())
                    || !"open".equalsIgnoreCase(uri.getHost())) {
                return "";
            }
            String path = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
            String normalized = path.toLowerCase(Locale.ROOT);
            return SECTIONS.contains(normalized) ? normalized : "main";
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }
}
