import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** OAuth window backed by the JavaFX WebView included in full Java 8 runtimes. */
final class AuthBrowserWindow {
    private static final java.awt.Color HEADER_BG = new java.awt.Color(20, 24, 31);
    private static final java.awt.Color STATUS_BG = new java.awt.Color(16, 19, 25);
    private static final java.awt.Color TEXT = new java.awt.Color(238, 242, 248);
    private static final java.awt.Color TEXT_DIM = new java.awt.Color(166, 177, 193);
    private static final java.awt.Color ACCENT = new java.awt.Color(70, 135, 230);

    private AuthBrowserWindow() {
    }

    static void open(Window owner, String title, String url,
                     CompletableFuture<?> closeSignal, Runnable onUserClosed) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(onUserClosed, "onUserClosed");
        SwingUtilities.invokeLater(() -> createWindow(owner, title, url, closeSignal, onUserClosed));
    }

    private static void createWindow(Window owner, String title, String url,
                                     CompletableFuture<?> closeSignal, Runnable onUserClosed) {
        JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setMinimumSize(new Dimension(680, 520));
        dialog.setSize(768, 608);
        dialog.setLocationRelativeTo(owner);

        JLabel titleLabel = new JLabel(title == null || title.trim().isEmpty() ? "Авторизация" : title);
        titleLabel.setForeground(TEXT);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));

        JLabel hostLabel = new JLabel(hostCaption(url));
        hostLabel.setForeground(TEXT_DIM);
        hostLabel.setFont(hostLabel.getFont().deriveFont(Font.PLAIN, 11f));

        JPanel headingText = new JPanel(new java.awt.GridLayout(2, 1, 0, 2));
        headingText.setOpaque(false);
        headingText.add(titleLabel);
        headingText.add(hostLabel);

        JButton closeButton = new JButton("×");
        closeButton.setToolTipText("Отменить вход");
        closeButton.setForeground(TEXT);
        closeButton.setFont(closeButton.getFont().deriveFont(Font.PLAIN, 20f));
        closeButton.setFocusPainted(false);
        closeButton.setBorderPainted(false);
        closeButton.setContentAreaFilled(false);
        closeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeButton.setPreferredSize(new Dimension(42, 36));
        closeButton.addActionListener(event -> dialog.dispose());

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBackground(HEADER_BG);
        header.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 8));
        header.add(headingText, BorderLayout.CENTER);
        header.add(closeButton, BorderLayout.EAST);

        JLabel statusLabel = new JLabel("Загрузка страницы авторизации…", SwingConstants.LEFT);
        statusLabel.setForeground(TEXT_DIM);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));

        JProgressBar loading = new JProgressBar();
        loading.setIndeterminate(true);
        loading.setBorderPainted(false);
        loading.setForeground(ACCENT);
        loading.setBackground(STATUS_BG);
        loading.setPreferredSize(new Dimension(120, 3));

        JPanel status = new JPanel(new BorderLayout(10, 0));
        status.setBackground(STATUS_BG);
        status.setBorder(BorderFactory.createEmptyBorder(6, 12, 7, 12));
        status.add(statusLabel, BorderLayout.CENTER);
        status.add(loading, BorderLayout.EAST);

        JFXPanel browserPanel;
        try {
            browserPanel = new JFXPanel();
        } catch (RuntimeException | Error ex) {
            // OAuth itself does not depend on JavaFX: the loopback callback server
            // can receive the result from any browser.  Current JRE distributions
            // often omit JavaFX, so continue in the system browser transparently.
            try {
                BrowserUtil.openUrl(url);
            } catch (RuntimeException browserError) {
                browserError.addSuppressed(ex);
                fail(closeSignal, "Не удалось открыть страницу авторизации: "
                        + browserError.getMessage(), browserError);
            }
            dialog.dispose();
            return;
        }

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(java.awt.Color.WHITE);
        content.add(browserPanel, BorderLayout.CENTER);
        dialog.setContentPane(content);

        AtomicBoolean closedBySignal = new AtomicBoolean(false);
        AtomicBoolean disposed = new AtomicBoolean(false);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                if (!disposed.compareAndSet(false, true)) {
                    return;
                }
                Platform.runLater(() -> browserPanel.setScene(null));
                if (!closedBySignal.get() && closeSignal != null && !closeSignal.isDone()) {
                    onUserClosed.run();
                }
            }
        });

        if (closeSignal != null) {
            closeSignal.whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
                closedBySignal.set(true);
                delay(error == null ? 650 : 80, dialog::dispose);
            }));
        }

        dialog.setVisible(true);
        initializeBrowser(browserPanel, dialog, url, hostLabel, statusLabel, loading, closeSignal);
    }

    private static void initializeBrowser(JFXPanel panel, Window owner, String url,
                                          JLabel hostLabel, JLabel statusLabel,
                                          JProgressBar loading, CompletableFuture<?> closeSignal) {
        Platform.runLater(() -> {
            try {
                Platform.setImplicitExit(false);
                WebView webView = new WebView();
                webView.setContextMenuEnabled(false);
                WebEngine engine = webView.getEngine();
                engine.setJavaScriptEnabled(true);
                engine.setCreatePopupHandler(features -> engine);

                engine.locationProperty().addListener((observable, oldLocation, newLocation) ->
                        SwingUtilities.invokeLater(() -> hostLabel.setText(hostCaption(newLocation))));

                engine.getLoadWorker().stateProperty().addListener((observable, oldState, newState) -> {
                    if (newState == Worker.State.RUNNING || newState == Worker.State.SCHEDULED) {
                        SwingUtilities.invokeLater(() -> {
                            statusLabel.setText("Загрузка страницы авторизации…");
                            loading.setVisible(true);
                            loading.setIndeterminate(true);
                        });
                    } else if (newState == Worker.State.SUCCEEDED) {
                        SwingUtilities.invokeLater(() -> {
                            statusLabel.setText("Введите данные аккаунта и подтвердите вход");
                            loading.setIndeterminate(false);
                            loading.setVisible(false);
                        });
                    } else if (newState == Worker.State.FAILED) {
                        Throwable cause = engine.getLoadWorker().getException();
                        String message = "Не удалось загрузить страницу авторизации"
                                + (cause == null || cause.getMessage() == null ? "" : ": " + cause.getMessage());
                        SwingUtilities.invokeLater(() -> {
                            statusLabel.setText(message);
                            loading.setVisible(false);
                        });
                        fail(closeSignal, message, cause);
                    }
                });

                StackPane root = new StackPane(webView);
                root.setStyle("-fx-background-color: white;");
                panel.setScene(new Scene(root, Color.WHITE));
                engine.load(url);
            } catch (RuntimeException ex) {
                String message = "Не удалось открыть встроенную авторизацию: " + ex.getMessage();
                fail(closeSignal, message, ex);
                SwingUtilities.invokeLater(() -> javax.swing.JOptionPane.showMessageDialog(
                        owner, message, "Ошибка авторизации", javax.swing.JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    private static String hostCaption(String location) {
        String host = hostOf(location);
        if ("localhost".equals(host) || "127.0.0.1".equals(host)) {
            return "Завершение входа  •  MSC Launcher";
        }
        return "Защищённый вход  •  " + host;
    }

    private static String hostOf(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value);
            String host = uri.getHost();
            return host == null || host.trim().isEmpty() ? "страница авторизации" : host;
        } catch (RuntimeException ignored) {
            return "страница авторизации";
        }
    }

    private static void fail(CompletableFuture<?> signal, String message, Throwable cause) {
        if (signal != null && !signal.isDone()) {
            signal.completeExceptionally(new LauncherException(message, cause));
        }
    }

    private static void delay(int milliseconds, Runnable action) {
        javax.swing.Timer timer = new javax.swing.Timer(milliseconds, event -> action.run());
        timer.setRepeats(false);
        timer.start();
    }
}
