package com.kubo.ui.logs;

import com.kubo.KuboApp;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.model.shell.StreamCall;
import com.kubo.ui.KuboWindow;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * A followed log in a window of its own, with one button on it.
 *
 * <p>Not bound to {@link com.kubo.ui.Attentive}, and that is the point of it. Every other window Kubo opens
 * takes the connection's attention while it is in front, because they all poll and one connection cannot
 * serve two pollers. This one never polls: it asked once and the server keeps talking. So it can stay open on
 * a second monitor while somebody works in FILES, and neither stops the other.
 */
public final class StreamWindow {

    private final Stage stage = new Stage();
    private final StreamView view;

    public StreamWindow(SshConnection connection, StreamCall call) {
        view = new StreamView(connection, call.command());

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(KuboWindow.installBare(stage, view),
                Math.min(1080, screen.getWidth() - 120), Math.min(620, screen.getHeight() - 160),
                Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        stage.setScene(scene);
        stage.setTitle("Kubo — " + call.title());
        stage.setMinWidth(Math.min(640, screen.getWidth() - 32));
        stage.setMinHeight(Math.min(360, screen.getHeight() - 48));
        // Where a second window goes: beside what opened it, not on top of it.
        stage.setX(Math.max(0, screen.getMinX() + screen.getWidth() - Math.min(1080, screen.getWidth() - 120) - 40));
        stage.setY(screen.getMinY() + 60);
        stage.setOnHidden(event -> view.shutdown());
    }

    public void show() {
        stage.show();
        stage.toFront();
        view.start();
    }

    public boolean isShowing() {
        return stage.isShowing();
    }

    public void close() {
        stage.close();
    }
}
