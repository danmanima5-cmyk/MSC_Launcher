import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class LauncherFrameModSearchTest {
    @Test
    void allVersionsDoesNotCreateAnApiVersionFilter() {
        assertEquals("", LauncherFrame.modGameVersionFilter(null));
        assertEquals("", LauncherFrame.modGameVersionFilter(""));
        assertEquals("", LauncherFrame.modGameVersionFilter("  All versions  "));
    }

    @Test
    void selectedCatalogVersionIsUsedInsteadOfAnUnrelatedTargetProfile() {
        assertEquals("1.21.1", LauncherFrame.modGameVersionFilter("  1.21.1  "));
    }

    @Test
    void backendErrorKeepsActionableDetailsInsteadOfOperationMessagePlaceholder() {
        String localized = LauncherFrame.translateBackendTextToEnglish(
                "Несовместимые моды: create.jar конфликтует с addon.jar");

        assertNotEquals("Operation message", localized);
        assertTrue(localized.contains("create.jar"));
        assertTrue(localized.contains("addon.jar"));
        assertEquals("Incompatible mods: create.jar conflicts with addon.jar", localized);
    }

    @Test
    void richCatalogDescriptionDoesNotExposeHtmlOrMarkdownImageArtifacts() {
        String source = "![Entity Culling Banner](https://example.invalid/banner.png)\n"
                + "<p align=\"center\"><a href=\"https://example.invalid\">"
                + "<img src=\"https://example.invalid/button.png\" alt=\"Discord\"></a></p>\n"
                + "<br>![Divider](https://example.invalid/divider.png)\n"
                + "## Features\nUseful text";

        String normalized = LauncherFrame.normalizeRichModDescription(source);

        assertTrue(normalized.contains("## Features"));
        assertTrue(normalized.contains("Useful text"));
        assertTrue(!normalized.contains("<p"));
        assertTrue(!normalized.contains("<img"));
        assertTrue(!normalized.contains("!Entity Culling Banner"));
        assertTrue(!normalized.contains("!Divider"));
    }
}
