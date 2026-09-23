package com.kubo.ui.logs;

import com.kubo.KuboApp;
import com.kubo.command.CommandService;
import com.kubo.logs.SessionLog;
import com.kubo.ui.KuboWindow;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.nio.file.Path;

/**
 * The history of every session in its own window.
 */
public final class LogsWindow {

    private final Stage stage = new Stage();

    /**
     * @param current  the running session's log, or null
     * @param commands the running session's commands, or null
     */
    public LogsWindow(Path current, CommandService commands) {
        LogsView view = new LogsView(SessionLog.DIRECTORY, current, commands);
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(KuboWindow.install(stage, view), Math.min(1260, screen.getWidth() - 60), Math.min(820, screen.getHeight() - 60),
                Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        stage.setScene(scene);
        stage.setTitle("Kubo — Logs");
        stage.setMinWidth(Math.min(820, screen.getWidth() - 32));
        stage.setMinHeight(Math.min(480, screen.getHeight() - 48));
        stage.setMaximized(true);
        stage.setOnHidden(event -> view.shutdown());
    }

    public void show() {
        // While the operator is in this window, the screens behind it stop polling: one connection, one command
        // at a time.
        com.kubo.ui.Attentive.bind(stage, "LOGS");
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
