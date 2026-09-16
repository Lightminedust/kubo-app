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
 */
final class EditorWindow {

    private final Stage stage = new Stage();
    private final FileEditor editor;

    EditorWindow(FileBrowser browser, String path, Consumer<String> onSaved) {
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

    void show() {
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
    void close() {
        stage.close();
    }
}
