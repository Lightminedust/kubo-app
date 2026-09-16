package com.kubo.ui.redis;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

/**
 * A card that explains the Redis topology, type colours, expiry mark and key actions.
 */
final class RedisHelp extends VBox {

    RedisHelp(Runnable onClose) {
        getStyleClass().addAll("float-card", "help-card");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(14);
        setPadding(new Insets(24, 30, 24, 28));

        Label title = label("READING THE REDIS SCREEN", "card-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox header = new HBox(12, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        Label navigation = label("EACH REDIS PROCESS IS A ROOT CARD. ITS TYPE NODES FORM THE NEXT COLUMN; CLICK ONE "
                + "TO OPEN A VERTICAL KEY COLUMN. DRAG OR USE THE WHEEL TO PAN WITH INERTIA. CTRL+WHEEL ZOOMS; "
                + "RIGHT-CLICKING A KEY OPENS ITS ACTIONS.", "card-note");
        navigation.setWrapText(true);
        navigation.setMaxWidth(520);

        GridPane types = table("COLOUR", "TYPE",
                RedisPalette.STRING, "STRING — a single value (GET / SET)",
                RedisPalette.HASH, "HASH — fields and values (HGETALL)",
                RedisPalette.LIST, "LIST — an ordered list (LRANGE)",
                RedisPalette.SET, "SET — unique members (SMEMBERS)",
                RedisPalette.ZSET, "ZSET — members with scores (ZRANGE)",
                RedisPalette.STREAM, "STREAM — an append log (shown as length)");

        Label ring = label("AN AMBER MARK AND A TTL LABEL MEAN THE KEY WILL EXPIRE; PERSISTENT KEYS ARE LABELLED "
                + "PERSISTENT.", "card-note");
        ring.setWrapText(true);
        ring.setMaxWidth(520);

        GridPane actions = table2("ACTION", "DOES",
                "ADD NEW KEY", "Creates String, Hash, List, Set or ZSet data",
                "RIGHT-CLICK", "Open, clone, rename, export JSON or delete",
                "SAVE VALUE", "Writes the edited string back (SET)",
                "SET TTL", "Makes the key expire after N seconds (EXPIRE)",
                "PERSIST", "Removes the expiry so it lives forever (PERSIST)",
                "🗑 DELETE", "Removes the key at once, without blocking (UNLINK)");

        Label scan = label("KUBO SCANS KEYS A PAGE AT A TIME (SCAN), NEVER KEYS *, SO IT WILL NOT BLOCK A BUSY REDIS. "
                + "SCROLLING NEAR THE END LOADS THE NEXT PAGE. USE MATCH TO FILTER AND THE DB0–DB15 SELECTOR TO "
                + "SWITCH DATABASE. MEMORY SORT, SLOWLOG, LIVE TRAFFIC AND REDIS CLI ARE IN THE TOP TOOLBAR.", "card-note");
        scan.setWrapText(true);
        scan.setMaxWidth(520);

        getChildren().addAll(header, label("NAVIGATION", "series"), navigation,
                label("KEY TYPES", "series"), types, ring,
                label("ACTIONS", "series"), actions, scan);
    }

    private static GridPane table(String h1, String h2, Object... rows) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(7);
        grid.add(label(h1, "metric-label"), 0, 0);
        grid.add(label(h2, "metric-label"), 1, 0);
        for (int i = 0; i < rows.length; i += 2) {
            int row = i / 2 + 1;
            HBox mark = new HBox(new Circle(5, (javafx.scene.paint.Color) rows[i]));
            mark.setAlignment(Pos.CENTER_LEFT);
            mark.setMinWidth(60);
            grid.add(mark, 0, row);
            grid.add(label((String) rows[i + 1], "card-note"), 1, row);
        }
        return grid;
    }

    private static GridPane table2(String h1, String h2, String... rows) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(7);
        grid.add(label(h1, "metric-label"), 0, 0);
        grid.add(label(h2, "metric-label"), 1, 0);
        for (int i = 0; i < rows.length; i += 2) {
            int row = i / 2 + 1;
            Label name = label(rows[i], "mono");
            name.setMinWidth(110);
            grid.add(name, 0, row);
            grid.add(label(rows[i + 1], "card-note"), 1, row);
        }
        return grid;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
