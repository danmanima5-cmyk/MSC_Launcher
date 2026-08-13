import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        launch(args);
    }

    static void launch(String... args) {
        configureLinuxSwingPerformance();
        SwingUtilities.invokeLater(() -> {
            LauncherFrame frame = new LauncherFrame();
            if (LauncherEdition.current().usesFullScreenShell()) {
                frame.showFullScreenShell();
            } else {
                frame.setVisible(true);
            }
            if (args != null && args.length > 0) {
                SwingUtilities.invokeLater(() -> frame.openExternalUri(args[0]));
            }
        });
    }

    private static void configureLinuxSwingPerformance() {
        if (!LinuxUiSupport.isLinux()) {
            return;
        }
        // XRender is an X11 pipeline. Forcing it in a Wayland session sends Swing
        // through an inefficient XWayland path and disabling pixmap buffering then
        // exposes partial repaints around translucent, rounded components.
        if (!LinuxUiSupport.isWaylandSession()) {
            System.setProperty("sun.java2d.xrender", "true");
            System.setProperty("sun.java2d.pmoffscreen", "false");
        }
        System.setProperty("awt.useSystemAAFontSettings", "off");
        System.setProperty("swing.aatext", "false");
    }
}
