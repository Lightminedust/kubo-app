package com.kubo.ui.docker;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * A card that explains the Docker screen: what each mark means and what each action does, as a small table.
 */
final class DockerHelp extends VBox {

    DockerHelp(Runnable onClose) {
        getStyleClass().addAll("float-card", "help-card");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(14);
        setPadding(new Insets(24, 30, 24, 28));

        Label title = label("READING THE DOCKER SCREEN", "card-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox header = new HBox(12, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        GridPane marks = table("MARK", "MEANS",
                dot(Color.web("#35e0a1")), "RUNNING — the container is up; a filled dot, particles flow to the daemon",
                dot(Color.web("#ffb347")), "RESTARTING — Docker is bringing it back up",
                dot(Color.web("#35e8ff")), "PAUSED — frozen, still in memory",
                ring(Color.web("#ff5a6e")), "STOPPED / EXITED / DEAD — a hollow dot with a cross",
                dot(Color.web("#ff2d55")), "A PUBLIC published port — reachable from the internet",
                dot(Color.web("#8a4dff")), "An INTERNAL port — reachable on a private network only");

        GridPane actions = table("ACTION", "DOES",
                text("▶ START"), "Starts a stopped container (docker start)",
                text("⏹ STOP"), "Stops a running container (docker stop)",
                text("⟳ RESTART"), "Stops then starts it (docker restart)",
                text("🗑 DELETE"), "Removes the container for good (docker rm -f) — asks twice",
                text("LOGS"), "The last 200 log lines (docker logs)",
                text("SHELL"), "Runs one command inside the container (docker exec)");

        Label gauges = label("EACH RUNNING CONTAINER SHOWS CPU (SHARE OF ONE CORE) AND MEMORY GAUGES. THE DAEMON NODE "
                + "ON THE LEFT SHOWS THE ENGINE VERSION AND HOW MANY CONTAINERS ARE UP.", "card-note");
        gauges.setWrapText(true);
        gauges.setMaxWidth(560);
        Label root = label("ACTIONS MAY NEED ROOT. WHEN THEY DO, KUBO ASKS FOR YOUR SUDO PASSWORD ONCE AND KEEPS IT IN "
                + "MEMORY FOR THE SESSION, NEVER ON DISK. EVERY DOCKER COMMAND IS LISTED IN THE LOGS.", "card-note");
        root.setWrapText(true);
        root.setMaxWidth(560);

        getChildren().addAll(header, label("STATES & PORTS", "series"), marks, label("ACTIONS", "series"), actions,
                gauges, root);
    }

    private static GridPane table(String head1, String head2, Object... rows) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(7);
        grid.add(label(head1, "metric-label"), 0, 0);
        grid.add(label(head2, "metric-label"), 1, 0);
        for (int i = 0; i < rows.length; i += 2) {
            int row = i / 2 + 1;
            Object mark = rows[i];
            if (mark instanceof javafx.scene.Node node) {
                HBox box = new HBox(node);
                box.setAlignment(Pos.CENTER_LEFT);
                box.setMinWidth(70);
                grid.add(box, 0, row);
            }
            Label text = label((String) rows[i + 1], "card-note");
            text.setWrapText(true);
            text.setMaxWidth(470);
            grid.add(text, 1, row);
        }
        return grid;
    }

    private static Circle dot(Color color) {
        return new Circle(5, color);
    }

    private static Circle ring(Color color) {
        Circle circle = new Circle(5);
        circle.setFill(Color.TRANSPARENT);
        circle.setStroke(color);
        return circle;
    }

    private static Label text(String value) {
        return label(value, "mono");
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
