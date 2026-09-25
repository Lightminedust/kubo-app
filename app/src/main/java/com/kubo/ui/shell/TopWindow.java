package com.kubo.ui.shell;

import com.kubo.KuboApp;
import com.kubo.command.CommandService;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.privilege.RootSession;
import com.kubo.ui.KuboWindow;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * What is running on the server, in a window of its own, with one button on it.
 */
public final class TopWindow {

    private final Stage stage = new Stage();
    private final TopView view;

    public TopWindow(SshConnection connection, CommandService commands, RootSession root) {
        view = new TopView(connection, commands, root);

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(KuboWindow.installBare(stage, view),
                Math.min(1320, screen.getWidth() - 60), Math.min(760, screen.getHeight() - 90),
                Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        stage.setScene(scene);
        stage.setTitle("Kubo — Processes");
        stage.setMinWidth(Math.min(980, screen.getWidth() - 32));
        stage.setMinHeight(Math.min(440, screen.getHeight() - 48));
        stage.setOnHidden(event -> view.shutdown());
    }

    public void show() {
        // It asks every few seconds, so while it is in front the screens behind stop: one connection, one
        // command at a time.
        com.kubo.ui.Attentive.bind(stage, "PROCESSES");
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
