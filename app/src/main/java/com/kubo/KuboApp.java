package com.kubo;

import com.kubo.connection.ssh.SshConnection;
import com.kubo.ui.KuboWindow;
import com.kubo.ui.connection.ConnectionView;
import com.kubo.ui.scope.ScopeView;
import javafx.application.Application;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.Objects;

public final class KuboApp extends Application {

    public static final String STYLESHEET =
            Objects.requireNonNull(KuboApp.class.getResource("/com/kubo/ui/kubo.css")).toExternalForm();

    public static final int WIDTH = 1280;
    public static final int HEIGHT = 800;

    private Scene scene;
    private KuboWindow window;
    private Runnable closeCurrentView = () -> {
    };
    /** The screen owning the live SSH connection, or null on the connection screen. */
    private ScopeView scope;

    @Override
    public void start(Stage stage) {
        // Fit small laptop screens, where 800 px plus the title bar would overflow.
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double width = Math.min(WIDTH, screen.getWidth() - 32);
        double height = Math.min(HEIGHT, screen.getHeight() - 48);

        window = KuboWindow.install(stage, new javafx.scene.layout.StackPane());
        scene = new Scene(window, width, height, Color.web("#0a0a0f"));
        scene.getStylesheets().add(STYLESHEET);

        stage.setTitle("Kubo");
        stage.setMinWidth(Math.min(1100, width));
        stage.setMinHeight(Math.min(680, height));
        stage.setScene(scene);
        stage.setMaximized(true);
        stage.setOnHidden(event -> closeCurrentView.run());
        com.kubo.ui.Attentive.bind(stage, ScopeView.ATTENTION, () -> {
            if (scope != null) {
                scope.resume();
            }
        });
        showConnection();
        stage.show();
    }

    private void showConnection() {
        closeCurrentView.run();
        ConnectionView view = new ConnectionView();
        view.setOnConnected(this::showScope);
        closeCurrentView = view::shutdown;
        window.setContent(view);
    }

    private void showScope(SshConnection connection) {
        closeCurrentView.run();
        ScopeView view = new ScopeView();
        scope = view;
        closeCurrentView = view::shutdown;
        window.setContent(view);
        view.attach(connection, () -> {
            scope = null;
            showConnection();
        });
    }

    /**
     * Called by JavaFX as the application shuts down, and it waits for this to return before the JVM exits. That is
     * the one place a disconnect can be finished cleanly: the daemon thread shutdown() starts would otherwise be
     * killed with the process before the server is told the session is over.
     */
    @Override
    public void stop() {
        closeCurrentView.run();
        if (scope != null) {
            scope.awaitDisconnect(3000);
            scope = null;
        }
    }
}
