import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Small helper to robustly open URLs across Linux (X11/Wayland) and other platforms.
 */
final class BrowserUtil {
    static void openUrl(String url) {
        // Try AWT Desktop first when available
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Throwable ignored) {
            // fallthrough to cli-based openers
        }

        // Try common command-line openers. Some require subcommands (e.g., "gio open").
        List<String[]> commands = new ArrayList<>();
        commands.add(new String[]{"xdg-open", url});
        commands.add(new String[]{"gio", "open", url});
        commands.add(new String[]{"gnome-open", url});
        commands.add(new String[]{"sensible-browser", url});
        commands.add(new String[]{"x-www-browser", url});
        commands.add(new String[]{"firefox", url});
        commands.add(new String[]{"google-chrome", url});
        commands.add(new String[]{"chromium", url});
        commands.add(new String[]{"chromium-browser", url});

        IOException last = null;
        for (String[] cmd : commands) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.inheritIO();
                Process p = pb.start();
                // Assume success if process started; many desktop openers return immediately.
                return;
            } catch (IOException ex) {
                last = ex;
            }
        }

        // If nothing worked, report a clear error
        String msg = "Could not open browser for authorization" + (last == null ? "." : ": " + last.getMessage());
        throw new LauncherException(msg, last);
    }
}
