import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class LauncherEditionTest {
    @AfterEach
    void restoreStandardEdition() {
        System.clearProperty(LauncherEdition.EDITION_PROPERTY);
    }

    @Test
    void standardEditionRemainsTheDefault() {
        assertEquals(LauncherEdition.STANDARD, LauncherEdition.current());
        assertEquals("msc", LauncherEdition.STANDARD.applyUiMode("msc"));
        assertFalse(LauncherEdition.STANDARD.locksUiMode());
        assertFalse(LauncherEdition.STANDARD.usesFullScreenShell());
    }

    @Test
    void metroEditionHasItsOwnIdentityDataAndLockedUi() {
        System.setProperty(LauncherEdition.EDITION_PROPERTY, "metro");

        assertEquals(LauncherEdition.METRO, LauncherEdition.current());
        assertEquals("MSC Launcher Metro", LauncherEdition.current().displayName());
        assertEquals("msc-launcher-metro-data", LauncherEdition.current().dataDirectoryName());
        assertEquals("metro", LauncherEdition.current().applyUiMode("msc"));
        assertTrue(LauncherEdition.current().locksUiMode());
        assertTrue(LauncherEdition.current().usesFullScreenShell());
    }
}
