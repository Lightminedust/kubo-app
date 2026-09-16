package com.kubo.ui.redis;

import com.kubo.model.redis.RedisKey;
import com.kubo.model.redis.RedisType;
import com.kubo.model.redis.RedisValue;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Typed form used both by ADD NEW KEY and IMPORT JSON. */
final class RedisKeyDialog extends VBox {

    record Request(String name, RedisType type, long ttlSeconds, RedisValue value) {
    }

    private final ComboBox<RedisType> type = new ComboBox<>();
    private final TextField name = new TextField();
    private final TextField ttl = new TextField();
    private final TextArea editor = new TextArea();
    private final Label editorHint = label("", "card-note");
    private final Label status = label("", "card-problem");
    private final Button submit = new Button("CREATE KEY");
    private final boolean replaceMode;

    RedisKeyDialog(Request preset, BiConsumer<Request, java.util.function.Consumer<String>> onCreate,
                   Runnable onCancel) {
        this(preset, false, onCreate, onCancel);
    }

    RedisKeyDialog(Request preset, boolean replaceMode,
                   BiConsumer<Request, java.util.function.Consumer<String>> onCreate, Runnable onCancel) {
        this.replaceMode = replaceMode;
        getStyleClass().addAll("float-card", "redis-key-dialog");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setPadding(new Insets(24, 28, 24, 26));
        setSpacing(11);

        type.getItems().setAll(RedisType.STRING, RedisType.HASH, RedisType.LIST, RedisType.SET, RedisType.ZSET);
        type.setValue(preset == null ? RedisType.STRING : preset.type());
        type.getStyleClass().add("redis-type-choice");
        name.getStyleClass().add("card-field");
        name.setPromptText("user:1002:session");
        ttl.getStyleClass().add("card-field");
        ttl.setPromptText("persistent");
        editor.getStyleClass().addAll("redis-value", "redis-create-editor");
        editor.setPrefSize(500, 220);
        editor.setWrapText(false);
        editorHint.setWrapText(true);
        editorHint.setMaxWidth(500);
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);

        if (preset != null) {
            name.setText(preset.name());
            if (preset.ttlSeconds() >= 0) ttl.setText(Long.toString(preset.ttlSeconds()));
            editor.setText(format(preset.value()));
        }
        if (replaceMode) {
            name.setDisable(true);
            type.setDisable(true);
            ttl.setDisable(true);
            submit.setText("SAVE VALUE");
        }
        updateHint(type.getValue());
        type.valueProperty().addListener((observable, before, value) -> updateHint(value));

        submit.getStyleClass().addAll("primary-button", "compact-button");
        submit.setOnAction(event -> submit(onCreate));
        Button cancel = new Button("CANCEL");
        cancel.getStyleClass().add("ghost-button");
        cancel.setOnAction(event -> onCancel.run());

        HBox names = new HBox(12, field("KEY NAME", name), field("TTL (SECONDS)", ttl));
        HBox.setHgrow(names.getChildren().get(0), javafx.scene.layout.Priority.ALWAYS);
        HBox buttons = new HBox(16, submit, cancel);
        buttons.setAlignment(Pos.CENTER_LEFT);
        String series = replaceMode ? "EDIT REDIS KEY" : preset == null ? "ADD NEW KEY" : "IMPORT JSON";
        String heading = replaceMode ? "REPLACE THE KEY VALUE" : preset == null
                ? "CREATE A REDIS VALUE" : "REVIEW BEFORE IMPORT";
        getChildren().addAll(label(series, "series"), label(heading, "card-title"),
                names, field("TYPE", type), field("VALUE / FIELDS", editor), editorHint, status, buttons);
        Platform.runLater(name::requestFocus);
    }

    private void submit(BiConsumer<Request, java.util.function.Consumer<String>> onCreate) {
        try {
            String key = name.getText().strip();
            if (key.isEmpty()) throw new IllegalArgumentException("Key name is required.");
            long seconds = ttl.getText().isBlank() ? RedisKey.PERSISTENT : Long.parseLong(ttl.getText().strip());
            if (seconds == 0 || seconds < RedisKey.PERSISTENT) {
                throw new IllegalArgumentException("TTL must be positive or blank for persistent.");
            }
            RedisValue value = parse(type.getValue(), editor.getText());
            setBusy(true);
            onCreate.accept(new Request(key, type.getValue(), seconds, value), error -> {
                setBusy(false);
                if (error != null) setProblem(error);
            });
        } catch (NumberFormatException e) {
            setProblem("TTL must be a number of seconds.");
        } catch (IllegalArgumentException e) {
            setProblem(e.getMessage());
        }
    }

    private static RedisValue parse(RedisType type, String text) {
        if (type == RedisType.STRING) return RedisValue.string(text, false);
        List<RedisValue.Entry> entries = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            if (raw.isBlank()) continue;
            if (type == RedisType.HASH || type == RedisType.ZSET) {
                int split = raw.indexOf('=');
                if (split <= 0) throw new IllegalArgumentException(type == RedisType.HASH
                        ? "Use one field = value pair per line." : "Use one score = member pair per line.");
                String first = raw.substring(0, split).strip();
                String second = raw.substring(split + 1).strip();
                if (first.isEmpty() || second.isEmpty()) throw new IllegalArgumentException("Both sides of = are required.");
                entries.add(type == RedisType.HASH ? new RedisValue.Entry(first, second)
                        : new RedisValue.Entry(second, first));
            } else {
                entries.add(new RedisValue.Entry(raw, ""));
            }
        }
        if (entries.isEmpty()) throw new IllegalArgumentException("Add at least one collection entry.");
        if (type == RedisType.ZSET) {
            for (RedisValue.Entry entry : entries) {
                try { Double.parseDouble(entry.second()); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("Every ZSet score must be a number."); }
            }
        }
        return RedisValue.collection(type, entries, false);
    }

    private void updateHint(RedisType selected) {
        editorHint.setText(switch (selected) {
            case STRING -> "TEXT OR JSON · STORED EXACTLY AS A REDIS STRING";
            case HASH -> "ONE field = value PAIR PER LINE";
            case LIST -> "ONE LIST ITEM PER LINE · ORDER IS PRESERVED";
            case SET -> "ONE SET MEMBER PER LINE";
            case ZSET -> "ONE score = member PAIR PER LINE";
            default -> "";
        });
    }

    private static String format(RedisValue value) {
        if (value.type() == RedisType.STRING) return value.text();
        return value.entries().stream().map(entry -> switch (value.type()) {
            case HASH -> entry.first() + " = " + entry.second();
            case ZSET -> entry.second() + " = " + entry.first();
            default -> entry.first();
        }).collect(java.util.stream.Collectors.joining("\n"));
    }

    private void setBusy(boolean busy) {
        submit.setDisable(busy);
        name.setDisable(busy);
        type.setDisable(busy);
        ttl.setDisable(busy);
        editor.setDisable(busy);
        submit.setText(busy ? (replaceMode ? "SAVING…" : "CREATING…")
                : replaceMode ? "SAVE VALUE" : "CREATE KEY");
        if (busy) {
            status.getStyleClass().setAll("label", "card-note");
            status.setText("WRITING TO REDIS…");
        }
    }

    private void setProblem(String text) {
        status.getStyleClass().setAll("label", "card-problem");
        status.setText(text == null ? "Redis rejected the key." : text);
    }

    private static VBox field(String title, javafx.scene.Node node) {
        VBox box = new VBox(4, label(title, "field-label"), node);
        if (node instanceof Region region) region.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }
}
