package com.kubo.ui.services;

import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.files.FileBrowser;
import com.kubo.model.files.DirectoryListing;
import com.kubo.model.files.PathKind;
import com.kubo.model.services.Action;
import com.kubo.model.services.BootState;
import com.kubo.model.services.Dependencies;
import com.kubo.model.services.Lifeline;
import com.kubo.model.services.Move;
import com.kubo.model.services.Removal;
import com.kubo.model.services.Resource;
import com.kubo.model.services.ServiceUnit;
import com.kubo.model.services.ServicesReport;
import com.kubo.model.services.Signal;
import com.kubo.model.services.Standing;
import com.kubo.model.services.UnitDraft;
import com.kubo.privilege.Privilege;
import com.kubo.privilege.RootSession;
import com.kubo.services.ServiceCommands;
import com.kubo.services.ServiceControl;
import com.kubo.services.ServiceReader;
import com.kubo.ui.ConfirmCard;
import com.kubo.ui.PathPicker;
import com.kubo.ui.Pulse;
import com.kubo.ui.RootButton;
import com.kubo.ui.ServerConsole;
import com.kubo.ui.Tile;
import com.kubo.ui.docker.SudoPrompt;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What systemd keeps running: the board of every service in the middle, the chosen one on the right with what
 * can be done to it, and under it what it last wrote.
 *
 * <p>Reading needs nothing special. Changing needs root, and whatever takes something away makes the user
 * type the service name back first. The two that would cut this very connection are refused rather than
 * confirmed; see {@link Lifeline}.
 *
 * <p>Whatever the screen says about a service, it also lets the reader change, on the same line: a service
 * that does not start at boot has the switch beside it, one that is stopped has its start.
 */
public final class ServicesScope extends StackPane {

    /** The name this screen answers to in {@link com.kubo.command.Attention}. */
    public static final String ATTENTION = "SERVICES";

    private static final System.Logger LOG = System.getLogger(ServicesScope.class.getName());
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int JOURNAL_LINES = 200;

    private final ServiceReader reader;
    private final ServiceControl control;
    /** Only for walking the server's folders from the new-service card. */
    private final FileBrowser browser;
    private final RootSession root;
    private final SshConnection connection;
    private final CommandService commands;
    private final ServerConsole console;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-services");
        thread.setDaemon(true);
        return thread;
    });
    /** The state of the machine drifts slowly: a reading every half minute, held back while behind. */
    private final Timeline poll = new Timeline(new KeyFrame(Duration.seconds(30), event -> {
        if (com.kubo.command.Attention.holds(ATTENTION)) {
            refresh();
        }
    }));
    /** The journal, read again every few seconds while it is followed. */
    private final Timeline followPoll = new Timeline(new KeyFrame(Duration.seconds(4), event -> {
        if (this.following && !this.showingFile && com.kubo.command.Attention.holds(ATTENTION)) {
            readJournal();
        }
    }));

    private final ServicesBoard board = new ServicesBoard();
    /** The first reading, as the journal of what systemd is being asked, over the board it fills. */
    private final com.kubo.ui.Journal journal = new com.kubo.ui.Journal(ServicesScope::words);
    private Runnable stopJournal = () -> { };
    private Tile consoleTile;

    private final Label who = new Label();
    private final Label tally = new Label();
    private final TextField find = new TextField();
    private final Button logButton = action("log", event -> toggleConsole(false));
    private final Button shellButton = action("shell", event -> toggleConsole(true));
    private final Label statusMode = new Label("services");
    private final Label statusMessage = new Label("");
    private final Label bootLabel = new Label("");
    private final Label readLabel = new Label("");

    private final VBox facts = new VBox(7);
    private final FlowPane actions = new FlowPane(16, 8);
    private final Region actionsRule = new Region();
    private final Pulse working = new Pulse();
    private final Label status = new Label();
    private Tile serviceTile;

    private final VBox lines = new VBox(1);
    private ScrollPane linesScroll;
    private final Pulse linesPulse = new Pulse();
    private final Label linesSource = new Label();
    private final Button journalTab = action("journal", event -> showFile(false));
    private final Button fileTab = action("unit file", event -> showFile(true));
    private final Button followButton = action("follow", event -> toggleFollow());
    private boolean showingFile;
    private boolean following;
    private boolean linesReading;
    /** Set once the user asked for the journal to be read as root, which it then is for the session. */
    private boolean journalAsRoot;

    private ServicesReport reading = ServicesReport.EMPTY;
    private Dependencies dependencies = Dependencies.EMPTY;
    /** What systemd said about the chosen service, and which service that was. */
    private Map<String, String> detail = Map.of();
    private String detailFor;
    private boolean detailReading;
    /** The settings Kubo gave the chosen service, or empty when it has none. */
    private String dropIn = "";
    private String unitFile;
    private String whoText = "you@server";
    /** A service to pick as soon as a reading has it: the one just created. */
    private String pickWhenRead;
    private boolean everRead;
    private boolean refreshing;
    private boolean closed;
    private StackPane overlay;
    private StackPane topOverlay;
    private ContextMenu menu;

    public ServicesScope(ServiceReader reader, ServiceControl control, SshConnection connection,
                         CommandService commands, RootSession root) {
        this.reader = reader;
        this.control = control;
        this.root = root;
        this.connection = connection;
        this.commands = commands;
        this.browser = connection == null ? null : new FileBrowser(connection, commands);
        if (this.browser != null) {
            // The same root session as everything else on this screen, so making a folder under /opt works
            // here exactly as it does on the file screen.
            this.browser.setRootSession(root);
        }
        this.console = new ServerConsole(connection, commands, root, "SERVICES",
                CommandService.Origin.SERVICES, java.util.Set.of());
        getStyleClass().add("scope-view");
        if (connection != null) {
            whoText = connection.target().username() + "@" + connection.target().host();
        }

        // The menu goes away on any press, scroll or Escape in the screen, and when the window loses focus.
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

        board.setOnSelect(unit -> chose());
        board.setOnOpen(unit -> {
            showFile(false);
            linesScroll.requestFocus();
        });
        board.setOnMenu(this::showMenu);
        board.setEmpty("reading", "");
        stopJournal = journal.follow(commands, CommandService.Origin.SERVICES);

        BorderPane layout = new BorderPane(buildBody());
        layout.setTop(buildHeader());
        layout.setBottom(buildStatus());
        getChildren().add(layout);
        poll.setCycleCount(Timeline.INDEFINITE);
        followPoll.setCycleCount(Timeline.INDEFINITE);
        paintTabs();
        drawHeader();
        drawStatus();
        drawChosen();
    }

    public void start() {
        journal.start("reading", whoText, "what systemd runs, what comes back at the next boot, what the last boot cost");
        refresh();
        poll.play();
        followPoll.play();
    }

    /** The window is in front again: read now rather than at the next tick. */
    public void resume() {
        refresh();
    }

    public void shutdown() {
        closed = true;
        poll.stop();
        followPoll.stop();
        stopJournal.run();
        board.stop();
        hideMenu();
        console.shutdown();
        worker.shutdownNow();
    }

    // Building

    private VBox buildHeader() {
        Label wordmark = RootButton.wordmark();
        Label window = new Label("services");
        window.getStyleClass().add("term-window");
        who.getStyleClass().add("term-window-quiet");
        who.setText("systemd of " + whoText);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        RootButton rootButton = new RootButton(root, verification(), this::showOverlay, this::hideOverlay);
        HBox bar = new HBox(14, wordmark, window, who, spacer, rootButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("term-bar");

        Button reread = action("re-read", event -> refresh());
        Button create = action("new service", event -> openBuilder());
        Button apps = action("apps", event -> showApps());
        Button help = action("?", event -> showOverlay(new ServicesHelp(this::hideOverlay)));
        find.getStyleClass().add("find-field");
        find.setPromptText("find a service");
        find.textProperty().addListener((observable, before, now) -> board.setFilter(now));
        find.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                find.clear();
                board.requestFocus();
                event.consume();
            } else if (event.getCode() == KeyCode.DOWN || event.getCode() == KeyCode.ENTER) {
                board.requestFocus();
            }
        });
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        tally.getStyleClass().add("home-muted");
        tally.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(18, reread, create, apps, logButton, shellButton, help, gap, find, tally);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("home-nav", "redis-actions");
        return new VBox(bar, row);
    }

    private Node buildBody() {
        StackPane boardStack = new StackPane(board, journal);
        Tile boardTile = new Tile("services", boardStack).accent("tile-cyan");
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
        serviceTile = new Tile("service", column).accent("tile-violet");
        serviceTile.setMinHeight(Region.USE_PREF_SIZE);

        linesSource.getStyleClass().add("home-muted");
        linesSource.setMinWidth(0);
        linesSource.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox tabs = new HBox(16, journalTab, fileTab, gap, followButton);
        tabs.setAlignment(Pos.CENTER_LEFT);
        linesScroll = scroll(lines);
        VBox.setVgrow(linesScroll, Priority.ALWAYS);
        linesPulse.setRunning(false);
        hide(linesPulse);
        VBox linesBox = new VBox(8, tabs, linesSource, linesPulse, linesScroll);
        Tile linesTile = new Tile("journal", linesBox).accent("tile-green");
        linesTile.setMinHeight(170);
        VBox.setVgrow(linesTile, Priority.ALWAYS);

        VBox right = new VBox(18, serviceTile, linesTile);
        right.setMinWidth(340);
        right.setPrefWidth(430);
        right.setMaxWidth(470);

        HBox frames = new HBox(18, boardTile, right);
        VBox.setVgrow(frames, Priority.ALWAYS);

        // The log and the shell: there when asked for, not before.
        console.setFramed();
        consoleTile = new Tile("log", console).accent("tile-green");
        consoleTile.setMinHeight(160);
        consoleTile.setPrefHeight(200);
        consoleTile.setMaxHeight(240);
        hide(consoleTile);

        VBox body = new VBox(18, frames, consoleTile);
        body.getStyleClass().add("tiles");
        return body;
    }

    private HBox buildStatus() {
        statusMode.getStyleClass().addAll("status-mode", "status-mode-ok");
        statusMessage.getStyleClass().add("status-message");
        statusMessage.setMinWidth(0);
        bootLabel.getStyleClass().add("status-fact");
        readLabel.getStyleClass().add("status-fact");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox line = new HBox(14, statusMode, statusMessage, spacer, bootLabel, readLabel);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("status-line");
        return line;
    }

    /** Shows the log, or the shell, under the tiles; the same button again puts it away. */
    private void toggleConsole(boolean shell) {
        boolean shown = consoleTile.isVisible();
        if (shown && console.showingShell() == shell) {
            hide(consoleTile);
        } else {
            if (shell) {
                console.showShell();
            } else {
                console.showLogs();
            }
            show(consoleTile);
        }
        logButton.getStyleClass().remove("tab-on");
        shellButton.getStyleClass().remove("tab-on");
        if (consoleTile.isVisible()) {
            (console.showingShell() ? shellButton : logButton).getStyleClass().add("tab-on");
        }
    }

    // Reading

    private void refresh() {
        if (reader == null || refreshing || closed || worker.isShutdown()) {
            return;
        }
        refreshing = true;
        worker.execute(() -> {
            ServicesReport read;
            try {
                read = reader.read();
            } catch (RuntimeException e) {
                // A screen stuck on "reading" tells the user nothing.
                LOG.log(System.Logger.Level.ERROR, "Reading the services failed", e);
                String trouble = reason(e);
                Platform.runLater(() -> {
                    refreshing = false;
                    if (!everRead) {
                        journal.finish("read", trouble);
                    }
                    board.setEmpty("systemd could not be read", trouble);
                    say(false, trouble);
                });
                return;
            }
            Platform.runLater(() -> {
                refreshing = false;
                if (!closed) {
                    apply(read);
                }
            });
            // The links are a second, longer reading. Nothing waits for it: the board is already up.
            try {
                Dependencies links = reader.relations(read.units());
                Platform.runLater(() -> setDependencies(links));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Reading what depends on what failed", e);
            }
        });
    }

    /** Shows a reading. Also the way the previews fill the screen without a server. */
    void apply(ServicesReport read) {
        reading = read;
        if (!everRead) {
            everRead = true;
            journal.finish("read", null);
        }
        board.show(read);
        if (pickWhenRead != null && board.unit(pickWhenRead) != null) {
            board.choose(pickWhenRead);
            pickWhenRead = null;
        }
        if (read.absent()) {
            board.setEmpty("this server does not run systemd", "so it has no services to show");
        } else if (read.units().isEmpty()) {
            board.setEmpty("systemd listed no service", read.unreadable().isEmpty() ? "" : read.unreadable().get(0));
        }
        drawHeader();
        drawStatus();
        ServiceUnit unit = board.chosenUnit();
        if (unit != null && !unit.name().equals(detailFor)) {
            chose();
        } else {
            drawChosen();
        }
    }

    void setDependencies(Dependencies links) {
        dependencies = links;
        board.setDependencies(links);
    }

    private void drawHeader() {
        if (!everRead) {
            tally.setText("");
            return;
        }
        List<String> parts = new ArrayList<>();
        int total = reading.units().size();
        parts.add(total + (total == 1 ? " unit" : " units"));
        int failed = reading.count(Standing.FAILED);
        if (failed > 0) {
            parts.add(failed + " failed");
        }
        int wrong = reading.count(Standing.WILL_NOT_RETURN) + reading.count(Standing.NOT_RUNNING);
        if (wrong > 0) {
            parts.add(wrong + " to look at");
        }
        tally.setText(String.join("  ·  ", parts));
    }

    private void drawStatus() {
        int failed = reading.count(Standing.FAILED);
        int leaving = reading.count(Standing.WILL_NOT_RETURN);
        int stopped = reading.count(Standing.NOT_RUNNING);
        String mode;
        String message;
        if (!everRead) {
            mode = "reading";
            message = "asking systemd";
        } else if (reading.absent()) {
            mode = "note";
            message = "this server does not run systemd";
        } else if (failed > 0) {
            mode = "failed";
            message = failed + (failed == 1 ? " service" : " services") + " tried to start and could not";
        } else if (leaving > 0) {
            mode = "reboot";
            message = leaving + (leaving == 1 ? " service runs" : " services run")
                    + " now but would not come back after a reboot";
        } else if (stopped > 0) {
            mode = "stopped";
            message = stopped + (stopped == 1 ? " service is" : " services are")
                    + " enabled at boot and not running";
        } else if (!reading.unreadable().isEmpty()) {
            mode = "note";
            message = reading.unreadable().get(0);
        } else {
            mode = "services";
            message = "everything holding: what runs now comes back at the next boot";
        }
        statusMode.setText(mode);
        statusMode.getStyleClass().setAll("label", "status-mode",
                mode.equals("services") ? "status-mode-ok" : mode.equals("failed") ? "status-mode-error"
                        : "status-mode-busy");
        statusMessage.setText(message);
        bootLabel.setText(reading.boot() == null ? "" : "last boot " + ServicesBoard.shortTime(reading.boot()));
        readLabel.setText(everRead ? "read " + LocalTime.now().format(CLOCK) : "");
    }

    // The chosen service

    /** A new pick on the board: the right side follows it. */
    private void chose() {
        ServiceUnit unit = board.chosenUnit();
        if (unit == null) {
            drawChosen();
            return;
        }
        if (!unit.name().equals(detailFor)) {
            detailFor = unit.name();
            detail = Map.of();
            dropIn = "";
            unitFile = null;
            hide(status);
            readDetail(unit.name());
            readLines();
        }
        drawChosen();
    }

    private void readDetail(String name) {
        if (reader == null || worker.isShutdown()) {
            return;
        }
        detailReading = true;
        worker.execute(() -> {
            Map<String, String> properties;
            String settings;
            try {
                properties = reader.detail(name);
                settings = reader.settings(name);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Reading one unit failed", e);
                String trouble = reason(e);
                Platform.runLater(() -> {
                    if (name.equals(detailFor)) {
                        detailReading = false;
                        drawChosen();
                        say(false, "could not read " + name + ": " + trouble);
                    }
                });
                return;
            }
            Platform.runLater(() -> {
                if (name.equals(detailFor)) {
                    detailReading = false;
                    detail = properties;
                    dropIn = settings == null ? "" : settings;
                    drawChosen();
                }
            });
        });
    }

    private boolean canAct() {
        return control != null && control.available();
    }

    private void drawChosen() {
        facts.getChildren().clear();
        actions.getChildren().clear();
        ServiceUnit unit = board.chosenUnit();
        boolean none = unit == null;
        actionsRule.setVisible(!none);
        actionsRule.setManaged(!none);
        actions.setVisible(!none);
        actions.setManaged(!none);
        followButton.setDisable(none);
        if (none) {
            serviceTile.setTag("");
            facts.getChildren().add(muted(everRead ? "pick a service on the board" : "reading"));
            return;
        }
        serviceTile.setTag(ServicesBoard.word(unit.standing()));
        boolean known = unit.name().equals(detailFor) && !detail.isEmpty();
        boolean act = canAct();

        Region swatch = new Region();
        swatch.setMinSize(9, 9);
        swatch.setMaxSize(9, 9);
        swatch.setStyle("-fx-background-color: " + hex(ServicesBoard.colour(unit.standing())) + ";");
        Label name = new Label(unit.shortName());
        name.getStyleClass().add("fetch-host");
        name.setMinWidth(0);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Label state = new Label("● " + ServicesBoard.nowWord(unit));
        state.setMinWidth(Region.USE_PREF_SIZE);
        state.getStyleClass().add(unit.failed() ? "port-alert" : unit.running() ? "journal-ok"
                : ServicesBoard.nowColour(unit) == ServicesBoard.AMBER ? "fetch-note" : "port-what");
        HBox head = new HBox(10, swatch, name, gap, state);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(0, 0, 4, 0));
        facts.getChildren().add(head);

        if (!unit.description().isEmpty()) {
            facts.getChildren().add(fact("is", unit.description(), "fetch-value"));
        }
        drawNow(unit, act);
        drawBoot(unit, act);
        if (Lifeline.carriesTheConnection(unit.name())) {
            facts.getChildren().add(fact("", "your way in: kubo will not stop or disable it from here",
                    "fetch-note"));
        }
        if (detailReading && unit.name().equals(detailFor)) {
            Pulse pulse = new Pulse();
            pulse.setText("reading the unit");
            pulse.setRunning(true);
            facts.getChildren().add(pulse);
        }
        if (known) {
            drawDetail(unit, act);
        }
        if (unit.startup() != null && !unit.startup().isZero()) {
            facts.getChildren().add(fact("boot cost", ServicesBoard.shortTime(unit.startup()) + " of the last boot",
                    "port-quiet"));
        }
        drawActions(unit, act, known);
    }

    /** What it does now, with what changes that at the end of the line. */
    private void drawNow(ServiceUnit unit, boolean act) {
        String since = pretty("ActiveEnterTimestamp", detail.get("ActiveEnterTimestamp"));
        String text;
        String style;
        if (unit.failed()) {
            String result = detail.getOrDefault("Result", "");
            text = "failed" + (result.isEmpty() || result.equals("success") ? "" : ": " + result);
            style = "port-alert";
        } else if (unit.running()) {
            text = "running" + (since == null ? "" : " since " + since);
            style = "fetch-value";
        } else if (unit.active()) {
            text = ServicesBoard.nowWord(unit) + (unit.oneShot() ? ": it ran and finished" : "");
            style = "port-quiet";
        } else {
            text = "not running";
            style = unit.standing() == Standing.NOT_RUNNING ? "fetch-note" : "port-quiet";
        }
        Button button = null;
        if (act && unit.boot() != BootState.MASKED) {
            if (!unit.active() || unit.failed()) {
                button = action("start", event -> ask(Action.START, unit));
                if (unit.standing() == Standing.NOT_RUNNING || unit.failed()) {
                    button.getStyleClass().add("apps-warn");
                }
            } else if (Lifeline.refusal(Action.STOP, unit.name()) == null && !unit.oneShot()) {
                button = action("stop", event -> ask(Action.STOP, unit));
            }
        }
        facts.getChildren().add(fact("now", text, style, button));
    }

    /** What it does at the next boot, with the switch beside it when it has one of its own. */
    private void drawBoot(ServiceUnit unit, boolean act) {
        String text = switch (unit.boot()) {
            case ENABLED -> "starts at every boot";
            case DISABLED -> unit.running() ? "does not start at boot: gone after a reboot" : "does not start at boot";
            case STATIC -> "no switch of its own: started when another unit needs it";
            case INDIRECT -> "started through another unit";
            case GENERATED -> "made by systemd from another file";
            case MASKED -> "masked: nothing can start it";
            case TRANSIENT -> "made at run time, gone after a reboot";
            case UNKNOWN -> "not known";
        };
        String style = unit.standing() == Standing.WILL_NOT_RETURN ? "fetch-note"
                : unit.boot() == BootState.MASKED ? "port-alert"
                : unit.boot() == BootState.ENABLED ? "fetch-value" : "port-quiet";
        Button button = null;
        if (act) {
            if (unit.boot() == BootState.ENABLED && Lifeline.refusal(Action.DISABLE, unit.name()) == null) {
                button = action("turn off", event -> ask(Action.DISABLE, unit));
            } else if (unit.boot() == BootState.DISABLED) {
                button = action("turn on", event -> ask(Action.ENABLE, unit));
                if (unit.standing() == Standing.WILL_NOT_RETURN) {
                    button.getStyleClass().add("apps-warn");
                }
            }
        }
        facts.getChildren().add(fact("at boot", text, style, button));
    }

    /** What only the detail of the unit says: what it runs, its process, its limits, its own settings. */
    private void drawDetail(ServiceUnit unit, boolean act) {
        String runs = pretty("ExecStart", detail.get("ExecStart"));
        if (runs != null) {
            facts.getChildren().add(fact("runs", runs, "fetch-value"));
        }
        String file = detail.getOrDefault("FragmentPath", "");
        if (!file.isEmpty()) {
            facts.getChildren().add(fact("file", file, "port-quiet"));
        }
        String pid = pretty("MainPID", detail.get("MainPID"));
        String tasks = pretty("TasksCurrent", detail.get("TasksCurrent"));
        if (pid != null) {
            facts.getChildren().add(fact("process", "pid " + pid + (tasks == null ? ""
                    : "  ·  " + tasks + (tasks.equals("1") ? " task" : " tasks")), "fetch-value"));
        }
        String memory = pretty("MemoryCurrent", detail.get("MemoryCurrent"));
        String memoryLimit = pretty("MemoryMax", detail.get("MemoryMax"));
        String cpuLimit = pretty("CPUQuotaPerSecUSec", detail.get("CPUQuotaPerSecUSec"));
        Button limit = act ? action("limit", event -> showLimits(unit)) : null;
        String limits = (memoryLimit == null || memoryLimit.equals("no limit") ? "" : "  ·  at most " + memoryLimit)
                + (cpuLimit == null ? "" : "  ·  cpu " + cpuLimit);
        if (memory != null || !limits.isEmpty() || act) {
            facts.getChildren().add(fact("uses", (memory == null ? "—" : memory) + limits,
                    limits.isEmpty() ? "fetch-value" : "fetch-note", limit));
        }
        String restarts = detail.getOrDefault("NRestarts", "0");
        String policy = detail.getOrDefault("Restart", "");
        String dies = switch (policy) {
            case "no" -> "stays down when it dies";
            case "always" -> "always started again";
            case "on-failure" -> "started again when it fails";
            case "" -> "";
            default -> "started again: " + policy;
        };
        Button change = act ? action("change", event -> showSettings(unit)) : null;
        facts.getChildren().add(fact("restarts", restarts + (dies.isEmpty() ? "" : "  ·  " + dies),
                restarts.equals("0") ? "fetch-value" : "fetch-note", change));
        boolean own = !dropIn.isBlank();
        Button edit = act ? action(own ? "edit" : "add", event -> showSettings(unit)) : null;
        facts.getChildren().add(fact("settings", own ? ServiceCommands.dropInFile(unit.name())
                : "none of its own", own ? "fetch-value" : "port-quiet", edit));
        String says = detail.getOrDefault("StatusText", "");
        if (!says.isBlank()) {
            facts.getChildren().add(fact("says", says, "port-quiet"));
        }
    }

    private void drawActions(ServiceUnit unit, boolean act, boolean known) {
        if (!act) {
            actions.getChildren().add(muted(control == null ? "nothing to change in a preview"
                    : "changing a service needs root, and this account cannot become root here"));
            return;
        }
        if (unit.active() && !unit.oneShot()) {
            actions.getChildren().add(action("restart", event -> ask(Action.RESTART, unit)));
        }
        if (unit.active() && !unit.oneShot() && Lifeline.refusal(Action.STOP, unit.name()) == null) {
            actions.getChildren().add(action("stop", event -> ask(Action.STOP, unit)));
        } else if (!unit.active() || unit.failed()) {
            actions.getChildren().add(action("start", event -> ask(Action.START, unit)));
        }
        if (unit.active() && !unit.oneShot()) {
            Button signal = action("signal…", null);
            signal.setOnAction(event -> showSignals(unit, signal));
            actions.getChildren().add(signal);
        }
        actions.getChildren().add(action("limits", event -> showLimits(unit)));
        String file = detail.getOrDefault("FragmentPath", "");
        if (known && Removal.refusal(unit.name(), file) == null) {
            Button delete = action("delete", event -> askRemoval(unit, file));
            delete.getStyleClass().add("port-danger");
            actions.getChildren().add(delete);
        }
    }

    // The journal and the unit file

    private void showFile(boolean file) {
        showingFile = file;
        paintTabs();
        readLines();
    }

    private void toggleFollow() {
        following = !following;
        paintTabs();
        if (following && !showingFile) {
            readJournal();
        }
    }

    private void paintTabs() {
        journalTab.getStyleClass().remove("tab-on");
        fileTab.getStyleClass().remove("tab-on");
        followButton.getStyleClass().remove("tab-on");
        (showingFile ? fileTab : journalTab).getStyleClass().add("tab-on");
        if (following) {
            followButton.getStyleClass().add("tab-on");
        }
        followButton.setText(following ? "following" : "follow");
        followButton.setVisible(!showingFile);
        followButton.setManaged(!showingFile);
    }

    private void readLines() {
        if (showingFile) {
            readUnitFile();
        } else {
            readJournal();
        }
    }

    private void readJournal() {
        ServiceUnit unit = board.chosenUnit();
        if (unit == null || reader == null || linesReading || closed || worker.isShutdown()) {
            if (unit == null) {
                linesSource.setText("");
                lines.getChildren().setAll(muted("no service picked"));
            }
            return;
        }
        linesSource.setText("journalctl --unit " + unit.name());
        boolean quiet = following && !lines.getChildren().isEmpty();
        if (!quiet) {
            linesPulse.setText("reading its journal");
            linesPulse.setRunning(true);
            show(linesPulse);
        }
        linesReading = true;
        String name = unit.name();
        boolean asRoot = (journalAsRoot || (root != null && root.isElevated())) && canAct()
                && !control.needsPassword();
        worker.execute(() -> {
            String text;
            try {
                text = asRoot ? control.journal(name, JOURNAL_LINES).output() : reader.journal(name, JOURNAL_LINES);
            } catch (RuntimeException e) {
                text = "!" + reason(e);
            }
            String read = text;
            Platform.runLater(() -> {
                linesReading = false;
                linesPulse.setRunning(false);
                hide(linesPulse);
                ServiceUnit now = board.chosenUnit();
                if (now != null && now.name().equals(name) && !showingFile) {
                    fillJournal(read);
                } else if (now != null) {
                    readLines();
                }
            });
        });
    }

    private void readUnitFile() {
        ServiceUnit unit = board.chosenUnit();
        if (unit == null || reader == null || closed || worker.isShutdown()) {
            return;
        }
        String path = detail.getOrDefault("FragmentPath", "");
        linesSource.setText(path.isEmpty() ? "systemctl cat " + unit.name() : path);
        if (unitFile != null && unit.name().equals(detailFor)) {
            fillFile(unitFile);
            return;
        }
        linesPulse.setText("reading its unit file");
        linesPulse.setRunning(true);
        show(linesPulse);
        String name = unit.name();
        worker.execute(() -> {
            String text;
            try {
                text = reader.unitFile(name);
            } catch (RuntimeException e) {
                text = "!" + reason(e);
            }
            String read = text;
            Platform.runLater(() -> {
                linesPulse.setRunning(false);
                hide(linesPulse);
                if (name.equals(detailFor) && !read.startsWith("!")) {
                    unitFile = read;
                }
                ServiceUnit now = board.chosenUnit();
                if (now != null && now.name().equals(name) && showingFile) {
                    fillFile(read);
                }
            });
        });
    }

    /** Only for the previews: what was read, without a server. */
    void linesForPreview(String text, boolean file) {
        showingFile = file;
        paintTabs();
        ServiceUnit unit = board.chosenUnit();
        if (file) {
            linesSource.setText(detail.getOrDefault("FragmentPath", ""));
            fillFile(text);
        } else {
            linesSource.setText(unit == null ? "" : "journalctl --unit " + unit.name());
            fillJournal(text);
        }
    }

    /**
     * The newest line at the bottom, where a terminal puts it, and the view kept there.
     *
     * <p>journalctl run by an account outside the adm and systemd-journal groups shows only that account's
     * own lines, after a hint saying so. The hint is not shown as if it were the journal: the screen says the
     * journal is closed to this account, and offers to read it as root.
     */
    private void fillJournal(String text) {
        boolean atBottom = linesScroll.getVvalue() > 0.97 || lines.getChildren().isEmpty();
        lines.getChildren().clear();
        if (text.startsWith("!")) {
            lines.getChildren().add(line(text.substring(1), "journal-error"));
            return;
        }
        boolean closed = text.contains("not seeing messages from other users")
                || text.contains("insufficient permissions") || text.contains("No journal files were opened");
        List<String> kept = new ArrayList<>();
        boolean inHint = false;
        for (String row : text.split("\\R")) {
            String trimmed = row.strip();
            if (trimmed.startsWith("Hint:") || trimmed.startsWith("No journal files were")) {
                inHint = true;
                continue;
            }
            if (inHint && (row.startsWith(" ") || row.startsWith("\t"))) {
                continue;
            }
            inHint = false;
            if (!trimmed.isEmpty() && !trimmed.equals("-- No entries --")) {
                kept.add(row);
            }
        }
        if (closed) {
            lines.getChildren().add(journalClosed());
        }
        if (kept.isEmpty()) {
            if (!closed) {
                lines.getChildren().add(muted("it has written nothing to the journal"));
            }
            return;
        }
        for (String row : kept) {
            String lower = row.toLowerCase(Locale.ROOT);
            lines.getChildren().add(line(row, lower.contains("error") || lower.contains("failed")
                    || lower.contains("fatal") || lower.contains("denied") ? "journal-error"
                    : lower.contains("warn") ? "journal-warning" : "journal-command"));
        }
        if (atBottom) {
            Platform.runLater(() -> linesScroll.setVvalue(1));
        }
    }

    /** The journal is closed to this account: said once, with the way to read it anyway. */
    private Node journalClosed() {
        Label words = new Label(canAct() ? "the journal of system services is closed to this account"
                : "the journal of system services is closed to this account. a user in the adm group can read it");
        words.getStyleClass().add("fetch-note");
        words.setWrapText(true);
        words.setMinWidth(0);
        words.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(words, Priority.ALWAYS);
        HBox row = new HBox(10, words);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(0, 0, 8, 0));
        if (canAct()) {
            Button read = action("read it as root", event -> withRoot(() -> {
                journalAsRoot = true;
                readJournal();
            }, null));
            read.getStyleClass().addAll("apps-warn", "apps-inline");
            row.getChildren().add(read);
        }
        return row;
    }

    /** The unit file from the top: comments quiet, the section names marked. */
    private void fillFile(String text) {
        lines.getChildren().clear();
        if (text.startsWith("!")) {
            lines.getChildren().add(line(text.substring(1), "journal-error"));
            return;
        }
        if (text.isBlank()) {
            lines.getChildren().add(muted("systemctl cat answered nothing: it may have no file of its own"));
            return;
        }
        for (String row : text.strip().split("\\R", -1)) {
            String trimmed = row.strip();
            lines.getChildren().add(line(row.isEmpty() ? " " : row, trimmed.startsWith("#") || trimmed.startsWith(";")
                    ? "journal-detail" : trimmed.startsWith("[") ? "fetch-key" : "journal-command"));
        }
        Platform.runLater(() -> linesScroll.setVvalue(0));
    }

    private static Label line(String text, String style) {
        Label row = new Label(text);
        row.getStyleClass().add(style);
        row.setMinWidth(0);
        row.setWrapText(true);
        row.setMinHeight(Region.USE_PREF_SIZE);
        return row;
    }

    // Cards

    private void showLimits(ServiceUnit unit) {
        String memory = pretty("MemoryCurrent", detail.get("MemoryCurrent"));
        String memoryLimit = pretty("MemoryMax", detail.get("MemoryMax"));
        String cpuLimit = pretty("CPUQuotaPerSecUSec", detail.get("CPUQuotaPerSecUSec"));
        showOverlay(new LimitCard(unit.shortName(), memory == null ? "—" : memory,
                memoryLimit == null ? "none" : memoryLimit, cpuLimit == null ? "none" : cpuLimit,
                (resource, value, done) -> send(done, () -> control.setLimit(resource, value, unit.name()),
                        "setting a limit on " + unit.shortName()),
                this::hideOverlay));
    }

    private void showSettings(ServiceUnit unit) {
        showOverlay(new SettingsCard(unit.shortName(), ServiceCommands.dropInFile(unit.name()), dropIn,
                detail.getOrDefault("Restart", ""), new SettingsCard.Hands() {
                    @Override
                    public void save(String content, Consumer<String> done) {
                        send(done, () -> {
                            CommandEntry written = control.saveOverride(unit.name(), content);
                            // No point telling systemd to re-read a file that was never written.
                            return written.success() ? control.reloadUnits() : written;
                        }, "saving the settings of " + unit.shortName());
                    }

                    @Override
                    public void remove(Consumer<String> done) {
                        send(done, () -> {
                            CommandEntry gone = control.removeOverride(unit.name());
                            return gone.success() ? control.reloadUnits() : gone;
                        }, "removing the settings of " + unit.shortName());
                    }
                }, this::hideOverlay));
    }

    /** The signals, as a menu under the button. The ones that end the service ask first. */
    private void showSignals(ServiceUnit unit, Node anchor) {
        hideMenu();
        List<MenuItem> items = new ArrayList<>();
        for (Signal signal : Signal.values()) {
            if (Lifeline.refusal(signal, unit.name()) != null) {
                continue;
            }
            MenuItem item = item(signal.signalName().toLowerCase(Locale.ROOT) + "   " + signalWords(signal),
                    () -> askSignal(signal, unit));
            if (signal.rough()) {
                item.getStyleClass().add("redis-menu-danger");
            }
            items.add(item);
        }
        ContextMenu shown = styledMenu(items);
        shown.show(anchor, Side.BOTTOM, 0, 4);
    }

    private static String signalWords(Signal signal) {
        return switch (signal) {
            case HUP -> "re-read its configuration";
            case USR1 -> "its own meaning. nginx reopens its logs";
            case USR2 -> "its own meaning. nginx starts a new binary";
            case TERM -> "ask it to stop";
            case KILL -> "end it now, all of it";
        };
    }

    private void askSignal(Signal signal, ServiceUnit unit) {
        String name = signal.signalName().toLowerCase(Locale.ROOT);
        Consumer<Consumer<String>> go = done -> send(done,
                () -> control.signal(signal, signal == Signal.KILL, unit.name()), "sending " + name);
        if (!signal.rough()) {
            send(trouble -> say(trouble == null, trouble == null ? name + " sent to " + unit.shortName() : trouble),
                    () -> control.signal(signal, false, unit.name()), "sending " + name);
            return;
        }
        showOverlay(new ConfirmCard("send " + name + " to " + unit.shortName() + "?", lower(signal.meaning())
                + (signal == Signal.KILL ? " it goes to every process of the service." : "")
                + " systemd may start it again, depending on its settings.",
                null, "send " + name, true, done -> go.accept(trouble -> {
                    done.accept(trouble);
                    if (trouble == null) {
                        hideOverlay();
                        say(true, name + " sent to " + unit.shortName());
                    }
                }), this::hideOverlay));
    }

    // The menu

    private void hideMenu() {
        if (menu != null) {
            menu.hide();
            menu = null;
        }
    }

    private ContextMenu styledMenu(List<MenuItem> items) {
        ContextMenu shown = new ContextMenu(items.toArray(new MenuItem[0]));
        // A context menu is a window of its own, so Kubo's stylesheet does not reach it by itself.
        shown.getStyleClass().add("redis-context-menu");
        shown.setAutoHide(true);
        shown.setOnHidden(event -> {
            if (menu == shown) {
                menu = null;
            }
        });
        menu = shown;
        return shown;
    }

    /**
     * What can be done with the service that was right clicked: the verbs, where to read it, and the moves.
     *
     * <p>A standing is not a place a service is put: it is what its two answers add up to. So "make it
     * running" is a short sequence of ordinary verbs, and only the moves that would really land where they
     * say are offered.
     */
    private void showMenu(ServiceUnit unit, double[] at) {
        hideMenu();
        List<MenuItem> items = new ArrayList<>();
        boolean act = canAct();
        if (act) {
            if (unit.active() && !unit.oneShot()) {
                items.add(item("restart", () -> ask(Action.RESTART, unit)));
            }
            if ((!unit.active() || unit.failed()) && unit.boot() != BootState.MASKED) {
                items.add(item("start", () -> ask(Action.START, unit)));
            } else if (!unit.oneShot() && Lifeline.refusal(Action.STOP, unit.name()) == null) {
                items.add(item("stop", () -> ask(Action.STOP, unit)));
            }
            if (unit.boot() == BootState.DISABLED) {
                items.add(item("turn on at boot", () -> ask(Action.ENABLE, unit)));
            } else if (unit.boot() == BootState.ENABLED && Lifeline.refusal(Action.DISABLE, unit.name()) == null) {
                items.add(item("turn off at boot", () -> ask(Action.DISABLE, unit)));
            }
            items.add(new SeparatorMenuItem());
        }
        items.add(item("its journal", () -> showFile(false)));
        items.add(item("its unit file", () -> showFile(true)));
        if (act) {
            items.add(item("limits…", () -> showLimits(unit)));
            items.add(item("its own settings…", () -> showSettings(unit)));
            List<Standing> targets = Move.targets(unit);
            if (!targets.isEmpty()) {
                items.add(new SeparatorMenuItem());
                for (Standing target : targets) {
                    MenuItem entry = item("make it " + ServicesBoard.word(target), () -> askMove(target, unit));
                    // The moves that take something away are marked as such, the way they are everywhere else.
                    if (Move.stepsTo(target, unit).stream().anyMatch(Action::takesAway)) {
                        entry.getStyleClass().add("redis-menu-danger");
                    }
                    items.add(entry);
                }
            }
            items.add(new SeparatorMenuItem());
            // Whether it may go at all depends on where its file is, which is one read away.
            MenuItem delete = item("delete " + unit.shortName() + "…", () -> findThenRemove(unit));
            delete.getStyleClass().add("redis-menu-danger");
            items.add(delete);
        }
        styledMenu(items).show(this, at[0], at[1]);
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

    // Acting

    /**
     * Anything that takes something away makes the user type the service name back first. Anything that only
     * adds, starting and enabling, runs on the click, because the button beside it undoes it.
     */
    private void ask(Action action, ServiceUnit unit) {
        String refusal = Lifeline.refusal(action, unit.name());
        if (refusal != null) {
            // The guard is in the service layer as well; this is the one the user reads.
            showOverlay(ConfirmCard.refusal(verb(action) + " " + unit.shortName(), refusal, this::hideOverlay));
            return;
        }
        if (!action.takesAway()) {
            act(action, unit, null);
            return;
        }
        String expected = unit.shortName();
        showOverlay(new ConfirmCard(verb(action) + " " + expected + "?",
                lower(action.meaning()) + reach(action, unit), expected, verb(action) + " it", true,
                done -> act(action, unit, done), this::hideOverlay));
    }

    private static String verb(Action action) {
        return action.label().toLowerCase(Locale.ROOT);
    }

    private void act(Action action, ServiceUnit unit, Consumer<String> finished) {
        if (control == null) {
            return;
        }
        working(verb(action) + " " + unit.shortName());
        withRoot(() -> worker.execute(() -> {
            String trouble;
            try {
                CommandEntry result = control.run(action, unit.name());
                trouble = result.success() ? null : said(result);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Acting on a service failed", e);
                trouble = reason(e);
            }
            String outcome = trouble;
            Platform.runLater(() -> {
                resting();
                if (finished != null) {
                    finished.accept(outcome);
                }
                if (outcome == null) {
                    hideOverlay();
                    say(true, unit.shortName() + ": " + verb(action) + " done");
                    refresh();
                    readDetail(unit.name());
                    readLines();
                } else if (finished == null) {
                    say(false, outcome);
                }
            });
        }), finished);
    }

    /**
     * Runs one thing on the server for a card, asking for the sudo password first if it is not held yet.
     * The card is told what came of it, and the board is read again when it worked.
     */
    private void send(Consumer<String> done, Supplier<CommandEntry> work, String saying) {
        if (!canAct()) {
            done.accept("kubo cannot change anything on this server: it has no way to become root");
            return;
        }
        working(saying);
        withRoot(() -> worker.execute(() -> {
            String trouble;
            try {
                CommandEntry result = work.get();
                trouble = result.success() ? null : said(result);
            } catch (IllegalArgumentException | IllegalStateException e) {
                // A value Kubo would not send, or an action it will not run: its own words, not systemd's.
                trouble = e.getMessage();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "A service change failed", e);
                trouble = reason(e);
            }
            String outcome = trouble;
            Platform.runLater(() -> {
                resting();
                done.accept(outcome);
                if (outcome == null) {
                    refresh();
                    if (detailFor != null) {
                        readDetail(detailFor);
                    }
                }
            });
        }), done);
    }

    /**
     * Asks before a move that takes something away, and refuses the ones that would cut this very connection:
     * each step of the move is judged, not only the move as a whole.
     */
    private void askMove(Standing target, ServiceUnit unit) {
        List<Action> steps = Move.stepsTo(target, unit);
        for (Action step : steps) {
            String refusal = Lifeline.refusal(step, unit.name());
            if (refusal != null) {
                showOverlay(ConfirmCard.refusal("make " + unit.shortName() + " " + ServicesBoard.word(target),
                        refusal, this::hideOverlay));
                return;
            }
        }
        if (steps.stream().noneMatch(Action::takesAway)) {
            walk(steps, unit, target, null);
            return;
        }
        String expected = unit.shortName();
        showOverlay(new ConfirmCard("make " + expected + " " + ServicesBoard.word(target) + "?",
                "this runs " + describe(steps) + " on " + expected + "." + reach(steps.get(0), unit),
                expected, "do it", true, done -> walk(steps, unit, target, done), this::hideOverlay));
    }

    /** The steps in words, so the card says what it is about to run rather than only where it lands. */
    private static String describe(List<Action> steps) {
        List<String> words = new ArrayList<>();
        for (Action step : steps) {
            words.add(verb(step));
        }
        return String.join(" then ", words);
    }

    /**
     * Runs the steps of a move in order, stopping at the first one that fails. A move is not one command, so
     * it can fail half way; when it does, the screen says which step failed and reads again.
     */
    private void walk(List<Action> steps, ServiceUnit unit, Standing target, Consumer<String> finished) {
        working("making " + unit.shortName() + " " + ServicesBoard.word(target));
        withRoot(() -> worker.execute(() -> {
            String trouble = null;
            Action failedAt = null;
            for (Action step : steps) {
                try {
                    CommandEntry result = control.run(step, unit.name());
                    if (!result.success()) {
                        trouble = said(result);
                    }
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "A step of a move failed", e);
                    trouble = reason(e);
                }
                if (trouble != null) {
                    failedAt = step;
                    break;
                }
            }
            String outcome = trouble == null ? null : verb(failedAt) + " failed, so the move stopped there: " + trouble;
            Platform.runLater(() -> {
                resting();
                if (finished != null) {
                    finished.accept(outcome);
                }
                if (outcome == null) {
                    hideOverlay();
                    say(true, unit.shortName() + " is now " + ServicesBoard.word(target));
                    // The board moves now rather than in five round trips' time; the reading that follows
                    // either agrees or corrects it.
                    apply(with(reading, Move.after(steps, unit)));
                } else if (finished == null) {
                    say(false, outcome);
                }
                refresh();
            });
        }), finished);
    }

    /** The same reading with one service replaced, which is how a move is shown before it is confirmed. */
    private static ServicesReport with(ServicesReport report, ServiceUnit moved) {
        List<ServiceUnit> units = new ArrayList<>(report.units().size());
        for (ServiceUnit unit : report.units()) {
            units.add(unit.name().equals(moved.name()) ? moved : unit);
        }
        return new ServicesReport(units, report.boot(), report.userspace(), report.unreadable());
    }

    /** Reads where the unit file is, then asks. Only the menu needs this: the tile already knows the path. */
    private void findThenRemove(ServiceUnit unit) {
        if (reader == null) {
            return;
        }
        if (unit.name().equals(detailFor) && !detail.isEmpty()) {
            askRemoval(unit, detail.getOrDefault("FragmentPath", ""));
            return;
        }
        working("reading " + unit.shortName());
        worker.execute(() -> {
            String filePath;
            try {
                filePath = reader.detail(unit.name()).getOrDefault("FragmentPath", "");
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Reading one unit failed", e);
                String trouble = reason(e);
                Platform.runLater(() -> {
                    resting();
                    say(false, trouble);
                });
                return;
            }
            String found = filePath;
            Platform.runLater(() -> {
                resting();
                askRemoval(unit, found);
            });
        });
    }

    /**
     * Asks before taking a service off the machine, saying which file goes. The path is the whole judgement:
     * a file in /etc/systemd/system was put there by hand, one anywhere else belongs to a package.
     */
    private void askRemoval(ServiceUnit unit, String filePath) {
        String refusal = Removal.refusal(unit.name(), filePath);
        if (refusal != null) {
            showOverlay(ConfirmCard.refusal("delete " + unit.shortName(), refusal, this::hideOverlay));
            return;
        }
        List<Action> steps = Removal.steps(unit);
        String expected = unit.shortName();
        String what = steps.isEmpty() ? "this deletes " : "this runs " + describe(steps) + ", then deletes ";
        showOverlay(new ConfirmCard("delete " + expected + "?",
                what + filePath + " and any settings kubo wrote beside it. nothing undoes this: the file is "
                        + "gone, not disabled." + reach(Action.STOP, unit),
                expected, "delete it", true, done -> erase(steps, unit, filePath, done), this::hideOverlay));
    }

    /**
     * Stops it, disables it, deletes its file, takes its settings with it and tells systemd. The order is the
     * point: anything failing before the file goes stops the whole thing, so a half-deleted service is not a
     * state this can end in.
     */
    private void erase(List<Action> steps, ServiceUnit unit, String filePath, Consumer<String> finished) {
        working("deleting " + unit.shortName());
        withRoot(() -> worker.execute(() -> {
            String trouble = null;
            try {
                for (Action first : steps) {
                    CommandEntry result = control.run(first, unit.name());
                    if (!result.success()) {
                        trouble = verb(first) + " failed, so nothing was deleted: " + said(result);
                        break;
                    }
                }
                if (trouble == null) {
                    trouble = step(control.removeUnit(unit.name(), filePath), "deleting " + filePath);
                }
                if (trouble == null) {
                    // A drop-in for a unit that is gone is a file nothing will ever read again.
                    trouble = step(control.removeOverride(unit.name()), "removing its own settings");
                }
                if (trouble == null) {
                    trouble = step(control.reloadUnits(), "telling systemd the file has gone");
                }
                if (trouble == null) {
                    // Allowed to find nothing: it only clears a failed entry, and most units never failed.
                    control.resetFailed(unit.name());
                }
            } catch (IllegalStateException e) {
                trouble = e.getMessage();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Deleting a service failed", e);
                trouble = reason(e);
            }
            String outcome = trouble;
            Platform.runLater(() -> {
                resting();
                if (finished != null) {
                    finished.accept(outcome);
                }
                if (outcome == null) {
                    hideOverlay();
                    say(true, unit.name() + " deleted");
                    detailFor = null;
                    refresh();
                }
            });
        }), finished);
    }

    /** What else this may take with it, when the reading knows of something leaning on this service. */
    private String reach(Action action, ServiceUnit unit) {
        List<String> dependents = dependencies.neededByOf(unit.name());
        if (dependents.isEmpty() || action == Action.ENABLE || action == Action.DISABLE) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (String name : dependents) {
            names.add(name.endsWith(".service") ? name.substring(0, name.length() - ".service".length()) : name);
        }
        boolean one = names.size() == 1;
        return " systemd says " + String.join(", ", names) + (one ? " depends" : " depend") + " on it, so "
                + (one ? "it may go too." : "they may go too.");
    }

    // A new service

    /** The card for making something on this server into a service. */
    private void openBuilder() {
        String account = connection == null ? null : connection.target().username();
        showOverlay(new NewServiceCard(account, browser == null ? null : this::pick, this::hideOverlay,
                this::write));
    }

    /** Opens the server's folders over the card, to walk to a program or a folder rather than type it. */
    private void pick(PathKind kind, String start, Consumer<String> chosen) {
        PathPicker picker = new PathPicker(kind == PathKind.FOLDER ? "where it runs" : "what it runs", kind,
                (path, done) -> {
                    if (worker.isShutdown()) {
                        return;
                    }
                    worker.execute(() -> {
                        DirectoryListing listing;
                        try {
                            listing = browser.list(path);
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

    /**
     * Writes a new service, tells systemd it is there, and does what was asked with it. Four commands, and the
     * screen says which one failed rather than a single word for the lot.
     */
    private void write(UnitDraft draft, String text, boolean startIt, Consumer<String> done) {
        if (!canAct()) {
            done.accept("kubo cannot write a service on this server: it has no way to become root");
            return;
        }
        String unit = draft.unitName();
        withRoot(() -> worker.execute(() -> {
            String trouble = null;
            try {
                if (reader != null && reader.exists(unit)) {
                    trouble = "there is already a unit called " + unit + " on this server. kubo will not write "
                            + "over it.";
                }
                if (trouble == null) {
                    // The text the screen showed, not a second generation of it.
                    trouble = step(control.createUnit(unit, text), "writing " + unit);
                }
                if (trouble == null) {
                    trouble = step(control.reloadUnits(), "telling systemd the file is there");
                }
                if (trouble == null && draft.atBoot()) {
                    trouble = step(control.run(Action.ENABLE, unit), "setting it to start at boot");
                }
                if (trouble == null && startIt) {
                    trouble = step(control.run(Action.START, unit), "starting it");
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Creating a service failed", e);
                trouble = reason(e);
            }
            String outcome = trouble;
            Platform.runLater(() -> {
                done.accept(outcome);
                if (outcome == null) {
                    hideOverlay();
                    say(true, unit + " created" + (startIt ? " and started" : "")
                            + (draft.atBoot() ? ", and set to start at boot" : ""));
                    find.clear();
                    pickWhenRead = unit;
                    refresh();
                }
            });
        }), done);
    }

    // Small pieces

    private void working(String what) {
        working.setText(what);
        working.setRunning(true);
        show(working);
        hide(status);
    }

    private void resting() {
        working.setRunning(false);
        hide(working);
    }

    private void say(boolean good, String text) {
        status.getStyleClass().setAll("label", good ? "card-success" : "card-problem");
        status.setText(text);
        show(status);
    }

    /**
     * Asks for the sudo password once, when it is not already held for this session.
     *
     * @param cancelled told when the password card is closed without one, so a waiting card does not wait on
     */
    private void withRoot(Runnable action, Consumer<String> cancelled) {
        if (control != null && control.needsPassword() && root != null) {
            showOverlayOnTop(new SudoPrompt(root.privilege(), verification(), () -> {
                root.refresh();
                hideTopOverlay();
                action.run();
            }, () -> {
                hideTopOverlay();
                resting();
                if (cancelled != null) {
                    cancelled.accept("no password given, nothing was sent");
                }
            }));
        } else {
            action.run();
        }
    }

    /** Checks a sudo password against this server, for the root button and the prompts. */
    private BiConsumer<Privilege.Command, Consumer<Boolean>> verification() {
        if (control == null) {
            return RootButton.noVerification();
        }
        return (command, answer) -> {
            if (worker.isShutdown()) {
                answer.accept(false);
                return;
            }
            worker.execute(() -> {
                CommandEntry entry = control.verify(command);
                Platform.runLater(() -> answer.accept(entry.success()));
            });
        };
    }

    /** What PM2 and supervisor run, as a card over the screen: they are services too, kept by someone else. */
    private void showApps() {
        if (connection != null) {
            showOverlay(com.kubo.ui.apps.AppsMini.open(connection, commands, root, null, this::hideOverlay));
        }
    }

    private void showOverlay(Region card) {
        hideOverlay();
        hideMenu();
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
        name.setMinWidth(66);
        return name;
    }

    private static HBox fact(String key, String value, String style) {
        return fact(key, value, style, null);
    }

    /** A line of the service: what it is about, what it says, and what changes it at the far end. */
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

    static Button action(String text, EventHandler<ActionEvent> action) {
        Button button = new Button(text);
        button.getStyleClass().add("ghost-button");
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        if (action != null) {
            button.setOnAction(action);
        }
        return button;
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

    private static String hex(Color colour) {
        return String.format("#%02x%02x%02x", (int) Math.round(colour.getRed() * 255),
                (int) Math.round(colour.getGreen() * 255), (int) Math.round(colour.getBlue() * 255));
    }

    /** One command of a sequence, named by what it was for so a failure says where it stopped. */
    private static String step(CommandEntry result, String what) {
        return result.success() ? null : "stopped while " + what + ": " + said(result);
    }

    /** The first line systemctl gave, the error when there is one. */
    private static String said(CommandEntry result) {
        String text = result.error().isBlank() ? result.output() : result.error();
        return text == null || text.isBlank() ? "it failed without saying why"
                : text.strip().lines().findFirst().orElse("it failed");
    }

    /** A sentence from the model, started in lower case like every other line of the screen. */
    private static String lower(String sentence) {
        return sentence == null || sentence.isEmpty() ? ""
                : Character.toLowerCase(sentence.charAt(0)) + sentence.substring(1);
    }

    private static String reason(RuntimeException problem) {
        return problem.getMessage() == null || problem.getMessage().isBlank()
                ? problem.getClass().getSimpleName() : problem.getMessage();
    }

    /**
     * systemd's raw values, in the words a person uses: a byte count is a size, an unset property is left out
     * rather than shown as the largest number a computer can hold, and ExecStart is a whole structure of which
     * only the command line matters.
     */
    static String pretty(String key, String value) {
        if (value == null || value.isBlank() || value.equals("[not set]") || value.equals("18446744073709551615")) {
            return null;
        }
        if ((key.equals("MainPID") && value.equals("0")) || (key.equals("ActiveEnterTimestamp") && value.isBlank())) {
            return null;
        }
        return switch (key) {
            case "MemoryCurrent", "MemoryMax" -> size(value);
            case "CPUQuotaPerSecUSec" -> quota(value);
            case "ExecStart" -> commandLine(value);
            default -> value;
        };
    }

    /** systemd answers a quota as microseconds of processor per second. A person reads it as a percentage. */
    private static String quota(String value) {
        String trimmed = value.trim();
        if (trimmed.equals("infinity")) {
            return null;
        }
        try {
            long micros;
            if (trimmed.endsWith("ms")) {
                micros = Math.round(Double.parseDouble(trimmed.replace("ms", "").trim()) * 1000);
            } else if (trimmed.endsWith("us")) {
                micros = Long.parseLong(trimmed.replace("us", "").trim());
            } else if (trimmed.endsWith("s")) {
                micros = Math.round(Double.parseDouble(trimmed.replace("s", "").trim()) * 1_000_000);
            } else {
                micros = Long.parseLong(trimmed);
            }
            return Math.round(micros / 10_000.0) + "%";
        } catch (NumberFormatException e) {
            return trimmed;
        }
    }

    /** ExecStart reads {@code { path=... ; argv[]=... ; ignore_errors=no ... }}: only the argv is the command. */
    private static String commandLine(String value) {
        int start = value.indexOf("argv[]=");
        if (start < 0) {
            return value;
        }
        int end = value.indexOf(" ; ", start);
        return value.substring(start + "argv[]=".length(), end < 0 ? value.length() : end).trim();
    }

    private static String size(String value) {
        String trimmed = value.trim();
        if (trimmed.equals("infinity")) {
            return "no limit";
        }
        long bytes;
        try {
            bytes = Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return trimmed;
        }
        String[] units = {"B", "KB", "MB", "GB"};
        double size = bytes;
        int unit = 0;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, unit == 0 ? "%.0f %s" : "%.1f %s", size, units[unit]);
    }

    /** What one of the services commands reads, in a few words. */
    static String words(String command) {
        if (command == null) {
            return null;
        }
        if (command.equals(ServiceCommands.PRESENT)) {
            return "is systemd here";
        }
        if (command.equals(ServiceCommands.UNITS)) {
            return "what is loaded";
        }
        if (command.equals(ServiceCommands.UNIT_FILES)) {
            return "what comes back at the next boot";
        }
        if (command.equals(ServiceCommands.BLAME)) {
            return "what each unit cost the last boot";
        }
        if (command.equals(ServiceCommands.BOOT_TIME)) {
            return "how long the last boot took";
        }
        if (command.contains("list-dependencies") || command.contains("show ")) {
            return "what depends on what";
        }
        for (String part : command.strip().split("\\s+")) {
            if (!part.contains("=")) {
                return part;
            }
        }
        return "command";
    }

    // Previews

    /** Fills the screen from a reading already in hand. Nothing is sent to a server. */
    void preview(ServicesReport read, Dependencies links) {
        setDependencies(links);
        apply(read);
    }

    /** Only for the previews: picks a service and fills its detail as if it had been read. */
    void pickForPreview(String name, Map<String, String> properties, String settings) {
        board.choose(name);
        detailFor = name;
        detail = properties;
        dropIn = settings == null ? "" : settings;
        drawChosen();
    }

    void filterForPreview(String text) {
        find.setText(text);
    }

    void openForPreview(Standing standing) {
        board.openStanding(standing);
    }

    void newServiceForPreview(String path, String folder) {
        NewServiceCard card = new NewServiceCard("xana", null, this::hideOverlay, (draft, text, now, done) -> { });
        card.fillForPreview(path, folder);
        showOverlay(card);
    }

    ServicesBoard board() {
        return board;
    }

    void showForPreview(Region card) {
        showOverlay(card);
    }

    void limitsForPreview() {
        ServiceUnit unit = board.chosenUnit();
        if (unit != null) {
            showLimits(unit);
        }
    }

    void settingsForPreview() {
        ServiceUnit unit = board.chosenUnit();
        if (unit != null) {
            showSettings(unit);
        }
    }

    void confirmForPreview() {
        ServiceUnit unit = board.chosenUnit();
        if (unit != null) {
            showOverlay(new ConfirmCard(verb(Action.STOP) + " " + unit.shortName() + "?",
                    lower(Action.STOP.meaning()) + reach(Action.STOP, unit), unit.shortName(), "stop it", true,
                    done -> { }, this::hideOverlay));
        }
    }

    /** Only for the previews: the first reading, part way through. */
    void readingForPreview() {
        java.time.Instant now = java.time.Instant.now();
        journal.showForPreview("reading", "xana@vmi2412345",
                "what systemd runs, what comes back at the next boot, what the last boot cost", List.of(
                        new CommandEntry(now, ServiceCommands.PRESENT, false, 0, "", "",
                                java.time.Duration.ofMillis(22)),
                        new CommandEntry(now, ServiceCommands.UNITS, false, 0, "", "",
                                java.time.Duration.ofMillis(141)),
                        new CommandEntry(now, ServiceCommands.UNIT_FILES, false, 0, "", "",
                                java.time.Duration.ofMillis(96))));
    }
}
