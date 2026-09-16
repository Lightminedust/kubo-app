package com.kubo.ui.network;

import com.kubo.KuboApp;
import com.kubo.command.CommandService;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.docker.DockerService;
import com.kubo.network.NetworkService;
import com.kubo.privilege.RootAccess;
import com.kubo.privilege.RootSession;
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
 * The network screen in its own window. It works out what root access the account has, then opens the map.
 */
public final class NetworkWindow {

    private final Stage stage = new Stage();
    private final StackPane root = new StackPane();
    private final SshConnection connection;
    private final CommandService commands;
    private final RootSession rootSession;
    private NetworkScope scope;

    public NetworkWindow(SshConnection connection, CommandService commands, RootSession rootSession) {
        this.connection = connection;
        this.commands = commands;
        this.rootSession = rootSession;
        root.getStyleClass().add("scope-view");
        root.getChildren().add(message("READING THE NETWORK…",
                "KUBO IS ASKING THE SERVER WHAT LISTENS, WHAT THE FIREWALL ALLOWS AND WHERE NGINX SENDS TRAFFIC."));

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(KuboWindow.install(stage, root),
                Math.min(1400, screen.getWidth() - 32), Math.min(860, screen.getHeight() - 48), Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        stage.setScene(scene);
        stage.setTitle("Kubo — Network — " + connection.target());
        stage.setMinWidth(Math.min(1040, screen.getWidth() - 32));
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
            RootSession session = rootSession != null ? rootSession
                    : new RootSession(RootAccess.parse(connection.exec(RootAccess.DETECT).output()));
            DockerService docker = new DockerService(connection, commands, session.privilege());
            docker.detect();
            NetworkService network = new NetworkService(connection, commands, session.privilege(), docker);
            Platform.runLater(() -> {
                scope = new NetworkScope(network, session, connection, commands);
                root.getChildren().setAll(scope);
                scope.start();
            });
        }, "kubo-network-detect");
        thread.setDaemon(true);
        thread.start();
    }

    private static VBox message(String title, String detail) {
        Label kicker = label("NETWORK.SCOPE", "series");
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
