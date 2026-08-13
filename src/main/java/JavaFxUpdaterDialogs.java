import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.util.Optional;
import java.util.function.Consumer;

/** JavaFX 8 dialogs used by the first stage of the Swing-to-JavaFX UI migration. */
final class JavaFxUpdaterDialogs {
    private static final String DIALOG_STYLE =
            "-fx-background-color: #151a22; -fx-font-family: 'Segoe UI'; -fx-font-size: 13px;";
    private static final String TEXT_STYLE = "-fx-text-fill: #eef2f8;";

    private JavaFxUpdaterDialogs() {
    }

    static void showInformation(Frame owner, String title, String message) {
        showMessage(owner, Alert.AlertType.INFORMATION, title, message);
    }

    static void showError(Frame owner, String title, String message) {
        showMessage(owner, Alert.AlertType.ERROR, title, message);
    }

    static void confirm(Frame owner, String title, String message,
                        String details, String acceptText, Consumer<Boolean> resultHandler) {
        runOnJavaFx(owner, new Runnable() {
            @Override
            public void run() {
                ButtonType accept = new ButtonType(acceptText, ButtonBar.ButtonData.YES);
                ButtonType cancel = new ButtonType("Отмена", ButtonBar.ButtonData.CANCEL_CLOSE);
                Alert alert = createAlert(owner, Alert.AlertType.CONFIRMATION, title, message);
                alert.getButtonTypes().setAll(accept, cancel);

                if (details != null && !details.trim().isEmpty()) {
                    Label summary = new Label(message);
                    summary.setWrapText(true);
                    summary.setMaxWidth(580.0);
                    summary.setStyle(TEXT_STYLE);
                    TextArea notes = new TextArea(details);
                    notes.setEditable(false);
                    notes.setWrapText(true);
                    notes.setPrefColumnCount(58);
                    notes.setPrefRowCount(Math.min(14, Math.max(5, lineCount(details))));
                    notes.setStyle("-fx-control-inner-background: #0f1319; -fx-text-fill: #eef2f8; "
                            + "-fx-highlight-fill: #466fe6; -fx-highlight-text-fill: white;");
                    Label caption = new Label("Описание релиза:");
                    caption.setStyle(TEXT_STYLE);
                    VBox content = new VBox(8.0, summary, caption, notes);
                    content.setPadding(new Insets(4.0, 0.0, 0.0, 0.0));
                    alert.getDialogPane().setContent(content);
                }

                Optional<ButtonType> selected = alert.showAndWait();
                enableOwner(owner, true);
                resultHandler.accept(selected.isPresent() && selected.get() == accept);
            }
        }, new Runnable() {
            @Override
            public void run() {
                Object[] content = details == null || details.trim().isEmpty()
                        ? new Object[]{message}
                        : new Object[]{message, details};
                int selected = JOptionPane.showConfirmDialog(owner, content, title,
                        JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
                resultHandler.accept(selected == JOptionPane.YES_OPTION);
            }
        });
    }

    private static void showMessage(Frame owner, Alert.AlertType type, String title, String message) {
        runOnJavaFx(owner, new Runnable() {
            @Override
            public void run() {
                Alert alert = createAlert(owner, type, title, message);
                alert.showAndWait();
                enableOwner(owner, true);
            }
        }, new Runnable() {
            @Override
            public void run() {
                JOptionPane.showMessageDialog(owner, message, title,
                        type == Alert.AlertType.ERROR
                                ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
            }
        });
    }

    private static Alert createAlert(Frame owner, Alert.AlertType type, String title, String message) {
        enableOwner(owner, false);
        Alert alert = new Alert(type);
        alert.initModality(Modality.APPLICATION_MODAL);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setGraphic(null);

        Label text = new Label(message);
        text.setWrapText(true);
        text.setMaxWidth(580.0);
        text.setStyle(TEXT_STYLE);
        DialogPane pane = alert.getDialogPane();
        pane.setContent(text);
        pane.setMinWidth(520.0);
        pane.setStyle(DIALOG_STYLE);

        alert.setOnShown(event -> centerOnOwner(alert, owner));
        alert.setOnHidden(event -> enableOwner(owner, true));
        return alert;
    }

    private static void runOnJavaFx(Frame owner, Runnable action, Runnable swingFallback) {
        Runnable initialize = new Runnable() {
            @Override
            public void run() {
                try {
                    // Constructing JFXPanel is the supported JavaFX 8 toolkit bootstrap
                    // when a JavaFX surface is introduced into an existing Swing app.
                    new JFXPanel();
                    Platform.runLater(new Runnable() {
                        @Override
                        public void run() {
                            Platform.setImplicitExit(false);
                            action.run();
                        }
                    });
                } catch (RuntimeException error) {
                    swingFallback.run();
                } catch (LinkageError error) {
                    // Some third-party Java 8 runtimes omit JavaFX. Keep updating usable there.
                    swingFallback.run();
                }
            }
        };

        if (SwingUtilities.isEventDispatchThread()) {
            initialize.run();
        } else {
            SwingUtilities.invokeLater(initialize);
        }
    }

    private static void centerOnOwner(Alert alert, Frame owner) {
        if (owner == null || !owner.isShowing()) {
            return;
        }
        double width = alert.getDialogPane().getScene().getWindow().getWidth();
        double height = alert.getDialogPane().getScene().getWindow().getHeight();
        alert.setX(owner.getX() + Math.max(0.0, (owner.getWidth() - width) / 2.0));
        alert.setY(owner.getY() + Math.max(0.0, (owner.getHeight() - height) / 2.0));
    }

    private static void enableOwner(Frame owner, boolean enabled) {
        if (owner == null) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                owner.setEnabled(enabled);
                if (enabled) {
                    owner.toFront();
                }
            }
        });
    }

    private static int lineCount(String text) {
        int lines = 1;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
