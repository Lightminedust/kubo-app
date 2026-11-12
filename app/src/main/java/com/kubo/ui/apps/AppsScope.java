package com.kubo.ui.apps;

import com.kubo.apps.AppCommands;
import com.kubo.apps.AppService;
import com.kubo.command.CommandEntry;
import com.kubo.model.apps.App;
import com.kubo.model.apps.AppProcess;
import com.kubo.model.apps.AppsReport;
import com.kubo.model.apps.Manager;
import com.kubo.model.files.DirectoryListing;
import com.kubo.model.files.PathKind;
import com.kubo.privilege.Privilege;
import com.kubo.privilege.RootSession;
import com.kubo.ui.PathPicker;
import com.kubo.ui.Pulse;
import com.kubo.ui.Tile;
import com.kubo.ui.docker.SudoPrompt;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What PM2 and supervisor keep running: the board in the middle, the chosen application on the right with what
 * can be done to it, and under it what it has been writing.
 *
 * <p>It reads every five seconds while it is the window in front, without saying so: the figures moving is
 * the point of the screen, and a loader every five seconds would say something is happening when nothing is.
 *
 * <p>Whatever the screen reports about an application, it also lets the reader change, on the same line: an
 * application that is not saved has its save beside it, and a PM2 that does not start at boot has the switch.
 */
public final class AppsScope extends StackPane {

    /** The name this screen answers to in {@link com.kubo.command.Attention}. */
    public static final String ATTENTION = "APPS";

    private static final System.Logger LOG = System.getLogger(AppsScope.class.getName());
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int LOG_LINES = 200;

    private final AppService service;
    private final String account;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-apps");
        thread.setDaemon(true);
        return thread;
    });
    private final Timeline poll = new Timeline(new KeyFrame(Duration.seconds(5), event -> {
        if (com.kubo.command.Attention.holds(ATTENTION)) {
            refresh();
        }
    }));
    /** Supervisor is read as root: turning root on or off changes what can be seen. */
    private final ChangeListener<Boolean> rootWatch = (observable, before, now) -> refresh();

    private final AppsBoard board = new AppsBoard();
    private final Label who = new Label();
    private final HBox rootSlot = new HBox();
    private final Label tally = new Label();
    private final Label statusMode = new Label("apps");
    private final Label statusMessage = new Label("");
    private final Label bootLabel = new Label("");
    private final Label readLabel = new Label("");
    private final Button saveButton = action("save the list", event -> saveList());
    private final Button newButton = action("new app", event -> showNewApp());
    private final Button bootButton = action("at boot", event -> toggleBoot());

    private final VBox facts = new VBox(7);
    private final FlowPane actions = new FlowPane(16, 8);
    private final Region actionsRule = new Region();
    private final Pulse working = new Pulse();
    private final Label status = new Label();
    private Tile appTile;

    private final VBox logLines = new VBox(1);
    private ScrollPane logScroll;
    private final Pulse logPulse = new Pulse();
    private final Label logSource = new Label();
    private final Button outTab = action("output", event -> showLog(false));
    private final Button errTab = action("errors", event -> showLog(true));
    private final Button followButton = action("follow", event -> toggleFollow());
    private final Button flushButton = action("empty", event -> confirmFlush());
    private boolean errors;
    private boolean following;
    private boolean logReading;

    private AppsReport report = AppsReport.EMPTY;
    private RootSession rootSession;
    private boolean reading;
    private boolean everRead;
    private boolean closed;
    private int wantedWorkers = -1;
    private String preferred;
    private Runnable onFirstRead = () -> { };
    private StackPane overlay;
    private StackPane topOverlay;
    private ContextMenu menu;

    public AppsScope(AppService service, String account) {
        this.service = service;
        this.account = account == null ? "" : account;
        getStyleClass().add("scope-view");
        // The menu goes away on any press, scroll or Escape in the screen, and when the window loses focus,
        // without counting on the popup noticing it by itself.
        addEventFilter(MouseEvent.MOUSE_PRESSED, event -> hideMenu());
        addEventFilter(ScrollEvent.SCROLL, event -> hideMenu());
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && menu != null && menu.isShowing()) {
                hideMenu();
                event.consume();
            }
        });
        sceneProperty().addListener((o, was, scene) -> {
            if (scene != null) {
                scene.windowProperty().addListener((w, old, window) -> {
                    if (window != null) {
                        window.focusedProperty().addListener((f, had, has) -> {
                            if (!has) {
                                hideMenu();
                            }
                        });
                    }
                });
            }
        });

        board.setOnSelect(app -> {
            wantedWorkers = -1;
            drawChosen();
            readLog();
        });
        board.setOnOpen(app -> showLog(false));
        board.setOnMenu(this::showMenu);
        board.setEmpty("reading", "");

        BorderPane layout = new BorderPane(buildBody());
        layout.setTop(buildHeader());
        layout.setBottom(buildStatus());
        getChildren().add(layout);
        poll.setCycleCount(Timeline.INDEFINITE);
        paintTabs();
        drawHeader();
        drawChosen();
    }

    public void setRootSession(RootSession session) {
        rootSession = session;
        rootSlot.getChildren().setAll(new com.kubo.ui.RootButton(session, verification(),
                this::showOverlay, this::hideOverlay));
        RootSession.elevatedProperty().removeListener(rootWatch);
        RootSession.elevatedProperty().addListener(rootWatch);
    }

    /** Told once, when the first reading is on screen. */
    public void setOnFirstRead(Runnable onFirstRead) {
        this.onFirstRead = onFirstRead == null ? () -> { } : onFirstRead;
    }

    public void start() {
        refresh();
        poll.play();
    }

    /** The window is in front again: read now rather than at the next tick. */
    public void resume() {
        refresh();
    }

    /** Picks this application as soon as it is known, as if it had been clicked. */
    public void prefer(String key) {
        preferred = key;
        takePreferred();
    }

    public void shutdown() {
        closed = true;
        RootSession.elevatedProperty().removeListener(rootWatch);
        poll.stop();
        board.stop();
        if (menu != null) {
            menu.hide();
        }
        worker.shutdownNow();
    }

    // Building

    private VBox buildHeader() {
        Label wordmark = com.kubo.ui.RootButton.wordmark();
        Label window = new Label("apps");
        window.getStyleClass().add("term-window");
        who.getStyleClass().add("term-window-quiet");
        who.setText(account.isEmpty() ? "" : "pm2 of " + account);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        rootSlot.setAlignment(Pos.CENTER_LEFT);
        HBox bar = new HBox(14, wordmark, window, who, spacer, rootSlot);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("term-bar");

        Button reread = action("re-read", event -> refresh());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        tally.getStyleClass().add("home-muted");
        tally.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(18, reread, newButton, saveButton, bootButton, gap, tally);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("home-nav", "redis-actions");
        return new VBox(bar, row);
    }

    private HBox buildBody() {
        ScrollPane boardScroll = scroll(board);
        boardScroll.setFitToHeight(true);
        Tile boardTile = new Tile("apps", boardScroll).accent("tile-cyan");
        boardTile.setMinWidth(560);
        HBox.setHgrow(boardTile, Priority.ALWAYS);

        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        hide(status);
        hide(working);
        actions.setAlignment(Pos.CENTER_LEFT);
        actionsRule.getStyleClass().add("fetch-rule");
        actionsRule.setMinHeight(1);
        actionsRule.setMaxHeight(1);
        VBox column = new VBox(14, facts, actionsRule, actions, working, status);
        column.setMinWidth(0);
        // Its own height and no scroll: the actions at the bottom of it must never be the part cut off.
        appTile = new Tile("app", column).accent("tile-violet");
        appTile.setMinHeight(Region.USE_PREF_SIZE);

        logSource.getStyleClass().add("home-muted");
        logSource.setMinWidth(0);
        logSource.setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox tabs = new HBox(16, outTab, errTab, followButton, gap, flushButton);
        tabs.setAlignment(Pos.CENTER_LEFT);
        logScroll = scroll(logLines);
        VBox.setVgrow(logScroll, Priority.ALWAYS);
        logPulse.setRunning(false);
        hide(logPulse);
        VBox logBox = new VBox(8, tabs, logSource, logPulse, logScroll);
        Tile logTile = new Tile("logs", logBox).accent("tile-green");
        logTile.setMinHeight(170);
        VBox.setVgrow(logTile, Priority.ALWAYS);

        VBox right = new VBox(18, appTile, logTile);
        right.setMinWidth(340);
        right.setPrefWidth(420);
        right.setMaxWidth(460);

        HBox body = new HBox(18, boardTile, right);
        body.getStyleClass().add("tiles");
        return body;
    }

    private HBox buildStatus() {
        statusMode.getStyleClass().addAll("status-mode", "status-mode-ok");
        statusMessage.getStyleClass().add("status-message");
        bootLabel.getStyleClass().add("status-fact");
        readLabel.getStyleClass().add("status-fact");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox line = new HBox(14, statusMode, statusMessage, spacer, bootLabel, readLabel);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("status-line");
        return line;
    }

    // Reading

    private void refresh() {
        if (reading || service == null || closed || worker.isShutdown()) {
            return;
        }
        reading = true;
        worker.execute(() -> {
            AppsReport read;
            try {
                read = service.read();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Reading the apps failed", e);
                read = new AppsReport(false, false, List.of(), 0, 0, 0, "", List.of(reason(e)));
            }
            AppsReport answer = read;
            Platform.runLater(() -> {
                reading = false;
                if (closed) {
                    return;
                }
                apply(answer);
                if (following) {
                    readLog();
                }
            });
        });
    }

    /** Shows a reading. Also the way the previews fill the screen without a server. */
    void apply(AppsReport read) {
        boolean first = !everRead;
        report = read;
        board.show(read);
        if (!read.pm2() && !read.supervisor()) {
            board.setEmpty("no process manager on this server",
                    "pm2 keeps node apps running: npm install -g pm2");
        } else if (read.apps().isEmpty()) {
            board.setEmpty(read.pm2() ? "pm2 runs nothing yet" : "supervisor runs nothing yet",
                    read.pm2() ? "new app starts one" : "");
        }
        takePreferred();
        drawHeader();
        drawStatus();
        drawChosen();
        if (first) {
            everRead = true;
            onFirstRead.run();
            readLog();
            if (service != null) {
                // How busy a core is takes two readings: the second one soon, rather than five seconds on.
                PauseTransition soon = new PauseTransition(Duration.seconds(1.2));
                soon.setOnFinished(event -> refresh());
                soon.play();
            }
        }
    }

    private void takePreferred() {
        if (preferred == null) {
            return;
        }
        for (App app : report.apps()) {
            if (app.key().equals(preferred) || app.name().equals(preferred)) {
                preferred = null;
                board.choose(app.key());
                wantedWorkers = -1;
                drawChosen();
                readLog();
                return;
            }
        }
    }

    /** The actions over the board, lit when something they would fix is wrong. */
    private void drawHeader() {
        boolean hasPm2 = report.apps().stream().anyMatch(app -> app.manager() == Manager.PM2);
        newButton.setDisable(!report.pm2());
        saveButton.setDisable(!report.pm2() || !hasPm2);
        warn(saveButton, report.unsaved() > 0);
        bootButton.setVisible(report.pm2());
        bootButton.setManaged(report.pm2());
        bootButton.setText(report.startsAtBoot() ? "starts at boot" : "start at boot");
        warn(bootButton, !report.startsAtBoot());

        List<String> parts = new ArrayList<>();
        int apps = report.apps().size();
        parts.add(apps + (apps == 1 ? " app" : " apps"));
        parts.add(report.processes() + (report.processes() == 1 ? " process" : " processes"));
        long failed = report.count(App.State.FAILED) + report.count(App.State.PARTLY);
        if (failed > 0) {
            parts.add(failed + " in trouble");
        }
        tally.setText(String.join("  ·  ", parts));
    }

    private void drawStatus() {
        long failed = report.count(App.State.FAILED);
        long partly = report.count(App.State.PARTLY);
        long exposed = report.apps().stream().filter(App::exposed).count();
        long unsaved = report.unsaved();
        String mode;
        String message;
        if (!report.notes().isEmpty() && report.apps().isEmpty()) {
            mode = "note";
            message = report.notes().get(0);
        } else if (failed + partly > 0) {
            mode = "down";
            message = (failed + partly) + (failed + partly == 1 ? " app is" : " apps are") + " not all up";
        } else if (exposed > 0) {
            mode = "exposed";
            message = exposed + (exposed == 1 ? " app listens" : " apps listen")
                    + " on every address: anyone reaches it without nginx";
        } else if (unsaved > 0) {
            mode = "unsaved";
            message = unsaved + (unsaved == 1 ? " app" : " apps") + " would not come back after a reboot";
        } else if (report.pm2() && !report.startsAtBoot() && !report.apps().isEmpty()) {
            mode = "boot";
            message = "pm2 does not start at boot: after a reboot nothing here comes back";
        } else {
            mode = "apps";
            message = report.apps().isEmpty() ? "nothing running" : "everything up";
        }
        statusMode.setText(mode);
        statusMode.getStyleClass().setAll("label", "status-mode",
                mode.equals("apps") ? "status-mode-ok" : mode.equals("note") || mode.equals("unsaved")
                        || mode.equals("boot") ? "status-mode-busy" : "status-mode-error");
        statusMessage.setText(message);
        bootLabel.setText(!report.pm2() ? "" : report.startsAtBoot() ? "pm2 starts at boot"
                : "pm2 does not start at boot");
        bootLabel.getStyleClass().setAll("label", report.startsAtBoot() || !report.pm2()
                ? "status-fact" : "status-fact-warn");
        readLabel.setText("read " + LocalTime.now().format(CLOCK));
    }

    // The chosen application

    private App chosen() {
        String key = board.chosen();
        for (App app : report.apps()) {
            if (app.key().equals(key)) {
                return app;
            }
        }
        return report.apps().isEmpty() ? null : report.apps().get(0);
    }

    private void drawChosen() {
        facts.getChildren().clear();
        actions.getChildren().clear();
        App app = chosen();
        boolean none = app == null;
        actionsRule.setVisible(!none);
        actionsRule.setManaged(!none);
        actions.setVisible(!none);
        actions.setManaged(!none);
        flushButton.setDisable(none || app.manager() != Manager.PM2);
        if (none) {
            appTile.setTag("");
            facts.getChildren().add(muted("pick an app on the board"));
            return;
        }
        appTile.setTag(app.manager().word());
        AppProcess first = app.first();

        Region swatch = new Region();
        swatch.setMinSize(9, 9);
        swatch.setMaxSize(9, 9);
        swatch.setStyle("-fx-background-color: " + hex(board.colourOf(app)) + ";");
        Label name = new Label(app.name());
        name.getStyleClass().add("fetch-host");
        name.setMinWidth(0);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Label state = new Label("● " + AppsBoard.stateWords(app));
        state.setMinWidth(Region.USE_PREF_SIZE);
        state.getStyleClass().add(switch (app.state()) {
            case UP -> "journal-ok";
            case FAILED -> "port-alert";
            case PARTLY -> "fetch-note";
            default -> "port-what";
        });
        HBox head = new HBox(10, swatch, name, gap, state);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(0, 0, 4, 0));
        facts.getChildren().add(head);

        if (first != null && !first.script().isEmpty()) {
            facts.getChildren().add(fact("runs", first.script(), "fetch-value"));
        }
        if (first != null && !first.cwd().isEmpty() && !first.script().startsWith(first.cwd() + "/")) {
            facts.getChildren().add(fact("in", first.cwd(), "port-quiet"));
        }
        if (app.ports().isEmpty()) {
            facts.getChildren().add(fact("listens", app.online() == 0 ? "—" : "no port found", "port-quiet"));
        } else {
            for (App.Listening port : app.ports()) {
                facts.getChildren().add(fact("listens", port.everywhere() ? port + "  ·  open to everyone"
                        : port.toString(), port.everywhere() ? "port-alert" : "fetch-value"));
            }
            if (app.exposed()) {
                facts.getChildren().add(fact("", "bind it to 127.0.0.1 and let nginx stand in front", "fetch-note"));
            }
        }
        facts.getChildren().add(fact("uses", app.online() == 0 ? "nothing, it is not running"
                : Math.round(app.cores() * 100) + "% of a core  ·  " + AppsBoard.bytes(app.memory()),
                app.online() == 0 ? "port-quiet" : "fetch-value"));
        boolean named = app.manager() != Manager.PM2 || AppCommands.validName(app.name());
        Button reset = null;
        if (app.manager() == Manager.PM2 && app.restarts() > 0 && named) {
            reset = action("back to 0", event -> act("pm2 reset " + app.name(), () -> service.reset(app.name()),
                    app.name() + " counts its restarts from zero again"));
        }
        facts.getChildren().add(fact("restarts", String.valueOf(app.restarts()),
                app.restarts() > 20 ? "port-alert" : app.restarts() > 3 ? "fetch-note" : "fetch-value", reset));
        if (app.manager() == Manager.PM2) {
            facts.getChildren().add(scale(app, named));
            drawReboot(app);
        }
        if (first != null && !first.version().isEmpty()) {
            facts.getChildren().add(fact("version", first.version(), "port-quiet"));
        }
        drawActions(app, named);
    }

    /**
     * Whether it comes back after a reboot, which takes two things: being in PM2's saved list, and PM2 itself
     * starting at boot. Each is said on its own line, with what changes it beside it.
     */
    private void drawReboot(App app) {
        Button save = null;
        if (!app.saved()) {
            save = action("save it", event -> saveList());
            save.getStyleClass().add("apps-warn");
        }
        facts.getChildren().add(fact("reboot", app.saved() ? "in the saved list" : "not in the saved list",
                app.saved() ? "port-quiet" : "fetch-note", save));
        boolean boot = report.startsAtBoot();
        Button toggle = action(boot ? "turn off" : "turn on", event -> toggleBoot());
        if (!boot) {
            toggle.getStyleClass().add("apps-warn");
        }
        facts.getChildren().add(fact("", boot ? "pm2 starts at boot" : "pm2 does not start at boot",
                boot ? "port-quiet" : "fetch-note", toggle));
    }

    private void drawActions(App app, boolean named) {
        boolean running = app.online() > 0;
        if (app.manager() == Manager.PM2) {
            actions.getChildren().add(action("restart", event -> pm2(app, AppCommands.Pm2Verb.RESTART)));
            if (app.cluster() && running) {
                actions.getChildren().add(action("reload", event -> pm2(app, AppCommands.Pm2Verb.RELOAD)));
            }
            actions.getChildren().add(running
                    ? action("stop", event -> pm2(app, AppCommands.Pm2Verb.STOP))
                    : action("start", event -> pm2(app, AppCommands.Pm2Verb.START)));
            Button delete = action("delete", event -> confirmDelete(app));
            delete.getStyleClass().add("port-danger");
            actions.getChildren().add(delete);
        } else {
            actions.getChildren().add(action("restart", event -> supervise(app, AppCommands.SupervisorVerb.RESTART)));
            actions.getChildren().add(running
                    ? action("stop", event -> supervise(app, AppCommands.SupervisorVerb.STOP))
                    : action("start", event -> supervise(app, AppCommands.SupervisorVerb.START)));
        }
        if (!named) {
            actions.getChildren().forEach(node -> node.setDisable(true));
            actions.getChildren().add(muted("its name has characters kubo does not send to a server"));
        }
    }

    /**
     * How many workers a cluster runs, with the machine's cores beside it. A fork is one process and says so:
     * spreading it over cores means starting it again in cluster mode.
     */
    private HBox scale(App app, boolean named) {
        if (!app.cluster()) {
            return fact("workers", "one process. several need cluster mode", "port-quiet");
        }
        int now = app.processes().size();
        int wanted = wantedWorkers > 0 ? wantedWorkers : now;
        int most = Math.max(now, Math.max(1, report.cores()) * 2);
        Button less = action("−", event -> {
            wantedWorkers = Math.max(1, wanted - 1);
            drawChosen();
        });
        Button more = action("+", event -> {
            wantedWorkers = Math.min(most, wanted + 1);
            drawChosen();
        });
        less.setDisable(wanted <= 1 || !named);
        more.setDisable(wanted >= most || !named);
        Label count = new Label(String.valueOf(wanted));
        count.getStyleClass().add("fetch-host");
        count.setMinWidth(22);
        count.setAlignment(Pos.CENTER);
        Label of = new Label(report.cores() > 0 ? "of " + report.cores() + " cores" : "");
        of.getStyleClass().add(wanted > report.cores() && report.cores() > 0 ? "fetch-note" : "port-quiet");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(10, key("workers"), less, count, more, of, gap);
        row.setAlignment(Pos.CENTER_LEFT);
        if (wanted != now) {
            Button apply = action("apply", event -> {
                wantedWorkers = -1;
                act("pm2 scale " + app.name() + " " + wanted, () -> service.scale(app.name(), wanted),
                        app.name() + " runs " + wanted + (wanted == 1 ? " worker" : " workers"));
            });
            apply.getStyleClass().add("apps-lit");
            row.getChildren().add(apply);
        }
        return row;
    }

    // Acting

    private void pm2(App app, AppCommands.Pm2Verb verb) {
        String word = verb.name().toLowerCase(Locale.ROOT);
        String done = switch (verb) {
            case START -> app.name() + " started";
            case STOP -> app.name() + " stopped";
            case RESTART -> app.name() + " restarted";
            case RELOAD -> app.name() + " reloaded, one worker at a time";
            case DELETE -> app.name() + " is gone from pm2. its files are where they were";
        };
        act("pm2 " + word + " " + app.name(), () -> service.pm2(verb, app.name()), done);
    }

    private void supervise(App app, AppCommands.SupervisorVerb verb) {
        String word = verb.name().toLowerCase(Locale.ROOT);
        withRoot(() -> act("supervisorctl " + word + " " + app.name(), () -> service.supervisor(verb, app.name()),
                app.name() + " " + (verb == AppCommands.SupervisorVerb.STOP ? "stopped" : word + "ed")));
    }

    private void saveList() {
        act("pm2 save", service::save, "the list is saved: these apps come back after a reboot");
    }

    /** Turns PM2 at boot on, or asks before turning it off. */
    private void toggleBoot() {
        if (service == null) {
            return;
        }
        if (!report.host().known()) {
            say(false, "kubo could not read where pm2 is installed for this account");
            return;
        }
        if (!report.startsAtBoot()) {
            boot(true);
            return;
        }
        confirm("stop starting pm2 at boot?", "after a reboot nothing pm2 runs comes back until somebody "
                + "starts it by hand. what runs now keeps running.", "turn it off", () -> boot(false));
    }

    private void boot(boolean on) {
        AppsReport.Host host = report.host();
        withRoot(() -> act(on ? "pm2 startup systemd" : "pm2 unstartup systemd", () -> service.boot(host, on),
                on ? "pm2 starts at boot, and the list it brings back is saved" : "pm2 no longer starts at boot"));
    }

    /** Sends one command, says it is waiting, then says how it went and reads everything again. */
    private void act(String what, Supplier<CommandEntry> send, String done) {
        if (service == null || closed) {
            return;
        }
        working.setText(what);
        working.setRunning(true);
        show(working);
        hide(status);
        worker.execute(() -> {
            String trouble;
            try {
                CommandEntry entry = send.get();
                trouble = entry.success() ? null : problem(entry);
            } catch (RuntimeException e) {
                trouble = reason(e);
            }
            String result = trouble;
            Platform.runLater(() -> {
                working.setRunning(false);
                hide(working);
                say(result == null, result == null ? done : result);
                refresh();
            });
        });
    }

    private void say(boolean good, String text) {
        status.getStyleClass().setAll("label", good ? "card-success" : "card-problem");
        status.setText(text);
        show(status);
    }

    /**
     * Runs something that needs root, asking for the sudo password first when it is not known yet.
     */
    private void withRoot(Runnable action) {
        if (service == null) {
            return;
        }
        if (!service.rootPossible()) {
            say(false, "this needs root, and this account cannot become root on this server");
            return;
        }
        if (service.needsPassword() && rootSession != null) {
            showOverlayOnTop(new SudoPrompt(rootSession.privilege(), verification(), () -> {
                rootSession.refresh();
                hideTopOverlay();
                action.run();
            }, this::hideTopOverlay));
            return;
        }
        action.run();
    }

    private BiConsumer<Privilege.Command, Consumer<Boolean>> verification() {
        return (command, done) -> {
            if (service == null || worker.isShutdown()) {
                done.accept(false);
                return;
            }
            worker.execute(() -> {
                CommandEntry entry = service.verify(command);
                Platform.runLater(() -> done.accept(entry.success()));
            });
        };
    }

    private void confirmDelete(App app) {
        confirm("delete " + app.name() + "?", "its processes stop and pm2 forgets it. the files on the disk "
                        + "stay, and it can be started again from them.", "delete " + app.name(),
                () -> pm2(app, AppCommands.Pm2Verb.DELETE));
    }

    private void confirmFlush() {
        App app = chosen();
        if (app == null || app.manager() != Manager.PM2) {
            return;
        }
        confirm("empty the logs of " + app.name() + "?", "its output and its errors are both emptied. "
                + "what it wrote is gone for good.", "empty them", () -> {
            act("pm2 flush " + app.name(), () -> service.flush(app.name()), "the logs of " + app.name()
                    + " are empty");
            logLines.getChildren().clear();
        });
    }

    /** A card asking before something that cannot be taken back. */
    private void confirm(String question, String consequence, String go, Runnable action) {
        Label title = new Label(question);
        title.getStyleClass().add("fetch-host");
        title.setWrapText(true);
        Label words = new Label(consequence);
        words.getStyleClass().add("fetch-value");
        words.setWrapText(true);
        Button cancel = action("cancel", event -> hideOverlay());
        Button yes = new Button(go);
        yes.getStyleClass().addAll("primary-button", "compact-button");
        yes.setOnAction(event -> {
            hideOverlay();
            action.run();
        });
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox buttons = new HBox(16, gap, cancel, yes);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox card = new VBox(16, title, words, buttons);
        card.getStyleClass().addAll("float-card", "redis-help");
        card.setPadding(new Insets(22, 26, 20, 26));
        card.setMaxSize(440, Region.USE_PREF_SIZE);
        showOverlay(card);
    }

    private void hideMenu() {
        if (menu != null) {
            menu.hide();
            menu = null;
        }
    }

    private void showMenu(App app, double[] at) {
        hideMenu();
        List<MenuItem> items = new ArrayList<>();
        boolean running = app.online() > 0;
        boolean isPm2 = app.manager() == Manager.PM2;
        if (isPm2) {
            items.add(item("restart", () -> pm2(app, AppCommands.Pm2Verb.RESTART)));
            if (app.cluster() && running) {
                items.add(item("reload, one worker at a time", () -> pm2(app, AppCommands.Pm2Verb.RELOAD)));
            }
            items.add(running ? item("stop", () -> pm2(app, AppCommands.Pm2Verb.STOP))
                    : item("start", () -> pm2(app, AppCommands.Pm2Verb.START)));
        } else {
            items.add(item("restart", () -> supervise(app, AppCommands.SupervisorVerb.RESTART)));
            items.add(running ? item("stop", () -> supervise(app, AppCommands.SupervisorVerb.STOP))
                    : item("start", () -> supervise(app, AppCommands.SupervisorVerb.START)));
        }
        items.add(new SeparatorMenuItem());
        items.add(item("its output", () -> showLog(false)));
        items.add(item("its errors", () -> showLog(true)));
        if (isPm2) {
            items.add(item("empty its logs…", this::confirmFlush));
            items.add(new SeparatorMenuItem());
            items.add(item(app.saved() ? "save the list again" : "save it for the next reboot", this::saveList));
            if (app.restarts() > 0) {
                items.add(item("count its restarts from zero", () -> act("pm2 reset " + app.name(),
                        () -> service.reset(app.name()), app.name() + " counts its restarts from zero again")));
            }
            items.add(new SeparatorMenuItem());
            MenuItem delete = item("delete…", () -> confirmDelete(app));
            delete.getStyleClass().add("redis-menu-danger");
            items.add(delete);
        }
        ContextMenu shown = new ContextMenu(items.toArray(new MenuItem[0]));
        shown.getStyleClass().add("redis-context-menu");
        shown.setAutoHide(true);
        shown.setOnHidden(event -> {
            if (menu == shown) {
                menu = null;
            }
        });
        menu = shown;
        shown.show(this, at[0], at[1]);
    }

    private static MenuItem item(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(event -> {
            if (item.getParentPopup() != null) {
                item.getParentPopup().hide();
            }
            action.run();
        });
        return item;
    }

    private void showNewApp() {
        showOverlay(new NewAppCard(report.cores(), report.host().home(), service == null ? null : this::pick,
                this::hideOverlay, app -> {
            hideOverlay();
            act("pm2 start " + app.name(), () -> service.start(app), app.name() + " started"
                    + (app.save() ? " and saved for the next reboot" : ""));
        }));
    }

    /** Opens the server's folders over the form, to walk to a script or a folder rather than type it. */
    private void pick(PathKind kind, String start, Consumer<String> chosen) {
        PathPicker picker = new PathPicker(kind == PathKind.FOLDER ? "the folder with package.json"
                : "the script to run", kind, (path, done) -> {
            if (worker.isShutdown()) {
                return;
            }
            worker.execute(() -> {
                DirectoryListing listing;
                try {
                    listing = service.list(path);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "Listing a folder failed", e);
                    listing = DirectoryListing.failed(path, reason(e));
                }
                DirectoryListing read = listing;
                Platform.runLater(() -> done.accept(read));
            });
        }, start, picked -> {
            hideTopOverlay();
            chosen.accept(picked);
        }, this::hideTopOverlay);
        showOverlayOnTop(picker);
    }

    // The log

    private void showLog(boolean wantErrors) {
        errors = wantErrors;
        paintTabs();
        readLog();
    }

    private void toggleFollow() {
        following = !following;
        paintTabs();
        if (following) {
            readLog();
        }
    }

    private void paintTabs() {
        outTab.getStyleClass().remove("tab-on");
        errTab.getStyleClass().remove("tab-on");
        followButton.getStyleClass().remove("tab-on");
        (errors ? errTab : outTab).getStyleClass().add("tab-on");
        if (following) {
            followButton.getStyleClass().add("tab-on");
        }
        followButton.setText(following ? "following" : "follow");
    }

    private void readLog() {
        App app = chosen();
        if (app == null || service == null || logReading || closed) {
            if (app == null) {
                logSource.setText("");
                logLines.getChildren().setAll(muted("no app picked"));
            }
            return;
        }
        AppProcess first = app.first();
        logSource.setText(app.manager() == Manager.SUPERVISOR ? "supervisorctl tail " + app.name()
                : first == null ? "" : errors ? first.errLog() : first.outLog());
        boolean quiet = !logLines.getChildren().isEmpty() && following;
        if (!quiet) {
            logPulse.setText(errors ? "reading its errors" : "reading its output");
            logPulse.setRunning(true);
            show(logPulse);
        }
        logReading = true;
        boolean wantErrors = errors;
        String key = app.key();
        worker.execute(() -> {
            String text;
            try {
                text = service.log(app, wantErrors, LOG_LINES);
            } catch (RuntimeException e) {
                text = "!" + reason(e);
            }
            String read = text;
            Platform.runLater(() -> {
                logReading = false;
                logPulse.setRunning(false);
                hide(logPulse);
                App current = chosen();
                if (current != null && key.equals(current.key()) && wantErrors == errors) {
                    fillLog(read, wantErrors);
                } else if (current != null) {
                    readLog();
                }
            });
        });
    }

    /** Only for the previews: a log already read. */
    void logForPreview(String text, boolean wantErrors) {
        errors = wantErrors;
        paintTabs();
        App app = chosen();
        AppProcess first = app == null ? null : app.first();
        logSource.setText(first == null ? "" : wantErrors ? first.errLog() : first.outLog());
        fillLog(text, wantErrors);
    }

    /** The newest line at the bottom, where a terminal puts it, and the view kept there. */
    private void fillLog(String text, boolean wantErrors) {
        boolean atBottom = logScroll.getVvalue() > 0.97 || logLines.getChildren().isEmpty();
        logLines.getChildren().clear();
        if (text.startsWith("!")) {
            Label trouble = new Label(text.substring(1));
            trouble.getStyleClass().add("journal-error");
            trouble.setWrapText(true);
            logLines.getChildren().add(trouble);
            return;
        }
        if (text.isBlank()) {
            logLines.getChildren().add(muted(wantErrors ? "no error written" : "nothing written yet"));
            return;
        }
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            Label row = new Label(line);
            row.getStyleClass().add(lower.contains("error") || lower.contains("exception") || lower.contains("fatal")
                    || lower.contains("unhandled") ? "journal-error"
                    : lower.contains("warn") ? "journal-warning" : wantErrors ? "journal-detail" : "journal-command");
            row.setMinWidth(0);
            row.setWrapText(true);
            row.setMinHeight(Region.USE_PREF_SIZE);
            logLines.getChildren().add(row);
        }
        if (atBottom) {
            Platform.runLater(() -> logScroll.setVvalue(1));
        }
    }

    // Small pieces

    private void showOverlay(Region card) {
        hideOverlay();
        if (menu != null) {
            menu.hide();
        }
        Region scrim = new Region();
        scrim.getStyleClass().add("picker-scrim");
        overlay = new StackPane(scrim, card);
        overlay.setMinSize(0, 0);
        getChildren().add(overlay);
    }

    private void hideOverlay() {
        hideTopOverlay();
        if (overlay != null) {
            getChildren().remove(overlay);
            overlay = null;
        }
    }

    /** A second card over the first, closed on its own without closing the one under it. */
    private void showOverlayOnTop(Region card) {
        hideTopOverlay();
        Region scrim = new Region();
        scrim.getStyleClass().add("picker-scrim");
        topOverlay = new StackPane(scrim, card);
        topOverlay.setMinSize(0, 0);
        getChildren().add(topOverlay);
    }

    private void hideTopOverlay() {
        if (topOverlay != null) {
            getChildren().remove(topOverlay);
            topOverlay = null;
        }
    }

    private static Label key(String text) {
        Label name = new Label(text);
        name.getStyleClass().add("fetch-key");
        name.setMinWidth(62);
        return name;
    }

    private static HBox fact(String key, String value, String style) {
        return fact(key, value, style, null);
    }

    /** A line of the application: what it is about, what it says, and what changes it at the far end. */
    private static HBox fact(String key, String value, String style, Node trailing) {
        Label text = new Label(value);
        text.getStyleClass().add(style);
        text.setWrapText(true);
        text.setMinWidth(0);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(10, key(key), text);
        row.setAlignment(Pos.TOP_LEFT);
        if (trailing != null) {
            trailing.getStyleClass().add("apps-inline");
            row.getChildren().add(trailing);
        }
        return row;
    }

    static Button action(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button button = new Button(text);
        button.getStyleClass().add("ghost-button");
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(action);
        return button;
    }

    private static void warn(Button button, boolean on) {
        button.getStyleClass().remove("apps-warn");
        if (on) {
            button.getStyleClass().add("apps-warn");
        }
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("home-muted");
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static ScrollPane scroll(Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("track-scroll");
        return scroll;
    }

    private static void show(Node node) {
        node.setVisible(true);
        node.setManaged(true);
    }

    private static void hide(Node node) {
        node.setVisible(false);
        node.setManaged(false);
    }

    static String hex(Color colour) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", Math.round(colour.getRed() * 255),
                Math.round(colour.getGreen() * 255), Math.round(colour.getBlue() * 255));
    }

    static String problem(CommandEntry entry) {
        String said = entry.error().isBlank() ? entry.output() : entry.error();
        said = said.strip();
        int end = said.indexOf('\n');
        String line = (end < 0 ? said : said.substring(0, end)).replaceAll("\\u001B\\[[;\\d]*m", "");
        return line.isBlank() ? "the server refused, without saying why" : line;
    }

    static String reason(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** Only for the previews: one application picked, as if it had been clicked. */
    void chooseForPreview(String key) {
        board.choose(key);
        drawChosen();
    }

    /** Only for the previews: the new app form, filled. */
    void newAppForPreview(String script, String port, int workers) {
        NewAppCard card = new NewAppCard(report.cores(), report.host().home(), (kind, start, chosen) -> { },
                this::hideOverlay, app -> { });
        card.fillForPreview(script, port, workers);
        showOverlay(card);
    }

    /** Only for the previews: the board, to hand it a history. */
    AppsBoard board() {
        return board;
    }
}
