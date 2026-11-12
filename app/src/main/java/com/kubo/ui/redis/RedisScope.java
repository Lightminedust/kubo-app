package com.kubo.ui.redis;

import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.model.redis.RedisInfo;
import com.kubo.model.redis.RedisKey;
import com.kubo.model.redis.RedisStatus;
import com.kubo.model.redis.RedisType;
import com.kubo.model.redis.RedisValue;
import com.kubo.privilege.Privilege;
import com.kubo.redis.RedisCommands;
import com.kubo.redis.RedisJson;
import com.kubo.redis.RedisService;
import com.kubo.ui.PromptCard;
import com.kubo.ui.docker.SudoPrompt;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Redis screen, in the same tiles as every other screen: the bar on top, one row of actions, then three
 * frames. On the left, what this database holds, one kind of value per station on a line, said in plain
 * words; in the middle, every key of the chosen kind; on the right, the chosen key's value. Keys are scanned
 * a page at a time (never {@code KEYS *}). When Redis is reached through a root-only container, actions ask
 * for sudo first.
 */
public final class RedisScope extends StackPane {

    /** The name this screen answers to in {@link com.kubo.command.Attention}. */
    public static final String ATTENTION = "REDIS";


    private final List<RedisService.Detected> instances;
    private RedisService redis;
    private RedisStatus status;
    private int activeInstance;
    private final Privilege privilege;
    private final RedisConsole console;
    private final java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "kubo-redis");
                thread.setDaemon(true);
                return thread;
            });

    private final RedisTrack track = new RedisTrack();
    private final com.kubo.ui.Tile keyspaceTile = new com.kubo.ui.Tile("keyspace", track).accent("tile-cyan");
    private final com.kubo.ui.Tile keysTile;
    private final com.kubo.ui.Tile valueTile;
    private final RedisKeyList keyList = new RedisKeyList();
    // Held back while another window is in front: one connection, one command at a time.
    private final Timeline poll = new Timeline(new KeyFrame(Duration.seconds(3), event -> {
        if (com.kubo.command.Attention.holds(ATTENTION)) {
            loadInfo();
        }
    }));

    // The bar and the status line
    private final HBox serviceChips = new HBox(6);
    private final Label instanceName = label("redis", "term-window");
    private final Label instanceCaption = label("", "term-window-quiet");
    private final Label loadedLabel = label("", "status-message");
    private final Label memValue = label("memory —", "status-fact");
    private final Label clientsValue = label("clients —", "status-fact");
    private final Label opsValue = label("ops/s —", "status-fact");
    private final Label databasesValue = label("", "status-fact");
    private final ComboBox<String> dbSelector = new ComboBox<>();
    private final TextField patternField = new TextField();

    // The value
    private final Label keyName = label("no key open", "inspector-key");
    private final Label typeBadge = label("", "badge");
    private final Label ttlLabel = label("—", "fetch-value");
    private final TextArea valueArea = new TextArea();
    private final VBox collectionBox = new VBox(2);
    private final ScrollPane collectionScroll = new ScrollPane(collectionBox);
    private final TextField ttlField = new TextField();
    private final Button saveButton = button("save", "primary-button");
    /** Switches a collection between the read-only rows and the JSON you can change. */
    private final Button editEntriesButton = button("edit entries", "ghost-button");
    private boolean editingEntries;
    private final Button setTtlButton = button("set ttl", "ghost-button");
    private final Button persistButton = button("never expire", "ghost-button");
    private final Button deleteButton = button("delete", "danger-button");
    private final Button cloneButton = button("clone", "ghost-button");
    private final Button renameButton = button("rename", "ghost-button");
    private final Button exportButton = button("export json", "ghost-button");
    private final Label inspectorStatus = label("", "card-note");

    private int db;
    /** The keys Redis says this database holds, against which the loaded count is read. */
    private long total;
    private long cursor;
    private String pattern = "*";
    private boolean loadingKeys;
    private final List<RedisKey> keys = new ArrayList<>();
    private RedisKey selected;
    private RedisValue selectedValue;
    private StackPane overlay;
    private ContextMenu keyMenu;
    /** Told once, when the first page of keys is on screen. */
    private Runnable onFirstRead = () -> { };
    /** Holds the root button once the window hands over the session shared by every screen. */
    private final HBox rootSlot = new HBox();

    public RedisScope(RedisService redis, RedisStatus status, Privilege privilege) {
        this(redis, status, privilege, new CommandService());
    }

    public RedisScope(RedisService redis, RedisStatus status, Privilege privilege, CommandService commands) {
        this(List.of(new RedisService.Detected(redis, status)), privilege, commands);
    }

    public RedisScope(List<RedisService.Detected> instances, Privilege privilege, CommandService commands) {
        this(instances, privilege, commands, false);
    }

    /**
     * Builds the screen without a live service, for previews.
     */
    RedisScope(RedisStatus status) {
        this(List.of(new RedisService.Detected(null, status)), null, new CommandService(), false);
    }

    private RedisScope(List<RedisService.Detected> instances, Privilege privilege, CommandService commands,
                       boolean unused) {
        if (instances == null || instances.isEmpty()) {
            throw new IllegalArgumentException("At least one Redis service is required");
        }
        this.instances = List.copyOf(instances);
        this.redis = instances.get(0).service();
        this.status = instances.get(0).status();
        this.privilege = privilege;
        this.console = new RedisConsole(commands);
        getStyleClass().add("scope-view");

        track.setOnSelect(this::openKey);
        track.setOnNeedMore(this::loadMore);
        track.setOnTypeSelect(this::showType);
        track.setOnMenu(request -> showKeyMenu(request.key(), request.screenX(), request.screenY()));
        keyList.setOnOpen(this::openKey);
        keyList.setOnMenu((key, event) -> {
            openKey(key);
            showKeyMenu(key, event.getScreenX(), event.getScreenY());
        });
        console.setOnRedisCommand(this::runRedisCli);

        for (int i = 0; i < 16; i++) {
            dbSelector.getItems().add("db" + i);
        }
        dbSelector.setValue("db0");
        dbSelector.getStyleClass().add("redis-db-selector");
        dbSelector.valueProperty().addListener((observable, before, value) -> {
            if (value == null) {
                return;
            }
            int chosen = Integer.parseInt(value.substring(2));
            if (chosen != db) {
                db = chosen;
                reload();
            }
        });
        buildServiceChips();
        applyInstance();

        keysTile = new com.kubo.ui.Tile("keys", keyList).accent("tile-violet");
        valueTile = new com.kubo.ui.Tile("value", buildInspector());
        keyspaceTile.setMinWidth(340);
        keyspaceTile.setPrefWidth(420);
        HBox.setHgrow(keyspaceTile, Priority.ALWAYS);
        keysTile.setMinWidth(220);
        keysTile.setPrefWidth(300);
        keysTile.setMaxWidth(300);
        valueTile.setMinWidth(300);
        valueTile.setPrefWidth(400);
        valueTile.setMaxWidth(400);
        HBox frames = new HBox(18, keyspaceTile, keysTile, valueTile);
        frames.setFillHeight(true);
        VBox.setVgrow(frames, Priority.ALWAYS);
        // The console is one more frame under the others: what Kubo sends, and redis-cli to type into.
        console.hideTitle();
        com.kubo.ui.Tile consoleTile = new com.kubo.ui.Tile("console", console).accent("tile-green");
        consoleTile.setMinHeight(150);
        consoleTile.setPrefHeight(170);
        consoleTile.setMaxHeight(170);
        VBox body = new VBox(22, frames, consoleTile);
        body.getStyleClass().add("tiles");

        BorderPane layout = new BorderPane(body);
        layout.setTop(buildHeader());
        layout.setBottom(buildStatus());
        getChildren().add(layout);

        showKey(null);
        poll.setCycleCount(Timeline.INDEFINITE);
    }

    /**
     * Gives this screen the session's root state, which puts the root button in its header.
     */
    public void setRootSession(com.kubo.privilege.RootSession session) {
        rootSlot.getChildren().setAll(new com.kubo.ui.RootButton(session,
                (command, done) -> worker.execute(() -> {
                    CommandEntry entry = redis.verify(command);
                    Platform.runLater(() -> done.accept(entry.success()));
                }),
                this::showOverlay, this::hideOverlay));
    }

    public void start() {
        loadInfo();
        reload();
        poll.play();
    }

    /** Told once, when the first page of keys is on screen. */
    public void setOnFirstRead(Runnable onFirstRead) {
        this.onFirstRead = onFirstRead == null ? () -> { } : onFirstRead;
    }


    /**
     * Called when this window comes back to the front. Its poll was held back while it was behind, so it reads
     * once now rather than leaving the operator looking at a stale screen until the next tick.
     */
    public void resume() {
        loadInfo();
    }

    public void shutdown() {
        poll.stop();
        track.stop();
        console.shutdown();
        // A Redis password lives no longer than the screen that asked for it.
        instances.stream().filter(item -> item.service() != null).forEach(item -> item.service().forgetPassword());
        if (privilege != null) {
            privilege.wipe();
        }
        worker.shutdownNow();
    }

    // Reading

    private void loadInfo() {
        if (redis == null) {
            return;
        }
        RedisService source = redis;
        worker.execute(() -> {
            RedisInfo info = source.info();
            Platform.runLater(() -> {
                if (redis == source) {
                    applyInfo(info);
                }
            });
        });
    }

    private void applyInfo(RedisInfo info) {
        String version = info.version().isEmpty() ? status.version() : info.version();
        instanceCaption.setText(caption(version));
        memValue.setText("memory " + info.usedMemory() + "  peak " + info.peakMemory());
        clientsValue.setText("clients " + info.connectedClients());
        opsValue.setText(info.opsPerSec() + " ops/s");
        long total = info.keyspace().stream().filter(entry -> entry.index() == db)
                .mapToLong(RedisInfo.Db::keys).findFirst().orElse(0);
        long expiring = info.keyspace().stream().filter(entry -> entry.index() == db)
                .mapToLong(RedisInfo.Db::expires).findFirst().orElse(0);
        setTags(total, expiring, info.keyspace().size());
    }

    private String caption(String version) {
        return "redis " + (version.isEmpty() ? "—" : version) + "  ·  "
                + (status.location() == RedisStatus.Location.HOST ? "on the host" : "in a container");
    }

    /**
     * How much there is: on the keyspace frame, and in the status line how much of it is on screen.
     */
    private void setTags(long total, long expiring, int databases) {
        keyspaceTile.setTag(total + (total == 1 ? " key" : " keys") + " in db" + db
                + (expiring > 0 ? "  ·  " + expiring + " expire" : ""));
        this.total = total;
        showLoaded();
        databasesValue.setText(databases + (databases == 1 ? " database in use" : " databases in use"));
    }

    /** How much of the keyspace is on screen, said again every time a page of keys comes in. */
    private void showLoaded() {
        loadedLabel.setText(total > keys.size()
                ? keys.size() + " of " + total + " keys loaded, scan further for more"
                : keys.size() + (keys.size() == 1 ? " key loaded" : " keys loaded"));
    }

    private void applyInstance() {
        instanceName.setText(status.location() == RedisStatus.Location.HOST ? "redis" : status.where());
        instanceCaption.setText(caption(status.version()));
        setTags(0, 0, 1);
        for (int i = 0; i < serviceChips.getChildren().size(); i++) {
            Button chip = (Button) serviceChips.getChildren().get(i);
            chip.getStyleClass().remove("service-chip-active");
            if (i == activeInstance) {
                chip.getStyleClass().add("service-chip-active");
            }
        }
    }

    private void buildServiceChips() {
        serviceChips.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < instances.size(); i++) {
            RedisStatus each = instances.get(i).status();
            Button chip = new Button(each.location() == RedisStatus.Location.HOST ? "host" : each.where());
            chip.getStyleClass().add("service-chip");
            chip.setFocusTraversable(false);
            int index = i;
            chip.setOnAction(event -> selectService(index));
            serviceChips.getChildren().add(chip);
        }
        serviceChips.setVisible(instances.size() > 1);
        serviceChips.setManaged(instances.size() > 1);
    }

    private void selectService(int index) {
        if (index < 0 || index >= instances.size() || index == activeInstance) {
            return;
        }
        activeInstance = index;
        redis = instances.get(index).service();
        status = instances.get(index).status();
        db = 0;
        dbSelector.setValue("db0");
        cursor = 0;
        loadingKeys = false;
        keys.clear();
        track.setMemoryUsage(Map.of(), false);
        keyList.setMemoryUsage(Map.of());
        showKey(null);
        applyInstance();
        loadInfo();
        reload();
    }

    /**
     * Starts a fresh scan of the current database and pattern.
     */
    private void reload() {
        if (redis == null) {
            return;
        }
        keys.clear();
        track.setMemoryUsage(Map.of(), false);
        keyList.setMemoryUsage(Map.of());
        cursor = 0;
        loadingKeys = false;
        showKey(null);
        track.setKeys(List.of(), true);
        track.setLoading(true);
        showType(track.selectedType());
        loadPage(true);
    }

    private void loadMore() {
        if (!loadingKeys && cursor != 0) {
            loadPage(false);
        }
    }

    private void loadPage(boolean first) {
        if (redis == null || loadingKeys) {
            return;
        }
        loadingKeys = true;
        track.setLoading(true);
        long fromCursor = first ? 0 : cursor;
        String forPattern = pattern;
        int forDb = db;
        RedisService source = redis;
        int forInstance = activeInstance;
        worker.execute(() -> {
            RedisCommands.ScanPage page = source.scan(forDb, fromCursor, forPattern, RedisService.PAGE);
            Platform.runLater(() -> {
                if (forDb != db || forInstance != activeInstance || source != redis || !forPattern.equals(pattern)) {
                    return;
                }
                loadingKeys = false;
                Map<String, RedisKey> merged = new LinkedHashMap<>();
                keys.forEach(key -> merged.put(key.name(), key));
                page.keys().forEach(key -> merged.put(key.name(), key));
                keys.clear();
                keys.addAll(merged.values());
                cursor = page.cursor();
                track.setKeys(keys, !page.done());
                track.setLoading(!page.done() && keys.isEmpty());
                showLoaded();
                onFirstRead.run();
                onFirstRead = () -> { };
                showType(track.selectedType());
                // Keep scanning silently until at least a screenful is gathered.
                if (!page.done() && keys.size() < 120) {
                    loadPage(false);
                }
            });
        });
    }

    /**
     * Points the dense index at one plate.
     */
    private void showType(RedisType type) {
        track.selectType(type);
        keyList.show(type, track.keysOf(type));
        if (selected != null) {
            keyList.select(selected.name());
        }
    }

    private void openKey(RedisKey key) {
        showKey(key);
        if (key != null) {
            loadValue(key);
        }
    }

    private void loadValue(RedisKey key) {
        valueArea.setText("");
        collectionBox.getChildren().clear();
        inspectorStatus.getStyleClass().setAll("label", "card-note");
        inspectorStatus.setText("reading…");
        RedisService source = redis;
        int forDb = db;
        int forInstance = activeInstance;
        worker.execute(() -> {
            RedisValue value = source.value(forDb, key);
            Platform.runLater(() -> {
                if (forInstance != activeInstance || source != redis || selected == null
                        || !selected.name().equals(key.name())) {
                    return;
                }
                selectedValue = value;
                inspectorStatus.setText("");
                showValue(value);
            });
        });
    }

    private void showValue(RedisValue value) {
        boolean string = value.type() == RedisType.STRING;
        boolean rebuildable = RedisJson.rebuildable(value.type());
        boolean asJson = !string && editingEntries && rebuildable && !value.failed();
        valueArea.setVisible(string || asJson);
        valueArea.setManaged(string || asJson);
        collectionScroll.setVisible(!string && !asJson);
        collectionScroll.setManaged(!string && !asJson);
        saveButton.setDisable(value.failed() || (!string && !asJson));
        // Only a collection Redis can be told to rebuild is worth offering to edit.
        editEntriesButton.setVisible(!string && rebuildable && !value.failed());
        editEntriesButton.setManaged(!string && rebuildable && !value.failed());
        editEntriesButton.setText(editingEntries ? "stop editing" : "edit entries");
        if (value.failed()) {
            valueArea.setVisible(true);
            valueArea.setManaged(true);
            collectionScroll.setVisible(false);
            collectionScroll.setManaged(false);
            valueArea.setText(value.error());
            valueArea.setEditable(false);
            return;
        }
        if (string) {
            valueArea.setEditable(true);
            valueArea.setText(value.text());
        } else if (asJson) {
            valueArea.setEditable(true);
            valueArea.setText(RedisJson.writeEntries(value));
            inspectorStatus.getStyleClass().setAll("label", "card-note");
            inspectorStatus.setText(value.truncated()
                    ? "only the first entries were read. saving would replace the key with just these, "
                            + "so stop editing unless that is what you want."
                    : "saving rewrites the whole key from this json and keeps its ttl.");
        } else {
            collectionBox.getChildren().clear();
            for (RedisValue.Entry entry : value.entries()) {
                Label first = label(entry.first(), "redis-entry-key");
                first.setMinWidth(116);
                Label second = label(entry.second(), "redis-entry-value");
                second.setWrapText(true);
                HBox.setHgrow(second, Priority.ALWAYS);
                HBox row = value.type().isPaired()
                        ? new HBox(12, first, second)
                        : new HBox(label("·", "mono-muted"), second);
                row.setAlignment(Pos.TOP_LEFT);
                collectionBox.getChildren().add(row);
            }
            if (value.truncated()) {
                collectionBox.getChildren().add(label("… the first " + value.entries().size() + " entries",
                        "metric-label"));
            }
        }
    }

    private void showKey(RedisKey key) {
        selected = key;
        selectedValue = null;
        // A new key starts in read mode, never with the last key's editor still open.
        editingEntries = false;
        inspectorStatus.setText("");
        track.select(key == null ? null : key.name());
        keyList.select(key == null ? null : key.name());
        if (key == null) {
            keyName.setText("no key open");
            typeBadge.setVisible(false);
            typeBadge.setManaged(false);
            ttlLabel.setText("—");
            valueArea.clear();
            collectionBox.getChildren().clear();
            for (Button each : new Button[]{saveButton, setTtlButton, persistButton, deleteButton,
                    cloneButton, renameButton, exportButton}) {
                each.setDisable(true);
            }
            ttlField.setDisable(true);
            editEntriesButton.setVisible(false);
            editEntriesButton.setManaged(false);
            return;
        }
        keyName.setText(key.name());
        typeBadge.setVisible(true);
        typeBadge.setManaged(true);
        typeBadge.setText(key.label().toLowerCase(Locale.ROOT));
        Color color = RedisPalette.color(key.type());
        typeBadge.setStyle("-fx-text-fill: " + RedisPalette.web(color)
                + "; -fx-border-color: " + RedisPalette.web(color) + ";");
        ttlLabel.setText(key.isPersistent() ? "never expires"
                : key.expires() ? "expires in " + RedisTrack.ttl(key.ttlSeconds()) : "—");
        for (Button each : new Button[]{setTtlButton, persistButton, deleteButton,
                cloneButton, renameButton, exportButton}) {
            each.setDisable(false);
        }
        ttlField.setDisable(false);
        persistButton.setDisable(!key.expires());
    }

    // Editing

    private void save() {
        if (selected == null || selectedValue == null) {
            return;
        }
        if (selectedValue.type() == RedisType.STRING) {
            String value = valueArea.getText();
            run("saved", () -> redis.set(db, selected.name(), value));
            return;
        }
        if (!editingEntries) {
            return;
        }
        // A collection is rewritten whole: Redis has no "save these entries" command, so the key is rebuilt from
        // the JSON in one server-side script that keeps its TTL.
        RedisValue rebuilt;
        try {
            rebuilt = RedisJson.readEntries(selectedValue.type(), valueArea.getText());
        } catch (java.io.IOException e) {
            inspectorStatus.getStyleClass().setAll("label", "card-problem");
            inspectorStatus.setText(e.getMessage());
            return;
        }
        RedisKey key = selected;
        showOverlay(new PromptCard("REDIS", "REWRITE " + key.name(),
                "Redis cannot edit part of a collection, so Kubo deletes the key and writes it again from your "
                        + "JSON, in one atomic script, keeping its TTL. Type the key name to confirm.",
                key.name(), "REWRITE", true, (typed, done) -> {
            if (!typed.strip().equals(key.name())) {
                done.accept("Type the key name exactly to confirm.");
                return;
            }
            hideOverlay();
            editingEntries = false;
            run("rewritten", () -> redis.replace(db, key.name(), rebuilt));
        }, this::hideOverlay));
    }

    private void setTtl() {
        if (selected == null) {
            return;
        }
        long seconds;
        try {
            seconds = Long.parseLong(ttlField.getText().strip());
        } catch (NumberFormatException e) {
            inspectorStatus.getStyleClass().setAll("label", "card-problem");
            inspectorStatus.setText("the ttl is a number of seconds.");
            return;
        }
        run("ttl set", () -> redis.expire(db, selected.name(), seconds));
    }

    private void persist() {
        if (selected != null) {
            run("never expires now", () -> redis.persist(db, selected.name()));
        }
    }

    private void delete() {
        if (selected != null) {
            RedisKey key = selected;
            run("deleted", () -> redis.delete(db, key.name()));
            keys.removeIf(each -> each.name().equals(key.name()));
            track.setKeys(keys, cursor != 0);
            showType(track.selectedType());
            showKey(null);
        }
    }

    private void showAddKey() {
        showKeyDialog(null);
    }

    private void showKeyDialog(RedisKeyDialog.Request preset) {
        RedisKeyDialog dialog = new RedisKeyDialog(preset, (request, done) -> runWithRoot(() -> {
            if (redis == null) {
                done.accept("No active Redis service.");
                return;
            }
            RedisService source = redis;
            int targetDb = db;
            worker.execute(() -> {
                CommandEntry result = source.create(targetDb, request.name(), request.value(), request.ttlSeconds());
                Platform.runLater(() -> {
                    if (result.success()) {
                        hideOverlay();
                        inspectorStatus.getStyleClass().setAll("label", "card-success");
                        inspectorStatus.setText("created " + request.name());
                        reload();
                    } else {
                        done.accept(problem(result));
                    }
                });
            });
        }), this::hideOverlay);
        showOverlay(dialog);
    }

    private void cloneSelected() {
        RedisKey key = selected;
        if (key == null) {
            return;
        }
        PromptCard card = new PromptCard("REDIS KEY", "CLONE KEY",
                "The source stays untouched. The destination name must not already exist.",
                key.name() + ":copy", "CLONE", false, (name, done) -> runWithRoot(() -> {
            RedisService source = redis;
            int targetDb = db;
            worker.execute(() -> {
                CommandEntry result = source.cloneKey(targetDb, key.name(), name);
                Platform.runLater(() -> finishMutation(result, "cloned to " + name, done));
            });
        }), this::hideOverlay);
        showOverlay(card);
    }

    private void renameSelected() {
        RedisKey key = selected;
        if (key == null) {
            return;
        }
        PromptCard card = new PromptCard("REDIS KEY", "RENAME KEY",
                "Redis RENAME is atomic. If the destination exists, Redis replaces it.",
                key.name(), "RENAME", false, (name, done) -> runWithRoot(() -> {
            RedisService source = redis;
            int targetDb = db;
            worker.execute(() -> {
                CommandEntry result = source.rename(targetDb, key.name(), name);
                Platform.runLater(() -> finishMutation(result, "renamed to " + name, done));
            });
        }), this::hideOverlay);
        showOverlay(card);
    }

    private void finishMutation(CommandEntry result, String success, java.util.function.Consumer<String> done) {
        if (result.success()) {
            hideOverlay();
            inspectorStatus.getStyleClass().setAll("label", "card-success");
            inspectorStatus.setText(success);
            reload();
        } else {
            done.accept(problem(result));
        }
    }

    private void exportSelected() {
        RedisKey key = selected;
        if (key == null || redis == null || getScene() == null) {
            return;
        }
        FileChooser chooser = jsonChooser("EXPORT REDIS KEY");
        chooser.setInitialFileName(safeFileName(key.name()) + ".json");
        java.io.File chosen = chooser.showSaveDialog(getScene().getWindow());
        if (chosen == null) {
            return;
        }
        Path path = chosen.toPath();
        RedisService source = redis;
        int sourceDb = db;
        runWithRoot(() -> {
            inspectorStatus.getStyleClass().setAll("label", "card-note");
            inspectorStatus.setText("exporting json…");
            worker.execute(() -> {
                String error = null;
                try {
                    RedisValue value = source.value(sourceDb, key);
                    RedisJson.write(path, sourceDb, key, value);
                } catch (IOException e) {
                    error = e.getMessage();
                }
                String outcome = error;
                Platform.runLater(() -> {
                    inspectorStatus.getStyleClass().setAll("label", outcome == null ? "card-success" : "card-problem");
                    inspectorStatus.setText(outcome == null ? "exported to " + path : outcome);
                });
            });
        });
    }

    private void importJson() {
        if (getScene() == null) {
            return;
        }
        java.io.File chosen = jsonChooser("IMPORT REDIS KEY").showOpenDialog(getScene().getWindow());
        if (chosen == null) {
            return;
        }
        worker.execute(() -> {
            try {
                RedisJson.Document document = RedisJson.read(chosen.toPath());
                Platform.runLater(() -> {
                    int targetDb = Math.max(0, Math.min(15, document.database()));
                    dbSelector.setValue("db" + targetDb);
                    RedisKey key = document.key();
                    showKeyDialog(new RedisKeyDialog.Request(key.name(), key.type(), key.ttlSeconds(),
                            document.value()));
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    inspectorStatus.getStyleClass().setAll("label", "card-problem");
                    inspectorStatus.setText("import failed: " + e.getMessage());
                });
            }
        });
    }

    private void confirmFlush(boolean all) {
        String expected = all ? "ALL" : "DB" + db;
        PromptCard card = new PromptCard("DANGER ZONE", all ? "FLUSH ALL DATABASES" : "FLUSH " + expected,
                "This removes data asynchronously and cannot be undone. Type " + expected + " to confirm.",
                expected, all ? "FLUSH ALL" : "FLUSH DB", true, (typed, done) -> {
            if (!typed.equals(expected)) {
                done.accept("Type " + expected + " exactly to confirm.");
                return;
            }
            runWithRoot(() -> {
                RedisService source = redis;
                int targetDb = db;
                worker.execute(() -> {
                    CommandEntry result = all ? source.flushAll() : source.flushDb(targetDb);
                    Platform.runLater(() -> finishMutation(result, expected.toLowerCase(Locale.ROOT) + " flushed", done));
                });
            });
        }, this::hideOverlay);
        showOverlay(card);
    }

    private void measureMemory() {
        if (redis == null || keys.isEmpty()) {
            inspectorStatus.getStyleClass().setAll("label", "card-problem");
            inspectorStatus.setText("no loaded keys to measure.");
            return;
        }
        RedisService source = redis;
        int sourceDb = db;
        List<String> names = keys.stream().map(RedisKey::name).toList();
        runWithRoot(() -> {
            inspectorStatus.getStyleClass().setAll("label", "card-note");
            inspectorStatus.setText("measuring " + names.size() + " loaded keys…");
            worker.execute(() -> {
                Map<String, Long> measured = new LinkedHashMap<>();
                for (int from = 0; from < names.size(); from += 100) {
                    measured.putAll(source.memoryUsage(sourceDb,
                            names.subList(from, Math.min(names.size(), from + 100))));
                }
                Platform.runLater(() -> {
                    track.setMemoryUsage(measured, true);
                    keyList.setMemoryUsage(measured);
                    inspectorStatus.getStyleClass().setAll("label", "card-success");
                    inspectorStatus.setText("weighed " + measured.size() + " keys, the sizes are beside each name");
                });
            });
        });
    }

    private void showSlowlog() {
        showDiagnostic("SLOWLOG · LAST 10", false);
    }

    private void showMonitor() {
        showDiagnostic("LIVE TRAFFIC · 6 SECONDS", true);
    }

    private void showDiagnostic(String title, boolean monitor) {
        if (redis == null) {
            return;
        }
        inspectorStatus.getStyleClass().setAll("label", "card-note");
        inspectorStatus.setText(monitor ? "watching live traffic for 6 seconds…" : "reading the slowlog…");
        runWithRoot(() -> {
            RedisService source = redis;
            int sourceDb = db;
            worker.execute(() -> {
                CommandEntry result = monitor ? source.monitor(sourceDb, 6) : source.slowlog();
                Platform.runLater(() -> {
                    inspectorStatus.setText("");
                    String text = !result.output().isBlank() ? result.output() : result.error();
                    showOverlay(PromptCard.output("REDIS DIAGNOSTICS", title, text, this::hideOverlay));
                });
            });
        });
    }

    private void runRedisCli(String command) {
        if (redis == null) {
            console.completeRedis(command, null);
            return;
        }
        runWithRoot(() -> {
            RedisService source = redis;
            int sourceDb = db;
            worker.execute(() -> {
                CommandEntry result;
                try {
                    result = source.raw(sourceDb, command);
                } catch (IllegalArgumentException e) {
                    result = null;
                }
                console.completeRedis(command, result);
                Platform.runLater(() -> {
                    loadInfo();
                    reload();
                });
            });
        }, () -> console.completeRedis(command, null));
    }

    private void showKeyMenu(RedisKey key, double screenX, double screenY) {
        if (keyMenu != null) {
            keyMenu.hide();
        }
        MenuItem open = item("open", event -> openKey(key));
        MenuItem clone = item("clone", event -> cloneSelected());
        MenuItem rename = item("rename", event -> renameSelected());
        MenuItem export = item("export json", event -> exportSelected());
        MenuItem remove = item("delete", event -> delete());
        remove.getStyleClass().add("redis-menu-danger");
        keyMenu = new ContextMenu(open, new SeparatorMenuItem(), clone, rename, export,
                new SeparatorMenuItem(), remove);
        keyMenu.getStyleClass().add("redis-context-menu");
        keyMenu.show(this, screenX, screenY);
    }

    private static MenuItem item(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(action);
        return item;
    }

    private static FileChooser jsonChooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Redis JSON", "*.json"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        return chooser;
    }

    private static String safeFileName(String key) {
        String safe = key.replaceAll("[\\\\/:*?\"<>|]", "_").strip();
        return safe.isEmpty() ? "redis-key" : safe;
    }

    private void run(String done, java.util.function.Supplier<CommandEntry> action) {
        runWithRoot(() -> {
            inspectorStatus.getStyleClass().setAll("label", "card-note");
            inspectorStatus.setText("WORKING…");
            worker.execute(() -> {
                CommandEntry result = action.get();
                Platform.runLater(() -> {
                    if (result.success()) {
                        inspectorStatus.getStyleClass().setAll("label", "card-success");
                        inspectorStatus.setText(done);
                        loadInfo();
                    } else {
                        inspectorStatus.getStyleClass().setAll("label", "card-problem");
                        inspectorStatus.setText(problem(result));
                    }
                });
            });
        });
    }

    // Root

    private void runWithRoot(Runnable action) {
        runWithRoot(action, () -> {
        });
    }

    private void runWithRoot(Runnable action, Runnable cancelled) {
        if (redis != null && redis.needsRoot() && privilege != null && privilege.needsPassword()) {
            SudoPrompt prompt = new SudoPrompt(privilege,
                    (command, doneCallback) -> worker.execute(() -> {
                        CommandEntry entry = redis.verify(command);
                        Platform.runLater(() -> doneCallback.accept(entry.success()));
                    }),
                    () -> {
                        hideOverlay();
                        action.run();
                    },
                    () -> {
                        hideOverlay();
                        cancelled.run();
                    });
            showOverlay(prompt);
        } else {
            action.run();
        }
    }

    private void showOverlay(Region card) {
        hideOverlay();
        if (keyMenu != null) {
            keyMenu.hide();
        }
        Region scrim = new Region();
        scrim.getStyleClass().add("picker-scrim");
        overlay = new StackPane(scrim, card);
        overlay.setMinSize(0, 0);
        getChildren().add(overlay);
        card.setOpacity(0);
        card.setTranslateY(10);
        javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(Duration.millis(150), card);
        fade.setToValue(1);
        javafx.animation.TranslateTransition slide = new javafx.animation.TranslateTransition(Duration.millis(180),
                card);
        slide.setToY(0);
        new javafx.animation.ParallelTransition(fade, slide).play();
    }

    private void hideOverlay() {
        if (overlay != null) {
            getChildren().remove(overlay);
            overlay = null;
        }
    }

    // Building

    /**
     * The bar every screen has: the block, which Redis this is, and the root switch. Under it, the actions,
     * in the same lower-case row the server screen uses for its windows.
     */
    private VBox buildHeader() {
        Label wordmark = com.kubo.ui.RootButton.wordmark();
        instanceName.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        serviceChips.setAlignment(Pos.CENTER_LEFT);
        rootSlot.setAlignment(Pos.CENTER_LEFT);
        Button help = new Button("help");
        help.getStyleClass().add("ghost-button");
        help.setFocusTraversable(false);
        help.setOnAction(event -> showOverlay(new RedisHelp(this::hideOverlay)));
        HBox bar = new HBox(14, wordmark, instanceName, instanceCaption, serviceChips, spacer, help, rootSlot);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("term-bar");
        return new VBox(bar, buildActions());
    }

    /**
     * What you can do, left to right: where you look, what you add, what you measure, and on the far right
     * what destroys.
     */
    private HBox buildActions() {
        dbSelector.setMinWidth(76);
        patternField.getStyleClass().addAll("card-field", "redis-pattern");
        patternField.setPromptText("match  user:*");
        patternField.setPrefColumnCount(13);
        patternField.setOnAction(event -> {
            pattern = patternField.getText().isBlank() ? "*" : patternField.getText().strip();
            reload();
        });

        Button refresh = action("scan again", event -> {
            loadInfo();
            reload();
        });
        Button add = action("new key", event -> showAddKey());
        add.getStyleClass().add("redis-action-lit");
        Button importButton = action("import json", event -> importJson());
        Button heavy = action("weigh keys", event -> measureMemory());
        Button slowlog = action("slowlog", event -> showSlowlog());
        Button monitor = action("live traffic", event -> showMonitor());
        Button flushDb = action("flush db", event -> confirmFlush(false));
        Button flushAll = action("flush all", event -> confirmFlush(true));
        flushDb.getStyleClass().add("redis-action-danger");
        flushAll.getStyleClass().add("redis-action-danger");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        patternField.setMinWidth(110);
        HBox actions = new HBox(16, dbSelector, patternField, refresh, add, importButton, heavy, slowlog, monitor,
                spacer, flushDb, flushAll);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().addAll("home-nav", "redis-actions");
        return actions;
    }

    /**
     * The chosen key: its name, its kind, when it expires, its value, and what can be done to it.
     */
    private VBox buildInspector() {
        keyName.setWrapText(true);
        keyName.setMinHeight(Region.USE_PREF_SIZE);
        typeBadge.setMinWidth(Region.USE_PREF_SIZE);
        Region nameSpacer = new Region();
        HBox.setHgrow(nameSpacer, Priority.ALWAYS);
        HBox nameRow = new HBox(10, keyName, nameSpacer, typeBadge);
        nameRow.setAlignment(Pos.TOP_LEFT);

        valueArea.getStyleClass().add("redis-value");
        valueArea.setWrapText(true);
        valueArea.setPrefRowCount(6);
        VBox.setVgrow(valueArea, Priority.ALWAYS);
        collectionScroll.setFitToWidth(true);
        collectionScroll.getStyleClass().add("redis-collection");
        collectionScroll.setVisible(false);
        collectionScroll.setManaged(false);
        VBox.setVgrow(collectionScroll, Priority.ALWAYS);
        collectionBox.setPadding(new Insets(6, 0, 6, 0));

        saveButton.setOnAction(event -> save());
        editEntriesButton.setOnAction(event -> {
            editingEntries = !editingEntries;
            if (selectedValue != null) {
                showValue(selectedValue);
            }
        });
        deleteButton.setOnAction(event -> delete());
        cloneButton.setOnAction(event -> cloneSelected());
        renameButton.setOnAction(event -> renameSelected());
        exportButton.setOnAction(event -> exportSelected());
        persistButton.setOnAction(event -> persist());
        setTtlButton.setOnAction(event -> setTtl());
        ttlField.getStyleClass().add("card-field");
        ttlField.setPromptText("seconds");
        ttlField.setPrefColumnCount(7);
        ttlField.setMinWidth(Region.USE_PREF_SIZE);
        ttlField.setOnAction(event -> setTtl());

        Label ttlKey = label("ttl", "fetch-key");
        ttlKey.setMinWidth(Region.USE_PREF_SIZE);
        ttlLabel.setMinWidth(Region.USE_PREF_SIZE);
        HBox ttlNow = new HBox(12, ttlKey, ttlLabel);
        ttlNow.setAlignment(Pos.CENTER_LEFT);
        HBox ttlChange = new HBox(14, ttlField, setTtlButton, persistButton);
        ttlChange.setAlignment(Pos.CENTER_LEFT);
        VBox ttlRow = new VBox(6, ttlNow, ttlChange);

        inspectorStatus.setWrapText(true);
        inspectorStatus.setMinHeight(Region.USE_PREF_SIZE);
        FlowPane tools = new FlowPane(16, 8, editEntriesButton, cloneButton, renameButton, exportButton);
        tools.setAlignment(Pos.CENTER_LEFT);
        Region actionSpacer = new Region();
        HBox.setHgrow(actionSpacer, Priority.ALWAYS);
        HBox actions = new HBox(10, saveButton, actionSpacer, deleteButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        Region rule = new Region();
        rule.getStyleClass().add("fetch-rule");
        rule.setMinHeight(1);
        rule.setMaxHeight(1);
        VBox column = new VBox(10, nameRow, rule, ttlRow, valueArea, collectionScroll, tools, actions,
                inspectorStatus);
        column.getStyleClass().add("redis-inspector");
        return column;
    }

    /**
     * The status line: the service in its block, how much of the keyspace is on screen, and the figures
     * Redis reports about itself.
     */
    private HBox buildStatus() {
        Label mode = label("redis", "status-mode");
        mode.getStyleClass().add("status-mode-live");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox status = new HBox(14, mode, loadedLabel, spacer, memValue, clientsValue, opsValue, databasesValue);
        status.setAlignment(Pos.CENTER_LEFT);
        status.getStyleClass().add("status-line");
        return status;
    }

    private static Button action(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button button = new Button(text);
        button.getStyleClass().add("ghost-button");
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(action);
        return button;
    }

    private static String problem(CommandEntry entry) {
        String error = entry.error().strip();
        if (!error.isEmpty()) {
            return error.lines().findFirst().orElse("");
        }
        return entry.ran() ? "failed with exit code " + entry.exitCode() : "could not run";
    }

    private static Button button(String text, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().add(styleClass);
        if (styleClass.equals("primary-button") || styleClass.equals("danger-button")) {
            button.getStyleClass().add("compact-button");
        }
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    // Previews reach in through these.

    RedisTrack track() {
        return track;
    }

    void showForPreview(RedisInfo info, List<RedisKey> sampleKeys, RedisKey select, RedisValue value) {
        keys.addAll(sampleKeys);
        applyInfo(info);
        track.setKeys(keys, false);
        showType(select == null ? RedisType.STRING : select.type());
        if (select != null) {
            showKey(select);
            selectedValue = value;
            showValue(value);
            inspectorStatus.setText("");
        }
    }
}

