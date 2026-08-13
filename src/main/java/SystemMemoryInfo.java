import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;

/**
 * Detects how much physical RAM the current machine actually has, so that the
 * launcher's memory (Xms/Xmx) sliders can be scaled to the real hardware
 * instead of always offering the same fixed range (e.g. up to 32 GB) to
 * every user regardless of their device.
 *
 * Works on any modern JDK (Oracle/OpenJDK/Temurin/etc.) via the
 * {@code com.sun.management.OperatingSystemMXBean} extension, which exposes
 * total physical memory on Windows, macOS and Linux alike. Reflection is
 * used so the code still compiles/runs even if that internal API is ever
 * unavailable, falling back to a conservative default in that case.
 */
final class SystemMemoryInfo {

    /** Used when the real amount of RAM could not be detected for any reason. */
    private static final int FALLBACK_TOTAL_MEMORY_MB = 8192;

    private static volatile int cachedTotalMemoryMb = -1;

    private SystemMemoryInfo() {
    }

    /** Total physical RAM installed in this machine, in megabytes. Never returns a value &lt;= 0. */
    static int totalMemoryMb() {
        int cached = cachedTotalMemoryMb;
        if (cached > 0) {
            return cached;
        }
        int detected = detectTotalMemoryMb();
        cachedTotalMemoryMb = detected > 0 ? detected : FALLBACK_TOTAL_MEMORY_MB;
        return cachedTotalMemoryMb;
    }

    private static int detectTotalMemoryMb() {
        try {
            Object osBean = ManagementFactory.getOperatingSystemMXBean();
            // JDK 14+: getTotalMemorySize(); older JDKs: getTotalPhysicalMemorySize().
            long bytes = readLongMethod(osBean, "getTotalMemorySize");
            if (bytes <= 0) {
                bytes = readLongMethod(osBean, "getTotalPhysicalMemorySize");
            }
            if (bytes > 0) {
                return (int) Math.min(Integer.MAX_VALUE, bytes / (1024L * 1024L));
            }
        } catch (Exception ignored) {
            // Fall through to the fallback value below.
        }
        return -1;
    }

    private static long readLongMethod(Object bean, String methodName) {
        try {
            Method m = bean.getClass().getMethod(methodName);
            m.setAccessible(true);
            Object result = m.invoke(bean);
            if (result instanceof Long) {
                return (Long) result;
            }
        } catch (Exception ignored) {
            // Method not present on this JVM/OS combination.
        }
        return -1L;
    }

    /**
     * Suggested ceiling (in MB) for the "maximum memory" (Xmx) slider/spinner,
     * derived from the machine's real RAM: rounded up to the next whole
     * gigabyte, with a sensible minimum so the control stays usable even on
     * very low-memory machines.
     */
    static int suggestedMaxMemoryCeilingMb() {
        int total = totalMemoryMb();
        int roundedUpToGb = (int) (Math.ceil(total / 1024.0) * 1024);
        return Math.max(4096, roundedUpToGb);
    }

    /**
     * Suggested ceiling (in MB) for the "minimum memory" (Xms) slider/spinner.
     * Kept at half of the max ceiling so the two controls stay visually
     * proportionate, as before.
     */
    static int suggestedMinMemoryCeilingMb() {
        return Math.max(2048, suggestedMaxMemoryCeilingMb() / 2);
    }

    /**
     * Sensible default for "maximum memory" (Xmx) on first run: roughly half
     * of the total RAM, snapped to a multiple of 256 MB, and always leaving
     * at least 1 GB of headroom for the OS.
     */
    static int suggestedDefaultMaxMemoryMb() {
        int total = totalMemoryMb();
        int half = total / 2;
        int headroomLimit = Math.max(1024, total - 1024);
        int candidate = Math.min(half, headroomLimit);
        candidate = Math.max(1024, candidate);
        // Snap down to the nearest multiple of 256 MB.
        candidate = (candidate / 256) * 256;
        return Math.max(1024, candidate);
    }

    /** Sensible default for "minimum memory" (Xms) on first run. */
    static int suggestedDefaultMinMemoryMb() {
        int defaultMax = suggestedDefaultMaxMemoryMb();
        int candidate = Math.min(512, defaultMax / 2);
        candidate = (Math.max(candidate, 256) / 256) * 256;
        return Math.max(256, candidate);
    }
}
