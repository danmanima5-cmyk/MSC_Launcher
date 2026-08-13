import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.CardLayout;
import java.util.Arrays;
import java.util.List;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

final class MetroSnapLayoutTest {
    @Test
    void splitLayoutIsLimitedToTwoPages() {
        List<String> open = Arrays.asList("mods", null);

        assertEquals(List.of("skins", "mods"),
                LauncherFrame.metroSnapInsertOrder(open, "skins", 0));
        assertEquals(List.of("mods", "skins"),
                LauncherFrame.metroSnapInsertOrder(open, "skins", 1));
        assertEquals(2, LauncherFrame.metroSnapInsertOrder(
                List.of("mods", "library"), "skins", 1).size());
    }

    @Test
    void draggingAnOpenPageReordersInsteadOfDuplicatingIt() {
        assertEquals(List.of("library", "mods"),
                LauncherFrame.metroSnapInsertOrder(
                        List.of("mods", "library"), "mods", 1));
    }

    @Test
    void emptyHalfDoesNotBecomeARealScreen() {
        assertEquals(List.of("mods", "skins"),
                LauncherFrame.metroSnapInsertOrder(
                        Arrays.asList("mods", null), "skins", 1));
    }

    @Test
    void pointerSelectsTheExpectedDropZone() {
        assertEquals(0, LauncherFrame.metroSnapTargetIndex(10, 900, 3));
        assertEquals(1, LauncherFrame.metroSnapTargetIndex(450, 900, 3));
        assertEquals(1, LauncherFrame.metroSnapTargetIndex(899, 900, 3));
    }

    @Test
    void cardLayoutHiddenPageIsRevealedWhenMovedIntoSnapWorkspace() {
        JPanel cards = new JPanel(new CardLayout());
        JPanel home = new JPanel();
        JPanel page = new JPanel();
        cards.add(home, "home");
        cards.add(page, "page");
        ((CardLayout) cards.getLayout()).show(cards, "home");
        cards.remove(page);

        assertFalse(page.isVisible());

        LauncherFrame.revealMetroSnapComponent(page);

        assertTrue(page.isVisible());
    }

    @Test
    void resizableDividerKeepsBothNeighbouringPagesUsable() {
        assertEquals(300, LauncherFrame.constrainMetroSnapDivider(50, 0, 1000, 300));
        assertEquals(700, LauncherFrame.constrainMetroSnapDivider(950, 0, 1000, 300));
        assertEquals(540, LauncherFrame.constrainMetroSnapDivider(540, 0, 1000, 300));
        assertEquals(500, LauncherFrame.constrainMetroSnapDivider(400, 400, 600, 300));
    }
}
