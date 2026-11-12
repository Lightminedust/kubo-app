package com.kubo.ui.redis;

import com.kubo.model.redis.RedisKey;
import com.kubo.model.redis.RedisType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What is in this database, read from top to bottom: one station per kind of value, on a line.
 *
 * <p>Each station says what the kind is in plain words, how many keys hold it, how many of them expire, and
 * names the first few. The chosen one also says what the kind is used for and which commands read and write
 * it. Choosing a station lists all of its keys beside it; choosing a key opens it.
 * The isometric plates this replaces showed the same counts as points of light, and nobody could tell what
 * a point was without hovering it.
 */
final class RedisTrack extends ScrollPane {

    /** Opens the key menu where the pointer is. */
    record MenuRequest(RedisKey key, double screenX, double screenY) {
    }

    private static final RedisType[] ORDER = {RedisType.STRING, RedisType.HASH, RedisType.LIST, RedisType.SET,
            RedisType.ZSET, RedisType.STREAM, RedisType.OTHER};
    /** How many keys each station names. The full list is the column beside this one. */
    private static final int SHOWN = 3;
    private static final double SPINE = 40;
    private static final double NODE_TOP = 14;

    private final VBox stations = new VBox();
    private final Map<RedisType, List<RedisKey>> keys = new EnumMap<>(RedisType.class);
    private final Label waiting = new Label("scanning the database…");
    private Map<String, Long> memory = Map.of();
    private RedisType selectedType = RedisType.STRING;
    private String selectedKey;
    private boolean more;
    private boolean loading;
    /** Under this width the counts move beside the name and the big numbers go, so the names keep their room. */
    private static final double NARROW = 470;
    private boolean narrow;

    private Consumer<RedisKey> onSelect = key -> {
    };
    private Consumer<RedisType> onTypeSelect = type -> {
    };
    private Consumer<MenuRequest> onMenu = request -> {
    };
    private Runnable onNeedMore = () -> {
    };

    RedisTrack() {
        getStyleClass().add("track-scroll");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        stations.getStyleClass().add("track");
        stations.setPadding(new Insets(6, 8, 18, 0));
        waiting.getStyleClass().add("track-quiet");
        setContent(stations);
        for (RedisType type : ORDER) {
            keys.put(type, new ArrayList<>());
        }
        rebuild();
        widthProperty().addListener((observable, before, width) -> {
            boolean now = width.doubleValue() > 0 && width.doubleValue() < NARROW;
            if (now != narrow) {
                narrow = now;
                rebuild();
            }
        });
    }

    // Wiring, the same as the screen it serves expects of whatever draws its keys.

    void setOnSelect(Consumer<RedisKey> onSelect) {
        this.onSelect = onSelect;
    }

    void setOnTypeSelect(Consumer<RedisType> onTypeSelect) {
        this.onTypeSelect = onTypeSelect;
    }

    void setOnMenu(Consumer<MenuRequest> onMenu) {
        this.onMenu = onMenu;
    }

    void setOnNeedMore(Runnable onNeedMore) {
        this.onNeedMore = onNeedMore;
    }

    void setKeys(List<RedisKey> all, boolean more) {
        this.more = more;
        keys.values().forEach(List::clear);
        for (RedisKey key : all) {
            keys.get(bucket(key.type())).add(key);
        }
        if (selectedKey != null && all.stream().noneMatch(key -> key.name().equals(selectedKey))) {
            selectedKey = null;
        }
        if (keys.get(selectedType).isEmpty()) {
            for (RedisType type : ORDER) {
                if (!keys.get(type).isEmpty()) {
                    selectedType = type;
                    break;
                }
            }
        }
        rebuild();
    }

    void setLoading(boolean loading) {
        this.loading = loading;
        rebuild();
    }

    void setMemoryUsage(Map<String, Long> memory, boolean unusedSort) {
        this.memory = Map.copyOf(memory);
        rebuild();
    }

    void select(String keyName) {
        selectedKey = keyName;
        rebuild();
    }

    void selectType(RedisType type) {
        selectedType = bucket(type);
        rebuild();
    }

    RedisType selectedType() {
        return selectedType;
    }

    List<RedisKey> keysOf(RedisType type) {
        return List.copyOf(keys.get(bucket(type)));
    }

    void stop() {
        // Nothing moves on its own here any more; kept so the screen can say it is closing.
    }

    // Drawing

    private void rebuild() {
        stations.getChildren().clear();
        List<RedisType> present = new ArrayList<>();
        for (RedisType type : ORDER) {
            if (!keys.get(type).isEmpty()) {
                present.add(type);
            }
        }
        if (present.isEmpty()) {
            waiting.setText(loading ? "scanning the database…" : "no keys match in this database");
            stations.getChildren().add(waiting);
            return;
        }
        for (int i = 0; i < present.size(); i++) {
            stations.getChildren().add(station(present.get(i), i, i == 0, i == present.size() - 1 && !more));
        }
        if (more) {
            Button next = new Button("scan further ↓");
            next.getStyleClass().add("track-more");
            next.setFocusTraversable(false);
            next.setOnAction(event -> onNeedMore.run());
            VBox.setMargin(next, new Insets(4, 0, 0, SPINE + 72));
            stations.getChildren().add(next);
        }
    }

    private HBox station(RedisType type, int index, boolean first, boolean last) {
        List<RedisKey> held = keys.get(type);
        Color colour = RedisPalette.color(type);
        String hex = RedisPalette.web(colour);
        boolean chosen = type == selectedType;
        long expiring = held.stream().filter(RedisKey::expires).count();

        // The count on the left, in a bracket, the way a schedule writes the days beside each step.
        Label count = new Label(held.size() + (held.size() == 1 ? " key" : " keys"));
        count.getStyleClass().add("track-count");
        count.setStyle("-fx-text-fill: " + hex + "; -fx-border-color: transparent transparent transparent " + hex + ";");
        Label ttl = new Label(expiring == 0 ? "no ttl" : expiring + " expire");
        ttl.getStyleClass().add("track-side");
        VBox side = new VBox(3, count, ttl);
        long weight = held.stream().mapToLong(key -> memory.getOrDefault(key.name(), 0L)).sum();
        if (weight > 0) {
            Label weighed = new Label(size(weight));
            weighed.getStyleClass().add("track-side");
            side.getChildren().add(weighed);
        }
        side.setAlignment(Pos.TOP_RIGHT);
        side.setMinWidth(72);
        side.setPrefWidth(72);
        side.setPadding(new Insets(NODE_TOP - 2, 0, 0, 0));

        // The line and its node. The line runs the whole height of the station, starting at the first
        // node and stopping at the last, so the stations read as one sequence.
        Region line = new Region();
        line.getStyleClass().add("track-line");
        line.setMinWidth(2);
        line.setPrefWidth(2);
        line.setMaxWidth(2);
        line.setMaxHeight(last ? NODE_TOP + 8 : Double.MAX_VALUE);
        StackPane.setAlignment(line, Pos.TOP_CENTER);
        StackPane.setMargin(line, new Insets(first ? NODE_TOP + 8 : 0, 0, 0, 0));
        Circle ring = new Circle(9, Color.web("#08080d"));
        ring.setStroke(colour);
        ring.setStrokeWidth(chosen ? 2.5 : 1.5);
        Circle core = new Circle(chosen ? 4.5 : 3, colour);
        StackPane node = new StackPane(ring, core);
        node.setMaxSize(22, 22);
        if (chosen) {
            node.setEffect(new javafx.scene.effect.DropShadow(14, colour.deriveColor(0, 1, 1, 0.7)));
        }
        StackPane.setAlignment(node, Pos.TOP_CENTER);
        StackPane.setMargin(node, new Insets(NODE_TOP - 3, 0, 0, 0));
        StackPane spine = new StackPane(line, node);
        spine.setMinWidth(SPINE);
        spine.setPrefWidth(SPINE);

        // The number and the name, big, then what the kind is in plain words.
        Label number = new Label(String.format(Locale.ROOT, "%02d", index + 1));
        number.getStyleClass().add("track-number");
        if (chosen) {
            number.setStyle("-fx-text-fill: " + hex + ";");
        }
        Label name = new Label(narrow ? title(type, held) + "  ·  " + held.size() : title(type, held));
        name.getStyleClass().add("track-name");
        Label meaning = new Label(meaning(type, held));
        meaning.getStyleClass().add("track-meaning");
        meaning.setWrapText(true);
        meaning.setMinHeight(Region.USE_PREF_SIZE);
        VBox naming = new VBox(1, name, meaning);
        naming.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(naming, Priority.ALWAYS);
        naming.setMinWidth(0);
        HBox heading = narrow ? new HBox(naming) : new HBox(14, number, naming);
        heading.setAlignment(Pos.CENTER_LEFT);

        // A few of its keys by name, each one opens on a click; the rest are in the column beside.
        HBox sample = new HBox(14);
        sample.setAlignment(Pos.CENTER_LEFT);
        for (RedisKey key : held.subList(0, Math.min(SHOWN, held.size()))) {
            sample.getChildren().add(keyChip(key, hex));
        }
        if (held.size() > SHOWN) {
            Label rest = new Label("+" + (held.size() - SHOWN));
            rest.getStyleClass().add("track-rest");
            rest.setMinWidth(Region.USE_PREF_SIZE);
            sample.getChildren().add(rest);
        }
        sample.setPadding(new Insets(0, 0, 0, narrow ? 0 : 62));

        VBox content = new VBox(8, heading, sample);
        if (chosen) {
            // What it is for and how it is read and written, in a dashed box, like the notes beside a step
            // in a schedule.
            VBox bracket = new VBox(4);
            bracket.getStyleClass().add("track-bracket");
            bracket.setStyle("-fx-border-color: " + hex + ";");
            Label header = new Label("// in practice");
            header.getStyleClass().add("track-bracket-head");
            header.setStyle("-fx-text-fill: " + hex + ";");
            bracket.getChildren().add(header);
            for (String[] note : practice(type, held)) {
                Label bullet = new Label(note[0]);
                bullet.getStyleClass().add("track-use-key");
                bullet.setMinWidth(54);
                Label text = new Label(note[1]);
                text.getStyleClass().add(note[0].equals("use") ? "track-use" : "track-command");
                text.setWrapText(true);
                text.setMinWidth(0);
                HBox row = new HBox(10, bullet, text);
                row.setAlignment(Pos.TOP_LEFT);
                bracket.getChildren().add(row);
            }
            VBox.setMargin(bracket, new Insets(4, 0, 0, 0));
            content.getChildren().add(bracket);
        }
        content.setPadding(new Insets(6, 0, chosen ? 26 : 20, 0));
        HBox.setHgrow(content, Priority.ALWAYS);
        content.setMinWidth(0);

        HBox row = narrow ? new HBox(12, spine, content) : new HBox(14, side, spine, content);
        row.getStyleClass().add("track-station");
        if (!chosen) {
            row.getStyleClass().add("track-station-quiet");
        }
        row.setCursor(Cursor.HAND);
        row.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && type != selectedType) {
                selectedType = type;
                rebuild();
                onTypeSelect.accept(type);
            }
        });
        return row;
    }

    private Label keyChip(RedisKey key, String hex) {
        Label chip = new Label(key.name());
        chip.getStyleClass().add("track-key");
        if (key.name().equals(selectedKey)) {
            chip.getStyleClass().add("track-key-chosen");
            chip.setStyle("-fx-border-color: transparent transparent " + hex + " transparent;");
        }
        chip.setMinWidth(0);
        chip.setOnMouseClicked(event -> {
            event.consume();
            selectedKey = key.name();
            if (bucket(key.type()) != selectedType) {
                selectedType = bucket(key.type());
                onTypeSelect.accept(selectedType);
            }
            rebuild();
            if (event.getButton() == MouseButton.SECONDARY) {
                onMenu.accept(new MenuRequest(key, event.getScreenX(), event.getScreenY()));
            } else {
                onSelect.accept(key);
            }
        });
        return chip;
    }

    static String title(RedisType type, List<RedisKey> held) {
        if (type != RedisType.OTHER) {
            return type.name().toLowerCase(Locale.ROOT);
        }
        String shared = held.isEmpty() ? "" : held.get(0).typeName();
        boolean agreed = held.stream().allMatch(key -> key.typeName().equals(shared));
        return agreed && !shared.isBlank() ? shared.toLowerCase(Locale.ROOT) : "other";
    }

    /** What the kind is, for somebody who has never had to know. */
    private static String meaning(RedisType type, List<RedisKey> held) {
        return switch (type) {
            case STRING -> "one value per key: text, a number, a token, a cached page";
            case HASH -> "a small record per key: named fields and their values";
            case LIST -> "an ordered list per key, often a queue of jobs";
            case SET -> "a bag of unique members per key, no order";
            case ZSET -> "unique members ranked by a score: leaderboards, schedules";
            case STREAM -> "an append-only log of events per key";
            case NONE, OTHER -> held.stream().anyMatch(RedisKey::isJson)
                    ? "a JSON document per key, from the RedisJSON module"
                    : "a kind added by a Redis module";
        };
    }

    /**
     * What a kind is for and the commands that read and write it, as label and text. Shown for the chosen
     * kind only, so it reads as an answer to "what is this" rather than as a manual.
     */
    private static List<String[]> practice(RedisType type, List<RedisKey> held) {
        return switch (type) {
            case STRING -> List.of(
                    new String[]{"use", "cached pages, sessions, tokens, counters"},
                    new String[]{"read", "GET key"},
                    new String[]{"write", "SET key value   \u00b7   INCR key"});
            case HASH -> List.of(
                    new String[]{"use", "a user, a product, settings: anything with fields"},
                    new String[]{"read", "HGETALL key   \u00b7   HGET key field"},
                    new String[]{"write", "HSET key field value"});
            case LIST -> List.of(
                    new String[]{"use", "job queues, the latest N items, logs"},
                    new String[]{"read", "LRANGE key 0 -1"},
                    new String[]{"write", "LPUSH key value   \u00b7   RPOP key"});
            case SET -> List.of(
                    new String[]{"use", "tags, who is online, unique visitors"},
                    new String[]{"read", "SMEMBERS key   \u00b7   SISMEMBER key member"},
                    new String[]{"write", "SADD key member"});
            case ZSET -> List.of(
                    new String[]{"use", "leaderboards, rate limits, scheduled jobs"},
                    new String[]{"read", "ZRANGE key 0 9 REV WITHSCORES"},
                    new String[]{"write", "ZADD key score member"});
            case STREAM -> List.of(
                    new String[]{"use", "events, chat, queues read by several workers"},
                    new String[]{"read", "XRANGE key - +"},
                    new String[]{"write", "XADD key * field value"});
            case NONE, OTHER -> held.stream().anyMatch(RedisKey::isJson)
                    ? List.of(
                    new String[]{"use", "documents you read and change by path"},
                    new String[]{"read", "JSON.GET key   \u00b7   JSON.GET key $.path"},
                    new String[]{"write", "JSON.SET key $ '{\"a\":1}'"})
                    : List.of(
                    new String[]{"use", "Kubo can rename, export and delete these keys"},
                    new String[]{"read", "the module's own commands, in redis-cli below"});
        };
    }

    static String ttl(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3_600) {
            return seconds / 60 + "m";
        }
        if (seconds < 86_400) {
            return seconds / 3_600 + "h";
        }
        return seconds / 86_400 + "d";
    }

    private static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static RedisType bucket(RedisType type) {
        return type == RedisType.NONE || type == null ? RedisType.OTHER : type;
    }
}
