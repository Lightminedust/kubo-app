package com.kubo.ui.network;

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

/**
 * How to read the network screen, in the same key and value lines as the rest of it.
 */
final class NetworkHelp extends VBox {

    NetworkHelp(Runnable onClose) {
        getStyleClass().addAll("float-card", "help-card", "redis-help");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(16);
        setPadding(new Insets(24, 30, 24, 28));

        Label title = label("reading this screen", "fetch-host");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("close");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox header = new HBox(12, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        GridPane groups = new GridPane();
        groups.setHgap(18);
        groups.setVgap(8);
        Object[][] rows = {
                {NetworkTree.ALERT, "exposed", "a database or admin port anyone can reach. Act on this one."},
                {NetworkTree.AMBER, "open", "anyone can reach it. Normal for 22, 80 and 443."},
                {NetworkTree.CYAN, "shielded", "it listens everywhere, but the firewall stops the internet."},
                {NetworkTree.VIOLET, "private", "a Docker bridge or the provider's internal network."},
                {NetworkTree.GREEN, "local", "bound to 127.0.0.1, nothing outside can open it."},
        };
        for (int i = 0; i < rows.length; i++) {
            Label name = label((String) rows[i][1], "fetch-key");
            name.setStyle("-fx-text-fill: " + NetworkTree.web((Color) rows[i][0]) + ";");
            name.setMinWidth(170);
            Label text = label((String) rows[i][2], "fetch-value");
            text.setWrapText(true);
            text.setMaxWidth(400);
            groups.add(name, 0, i);
            groups.add(text, 1, i);
        }

        GridPane moving = table(
                "click a port", "shows it on the right; click it again to show them all",
                "right click a port", "what can be done to that port",
                "right click the map", "open or block a port at the firewall",
                "click a branch", "folds it or opens it",
                "drag, wheel", "moves the map, zooms where the pointer is",
                "double click", "puts the whole map back in the frame");

        GridPane runs = table(
                "open port", "ufw allow <port>/<proto>",
                "close port", "ufw --force delete allow <port>/<proto>",
                "restart", "pm2 restart <app>",
                "stop", "pm2 stop <app>",
                "app logs", "pm2 logs <app> --lines 200 --nostream",
                "kill", "kill -9 <pid>",
                "reload nginx", "nginx -t && systemctl reload nginx");

        Label root = label("The firewall rules and the nginx configuration only answer to root. Turn root on in "
                + "the bar and the screen reads them again at once. Kubo only writes ufw rules; on any other "
                + "firewall it reads and never writes.", "fetch-value");
        root.setWrapText(true);
        root.setMaxWidth(580);

        getChildren().addAll(header, groups, rule(), moving, rule(), runs, rule(), root);
    }

    private static GridPane table(String... rows) {
        GridPane grid = new GridPane();
        grid.setHgap(18);
        grid.setVgap(6);
        for (int i = 0; i < rows.length; i += 2) {
            Label key = label(rows[i], "fetch-key");
            key.setMinWidth(170);
            grid.add(key, 0, i / 2);
            grid.add(label(rows[i + 1], "fetch-value"), 1, i / 2);
        }
        return grid;
    }

    private static Region rule() {
        Region rule = new Region();
        rule.getStyleClass().add("fetch-rule");
        rule.setMinHeight(1);
        rule.setMaxHeight(1);
        return rule;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
