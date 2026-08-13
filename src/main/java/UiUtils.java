import javax.swing.*;
import java.awt.*;

final class UiUtils {
    // Compute preferred width for a JList based on widest cell renderer component
    static int computeListPreferredWidth(JList<?> list) {
        ListCellRenderer<? super Object> renderer = (ListCellRenderer<? super Object>) list.getCellRenderer();
        int width = 0;
        ListModel<?> model = list.getModel();
        for (int i = 0; i < model.getSize(); i++) {
            Object value = model.getElementAt(i);
            Component c = renderer.getListCellRendererComponent(list, value, i, false, false);
            Dimension d = c.getPreferredSize();
            width = Math.max(width, d.width);
        }
        return width;
    }

    static void adjustScrollPaneWidthTo(int width, JScrollPane scroll) {
        if (scroll == null) return;
        Dimension pref = scroll.getPreferredSize();
        scroll.setPreferredSize(new Dimension(Math.max(pref.width, width + 32), pref.height));
    }
}
