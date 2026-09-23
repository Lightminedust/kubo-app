package com.kubo.ui.redis;

import com.kubo.KuboApp;
import com.kubo.command.CommandService;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.docker.DockerService;
import com.kubo.model.redis.RedisStatus;
import com.kubo.privilege.Privilege;
import com.kubo.privilege.RootAccess;
import com.kubo.redis.RedisService;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The Redis screen in its own window. On open it finds Redis on its own — on the host, or inside a running Redis
 * container — and either shows the keyspace or explains why it cannot.
 */
public final class RedisWindow {

    private final Stage stage = new Stage();
    private final StackPane root = new StackPane();
    private final SshConnection connection;
    private final CommandService commands;
    private RedisScope scope;

    private final com.kubo.privilege.RootSession rootSession;

    public RedisWindow(SshConnection connection, CommandService commands,
                       com.kubo.privilege.RootSession rootSession) {
        this.connection = connection;
        this.commands = commands;
        this.rootSession = rootSession;
        root.getStyleClass().add("scope-view");
        root.getChildren().add(message("LOOKING FOR REDIS…", ""));

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(KuboWindow.install(stage, root),
                Math.min(1400, screen.getWidth() - 32), Math.min(860, screen.getHeight() - 48), Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        stage.setScene(scene);
        stage.setTitle("Kubo — Redis — " + connection.target());
        stage.setMinWidth(Math.min(1000, screen.getWidth() - 32));
        stage.setMinHeight(Math.min(620, screen.getHeight() - 48));
        stage.setMaximized(true);
        stage.setOnHidden(event -> {
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
            DockerService docker = new DockerService(connection, commands, privilege);
            docker.detect();
            RedisService redis = new RedisService(connection, commands, privilege, docker);
            List<RedisService.Detected> detected = redis.detectAll();
            Platform.runLater(() -> show(detected, privilege));
        }, "kubo-redis-detect");
        thread.setDaemon(true);
        thread.start();
    }

    private void show(List<RedisService.Detected> detected, Privilege privilege) {
        List<RedisService.Detected> usable = detected.stream().filter(item -> item.status().usable()).toList();
        if (!usable.isEmpty()) {
            scope = new RedisScope(usable, privilege, commands);
            root.getChildren().setAll(scope);
            if (rootSession != null) {
                scope.setRootSession(rootSession);
            }
            scope.start();
        } else if (detected.stream().anyMatch(item -> item.status().availability() == RedisStatus.Availability.NEEDS_AUTH)) {
            askForPassword(detected, privilege, "");
        } else {
            root.getChildren().setAll(message("NO REDIS FOUND",
                    "KUBO TRIED redis-cli ON THE HOST AND CHECKED EVERY RUNNING REDIS CONTAINER."));
        }
    }

    /**
     * Redis answered NOAUTH: ask for its {@code requirepass} and try again with it.
     *
     * <p>The password is kept by the service for this session only, travels to the server in the environment of
     * each redis-cli process rather than on its command line, and never reaches the log or the disk.
     */
    private void askForPassword(List<RedisService.Detected> detected, Privilege privilege, String problem) {
        List<RedisService.Detected> locked = detected.stream()
                .filter(item -> item.status().availability() == RedisStatus.Availability.NEEDS_AUTH).toList();
        root.getChildren().setAll(new RedisPasswordCard(locked, problem, password -> {
            Thread thread = new Thread(() -> {
                List<RedisService.Detected> opened = new ArrayList<>();
                for (RedisService.Detected item : locked) {
                    if (item.service().authenticate(password)) {
                        opened.add(new RedisService.Detected(item.service(),
                                new RedisStatus(RedisStatus.Availability.USABLE, item.status().location(),
                                        item.status().where(), item.status().version())));
                    }
                }
                java.util.Arrays.fill(password, (char) 0);
                Platform.runLater(() -> {
                    if (opened.isEmpty()) {
                        askForPassword(detected, privilege, "Redis refused that password.");
                    } else {
                        show(opened, privilege);
                    }
                });
            }, "kubo-redis-auth");
            thread.setDaemon(true);
            thread.start();
        }));
    }

    private static VBox message(String title, String detail) {
        Label kicker = label("REDIS.SCOPE", "series");
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
        com.kubo.ui.Attentive.bind(stage, RedisScope.ATTENTION, () -> {
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
