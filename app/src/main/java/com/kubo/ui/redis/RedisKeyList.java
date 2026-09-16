package com.kubo.ui.redis;

import com.kubo.model.redis.RedisKey;
import com.kubo.model.redis.RedisType;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The precise index beside the stack: every key of the chosen type, one dense row each, filtered as you type. The
 * rows are virtualised, so a type holding tens of thousands of keys costs no more to show than one holding ten.
 *
 * <p>When memory has been measured, each row carries a bar of its weight, which turns the list into the heavy-key
 * report without a separate screen.
 */
final class RedisKeyList extends VBox {

    private static final int ROW_HEIGHT = 26;

    private final Label heading = label("KEYS", "series");
    private final Label countLabel = label("", "metric-label");
    private final TextField filterField = new TextField();
    private final ListView<RedisKey> list = new ListView<>();
    private final ObservableList<RedisKey> shown = FXCollections.observableArrayList();
    private final Label empty = label("NO KEYS ON THIS PLATE.", "metric-label");

    private List<RedisKey> source = List.of();
    private RedisType type = RedisType.STRING;
    private Map<String, Long> memory = Map.of();
    private long heaviest;

    private Consumer<RedisKey> onOpen = key -> {
    };
    private BiConsumer<RedisKey, javafx.scene.input.MouseEvent> onMenu = (key, event) -> {
    };

    RedisKeyList() {
        getStyleClass().add("redis-key-list");
        setSpacing(8);
        setPadding(new Insets(16, 14, 14, 26));
        setMinWidth(250);
        setPrefWidth(292);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(10, heading, spacer, countLabel);
        head.setAlignment(Pos.CENTER_LEFT);

        filterField.getStyleClass().add("card-field");
        filterField.setPromptText("filter these keys");
        filterField.textProperty().addListener((observable, before, value) -> refilter());

        list.setItems(shown);
        list.getStyleClass().add("redis-list");
        list.setFixedCellSize(ROW_HEIGHT);
        list.setPlaceholder(empty);
        list.setCellFactory(view -> new KeyCell());
        list.setOnMouseClicked(event -> {
            RedisKey chosen = list.getSelectionModel().getSelectedItem();
            if (chosen == null) {
                return;
            }
            if (event.getButton() == MouseButton.SECONDARY) {
                onMenu.accept(chosen, event);
            } else {
                onOpen.accept(chosen);
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        getChildren().addAll(head, filterField, list);
    }

    void setOnOpen(Consumer<RedisKey> onOpen) {
        this.onOpen = onOpen;
    }

    void setOnMenu(BiConsumer<RedisKey, javafx.scene.input.MouseEvent> onMenu) {
        this.onMenu = onMenu;
    }

    /**
     * Shows the keys of one plate.
     */
    void show(RedisType type, List<RedisKey> keys) {
        this.type = type;
        this.source = List.copyOf(keys);
        heading.setText("KEYS  ·  " + (type == null ? "ALL" : type.name().toUpperCase(Locale.ROOT)));
        refilter();
    }

    void setMemoryUsage(Map<String, Long> memory) {
        this.memory = Map.copyOf(memory);
        heaviest = this.memory.values().stream().mapToLong(Long::longValue).max().orElse(0);
        list.refresh();
    }

    void select(String name) {
        if (name == null) {
            list.getSelectionModel().clearSelection();
            return;
        }
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).name().equals(name)) {
                list.getSelectionModel().select(i);
                list.scrollTo(Math.max(0, i - 4));
                return;
            }
        }
    }

    private void refilter() {
        String filter = filterField.getText() == null ? "" : filterField.getText().strip().toLowerCase(Locale.ROOT);
        List<RedisKey> matching = filter.isEmpty() ? source
                : source.stream().filter(key -> key.name().toLowerCase(Locale.ROOT).contains(filter)).toList();
        RedisKey previous = list.getSelectionModel().getSelectedItem();
        shown.setAll(matching);
        countLabel.setText(matching.size() + (filter.isEmpty() ? "" : " / " + source.size()));
        if (previous != null) {
            select(previous.name());
        }
    }

    /**
     * One row: the type mark, the key, its expiry, and its weight when it has been measured.
     */
    private final class KeyCell extends ListCell<RedisKey> {

        private final Circle dot = new Circle(3);
        private final Label name = label("", "redis-row-name");
        private final Label ttl = label("", "redis-row-ttl");
        private final Region bar = new Region();
        private final StackPane barHolder = new StackPane(bar);
        private final HBox row = new HBox(9, dot, name, barHolder, ttl);

        KeyCell() {
            getStyleClass().add("redis-row");
            row.setAlignment(Pos.CENTER_LEFT);
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);
            bar.getStyleClass().add("redis-row-bar");
            barHolder.setAlignment(Pos.CENTER_LEFT);
            barHolder.setMinWidth(46);
            barHolder.setPrefWidth(46);
            barHolder.setMaxWidth(46);
            bar.setMaxHeight(3);
            bar.setPrefHeight(3);
        }

        @Override
        protected void updateItem(RedisKey key, boolean isEmpty) {
            super.updateItem(key, isEmpty);
            if (isEmpty || key == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Color color = RedisPalette.color(key.type());
            dot.setFill(color);
            name.setText(key.name());
            if (key.isPersistent()) {
                ttl.setText("∞");
                ttl.setStyle("-fx-text-fill: #4d4d5c;");
            } else if (key.expires()) {
                ttl.setText(shortTtl(key.ttlSeconds()));
                ttl.setStyle("-fx-text-fill: #ffb347;");
            } else {
                ttl.setText("");
            }
            Long bytes = memory.get(key.name());
            if (bytes != null && heaviest > 0) {
                bar.setVisible(true);
                bar.setPrefWidth(Math.max(2, 46 * Math.sqrt(bytes / (double) heaviest)));
                bar.setStyle("-fx-background-color: " + RedisPalette.web(color) + ";");
                barHolder.setVisible(true);
                barHolder.setManaged(true);
            } else {
                barHolder.setVisible(false);
                barHolder.setManaged(false);
            }
            setGraphic(row);
        }
    }

    private static String shortTtl(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return seconds / 60 + "m";
        }
        if (seconds < 86400) {
            return seconds / 3600 + "h";
        }
        return seconds / 86400 + "d";
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
