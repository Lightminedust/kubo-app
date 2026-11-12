package com.kubo.ui.apps;

import com.kubo.apps.AppCommands;
import com.kubo.apps.AppService;
import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.model.apps.App;
import com.kubo.model.apps.AppProcess;
import com.kubo.model.apps.AppsReport;
import com.kubo.model.apps.Manager;
import com.kubo.privilege.RootSession;
import com.kubo.ui.Pulse;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The apps screen as a card any other screen can put over itself: what PM2 and supervisor run, one line each,
 * with the three things most often wanted without leaving where you are, restart, stop and the last lines of
 * the log.
 *
 * <p>It is the short version on purpose. Starting a new application, changing how many workers one has or
 * deleting one are in the full screen, which the card opens on the application that is picked.
 *
 * <p>Opened with {@link #open}: one line from the network screen, the services screen, or anything else that
 * has a connection.
 */
public final class AppsMini extends VBox {

    private static final System.Logger LOG = System.getLogger(AppsMini.class.getName());
    private static final int LOG_LINES = 60;

    private final AppService service;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-apps-mini");
        thread.setDaemon(true);
        return thread;
    });
    private final Timeline poll = new Timeline(new KeyFrame(Duration.seconds(5), event -> refresh()));
    private final Map<String, Color> colours = new HashMap<>();
    private final Label who = new Label();
    private final VBox rows = new VBox(0);
    private final VBox logBox = new VBox(8);
    private final ScrollPane body;
    private final Pulse working = new Pulse();
    private final Label status = new Label();
    private final Label facts = new Label();
    private final Button save;
    private final Consumer<String> onOpenFull;

    private AppsReport report = AppsReport.EMPTY;
    private String chosen;
    private boolean reading;
    private boolean everRead;
    private boolean closed;
    private boolean showingLog;

    /**
     * The card for this server, reading through its own connection.
     *
     * @param focus   the name of the application to open on, or null for the first
     * @param onClose puts the card away
     */
    public static AppsMini open(SshConnection connection, CommandService commands, RootSession root, String focus,
                                Runnable onClose) {
        AppService service = new AppService(connection, commands, root == null ? null : root.privilege());
        return new AppsMini(service, connection.target().username(), focus, key -> {
            onClose.run();
            AppsWindow.open(connection, commands, root, key);
        }, onClose);
    }

    /**
     * @param service    reads and acts, or null for a card filled by hand
     * @param onOpenFull opens the full screen on the application with this key, which may be null
     */
    AppsMini(AppService service, String account, String focus, Consumer<String> onOpenFull, Runnable onClose) {
        this.service = service;
        this.chosen = focus;
        this.onOpenFull = onOpenFull;
        this.save = AppsScope.action("save the list", event -> act("pm2 save", () -> this.service.save(),
                "the list is saved"));
        getStyleClass().addAll("float-card", "redis-help");
        setSpacing(12);
        setPadding(new Insets(20, 24, 16, 24));
        setPrefWidth(600);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        Label title = new Label("apps");
        title.getStyleClass().add("fetch-host");
        who.getStyleClass().add("home-muted");
        who.setText(account == null || account.isEmpty() ? "" : "pm2 of " + account);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button full = AppsScope.action("full screen", event -> onOpenFull.accept(chosenKey()));
        full.getStyleClass().add("apps-lit");
        Button close = AppsScope.action("close", event -> onClose.run());
        HBox top = new HBox(14, title, who, spacer, full, close);
        top.setAlignment(Pos.BASELINE_LEFT);

        Pulse first = new Pulse();
        first.setText("reading what pm2 and supervisor run");
        first.setRunning(service != null);
        rows.getChildren().add(first);
        body = new ScrollPane(rows);
        body.setFitToWidth(true);
        body.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        body.getStyleClass().add("track-scroll");
        body.setMinHeight(120);
        body.setPrefHeight(330);
        body.setMaxHeight(420);

        working.setRunning(false);
        working.setVisible(false);
        working.setManaged(false);
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        status.setVisible(false);
        status.setManaged(false);
        facts.getStyleClass().add("home-muted");
        facts.setMinWidth(Region.USE_PREF_SIZE);
        save.getStyleClass().add("apps-warn");
        save.setVisible(false);
        save.setManaged(false);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox.setHgrow(status, Priority.ALWAYS);
        HBox foot = new HBox(14, working, status, gap, facts, save);
        foot.setAlignment(Pos.CENTER_LEFT);
        Region rule = new Region();
        rule.getStyleClass().add("fetch-rule");
        rule.setMinHeight(1);
        rule.setMaxHeight(1);

        setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                if (showingLog) {
                    showList();
                } else {
                    onClose.run();
                }
                event.consume();
            }
        });
        getChildren().addAll(top, body, rule, foot);

        poll.setCycleCount(Timeline.INDEFINITE);
        // It reads while it is on a screen, and stops the moment it is taken off.
        sceneProperty().addListener((observable, before, now) -> {
            if (now == null) {
                shutdown();
            } else if (service != null && !closed) {
                refresh();
                poll.play();
            }
        });
    }

    private void shutdown() {
        closed = true;
        poll.stop();
        worker.shutdownNow();
    }

    private String chosenKey() {
        App app = chosen();
        return app == null ? null : app.key();
    }

    private App chosen() {
        for (App app : report.apps()) {
            if (app.key().equals(chosen) || app.name().equals(chosen)) {
                return app;
            }
        }
        return null;
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
                read = new AppsReport(false, false, List.of(), 0, 0, 0, "", List.of(AppsScope.reason(e)));
            }
            AppsReport answer = read;
            Platform.runLater(() -> {
                reading = false;
                if (!closed) {
                    apply(answer);
                }
            });
        });
    }

    /** Shows a reading. Also the way the previews fill the card without a server. */
    void apply(AppsReport read) {
        report = read;
        everRead = true;
        for (App app : read.apps()) {
            colours.computeIfAbsent(app.key(), key -> AppsBoard.PALETTE[colours.size() % AppsBoard.PALETTE.length]);
        }
        App picked = chosen();
        chosen = picked != null ? picked.key() : read.apps().isEmpty() ? null : read.apps().get(0).key();
        if (!showingLog) {
            drawRows();
        }
        drawFoot();
    }

    private void drawRows() {
        rows.getChildren().clear();
        if (!everRead) {
            return;
        }
        if (report.apps().isEmpty()) {
            String words = !report.notes().isEmpty() ? report.notes().get(0)
                    : !report.pm2() && !report.supervisor() ? "no process manager on this server"
                    : "nothing is kept running yet. the full screen starts a new app";
            Label empty = new Label(words);
            empty.getStyleClass().add("home-muted");
            empty.setWrapText(true);
            empty.setPadding(new Insets(18, 0, 18, 0));
            rows.getChildren().add(empty);
            return;
        }
        for (App app : report.apps()) {
            rows.getChildren().add(row(app));
        }
    }

    /** One application: its colour and name, how it holds, its port, its processes, what it uses. */
    private VBox row(App app) {
        boolean picked = app.key().equals(chosen);
        boolean running = app.online() > 0;
        Color colour = colours.getOrDefault(app.key(), AppsBoard.CYAN);

        Region swatch = new Region();
        swatch.setMinSize(8, 8);
        swatch.setMaxSize(8, 8);
        swatch.setStyle("-fx-background-color: " + AppsScope.hex(colour) + ";" + (running ? "" : "-fx-opacity: 0.5;"));
        Label name = new Label(app.name());
        name.getStyleClass().add(picked ? "apps-mini-name-on" : "apps-mini-name");
        name.setMinWidth(150);
        name.setPrefWidth(150);
        Label state = new Label("● " + AppsBoard.stateWords(app));
        state.setMinWidth(Region.USE_PREF_SIZE);
        state.getStyleClass().add(switch (app.state()) {
            case UP -> "journal-ok";
            case FAILED -> "port-alert";
            case PARTLY -> "fetch-note";
            default -> "port-what";
        });
        Label how = new Label(AppsBoard.how(app));
        how.getStyleClass().add("home-muted");
        how.setMinWidth(Region.USE_PREF_SIZE);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        Label port = new Label();
        port.setMinWidth(Region.USE_PREF_SIZE);
        if (!app.ports().isEmpty()) {
            App.Listening first = app.ports().get(0);
            port.setText(first.everywhere() ? ":" + first.port() + " open" : ":" + first.port());
            port.getStyleClass().add(first.everywhere() ? "port-alert" : "apps-mini-port");
        }
        Label cpu = figure(running ? Math.round(app.cores() * 100) + "%" : "—", 44, running);
        Label memory = figure(running ? AppsBoard.bytes(app.memory()) : "—", 62, running);
        HBox line = new HBox(10, swatch, name, state, how, gap, port, cells(app, colour), cpu, memory);
        line.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, line);
        box.getStyleClass().add(picked ? "apps-mini-row-on" : "apps-mini-row");
        box.setPadding(new Insets(9, 10, 9, 10));
        box.setOnMouseClicked(event -> {
            chosen = app.key();
            drawRows();
        });
        if (picked) {
            box.setStyle("-fx-border-color: transparent transparent transparent " + AppsScope.hex(colour)
                    + "; -fx-border-width: 0 0 0 2;");
            box.getChildren().add(actions(app));
            if (!app.saved()) {
                Label unsaved = new Label("not in the saved list: gone after a reboot");
                unsaved.getStyleClass().add("fetch-note");
                box.getChildren().add(unsaved);
            }
        }
        return box;
    }

    private HBox actions(App app) {
        boolean running = app.online() > 0;
        HBox actions = new HBox(16);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(0, 0, 0, 18));
        if (app.manager() == Manager.PM2) {
            boolean named = AppCommands.validName(app.name());
            actions.getChildren().add(AppsScope.action("restart", event -> act("pm2 restart " + app.name(),
                    () -> service.pm2(AppCommands.Pm2Verb.RESTART, app.name()), app.name() + " restarted")));
            if (app.cluster() && running) {
                actions.getChildren().add(AppsScope.action("reload", event -> act("pm2 reload " + app.name(),
                        () -> service.pm2(AppCommands.Pm2Verb.RELOAD, app.name()),
                        app.name() + " reloaded, one worker at a time")));
            }
            actions.getChildren().add(running
                    ? AppsScope.action("stop", event -> act("pm2 stop " + app.name(),
                            () -> service.pm2(AppCommands.Pm2Verb.STOP, app.name()), app.name() + " stopped"))
                    : AppsScope.action("start", event -> act("pm2 start " + app.name(),
                            () -> service.pm2(AppCommands.Pm2Verb.START, app.name()), app.name() + " started")));
            if (!named) {
                actions.getChildren().forEach(node -> node.setDisable(true));
            }
        } else {
            actions.getChildren().add(AppsScope.action("restart", event -> supervise(app,
                    AppCommands.SupervisorVerb.RESTART, "restarted")));
            actions.getChildren().add(running
                    ? AppsScope.action("stop", event -> supervise(app, AppCommands.SupervisorVerb.STOP, "stopped"))
                    : AppsScope.action("start", event -> supervise(app, AppCommands.SupervisorVerb.START,
                            "started")));
        }
        actions.getChildren().add(AppsScope.action("output", event -> showLog(app, false)));
        actions.getChildren().add(AppsScope.action("errors", event -> showLog(app, true)));
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        AppProcess first = app.first();
        Label pid = new Label(first != null && first.pid() > 0 && app.processes().size() == 1
                ? "pid " + first.pid() : "↻ " + app.restarts());
        pid.getStyleClass().add("home-muted");
        actions.getChildren().addAll(gap, pid);
        return actions;
    }

    /** The processes as small cells, filled by the CPU each one uses. Drawn once a reading: nothing moves. */
    private static Canvas cells(App app, Color colour) {
        List<AppProcess> processes = app.processes();
        int shown = Math.min(processes.size(), 8);
        double cell = 7;
        double gap = 3;
        double height = 16;
        Canvas canvas = new Canvas(8 * (cell + gap), height);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double left = canvas.getWidth() - shown * (cell + gap);
        for (int i = 0; i < shown; i++) {
            AppProcess process = processes.get(i);
            double x = left + i * (cell + gap);
            gc.setFill(Color.web("#0b0b12"));
            gc.fillRect(x, 0, cell, height);
            if (process.online()) {
                double filled = Math.max(2, (height - 2) * Math.min(1, process.cpu() / 100));
                gc.setFill(colour);
                gc.fillRect(x + 1, height - 1 - filled, cell - 2, filled);
                gc.setStroke(colour.deriveColor(0, 1, 1, 0.7));
            } else {
                gc.setStroke(process.failed() ? AppsBoard.RED : AppsBoard.GREY);
            }
            gc.setLineWidth(1);
            gc.strokeRect(x + 0.5, 0.5, cell - 1, height - 1);
        }
        return canvas;
    }

    private static Label figure(String text, double width, boolean lit) {
        Label label = new Label(text);
        label.getStyleClass().add(lit ? "fetch-value" : "port-what");
        label.setMinWidth(width);
        label.setPrefWidth(width);
        label.setAlignment(Pos.CENTER_RIGHT);
        return label;
    }

    private void drawFoot() {
        if (!report.pm2()) {
            facts.setText("");
        } else {
            facts.setText(report.startsAtBoot() ? "pm2 starts at boot" : "pm2 does not start at boot");
        }
        facts.getStyleClass().setAll("label", report.pm2() && !report.startsAtBoot() ? "fetch-note" : "home-muted");
        boolean unsaved = report.unsaved() > 0;
        save.setText(report.unsaved() + " not saved  ·  save");
        save.setVisible(unsaved);
        save.setManaged(unsaved);
    }

    // Acting

    private void supervise(App app, AppCommands.SupervisorVerb verb, String done) {
        if (service != null && service.needsPassword()) {
            say(false, "supervisor needs root: turn root on first");
            return;
        }
        act("supervisorctl " + verb.name().toLowerCase(Locale.ROOT) + " " + app.name(),
                () -> service.supervisor(verb, app.name()), app.name() + " " + done);
    }

    private void act(String what, Supplier<CommandEntry> send, String done) {
        if (service == null || closed) {
            return;
        }
        working.setText(what);
        working.setRunning(true);
        working.setVisible(true);
        working.setManaged(true);
        status.setVisible(false);
        status.setManaged(false);
        worker.execute(() -> {
            String trouble;
            try {
                CommandEntry entry = send.get();
                trouble = entry.success() ? null : AppsScope.problem(entry);
            } catch (RuntimeException e) {
                trouble = AppsScope.reason(e);
            }
            String result = trouble;
            Platform.runLater(() -> {
                working.setRunning(false);
                working.setVisible(false);
                working.setManaged(false);
                say(result == null, result == null ? done : result);
                refresh();
            });
        });
    }

    private void say(boolean good, String text) {
        status.getStyleClass().setAll("label", good ? "card-success" : "card-problem");
        status.setText(text);
        status.setVisible(true);
        status.setManaged(true);
    }

    // The log

    private void showLog(App app, boolean errors) {
        showingLog = true;
        Label title = new Label((errors ? "errors of " : "output of ") + app.name());
        title.getStyleClass().add("fetch-key");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Button other = AppsScope.action(errors ? "output" : "errors", event -> showLog(app, !errors));
        Button back = AppsScope.action("back", event -> showList());
        HBox head = new HBox(16, title, gap, other, back);
        head.setAlignment(Pos.CENTER_LEFT);
        Pulse pulse = new Pulse();
        pulse.setText("reading the last " + LOG_LINES + " lines");
        pulse.setRunning(service != null);
        VBox lines = new VBox(1);
        logBox.getChildren().setAll(head, pulse, lines);
        logBox.setPadding(new Insets(4, 4, 4, 0));
        body.setContent(logBox);
        if (service == null) {
            return;
        }
        worker.execute(() -> {
            String text;
            try {
                text = service.log(app, errors, LOG_LINES);
            } catch (RuntimeException e) {
                text = "!" + AppsScope.reason(e);
            }
            String read = text;
            Platform.runLater(() -> {
                pulse.setRunning(false);
                logBox.getChildren().remove(pulse);
                fillLog(lines, read, errors);
            });
        });
    }

    /** Only for the previews: a log already read. */
    void logForPreview(String key, String text, boolean errors) {
        chosen = key;
        App app = chosen();
        if (app == null) {
            return;
        }
        showLog(app, errors);
        logBox.getChildren().removeIf(node -> node instanceof Pulse);
        fillLog((VBox) logBox.getChildren().get(logBox.getChildren().size() - 1), text, errors);
    }

    private void fillLog(VBox lines, String text, boolean errors) {
        lines.getChildren().clear();
        if (text.startsWith("!") || text.isBlank()) {
            Label word = new Label(text.startsWith("!") ? text.substring(1)
                    : errors ? "no error written" : "nothing written yet");
            word.getStyleClass().add(text.startsWith("!") ? "journal-error" : "home-muted");
            word.setWrapText(true);
            lines.getChildren().add(word);
            return;
        }
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            Label row = new Label(line);
            row.getStyleClass().add(lower.contains("error") || lower.contains("exception") || lower.contains("fatal")
                    ? "journal-error" : lower.contains("warn") ? "journal-warning"
                    : errors ? "journal-detail" : "journal-command");
            row.setMinWidth(0);
            row.setWrapText(true);
            row.setMinHeight(Region.USE_PREF_SIZE);
            lines.getChildren().add(row);
        }
        Platform.runLater(() -> body.setVvalue(1));
    }

    private void showList() {
        showingLog = false;
        body.setContent(rows);
        drawRows();
        Platform.runLater(() -> body.setVvalue(0));
    }
}
