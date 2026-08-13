import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Launcher-owned status window for OAuth performed in the secure system browser. */
final class SystemBrowserAuthWindow {
    private static final Color BG = new Color(25, 29, 36);
    private static final Color CARD = new Color(34, 39, 48);
    private static final Color TEXT = new Color(240, 244, 249);
    private static final Color TEXT_DIM = new Color(174, 184, 198);
    private static final Color ACCENT = new Color(74, 137, 230);

    private SystemBrowserAuthWindow() {
    }

    static void open(Window owner, String title, String url,
                     CompletableFuture<?> closeSignal, Runnable onUserClosed) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(onUserClosed, "onUserClosed");
        SwingUtilities.invokeLater(() -> create(owner, title, url, closeSignal, onUserClosed));
    }

    private static void create(Window owner, String title, String url,
                               CompletableFuture<?> closeSignal, Runnable onUserClosed) {
        JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setResizable(false);
        dialog.setSize(560, 300);
        dialog.setLocationRelativeTo(owner);

        String provider = title == null || title.trim().isEmpty() ? "аккаунт" : title;
        JLabel heading = new JLabel("Продолжите вход: " + provider, SwingConstants.CENTER);
        heading.setForeground(TEXT);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 21f));

        JTextArea message = new JTextArea(
                "Защищённая страница входа открыта в системном браузере. "
                        + "Завершите авторизацию там.\n\n"
                        + "После подтверждения это окно закроется автоматически.");
        message.setEditable(false);
        message.setFocusable(false);
        message.setOpaque(false);
        message.setWrapStyleWord(true);
        message.setLineWrap(true);
        message.setForeground(TEXT_DIM);
        message.setFont(message.getFont().deriveFont(Font.PLAIN, 13f));

        JLabel status = new JLabel("Ожидание подтверждения входа…", SwingConstants.CENTER);
        status.setForeground(new Color(132, 190, 255));
        status.setFont(status.getFont().deriveFont(Font.BOLD, 12f));

        JPanel center = new JPanel(new BorderLayout(0, 14));
        center.setOpaque(false);
        center.add(heading, BorderLayout.NORTH);
        center.add(message, BorderLayout.CENTER);
        center.add(status, BorderLayout.SOUTH);

        JButton reopen = button("Открыть браузер снова", true);
        reopen.addActionListener(event -> openPage(dialog, url, status));
        JButton cancel = button("Отмена", false);
        cancel.addActionListener(event -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 9, 0));
        buttons.setOpaque(false);
        buttons.add(reopen);
        buttons.add(cancel);

        JPanel root = new JPanel(new BorderLayout(0, 18));
        root.setBackground(BG);
        root.setBorder(BorderFactory.createEmptyBorder(24, 28, 22, 28));
        root.add(center, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root);

        AtomicBoolean closedBySignal = new AtomicBoolean(false);
        AtomicBoolean disposed = new AtomicBoolean(false);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (!disposed.compareAndSet(false, true)) {
                    return;
                }
                if (!closedBySignal.get() && closeSignal != null && !closeSignal.isDone()) {
                    onUserClosed.run();
                }
            }
        });

        if (closeSignal != null) {
            closeSignal.whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
                closedBySignal.set(true);
                if (error == null) {
                    status.setText("Вход подтверждён");
                    TimerCompat.delay(500, dialog::dispose);
                } else {
                    dialog.dispose();
                }
            }));
        }

        dialog.setVisible(true);
        openPage(dialog, url, status);
    }

    private static JButton button(String text, boolean primary) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setForeground(TEXT);
        button.setBackground(primary ? ACCENT : CARD);
        button.setOpaque(true);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(primary ? new Color(118, 173, 250) : new Color(75, 84, 98)),
                BorderFactory.createEmptyBorder(8, 14, 8, 14)));
        return button;
    }

    private static void openPage(Window owner, String url, JLabel status) {
        try {
            BrowserUtil.openUrl(url);
            status.setText("Браузер открыт — завершите вход");
        } catch (RuntimeException ex) {
            status.setText("Не удалось открыть системный браузер");
            javax.swing.JOptionPane.showMessageDialog(
                    owner,
                    "Не удалось открыть браузер:\n" + ex.getMessage(),
                    "Ошибка входа",
                    javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }

    private static final class TimerCompat {
        private TimerCompat() {
        }

        static void delay(int milliseconds, Runnable action) {
            javax.swing.Timer timer = new javax.swing.Timer(milliseconds, event -> action.run());
            timer.setRepeats(false);
            timer.start();
        }
    }
}
