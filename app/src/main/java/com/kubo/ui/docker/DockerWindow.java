package com.kubo.ui.docker;

import com.kubo.KuboApp;
import com.kubo.command.CommandService;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.docker.DockerService;
import com.kubo.model.docker.DockerStatus;
import com.kubo.privilege.Privilege;
import com.kubo.privilege.RootAccess;
import com.kubo.ui.KuboWindow;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * The Docker screen in its own window. On open it finds out whether Docker is installed and reachable — directly,
 * or through root — and either shows the cluster or explains why it cannot.
 */
public final class DockerWindow {

    private final Stage stage = new Stage();
    private final StackPane root = new StackPane();
    /** What the window shows under the journal: the screen, or why there is none. */
    private final StackPane content = new StackPane();
    /** From the moment the window opens until the containers are on screen, what is being asked. */
    private final com.kubo.ui.Journal journal = new com.kubo.ui.Journal(DockerWindow::words);
    private Runnable stopJournal = () -> { };
    private final SshConnection connection;
    private final CommandService commands;
    private DockerScope scope;

    private final com.kubo.privilege.RootSession rootSession;

    public DockerWindow(SshConnection connection, CommandService commands, Runnable openLogs,
                        com.kubo.privilege.RootSession rootSession) {
        this.connection = connection;
        this.commands = commands;
        this.rootSession = rootSession;
        root.getStyleClass().add("scope-view");
        root.getChildren().addAll(content, journal);
        journal.start("reading", connection.target().username() + "@" + connection.target().host(),
                "is docker here, can this account reach it, what is running");
        stopJournal = journal.follow(commands, CommandService.Origin.DOCKER);

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(KuboWindow.install(stage, root),
                Math.min(1400, screen.getWidth() - 32), Math.min(860, screen.getHeight() - 48), Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        stage.setScene(scene);
        stage.setTitle("Kubo — Docker — " + connection.target());
        stage.setMinWidth(Math.min(1000, screen.getWidth() - 32));
        stage.setMinHeight(Math.min(620, screen.getHeight() - 48));
        com.kubo.ui.KuboWindow.maximize(stage);
        stage.setOnHidden(event -> {
            stopJournal.run();
            if (scope != null) {
                scope.shutdown();
            }
        });
        detect();
    }

    private void detect() {
        Thread thread = new Thread(() -> {
            com.kubo.privilege.RootSession session = rootSession != null ? rootSession
                    : new com.kubo.privilege.RootSession(
                            RootAccess.parse(connection.exec(RootAccess.DETECT).output()));
            Privilege privilege = session.privilege();
            DockerService service = new DockerService(connection, commands, privilege);
            DockerStatus status = service.detect();
            Platform.runLater(() -> {
                show(service, status, privilege, session.access());
                if (scope != null) {
                    scope.setRootSession(session);
                }
            });
        }, "kubo-docker-detect");
        thread.setDaemon(true);
        thread.start();
    }

    private void show(DockerService service, DockerStatus status, Privilege privilege, RootAccess access) {
        try {
            showFound(service, status, privilege, access);
        } finally {
            if (status.availability() != DockerStatus.Availability.USABLE) {
                journal.finish("read", null);
            }
        }
    }

    private void showFound(DockerService service, DockerStatus status, Privilege privilege, RootAccess access) {
        switch (status.availability()) {
            case ABSENT -> content.getChildren().setAll(message("DOCKER IS NOT INSTALLED ON THIS SERVER",
                    "KUBO LOOKED FOR THE DOCKER COMMAND AND DID NOT FIND IT."));
            case NO_PERMISSION -> {
                String why = access.available()
                        ? "THE DOCKER DAEMON REFUSED THE CONNECTION EVEN AS ROOT. IS IT RUNNING?"
                        : "YOU ARE NOT IN THE DOCKER GROUP AND CANNOT BECOME ROOT ON THIS SERVER. ADD YOUR USER TO "
                        + "THE DOCKER GROUP, OR CONNECT AS A SUDOER.";
                content.getChildren().setAll(message("CANNOT REACH THE DOCKER DAEMON", why));
            }
            case USABLE -> {
                scope = new DockerScope(service, status, privilege);
                content.getChildren().setAll(scope);
                scope.setOnFirstRead(() -> journal.finish("read", null));
                scope.start();
                return;
            }
        }
    }

    /** What one of the Docker commands reads, in a few words, or null for one not worth a line. */
    static String words(String command) {
        if (command == null) {
            return null;
        }
        if (command.equals(com.kubo.docker.DockerCommands.STATUS)) {
            return "is docker here, and reachable";
        }
        if (command.contains(com.kubo.docker.DockerCommands.LIST)) {
            return "containers";
        }
        if (command.contains(com.kubo.docker.DockerCommands.STATS)) {
            return "what each container uses";
        }
        if (command.contains(com.kubo.docker.DockerCommands.IMAGES)) {
            return "images";
        }
        if (command.contains(com.kubo.docker.DockerCommands.NETWORKS)) {
            return "networks";
        }
        if (command.contains("docker inspect")) {
            return "container details";
        }
        return null;
    }

    private static VBox message(String title, String detail) {
        Label kicker = label("DOCKER.SCOPE", "series");
        Label heading = label(title, "host-key-host");
        heading.setWrapText(true);
        heading.setMaxWidth(560);
        Label note = label(detail, "lede");
        note.setWrapText(true);
        note.setMaxWidth(520);
        VBox box = new VBox(14, kicker, heading, note);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        box.setPadding(new Insets(40));
        return box;
    }

    public void show() {
        com.kubo.ui.Attentive.bind(stage, DockerScope.ATTENTION, () -> {
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

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
