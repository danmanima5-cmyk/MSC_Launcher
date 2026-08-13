import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Opens local directories using the native desktop integration for the current OS. */
final class DesktopUtil {
    private DesktopUtil() {
    }

    static void openDirectory(Path directory) throws IOException {
        Path target = directory.toAbsolutePath().normalize();
        IOException lastFailure = null;

        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(target.toFile());
                return;
            }
        } catch (IOException ex) {
            lastFailure = ex;
        } catch (RuntimeException ex) {
            lastFailure = new IOException(ex.getMessage(), ex);
        }

        for (List<String> command : nativeOpenCommands(target)) {
            try {
                new ProcessBuilder(command).start();
                return;
            } catch (IOException ex) {
                lastFailure = ex;
            }
        }

        throw new IOException("Opening folders is not supported on this system.", lastFailure);
    }

    private static List<List<String>> nativeOpenCommands(Path directory) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String path = directory.toString();
        List<List<String>> commands = new ArrayList<>();
        if (os.contains("win")) {
            commands.add(List.of("explorer.exe", path));
        } else if (os.contains("mac")) {
            commands.add(List.of("open", path));
        } else {
            commands.add(List.of("xdg-open", path));
            commands.add(List.of("gio", "open", path));
        }
        return commands;
    }
}
