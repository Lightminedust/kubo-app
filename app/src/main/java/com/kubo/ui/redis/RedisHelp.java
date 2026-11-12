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

/**
 * How to read the Redis screen, in the same key and value lines as the rest of it.
 */
final class RedisHelp extends VBox {

    RedisHelp(Runnable onClose) {
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

        GridPane frames = table(
                "keyspace", "every kind of value in this database, one per line, with how many keys hold it. "
                        + "Click one to list its keys.",
                "keys", "every key of the chosen kind. Type to filter, right click for clone, rename, export "
                        + "and delete.",
                "value", "the key you opened. A text value can be edited and saved; a collection can be "
                        + "edited as JSON.");

        GridPane actions = table(
                "db0", "Redis keeps 16 numbered databases. Most applications only use db0.",
                "match", "only keys whose name fits, for example user:* for every key starting with user:",
                "ttl", "how long until Redis deletes the key by itself. never expire removes that timer.",
                "weigh keys", "asks Redis how much memory each loaded key takes.",
                "slowlog", "the last commands Redis found slow.",
                "live traffic", "every command Redis receives, for 6 seconds.",
                "flush", "deletes every key of the database, or of all of them. It cannot be undone.");

        Label scan = label("Kubo reads keys a page at a time with SCAN, never KEYS *, so a busy Redis is never "
                + "blocked. Scan further loads the next page.", "fetch-value");
        scan.setWrapText(true);
        scan.setMaxWidth(560);

        getChildren().addAll(header, frames, rule(), actions, rule(), scan);
    }

    private static GridPane table(String... rows) {
        GridPane grid = new GridPane();
        grid.setHgap(18);
        grid.setVgap(8);
        for (int i = 0; i < rows.length; i += 2) {
            Label key = label(rows[i], "fetch-key");
            key.setMinWidth(96);
            Label text = label(rows[i + 1], "fetch-value");
            text.setWrapText(true);
            text.setMaxWidth(460);
            grid.add(key, 0, i / 2);
            grid.add(text, 1, i / 2);
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
