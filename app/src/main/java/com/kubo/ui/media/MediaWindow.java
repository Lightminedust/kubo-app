package com.kubo.ui.media;

import com.kubo.KuboApp;
import com.kubo.files.FileBrowser;
import com.kubo.model.files.RemoteEntry;
import com.kubo.model.media.MediaKind;
import com.kubo.ui.KuboWindow;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One picture, sound or film in a window of its own, with one button on it.
 *
 * <p>Not bound to {@link com.kubo.ui.Attentive}. It copies a file once and then plays it from this computer,
 * so it is not asking the server anything while it is open and has no reason to stop the screens behind it.
 */
public final class MediaWindow {

    private final Stage stage = new Stage();
    private final MediaPanel panel;

    public MediaWindow(FileBrowser browser, RemoteEntry file, MediaKind kind) {
        panel = new MediaPanel(browser, file, kind, scratch());

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        boolean wide = kind != MediaKind.AUDIO;
        Scene scene = new Scene(KuboWindow.installBare(stage, panel),
                Math.min(wide ? 1120 : 620, screen.getWidth() - 120),
                Math.min(wide ? 720 : 300, screen.getHeight() - 140),
                Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        stage.setScene(scene);
        stage.setTitle("Kubo — " + file.name());
        stage.setMinWidth(Math.min(520, screen.getWidth() - 32));
        stage.setMinHeight(Math.min(wide ? 320 : 240, screen.getHeight() - 48));
        stage.setOnHidden(event -> panel.shutdown());
    }

    /**
     * A folder of its own per window, under Kubo's own folder rather than the system temporary one.
     *
     * <p>Somewhere a person can find it and look at it, which matters for a copy of their own file. It is
     * emptied when the window closes.
     */
    private static Path scratch() {
        try {
            Path home = Path.of(System.getProperty("user.home"), ".kubo", "media");
            Files.createDirectories(home);
            return Files.createTempDirectory(home, "open-");
        } catch (IOException cannotWrite) {
            return null;
        }
    }

    public void show() {
        stage.show();
        stage.toFront();
    }

    public boolean isShowing() {
        return stage.isShowing();
    }

    public void close() {
        stage.close();
    }
}
