package com.kubo.ui.shell;

import com.kubo.KuboApp;
import com.kubo.command.CommandService;
import com.kubo.ui.KuboWindow;
import com.kubo.ui.redis.RedisConsole;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * Kubo's explicitly local, non-interactive console for this PC: PowerShell on Windows, /bin/sh elsewhere.
 */
public final class LocalShellWindow {

    private final Stage stage = new Stage();
    private final RedisConsole console;

    public LocalShellWindow(CommandService commands) {
        console = new RedisConsole(commands, true);
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(KuboWindow.install(stage, console),
                Math.min(1180, screen.getWidth() - 60), Math.min(760, screen.getHeight() - 60),
                Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        stage.setScene(scene);
        stage.setTitle("Kubo — " + com.kubo.platform.LocalShell.title() + " — Local PC");
        stage.setMinWidth(Math.min(760, screen.getWidth()));
        stage.setMinHeight(Math.min(480, screen.getHeight()));
        stage.setMaximized(true);
        stage.setOnHidden(event -> console.shutdown());
    }

    public boolean isShowing() {
        return stage.isShowing();
    }

    public void show() {
        stage.show();
        stage.toFront();
    }

    public void close() {
        stage.close();
    }
}
