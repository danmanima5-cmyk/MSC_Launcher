import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

final class InstallerProfileSupport {
    private static final String LAUNCHER_PROFILES_FILE = "launcher_profiles.json";
    private static final String LAUNCHER_PROFILES_MS_STORE_FILE = "launcher_profiles_microsoft_store.json";

    private InstallerProfileSupport() {
    }

    static void ensureLauncherProfiles(Path gameDirectory, ProgressSink progress) {
        ensureProfileFile(gameDirectory, LAUNCHER_PROFILES_FILE, progress);
        // Официальные installer'ы (OptiFine, Forge) также проверяют MS Store вариант
        // launcher_profiles_microsoft_store.json и падают с "File not found",
        // если его нет вовсе — создаём и его, чтобы установка проходила без ошибок.
        ensureProfileFile(gameDirectory, LAUNCHER_PROFILES_MS_STORE_FILE, progress);
    }

    private static void ensureProfileFile(Path gameDirectory, String fileName, ProgressSink progress) {
        Path profileFile = gameDirectory.resolve(fileName);
        if (Files.isRegularFile(profileFile)) {
            return;
        }
        LinkedHashMap<String, Object> json = new LinkedHashMap<>();
        json.put("profiles", new LinkedHashMap<String, Object>());
        json.put("selectedProfile", "");
        json.put("clientToken", "");
        json.put("authenticationDatabase", new LinkedHashMap<String, Object>());
        json.put("launcherVersion", HttpService.map(
                "name", "MSC Launcher",
                "format", 21,
                "profilesFormat", 2
        ));
        try {
            Files.createDirectories(gameDirectory);
            Files.writeString(profileFile, Json.stringify(json), StandardCharsets.UTF_8);
            progress.log("Создан минимальный " + fileName + " для installer: " + profileFile);
        } catch (IOException ex) {
            throw new LauncherException("Не удалось создать " + fileName + ": " + ex.getMessage(), ex);
        }
    }
}
