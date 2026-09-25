package com.kubo.ui.shell;

import com.kubo.KuboApp;
import com.kubo.command.CommandService;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.ui.KuboWindow;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * The pm2 table in a window of its own, with one button on it.
 */
public final class ProcessWindow {

    private final Stage stage = new Stage();
    private final ProcessView view;

    public ProcessWindow(SshConnection connection, CommandService commands) {
        view = new ProcessView(connection, commands);

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(KuboWindow.installBare(stage, view),
                Math.min(1240, screen.getWidth() - 80), Math.min(680, screen.getHeight() - 120),
                Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        stage.setScene(scene);
        stage.setTitle("Kubo — PM2");
        stage.setMinWidth(Math.min(900, screen.getWidth() - 32));
        stage.setMinHeight(Math.min(420, screen.getHeight() - 48));
        stage.setOnHidden(event -> view.shutdown());
    }

    public void show() {
        // This one does send commands, so while it is in front the screens behind stop polling: one
        // connection, one command at a time.
        com.kubo.ui.Attentive.bind(stage, "PM2");
        stage.show();
        stage.toFront();
        view.read();
    }

    public boolean isShowing() {
        return stage.isShowing();
    }

    public void close() {
        stage.close();
    }
}
