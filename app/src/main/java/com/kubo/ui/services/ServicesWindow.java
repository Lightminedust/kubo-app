package com.kubo.ui.services;

import com.kubo.KuboApp;
import com.kubo.command.CommandService;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.privilege.RootSession;
import com.kubo.services.ServiceControl;
import com.kubo.services.ServiceReader;
import com.kubo.ui.KuboWindow;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * The services screen in its own window.
 *
 * <p>Unlike the network screen there is nothing to work out first: reading systemd needs no root, so the map
 * opens straight away and fills itself as the answers come back.
 */
public final class ServicesWindow {

    private final Stage stage = new Stage();
    private final ServicesScope scope;

    public ServicesWindow(SshConnection connection, CommandService commands, RootSession rootSession) {
        scope = new ServicesScope(new ServiceReader(connection, commands),
                new ServiceControl(connection, commands, rootSession == null ? null : rootSession.privilege()),
                connection, commands, rootSession);
        StackPane root = new StackPane(scope);
        root.getStyleClass().add("scope-view");

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(KuboWindow.install(stage, root),
                Math.min(1400, screen.getWidth() - 32), Math.min(860, screen.getHeight() - 48), Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        stage.setScene(scene);
        stage.setTitle("Kubo — Services — " + connection.target());
        stage.setMinWidth(Math.min(1040, screen.getWidth() - 32));
        stage.setMinHeight(Math.min(620, screen.getHeight() - 48));
        stage.setMaximized(true);
        stage.setOnHidden(event -> scope.shutdown());
        scope.start();
    }

    public void show() {
        com.kubo.ui.Attentive.bind(stage, ServicesScope.ATTENTION, scope::resume);
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
