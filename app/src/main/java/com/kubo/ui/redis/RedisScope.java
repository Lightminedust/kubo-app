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
 * The Redis screen, laid out as a technical sheet: a masthead naming the service and its live figures, one rail of
 * grouped actions, then the keyspace itself — a stack of lit plates, one per data type, with the dense index of the
 * chosen plate on its left and the value inspector on its right. Keys are scanned a page at a time (never
 * {@code KEYS *}). When Redis is reached through a root-only container, actions ask for sudo first.
 */
public final class RedisScope extends StackPane {

    private static final Color MAGENTA = Color.web("#ff3fd8");
    private static final Color CYAN = Color.web("#35e8ff");
    private static final Color GREEN = Color.web("#35e0a1");

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

    private final RedisStack stack = new RedisStack();
    private final RedisKeyList keyList = new RedisKeyList();
    private final Timeline poll = new Timeline(new KeyFrame(Duration.seconds(3), event -> loadInfo()));

    // Masthead
    private final HBox serviceChips = new HBox(6);
    private final Label instanceName = label("REDIS", "sheet-title");
    private final Label instanceCaption = label("", "sheet-caption");
    private final HBox tagRow = new HBox(6);
    private final Label memValue = label("—", "readout-value");
    private final Label peakValue = label("PEAK —", "readout-note");
    private final Label clientsValue = label("—", "readout-value");
    private final Label opsValue = label("—", "readout-value");
    private final ComboBox<String> dbSelector = new ComboBox<>();
    private final TextField patternField = new TextField();

    // Inspector
    private final Label keyName = label("NO KEY SELECTED", "inspector-key");
    private final Label typeBadge = label("", "badge");
    private final Label ttlLabel = label("—", "readout-value-small");
    private final TextArea valueArea = new TextArea();
    private final VBox collectionBox = new VBox(2);
    private final ScrollPane collectionScroll = new ScrollPane(collectionBox);
    private final TextField ttlField = new TextField();
    private final Button saveButton = button("SAVE VALUE", "primary-button");
    /** Switches a collection between the read-only rows and the JSON you can change. */
    private final Button editEntriesButton = button("EDIT ENTRIES", "rail-button");
    private boolean editingEntries;
    private final Button setTtlButton = button("SET TTL", "rail-button");
    private final Button persistButton = button("PERSIST", "rail-button");
    private final Button deleteButton = button("DELETE", "danger-button");
    private final Button cloneButton = button("CLONE", "rail-button");
    private final Button renameButton = button("RENAME", "rail-button");
    private final Button exportButton = button("EXPORT JSON", "rail-button");
    private final Label inspectorStatus = label("", "card-note");

    private int db;
    private long cursor;
    private String pattern = "*";
    private boolean loadingKeys;
    private final List<RedisKey> keys = new ArrayList<>();
    private RedisKey selected;
    private RedisValue selectedValue;
    private StackPane overlay;
    private ContextMenu keyMenu;
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

        stack.setOnSelect(this::openKey);
        stack.setOnNeedMore(this::loadMore);
        stack.setOnTypeSelect(this::showType);
        stack.setOnMenu(request -> showKeyMenu(request.key(), request.screenX(), request.screenY()));
        keyList.setOnOpen(this::openKey);
        keyList.setOnMenu((key, event) -> {
            openKey(key);
            showKeyMenu(key, event.getScreenX(), event.getScreenY());
        });
        console.setOnRedisCommand(this::runRedisCli);

        for (int i = 0; i < 16; i++) {
            dbSelector.getItems().add("DB" + i);
        }
        dbSelector.setValue("DB0");
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

        BorderPane body = new BorderPane(stack);
        body.setLeft(keyList);
        body.setRight(buildInspector());

        BorderPane layout = new BorderPane(body);
        layout.setTop(buildMasthead());
        layout.setBottom(console);
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

    public void shutdown() {
        poll.stop();
        stack.stop();
        console.shutdown();
        // A Redis password lives no longer than the screen that asked for it.
        instances.forEach(item -> item.service().forgetPassword());
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
        instanceCaption.setText(("REDIS " + (version.isEmpty() ? "—" : version) + "   ·   "
                + (status.location() == RedisStatus.Location.HOST ? "HOST PROCESS" : "CONTAINER")
                + "   ·   DB" + db).toUpperCase(Locale.ROOT));
        memValue.setText(info.usedMemory());
        peakValue.setText("PEAK " + info.peakMemory());
        clientsValue.setText(String.valueOf(info.connectedClients()));
        opsValue.setText(String.valueOf(info.opsPerSec()));
        long total = info.keyspace().stream().filter(entry -> entry.index() == db)
                .mapToLong(RedisInfo.Db::keys).findFirst().orElse(0);
        long expiring = info.keyspace().stream().filter(entry -> entry.index() == db)
                .mapToLong(RedisInfo.Db::expires).findFirst().orElse(0);
        setTags(total, expiring, info.keyspace().size());
    }

    /**
     * The bracketed facts under the title.
     */
    private void setTags(long total, long expiring, int databases) {
        tagRow.getChildren().setAll(
                tag(total + " KEYS IN DB" + db),
                tag(expiring + " WITH A TTL"),
                tag(databases + (databases == 1 ? " DATABASE IN USE" : " DATABASES IN USE")),
                tag(keys.size() + " LOADED"));
    }

    private void applyInstance() {
        instanceName.setText(status.location() == RedisStatus.Location.HOST ? "HOST REDIS" : status.where());
        instanceCaption.setText(("REDIS " + (status.version().isEmpty() ? "—" : status.version()) + "   ·   "
                + (status.location() == RedisStatus.Location.HOST ? "HOST PROCESS" : "CONTAINER")
                + "   ·   DB" + db).toUpperCase(Locale.ROOT));
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
            Button chip = new Button((i + 1) + " · " + (each.location() == RedisStatus.Location.HOST
                    ? "HOST" : each.where().toUpperCase(Locale.ROOT)));
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
        dbSelector.setValue("DB0");
        cursor = 0;
        loadingKeys = false;
        keys.clear();
        stack.setMemoryUsage(Map.of(), false);
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
        stack.setMemoryUsage(Map.of(), false);
        keyList.setMemoryUsage(Map.of());
        cursor = 0;
        loadingKeys = false;
        showKey(null);
        stack.setKeys(List.of(), true);
        stack.setLoading(true);
        showType(stack.selectedType());
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
        stack.setLoading(true);
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
                stack.setKeys(keys, !page.done());
                stack.setLoading(!page.done() && keys.isEmpty());
                showType(stack.selectedType());
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
        stack.selectType(type);
        keyList.show(type, stack.keysOf(type));
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
        inspectorStatus.setText("READING…");
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
        editEntriesButton.setText(editingEntries ? "STOP EDITING" : "EDIT ENTRIES");
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
                    ? "ONLY THE FIRST ENTRIES WERE READ. SAVING WOULD REPLACE THE KEY WITH JUST THESE — "
                            + "STOP EDITING UNLESS THAT IS WHAT YOU WANT."
                    : "SAVING REWRITES THE WHOLE KEY FROM THIS JSON, KEEPING ITS TTL.");
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
                collectionBox.getChildren().add(label("… FIRST " + value.entries().size() + " ENTRIES",
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
        stack.select(key == null ? null : key.name());
        keyList.select(key == null ? null : key.name());
        if (key == null) {
            keyName.setText("NO KEY SELECTED");
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
            return;
        }
        keyName.setText(key.name());
        typeBadge.setVisible(true);
        typeBadge.setManaged(true);
        typeBadge.setText(key.label());
        Color color = RedisPalette.color(key.type());
        typeBadge.setStyle("-fx-text-fill: " + RedisPalette.web(color)
                + "; -fx-border-color: " + RedisPalette.web(color) + ";");
        ttlLabel.setText(key.isPersistent() ? "PERSISTENT" : key.expires() ? key.ttlSeconds() + " S" : "—");
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
            run("SAVED", () -> redis.set(db, selected.name(), value));
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
            inspectorStatus.setText(e.getMessage().toUpperCase(Locale.ROOT));
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
            run("REWRITTEN", () -> redis.replace(db, key.name(), rebuilt));
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
            inspectorStatus.setText("TTL MUST BE A NUMBER OF SECONDS.");
            return;
        }
        run("TTL SET", () -> redis.expire(db, selected.name(), seconds));
    }

    private void persist() {
        if (selected != null) {
            run("NOW PERSISTENT", () -> redis.persist(db, selected.name()));
        }
    }

    private void delete() {
        if (selected != null) {
            RedisKey key = selected;
            run("DELETED", () -> redis.delete(db, key.name()));
            keys.removeIf(each -> each.name().equals(key.name()));
            stack.setKeys(keys, cursor != 0);
            showType(stack.selectedType());
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
                        inspectorStatus.setText("CREATED  ·  " + request.name());
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
                Platform.runLater(() -> finishMutation(result, "CLONED  ·  " + name, done));
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
                Platform.runLater(() -> finishMutation(result, "RENAMED  ·  " + name, done));
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
            inspectorStatus.setText("EXPORTING JSON…");
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
                    inspectorStatus.setText(outcome == null ? "EXPORTED  ·  " + path : outcome);
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
                    dbSelector.setValue("DB" + targetDb);
                    RedisKey key = document.key();
                    showKeyDialog(new RedisKeyDialog.Request(key.name(), key.type(), key.ttlSeconds(),
                            document.value()));
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    inspectorStatus.getStyleClass().setAll("label", "card-problem");
                    inspectorStatus.setText("IMPORT FAILED  ·  " + e.getMessage());
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
                    Platform.runLater(() -> finishMutation(result, expected + " FLUSHED ASYNC", done));
                });
            });
        }, this::hideOverlay);
        showOverlay(card);
    }

    private void measureMemory() {
        if (redis == null || keys.isEmpty()) {
            inspectorStatus.getStyleClass().setAll("label", "card-problem");
            inspectorStatus.setText("NO LOADED KEYS TO MEASURE.");
            return;
        }
        RedisService source = redis;
        int sourceDb = db;
        List<String> names = keys.stream().map(RedisKey::name).toList();
        runWithRoot(() -> {
            inspectorStatus.getStyleClass().setAll("label", "card-note");
            inspectorStatus.setText("MEASURING " + names.size() + " LOADED KEYS…");
            worker.execute(() -> {
                Map<String, Long> measured = new LinkedHashMap<>();
                for (int from = 0; from < names.size(); from += 100) {
                    measured.putAll(source.memoryUsage(sourceDb,
                            names.subList(from, Math.min(names.size(), from + 100))));
                }
                Platform.runLater(() -> {
                    stack.setMemoryUsage(measured, true);
                    keyList.setMemoryUsage(measured);
                    inspectorStatus.getStyleClass().setAll("label", "card-success");
                    inspectorStatus.setText("WEIGHED  ·  " + measured.size() + " KEYS  ·  BARS SHOW SIZE");
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
        inspectorStatus.setText(monitor ? "MONITORING LIVE TRAFFIC FOR 6 SECONDS…" : "READING SLOWLOG…");
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
        MenuItem open = item("OPEN IN INSPECTOR", event -> openKey(key));
        MenuItem clone = item("CLONE KEY", event -> cloneSelected());
        MenuItem rename = item("RENAME KEY", event -> renameSelected());
        MenuItem export = item("EXPORT JSON", event -> exportSelected());
        MenuItem remove = item("DELETE", event -> delete());
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
     * The masthead: what this service is, its live figures, and the one rail of actions.
     */
    private VBox buildMasthead() {
        Label wordmark = com.kubo.ui.RootButton.wordmark();
        Label mark = label("R", "sheet-mark");
        Label kicker = label("REDIS.SCOPE", "series");
        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);

        Button zoomOut = railButton("−", event -> stack.zoomOut());
        Button fit = railButton("FIT", event -> stack.fit());
        Button zoomIn = railButton("+", event -> stack.zoomIn());
        HBox camera = new HBox(2, zoomOut, fit, zoomIn);
        camera.getStyleClass().add("zoom-bar");
        camera.setAlignment(Pos.CENTER_LEFT);

        HBox identity = new HBox(12, wordmark, mark, kicker, serviceChips, topSpacer, rootSlot, camera);
        identity.setAlignment(Pos.CENTER_LEFT);

        instanceName.setMinWidth(Region.USE_PREF_SIZE);
        VBox naming = new VBox(2, instanceName, instanceCaption);
        tagRow.setAlignment(Pos.CENTER_LEFT);
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);

        HBox readout = new HBox(0, readout("MEMORY", memValue, MAGENTA, peakValue),
                rule(), readout("CLIENTS", clientsValue, CYAN, null),
                rule(), readout("OPS / SEC", opsValue, GREEN, null));
        readout.setAlignment(Pos.CENTER_LEFT);
        readout.getStyleClass().add("readout-bar");

        HBox title = new HBox(26, naming, tagRow, titleSpacer, readout);
        title.setAlignment(Pos.CENTER_LEFT);

        VBox masthead = new VBox(12, identity, title, buildRail());
        masthead.getStyleClass().add("sheet-masthead");
        masthead.setPadding(new Insets(16, 34, 0, 26));
        return masthead;
    }

    /**
     * One rail, grouped: where you are, what you add, what you inspect, what destroys.
     */
    private HBox buildRail() {
        dbSelector.setMinWidth(78);
        patternField.getStyleClass().add("card-field");
        patternField.setPromptText("match  user:*");
        patternField.setPrefColumnCount(14);
        patternField.setOnAction(event -> {
            pattern = patternField.getText().isBlank() ? "*" : patternField.getText().strip();
            reload();
        });

        Button refresh = railButton("↻ SCAN", event -> {
            loadInfo();
            reload();
        });
        Button add = button("+ NEW KEY", "primary-button");
        add.setOnAction(event -> showAddKey());
        Button importButton = railButton("IMPORT JSON", event -> importJson());
        Button heavy = railButton("WEIGH KEYS", event -> measureMemory());
        Button slowlog = railButton("SLOWLOG", event -> showSlowlog());
        Button monitor = railButton("LIVE TRAFFIC", event -> showMonitor());
        Button cli = railButton("REDIS CLI", event -> console.showRedisCli());
        Button flushDb = button("FLUSH DB", "danger-button");
        flushDb.setOnAction(event -> confirmFlush(false));
        Button flushAll = button("FLUSH ALL", "danger-button");
        flushAll.setOnAction(event -> confirmFlush(true));
        Button help = railButton("?", event -> showOverlay(new RedisHelp(this::hideOverlay)));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox rail = new HBox(8, label("DB", "rail-label"), dbSelector, patternField, refresh,
                separator(), add, importButton,
                separator(), heavy, slowlog, monitor, cli,
                spacer, flushDb, flushAll, separator(), help);
        rail.setAlignment(Pos.CENTER_LEFT);
        rail.getStyleClass().add("sheet-rail");
        rail.setPadding(new Insets(0, 0, 10, 0));
        return rail;
    }

    private VBox buildInspector() {
        keyName.setWrapText(true);
        HBox nameRow = new HBox(10, keyName);
        nameRow.setAlignment(Pos.CENTER_LEFT);

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
        ttlField.setOnAction(event -> setTtl());

        Region ttlSpacer = new Region();
        HBox.setHgrow(ttlSpacer, Priority.ALWAYS);
        HBox ttlRow = new HBox(8, label("TTL", "rail-label"), ttlLabel, ttlSpacer, ttlField, setTtlButton,
                persistButton);
        ttlRow.setAlignment(Pos.CENTER_LEFT);

        inspectorStatus.setWrapText(true);
        inspectorStatus.setMinHeight(Region.USE_PREF_SIZE);
        FlowPane actions = new FlowPane(8, 8, saveButton, editEntriesButton, cloneButton, renameButton,
                exportButton, deleteButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        HBox head = new HBox(10, label("INSPECTOR", "series"), typeBadge);
        head.setAlignment(Pos.CENTER_LEFT);

        VBox column = new VBox(9, head, nameRow, ttlRow,
                label("VALUE", "series"), valueArea, collectionScroll, actions, inspectorStatus);
        column.getStyleClass().add("redis-inspector");
        column.setPadding(new Insets(16, 30, 14, 20));
        column.setPrefWidth(392);
        column.setMinWidth(330);
        return column;
    }

    private VBox readout(String caption, Label value, Color color, Label note) {
        Label title = label(caption, "readout-caption");
        value.setStyle("-fx-text-fill: " + RedisPalette.web(color) + ";");
        VBox box = new VBox(0, title, value);
        if (note != null) {
            box.getChildren().add(note);
        }
        box.setPadding(new Insets(0, 20, 0, 20));
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static Region rule() {
        Region rule = new Region();
        rule.getStyleClass().add("readout-rule");
        rule.setMinWidth(1);
        rule.setPrefWidth(1);
        rule.setMinHeight(30);
        return rule;
    }

    private static Region separator() {
        Region separator = new Region();
        separator.getStyleClass().add("rail-separator");
        separator.setMinWidth(1);
        separator.setPrefWidth(1);
        separator.setMinHeight(18);
        return separator;
    }

    private static Label tag(String text) {
        Label label = new Label("▸" + text);
        label.getStyleClass().add("sheet-tag");
        return label;
    }

    private static Button railButton(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button button = new Button(text);
        button.getStyleClass().add("rail-button");
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(action);
        return button;
    }

    private static String problem(CommandEntry entry) {
        String error = entry.error().strip();
        if (!error.isEmpty()) {
            return error.lines().findFirst().orElse("").toUpperCase(Locale.ROOT);
        }
        return entry.ran() ? "FAILED WITH EXIT CODE " + entry.exitCode() : "COULD NOT RUN";
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

    RedisStack stack() {
        return stack;
    }

    void showForPreview(RedisInfo info, List<RedisKey> sampleKeys, RedisKey select, RedisValue value) {
        keys.addAll(sampleKeys);
        applyInfo(info);
        stack.setKeys(keys, false);
        showType(select == null ? RedisType.STRING : select.type());
        if (select != null) {
            showKey(select);
            selectedValue = value;
            showValue(value);
            inspectorStatus.setText("");
        }
    }
}

