package com.kubo.ui.apps;

import com.kubo.KuboApp;
import com.kubo.apps.AppService;
import com.kubo.command.CommandService;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.privilege.RootAccess;
import com.kubo.privilege.RootSession;
import com.kubo.ui.KuboWindow;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * The apps screen in its own window: what PM2 and supervisor keep running, with the journal saying what is
 * asked until the first reading is on screen.
 */
public final class AppsWindow {

    /** The one apps window of the session, so every screen that opens it opens the same one. */
    private static AppsWindow shared;

    private final Stage stage = new Stage();
    private final SshConnection connection;
    private String wanted;
    private final StackPane root = new StackPane();
    private final StackPane content = new StackPane();
    private final com.kubo.ui.Journal journal = new com.kubo.ui.Journal(AppsWindow::words);
    private final Runnable stopJournal;
    private AppsScope scope;

    /**
     * Shows the apps window of this server, opening it when it is not there yet.
     *
     * @param app the application to pick in it, by key or by name, or null to leave the choice alone
     */
    public static void open(SshConnection connection, CommandService commands, RootSession rootSession,
                            String app) {
        if (shared == null || !shared.isShowing() || shared.connection != connection) {
            if (shared != null) {
                shared.close();
            }
            shared = new AppsWindow(connection, commands, rootSession);
        }
        shared.prefer(app);
        shared.show();
    }

    /** Closes the session's apps window, when the server is left. */
    public static void closeShared() {
        if (shared != null) {
            shared.close();
            shared = null;
        }
    }

    public AppsWindow(SshConnection connection, CommandService commands, RootSession rootSession) {
        this.connection = connection;
        root.getStyleClass().add("scope-view");
        root.getChildren().addAll(content, journal);
        String who = connection.target().username() + "@" + connection.target().host();
        journal.start("reading", who, "what pm2 and supervisor keep running, the cores, the ports");
        stopJournal = journal.follow(commands, CommandService.Origin.APPS);

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(KuboWindow.install(stage, root),
                Math.min(1400, screen.getWidth() - 32), Math.min(860, screen.getHeight() - 48), Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        stage.setScene(scene);
        stage.setTitle("Kubo — apps — " + connection.target());
        stage.setMinWidth(Math.min(1000, screen.getWidth() - 32));
        stage.setMinHeight(Math.min(620, screen.getHeight() - 48));
        KuboWindow.maximize(stage);
        stage.setOnHidden(event -> {
            stopJournal.run();
            if (scope != null) {
                scope.shutdown();
            }
        });

        Thread thread = new Thread(() -> {
            RootSession session = rootSession != null ? rootSession
                    : new RootSession(RootAccess.parse(connection.exec(RootAccess.DETECT).output()));
            AppService service = new AppService(connection, commands, session.privilege());
            Platform.runLater(() -> {
                scope = new AppsScope(service, connection.target().username());
                scope.setRootSession(session);
                scope.setOnFirstRead(() -> journal.finish("read", null));
                if (wanted != null) {
                    scope.prefer(wanted);
                }
                content.getChildren().setAll(scope);
                scope.start();
            });
        }, "kubo-apps-open");
        thread.setDaemon(true);
        thread.start();
    }

    /** What one of the apps commands reads, in a few words, or null for one not worth a line. */
    static String words(String command) {
        if (command == null) {
            return null;
        }
        if (command.contains("@@pm2")) {
            return "pm2, the cores, the memory, the ports";
        }
        if (command.startsWith("supervisorctl status")) {
            return "what supervisor runs";
        }
        if (command.startsWith("ps -o")) {
            return "cpu and memory of supervisor's programs";
        }
        return null;
    }

    /** Picks this application, now or as soon as the first reading is in. */
    public void prefer(String app) {
        if (app == null) {
            return;
        }
        wanted = app;
        if (scope != null) {
            scope.prefer(app);
        }
    }

    public void show() {
        com.kubo.ui.Attentive.bind(stage, AppsScope.ATTENTION, () -> {
            if (scope != null) {
                scope.resume();
            }
        });
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
