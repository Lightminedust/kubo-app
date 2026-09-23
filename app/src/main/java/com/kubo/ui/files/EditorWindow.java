package com.kubo.ui.files;

import com.kubo.KuboApp;
import com.kubo.files.FileBrowser;
import com.kubo.ui.KuboWindow;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.function.Consumer;

/**
 * One file's editor in its own window. Closing it with unsaved changes asks first.
 *
 * <p>Public because the services screen opens it too: somebody making a service out of a script should be
 * able to write that script where they are, rather than being sent to another screen for it.
 */
public final class EditorWindow {

    private final Stage stage = new Stage();
    private final FileEditor editor;

    public EditorWindow(FileBrowser browser, String path, Consumer<String> onSaved) {
        editor = new FileEditor(browser, path);
        editor.setOnSaved(onSaved);

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(KuboWindow.install(stage, editor),
                Math.min(1180, screen.getWidth() - 80), Math.min(820, screen.getHeight() - 80), Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        stage.setScene(scene);
        stage.setMinWidth(Math.min(720, screen.getWidth() - 32));
        stage.setMinHeight(Math.min(480, screen.getHeight() - 48));
        stage.setMaximized(true);

        editor.setOnTitle(title -> stage.setTitle(title + " — Kubo Edit — " + browser.target()));
        editor.setOnClose(stage::close);
        stage.setOnCloseRequest(event -> {
            if (editor.isDirty()) {
                event.consume();
                editor.confirmClose();
            }
        });
        stage.setOnHidden(event -> editor.shutdown());
        editor.load();
    }

    public void show() {
        // While the operator is in this window, the screens behind it stop polling: one connection, one command
        // at a time.
        com.kubo.ui.Attentive.bind(stage, "EDITOR");
        stage.show();
        stage.toFront();
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    boolean isDirty() {
        return editor.isDirty();
    }

    /**
     * Closes without asking, e.g. when the session ends.
     */
    public void close() {
        stage.close();
    }
}
