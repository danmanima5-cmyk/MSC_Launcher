import java.util.Locale;

/** Linux desktop-session quirks kept in one place so Windows remains untouched. */
final class LinuxUiSupport {
    private LinuxUiSupport() {}

    static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    static boolean isWaylandSession() {
        if (!isLinux()) return false;
        String sessionType = System.getenv("XDG_SESSION_TYPE");
        String display = System.getenv("WAYLAND_DISPLAY");
        return (sessionType != null && sessionType.equalsIgnoreCase("wayland"))
                || (display != null && !display.isBlank());
    }

    /** 30 FPS avoids flooding Swing's EDT through XWayland while retaining smooth motion. */
    static int animationDelay(int requestedMillis) {
        return isWaylandSession() ? Math.max(33, requestedMillis) : requestedMillis;
    }

    static boolean animationsEnabled() {
        return !isLinux();
    }

    /** Wayland benefits from opaque surfaces and simpler paint operations. */
    static boolean reducedEffects() {
        return isWaylandSession();
    }

    /** Custom buttons are rectangular on Linux and keep their original radius elsewhere. */
    static int buttonArc(int requestedArc) {
        return isLinux() ? 0 : requestedArc;
    }

    static float buttonArc(float requestedArc) {
        return isLinux() ? 0f : requestedArc;
    }
}
