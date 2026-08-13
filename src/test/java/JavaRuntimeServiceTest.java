import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JavaRuntimeServiceTest {
    @Test
    void recognizesWindowsVersionsOlderThanTen() {
        assertTrue(JavaRuntimeService.isLegacyWindows("Windows 7", "6.1"));
        assertTrue(JavaRuntimeService.isLegacyWindows("Windows 8", "6.2"));
        assertTrue(JavaRuntimeService.isLegacyWindows("Windows 8.1", "6.3"));
    }

    @Test
    void doesNotMisclassifySupportedOrNonWindowsSystems() {
        assertFalse(JavaRuntimeService.isLegacyWindows("Windows 10", "10.0"));
        assertFalse(JavaRuntimeService.isLegacyWindows("Windows 11", "10.0"));
        assertFalse(JavaRuntimeService.isLegacyWindows("Linux", "6.3.0"));
    }
}
