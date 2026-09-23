package com.kubo.ui.files;

import com.kubo.KuboApp;
import com.kubo.command.CommandService;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.files.FileBrowser;
import com.kubo.ui.KuboWindow;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * The file manager in its own window, sharing the session's connection and command history.
 */
public final class FilesWindow {

    private final Stage stage = new Stage();
    private final FilesView view;

    /**
     * @param openLogs opens the session history
     */
    public FilesWindow(SshConnection connection, CommandService commands, Runnable openLogs) {
        this(connection, commands, openLogs, null);
    }

    /**
     * @param root the session's root state, so the file manager can act as root when root mode is on
     */
    public FilesWindow(SshConnection connection, CommandService commands, Runnable openLogs,
                       com.kubo.privilege.RootSession root) {
        FileBrowser browser = new FileBrowser(connection, commands);
        browser.setRootSession(root);
        view = new FilesView(browser);
        view.setOnLogs(openLogs);
        if (root != null) {
            view.setRootSession(root);
        }

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(KuboWindow.install(stage, view),
                Math.min(1440, screen.getWidth() - 32), Math.min(880, screen.getHeight() - 48), Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);

        stage.setTitle("Kubo — Files — " + connection.target());
        stage.setMinWidth(Math.min(1000, screen.getWidth() - 32));
        stage.setMinHeight(Math.min(640, screen.getHeight() - 48));
        stage.setScene(scene);
        stage.setMaximized(true);
        stage.setOnCloseRequest(event -> {
            // Editors close with this window: unsaved work is shown first instead of being lost.
            if (view.hasUnsavedEditors()) {
                event.consume();
                view.notice("SAVE OR CLOSE THE EDITORS WITH UNSAVED CHANGES FIRST", true);
            } else if (view.hasActiveUploads()) {
                event.consume();
                view.notice("TRANSFERS ARE STILL RUNNING: WAIT FOR THEM OR CANCEL THEM FIRST", true);
            }
        });
        stage.setOnHidden(event -> view.shutdown());
    }

    public void show() {
        // While the operator is in this window, the screens behind it stop polling: one connection, one command
        // at a time.
        com.kubo.ui.Attentive.bind(stage, "FILES");
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
