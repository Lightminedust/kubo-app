package com.kubo.ui.scope;

import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.logs.SessionLog;
import com.kubo.model.server.DiskPartition;
import com.kubo.model.server.Server;
import com.kubo.model.server.ServerIdentity;
import com.kubo.model.server.ServerIdentity.UserIdentity;
import com.kubo.model.server.ServerMetrics;
import com.kubo.monitor.ServerMonitor;
import com.kubo.monitor.parse.MetricsParser;
import com.kubo.ui.files.FilesWindow;
import com.kubo.ui.logs.LogsWindow;
import com.kubo.ui.shell.LocalShellWindow;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A quiet terminal-style overview: measured resources and one compact session journal.
 */
public final class ScopeView extends StackPane {

    /** The name this screen answers to in {@link com.kubo.command.Attention}. */
    public static final String ATTENTION = "SCOPE";

    private static final Color LIVE = Color.web("#35e0a1");
    private static final Color ALERT = Color.web("#ff2d55");
    private static final Color IDLE = Color.web("#6e6e7d");
    private static final Color AMBER = Color.web("#ffb347");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private final ScopeHistory scopeHistory = new ScopeHistory();
    private final ResourceGauge cpuGauge = new ResourceGauge("cpu", "#35e8ff");
    private final ResourceGauge memoryGauge = new ResourceGauge("ram", "#b58cff");
    private final ResourceGauge diskGauge = new ResourceGauge("disk", "#ffb347");
    private final com.kubo.ui.Tile cpuTile = new com.kubo.ui.Tile("cpu", cpuGauge).accent("tile-cyan");
    private final com.kubo.ui.Tile memoryTile = new com.kubo.ui.Tile("ram", memoryGauge).accent("tile-violet");
    private final com.kubo.ui.Tile diskTile = new com.kubo.ui.Tile("disk", diskGauge).accent("tile-amber");
    private final com.kubo.ui.Tile historyTile = new com.kubo.ui.Tile("history", scopeHistory).accent("tile-green");
    private final VBox logBox = new VBox(10);
    private final Label logOk = label("0 OK", "journal-ok");
    private final Label logFailed = label("0 FAILED", "journal-error");
    private final Label logNotRun = label("0 NOT RUN", "journal-warning");
    private final Label transferValue = label("—", "metric-value");
    private final Label sampleTime = label("waiting for data", "home-muted");
    private final Label connectionState = label("connecting", "home-muted");
    private final Button terminalButton = new Button("terminal");
    private com.kubo.ui.Tile shellTile;
    private Node machineArea;
    private Node journal;
    private boolean terminalVisible;
    private Runnable stopLogListener = () -> {};
    private final CommandService commands = new CommandService();

    private final Circle stateDot = new Circle(3.5, IDLE);
    private final Label targetLabel = label("", "series");
    private final Label latencyLabel = label("latency —", "header-note");
    // A polling step failing while monitoring goes on: shown until a step succeeds again.
    private final Label problemLabel = label("", "monitor-problem");
    private final Button filesButton = new Button("FILES");
    private final Button disconnectButton = new Button("DISCONNECT");
    private FilesWindow filesWindow;
    private final Button logsButton = new Button("LOGS");
    private LogsWindow logsWindow;
    private final Button dockerButton = new Button("DOCKER");
    private com.kubo.ui.docker.DockerWindow dockerWindow;
    private final Button redisButton = new Button("REDIS");
    private com.kubo.ui.redis.RedisWindow redisWindow;
    private final Button networkButton = new Button("NETWORK");
    private final Button servicesButton = new Button("SERVICES");
    private final Button appsButton = new Button("apps");
    private com.kubo.ui.network.NetworkWindow networkWindow;
    private com.kubo.ui.services.ServicesWindow servicesWindow;
    /** One root state for the whole session, so the sudo password is asked for once, not per window. */
    private volatile com.kubo.privilege.RootSession rootSession;
    /** Holds the root button once the server has said whether root is possible at all. */
    private final HBox rootSlot = new HBox();
    private StackPane rootOverlay;
    private volatile Thread disconnect;
    private final Button localShellButton = new Button(com.kubo.platform.LocalShell.name());
    private LocalShellWindow localShellWindow;
    private final Button serverTab = new Button("SERVER");
    private final Button packagesTab = new Button("PACKAGES");
    private BorderPane layout;
    private BorderPane serverBody;
    /** The server body with the first reading's journal over it, which is what the window shows. */
    private StackPane serverStack;
    /** The first reading of a session, as Kubo's journal: who the machine is, its disks, what it is doing. */
    private final com.kubo.ui.Journal bootJournal = new com.kubo.ui.Journal(ScopeView::words);
    private Runnable stopBootJournal = () -> { };
    private com.kubo.ui.ServerConsole console;
    private com.kubo.ui.packages.PackagesView packagesView;

    private final Label titleLabel = label("READING", "scope-title");
    /** Recording failures stay visible even when the journal is hidden. */
    private final Label logTrouble = label("", "card-problem");
    private final Label ledeLabel = label("WAITING FOR THE FIRST ANSWER FROM THE SERVER.", "lede");
    private final HBox badges = new HBox(8);
    private final Label loadValue = label("—", "metric-value");
    private final Label osValue = label("—", "fetch-value");
    private final Label kernelValue = label("—", "fetch-value");
    private final Label cpuFact = label("—", "fetch-value");
    private final Label memoryFact = label("—", "fetch-value");
    private final Label processesValue = label("—", "metric-value");
    private final Label uptimeValue = label("—", "metric-value");


    private final Label lostMessage = label("", "lede");
    private final VBox lostCard;


    private SshConnection connection;
    private ServerMonitor monitor;
    private SessionLog sessionLog;
    private Runnable onClosed = () -> {
    };
    private boolean closed;

    public ScopeView() {
        getStyleClass().add("scope-view");

        // A short row of three dials, and under it one panel with all of the history. The dials say
        // where things are; the history says where they have been. Kept apart, the row stays short and
        // even, and the history gets the height instead of three half-empty boxes sharing it.
        GridPane resources = new GridPane();
        resources.setHgap(18);
        com.kubo.ui.Tile[] tiles = {cpuTile, memoryTile, diskTile};
        for (int i = 0; i < tiles.length; i++) {
            javafx.scene.layout.ColumnConstraints column = new javafx.scene.layout.ColumnConstraints();
            column.setPercentWidth(100.0 / tiles.length);
            resources.getColumnConstraints().add(column);
            tiles[i].setMinHeight(Region.USE_PREF_SIZE);
            resources.add(tiles[i], i, 0);
        }
        resources.setMinHeight(Region.USE_PREF_SIZE);
        historyTile.setTag("last 3 min");
        historyTile.setMinHeight(0);
        com.kubo.ui.Tile machine = new com.kubo.ui.Tile("machine", buildMachine());
        machine.setMinHeight(Region.USE_PREF_SIZE);
        VBox left = new VBox(20, machine, resources, historyTile);
        VBox.setVgrow(historyTile, Priority.ALWAYS);
        left.setMinWidth(0);
        left.setPadding(new Insets(10, 0, 0, 0));
        ScrollPane machineScroll = scroll(left);
        machineScroll.setMinSize(0, 0);
        machineScroll.setFitToHeight(true);
        journal = buildJournal();
        machineArea = machineScroll;
        serverBody = new BorderPane(machineScroll);
        serverBody.getStyleClass().addAll("home-body", "tiles");
        serverBody.setRight(journal);
        BorderPane.setMargin(journal, new Insets(10, 0, 0, 18));
        serverBody.setMinSize(0, 0);
        serverBody.heightProperty().addListener((observable, before, height) -> {
            boolean compact = terminalVisible || height.doubleValue() < 480;
            serverBody.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("compact"), compact);
            // With the shell open, or in a short window, the history goes and the dials stay.
            historyTile.setVisible(!compact);
            historyTile.setManaged(!compact);
        });
        serverStack = new StackPane(serverBody, bootJournal);
        layout = new BorderPane(serverStack);
        layout.setTop(buildHeader());
        layout.setBottom(buildStatus());
        lostCard = buildLostCard();
        StackPane.setAlignment(lostCard, Pos.CENTER);
        getChildren().addAll(layout, lostCard);
    }

    /**
     * Starts monitoring over an open connection. The view owns the connection from now on and closes it
     * when the user leaves; {@code onClosed} is then called on the JavaFX thread.
     */
    public void attach(SshConnection connection, Runnable onClosed) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
        detectRootAccess(connection);
        console = new com.kubo.ui.ServerConsole(connection, commands, null, "SERVER",
                CommandService.Origin.MONITOR, java.util.Set.of(CommandService.Origin.PACKAGES));
        targetLabel.setText(connection.target().toString());
        stateDot.setFill(AMBER);
        // Until the first answer, the journal of what is being asked rather than a screen of dashes.
        bootJournal.start("reading", connection.target().username() + "@" + connection.target().host(),
                "who the machine is, its disks, what it is doing");
        stopBootJournal = bootJournal.follow(commands, CommandService.Origin.MONITOR);
        stopLogListener = commands.addListener((entry, origin) -> Platform.runLater(() -> {
            if (!closed) updateLog(commands.getHistory());
        }));
        try {
            sessionLog = SessionLog.start(connection.target().toString());
            commands.addLoggedListener(sessionLog::append);
            // Where the log file is lives in the LOGS window, which is the screen for it.
        } catch (IOException e) {
            logTrouble.setText("NOT RECORDING: " + e.getMessage());
            logTrouble.setVisible(true);
            logTrouble.setManaged(true);
        }
        monitor = new ServerMonitor(connection, commands,
                server -> Platform.runLater(() -> apply(server, commands.getHistory())),
                message -> Platform.runLater(() -> showLost(message)));
        // The scope reads every two seconds. It does that only while its window is the one in front: otherwise
        // it takes the single connection from whichever screen the operator opened on top of it.
        monitor.setAwake(() -> com.kubo.command.Attention.holds(ATTENTION));
        monitor.setOnProblem(message -> Platform.runLater(() -> {
            problemLabel.setText(message.isEmpty() ? "" : "DEGRADED  ·  " + message);
            problemLabel.setVisible(!message.isEmpty());
            problemLabel.setManaged(!message.isEmpty());
        }));
        monitor.start();
    }

    /**
     * Called when the main window comes back to the front: the figures stopped moving while it was behind.
     */
    public void resume() {
        if (monitor != null) {
            monitor.readNow();
        }
    }

    /** Stops monitoring and closes the connection in the background. Safe to call more than once. */
    public void shutdown() {
        closed = true;
        stopLogListener.run();
        if (filesWindow != null) {
            filesWindow.close();
            filesWindow = null;
        }
        if (logsWindow != null) {
            logsWindow.close();
            logsWindow = null;
        }
        if (dockerWindow != null) {
            dockerWindow.close();
            dockerWindow = null;
        }
        if (redisWindow != null) {
            redisWindow.close();
            redisWindow = null;
        }
        if (servicesWindow != null) {
            servicesWindow.close();
            servicesWindow = null;
        }
        com.kubo.ui.apps.AppsWindow.closeShared();
        if (networkWindow != null) {
            networkWindow.close();
            networkWindow = null;
        }
        if (packagesView != null) {
            packagesView.shutdown();
            packagesView = null;
        }
        if (console != null) {
            console.shutdown();
            console = null;
        }
        if (rootSession != null) {
            // Leaving the server forgets the sudo password and takes every window out of root mode.
            rootSession.close();
            rootSession = null;
        }
        if (localShellWindow != null) {
            localShellWindow.close();
            localShellWindow = null;
        }
        if (monitor != null) {
            monitor.close();
        }
        if (sessionLog != null) {
            sessionLog.close();
        }
        if (connection != null) {
            SshConnection closing = connection;
            connection = null;
            // A daemon thread, so navigating away never waits on a dead socket. On app exit the JVM would kill it
            // before the disconnect goes out, so the thread is kept for awaitDisconnect() to join briefly.
            disconnect = new Thread(closing::close, "kubo-disconnect");
            disconnect.setDaemon(true);
            disconnect.start();
        }
    }

    /**
     * Waits, up to {@code millis}, for the SSH disconnect started by {@link #shutdown()} to finish, so the server
     * actually sees the session close when Kubo exits rather than losing it to a killed thread.
     */
    public void awaitDisconnect(long millis) {
        Thread thread = disconnect;
        if (thread == null) {
            return;
        }
        try {
            thread.join(Math.max(0, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Shows sample data without a server, to render previews.
     */
    void showPreview(Server server, List<ServerMetrics> history, List<CommandEntry> log, String target,
                     boolean lost, boolean showTerminal) {
        rootSession = new com.kubo.privilege.RootSession(com.kubo.privilege.RootAccess.SUDO_PASSWORD);
        installRootButton();
        console = new com.kubo.ui.ServerConsole(null, commands, null, "SERVER",
                CommandService.Origin.MONITOR, java.util.Set.of(CommandService.Origin.PACKAGES));
        console.showForPreview(log.stream()
                .map(entry -> new CommandService.Logged(entry, CommandService.Origin.MONITOR, false)).toList());
        targetLabel.setText(target);
        history.forEach(scopeHistory::push);
        apply(server, log);
        if (showTerminal) toggleTerminal();
        if (lost) {
            showLost("Connection lost: Broken pipe");
            updateLog(log);
        }
        applyCss();
        layout();
    }

    private void apply(Server server, List<CommandEntry> log) {
        if (closed) {
            return;
        }
        if (bootJournal.active()) {
            stopBootJournal.run();
            bootJournal.finish("read", null);
        }
        ServerIdentity identity = server.identity();
        titleLabel.setText(identity.user().username() + "@" + identity.hostname());
        osValue.setText(identity.osRelease());
        kernelValue.setText(identity.kernelVersion());
        cpuFact.setText(identity.cores() + " × " + identity.cpuModel());
        cpuFact.setTooltip(new Tooltip(identity.cpuModel()));
        memoryFact.setText(Format.bytes(identity.totalMemoryBytes()));
        updateBadges(identity.user());
        if (server.metrics().isPresent()) {
            ServerMetrics metrics = server.metrics().get();
            scopeHistory.push(metrics);
            updateFigures(metrics, identity.cores());
            ServerMetrics.MemoryMetrics memory = metrics.memory();
            cpuGauge.update(metrics.cpuPercent());
            cpuGauge.facts("steal", Format.percent(metrics.stealPercent()).replace(" ", ""),
                    "load/core", String.format(Locale.ROOT, "%.2f",
                            metrics.loadAverage().oneMin() / Math.max(1, identity.cores())),
                    "procs", String.valueOf(metrics.processCount()));
            cpuTile.setTag(identity.cores() + (identity.cores() == 1 ? " core" : " cores"));
            memoryGauge.update(memory.usagePercentage());
            memoryGauge.facts("used", Format.bytes(memory.usedBytes()),
                    "free", Format.bytes(memory.availableBytes()),
                    "swap", memory.totalSwapBytes() == 0 ? "none" : Format.bytes(memory.usedSwapBytes()));
            memoryTile.setTag(Format.bytes(memory.totalBytes()));
            transferValue.setText("↓ " + Format.rate(metrics.network().rxBytesPerSec())
                    + "   ↑ " + Format.rate(metrics.network().txBytesPerSec()));
            sampleTime.setText("updated " + CLOCK.format(metrics.timestamp()));
            connectionState.setText("connected");
        } else {
            cpuGauge.update(Double.NaN);
            memoryGauge.update(Double.NaN);
            connectionState.setText("reading");
        }
        updateDisks(server.disks());
        updateLog(log);
        stateDot.setFill(LIVE);
    }

    private void updateBadges(UserIdentity user) {
        badges.getChildren().setAll(badge(user.username(), "badge"));
        if (user.isRoot()) {
            badges.getChildren().add(badge("root", "badge-alert"));
        }
        if (user.inSudoGroup()) {
            badges.getChildren().add(badge("sudo", "badge"));
        }
        if (user.inDockerGroup()) {
            badges.getChildren().add(badge("docker", "badge-accent"));
        }
    }

    private void updateFigures(ServerMetrics metrics, int cores) {
        ServerMetrics.LoadAverage load = metrics.loadAverage();
        loadValue.setText(String.format(Locale.ROOT, "%.2f  %.2f  %.2f", load.oneMin(), load.fiveMin(), load.fifteenMin()));
        loadValue.setStyle(load.oneMin() >= cores ? "-fx-text-fill: " + web(ALERT) + ";" : "");

        processesValue.setText(String.valueOf(metrics.processCount()));
        uptimeValue.setText(Format.uptime(metrics.uptimeSeconds()));
    }

    /**
     * The dial is the fullest filesystem, since that is the one that stops the machine. Beside it, the
     * fullest three by name and share; the sizes are in each line's tooltip.
     */
    private void updateDisks(List<DiskPartition> disks) {
        if (disks.isEmpty()) {
            diskGauge.update(Double.NaN);
            diskGauge.facts("disk", "none found");
            diskTile.setTag("");
            return;
        }
        List<DiskPartition> fullest = disks.stream()
                .sorted(java.util.Comparator.comparingDouble(DiskPartition::usedPercentage).reversed())
                .toList();
        diskGauge.update(fullest.getFirst().usedPercentage());
        List<String[]> rows = new java.util.ArrayList<>();
        for (DiskPartition disk : fullest.subList(0, Math.min(3, fullest.size()))) {
            // The share fits beside the dial; the sizes do not, so they are on the line's tooltip.
            rows.add(new String[]{disk.mountPoint(), String.format(Locale.ROOT, "%.0f%%", disk.usedPercentage()),
                    disk.mountPoint() + "  " + Format.bytes(disk.usedBytes()) + " of "
                            + Format.bytes(disk.totalBytes()) + ", " + Format.bytes(disk.availableBytes())
                            + " free"});
        }
        diskGauge.rows(rows.toArray(String[][]::new));
        diskTile.setTag(disks.size() + (disks.size() == 1 ? " filesystem" : " filesystems"));
    }

    private void updateLog(List<CommandEntry> log) {
        logOk.setText(log.stream().filter(CommandEntry::success).count() + " ok");
        logFailed.setText(log.stream().filter(entry -> entry.ran() && !entry.success()).count() + " failed");
        logNotRun.setText(log.stream().filter(entry -> !entry.ran()).count() + " skipped");
        logBox.getChildren().clear();
        if (log.isEmpty()) logBox.getChildren().add(label("waiting for commands…", "home-muted"));
        // Bound the visible journal; the LOGS window keeps the complete session.
        for (int i = log.size() - 1; i >= Math.max(0, log.size() - 40); i--) {
            CommandEntry entry = log.get(i);
            // The way systemd reports a boot: the verdict in brackets at the start of the line, green,
            // red or amber, and everything else after it. Anybody who has watched a Linux machine start
            // reads this column without being told what it is.
            String result = entry.success() ? "[  OK  ]" : entry.ran() ? "[FAILED]" : "[ SKIP ]";
            Label state = label(result, entry.success() ? "journal-ok" : entry.ran() ? "journal-error" : "journal-warning");
            state.setMinWidth(Region.USE_PREF_SIZE);
            Label time = label(CLOCK.format(entry.executedAt()), "home-muted");
            time.setMinWidth(Region.USE_PREF_SIZE);
            Label took = label(entry.duration().toMillis() + "ms", "home-muted");
            took.setMinWidth(Region.USE_PREF_SIZE);
            String commandText = entry.command().replaceAll("echo @@\\w+; ", "")
                    .replaceAll("\\s+", " ").strip();
            Label command = label(commandText, "journal-command");
            command.setMinWidth(0);
            command.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(command, Priority.ALWAYS);
            HBox meta = new HBox(10, state, time, command, took);
            meta.setAlignment(Pos.CENTER_LEFT);
            VBox row = new VBox(2, meta);
            row.setMinHeight(Region.USE_PREF_SIZE);
            if (!entry.success() && !entry.error().isBlank()) {
                Label error = label(entry.error().replaceAll("\\s+", " ").strip(), "journal-detail");
                error.setMinWidth(0);
                error.setMaxWidth(Double.MAX_VALUE);
                row.getChildren().add(error);
            }
            row.getStyleClass().add("journal-row");
            Tooltip.install(row, new Tooltip(entry.command() + "\nexit " + entry.exitCode()
                    + (entry.error().isBlank() ? "" : "\n" + entry.error().strip())));
            logBox.getChildren().add(row);
        }
        for (int i = log.size() - 1; i >= 0; i--) {
            CommandEntry entry = log.get(i);
            if (entry.ran() && entry.command().equals(MetricsParser.COMMAND)) {
                latencyLabel.setText("latency " + entry.duration().toMillis() + " ms");
                break;
            }
        }
    }

    private void showLost(String message) {
        if (closed) {
            return;
        }
        if (bootJournal.active()) {
            stopBootJournal.run();
            bootJournal.finish("read", "the connection was lost");
        }
        connectionState.setText("disconnected");
        // The block that was green goes red: the one place on the screen that says the link is gone.
        connectionState.getStyleClass().remove("status-mode-live");
        connectionState.getStyleClass().add("status-mode-error");
        sampleTime.setText("last reading · connection lost");
        stateDot.setFill(ALERT);
        lostMessage.setText(message);
        lostCard.setVisible(true);
        disconnectButton.setText("BACK");
        filesButton.setDisable(true);
        updateLog(commands.getHistory());
    }

    /**
     * Opens the file manager window, or brings it forward when already open.
     */
    private void openFiles() {
        if (connection == null || closed) {
            return;
        }
        if (filesWindow == null || !filesWindow.isShowing()) {
            filesWindow = new FilesWindow(connection, commands, this::openLogs, rootSession);
        }
        filesWindow.show();
    }

    /**
     * Opens the Docker screen, finding out first whether Docker is usable and how to reach it.
     */
    private void openDocker() {
        if (connection == null || closed) {
            return;
        }
        if (dockerWindow == null || !dockerWindow.isShowing()) {
            dockerWindow = new com.kubo.ui.docker.DockerWindow(connection, commands, this::openLogs, rootSession);
        }
        dockerWindow.show();
    }

    /**
     * Shows either the server itself or what is installed on it, keeping the header and the shell in place.
     */
    private void showPackages(boolean packages) {
        serverTab.getStyleClass().remove("scope-tab-active");
        packagesTab.getStyleClass().remove("scope-tab-active");
        (packages ? packagesTab : serverTab).getStyleClass().add("scope-tab-active");
        if (!packages) {
            layout.setCenter(serverStack);
            return;
        }
        if (packagesView == null) {
            if (connection == null) {
                return;
            }
            packagesView = new com.kubo.ui.packages.PackagesView(
                    new com.kubo.packages.AptService(connection, commands,
                            rootSession == null ? null : rootSession.privilege()),
                    rootSession,
                    // Only so the packages screen can restart what an upgrade left running on old libraries.
                    new com.kubo.services.ServiceControl(connection, commands,
                            rootSession == null ? null : rootSession.privilege()));
            packagesView.watch(commands, connection.target().username() + "@" + connection.target().host());
            packagesView.start();
        }
        layout.setCenter(packagesView);
    }

    /**
     * Works out how this account can become root, once, so every screen shares the same root state.
     */
    private void detectRootAccess(SshConnection connection) {
        Thread thread = new Thread(() -> {
            try {
                rootSession = new com.kubo.privilege.RootSession(
                        com.kubo.privilege.RootAccess.parse(connection.exec(
                                com.kubo.privilege.RootAccess.DETECT).output()));
            } catch (RuntimeException e) {
                rootSession = new com.kubo.privilege.RootSession(com.kubo.privilege.RootAccess.NONE);
            }
            Platform.runLater(this::installRootButton);
        }, "kubo-root-detect");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Puts the root button in the header. It is the only way root is ever switched on, and from here it holds for
     * every screen of the session: the wordmark then reads Kubo_root in each window.
     */
    private void installRootButton() {
        if (rootSession == null || closed) {
            return;
        }
        rootSlot.getChildren().setAll(new com.kubo.ui.RootButton(rootSession,
                (command, done) -> {
                    Thread thread = new Thread(() -> {
                        boolean ok;
                        try {
                            ok = connection.exec(command.command(), command.input(),
                                    java.time.Duration.ofSeconds(20)).success();
                        } catch (RuntimeException e) {
                            ok = false;
                        }
                        boolean result = ok;
                        Platform.runLater(() -> done.accept(result));
                    }, "kubo-root-verify");
                    thread.setDaemon(true);
                    thread.start();
                },
                this::showRootOverlay, this::hideRootOverlay));
    }

    private void showRootOverlay(Region card) {
        hideRootOverlay();
        Region scrim = new Region();
        scrim.getStyleClass().add("picker-scrim");
        rootOverlay = new StackPane(scrim, card);
        rootOverlay.setMinSize(0, 0);
        getChildren().add(rootOverlay);
    }

    private void hideRootOverlay() {
        if (rootOverlay != null) {
            getChildren().remove(rootOverlay);
            rootOverlay = null;
        }
    }

    /**
     * Opens the network screen: the firewall, the web routing and what listens behind them.
     */
    private void openNetwork() {
        if (connection == null || closed) {
            return;
        }
        if (networkWindow == null || !networkWindow.isShowing()) {
            networkWindow = new com.kubo.ui.network.NetworkWindow(connection, commands, rootSession);
        }
        networkWindow.show();
    }

    /**
     * Opens the services screen: what systemd is running, and what it would still be running after a reboot.
     */
    private void openServices() {
        if (connection == null || closed) {
            return;
        }
        if (servicesWindow == null || !servicesWindow.isShowing()) {
            servicesWindow = new com.kubo.ui.services.ServicesWindow(connection, commands, rootSession);
        }
        servicesWindow.show();
    }

    /**
     * Opens the apps screen: what PM2 and supervisor keep running, and the cores and ports they use.
     */
    private void openApps() {
        if (connection == null || closed) {
            return;
        }
        com.kubo.ui.apps.AppsWindow.open(connection, commands, rootSession, null);
    }

    /**
     * Opens the Redis screen, finding Redis on the host or in a container.
     */
    private void openRedis() {
        if (connection == null || closed) {
            return;
        }
        if (redisWindow == null || !redisWindow.isShowing()) {
            redisWindow = new com.kubo.ui.redis.RedisWindow(connection, commands, rootSession);
        }
        redisWindow.show();
    }

    private void openLocalShell() {
        if (closed) {
            return;
        }
        if (localShellWindow == null || !localShellWindow.isShowing()) {
            localShellWindow = new LocalShellWindow(commands);
        }
        localShellWindow.show();
    }

    /**
     * Opens the history of every session, following this one live.
     */
    private void openLogs() {
        if (logsWindow == null || !logsWindow.isShowing()) {
            logsWindow = new LogsWindow(sessionLog == null ? null : sessionLog.path(), commands);
        }
        logsWindow.show();
    }

    private void leave() {
        if (closed) {
            return;
        }
        closed = true;
        shutdown();
        onClosed.run();
    }

    private VBox buildHeader() {
        Label wordmark = com.kubo.ui.RootButton.wordmark();
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        disconnectButton.getStyleClass().add("ghost-button");
        disconnectButton.setOnAction(event -> leave());
        filesButton.getStyleClass().add("ghost-button");
        filesButton.setOnAction(event -> openFiles());
        logsButton.getStyleClass().add("ghost-button");
        logsButton.setOnAction(event -> openLogs());
        dockerButton.getStyleClass().add("ghost-button");
        dockerButton.setOnAction(event -> openDocker());
        redisButton.getStyleClass().add("ghost-button");
        redisButton.setOnAction(event -> openRedis());
        networkButton.getStyleClass().add("ghost-button");
        networkButton.setOnAction(event -> openNetwork());
        servicesButton.getStyleClass().add("ghost-button");
        servicesButton.setOnAction(event -> openServices());
        appsButton.getStyleClass().add("ghost-button");
        appsButton.setOnAction(event -> openApps());
        serverTab.getStyleClass().addAll("scope-tab", "scope-tab-active");
        serverTab.setFocusTraversable(false);
        serverTab.setOnAction(event -> showPackages(false));
        packagesTab.getStyleClass().add("scope-tab");
        packagesTab.setFocusTraversable(false);
        packagesTab.setOnAction(event -> showPackages(true));
        localShellButton.getStyleClass().add("ghost-button");
        localShellButton.setOnAction(event -> openLocalShell());

        problemLabel.setVisible(false);
        problemLabel.setManaged(false);
        problemLabel.setMaxWidth(420);
        HBox tabs = new HBox(2, serverTab, packagesTab);
        tabs.getStyleClass().add("scope-switch");
        tabs.setAlignment(Pos.CENTER_LEFT);
        rootSlot.setAlignment(Pos.CENTER_LEFT);

        // The bar tmux keeps: the session in a block, then where you are.
        targetLabel.getStyleClass().setAll("label", "term-window");
        HBox header = new HBox(14, wordmark, targetLabel, spacer, rootSlot,
                disconnectButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("term-bar");
        terminalButton.getStyleClass().add("ghost-button");
        terminalButton.setOnAction(event -> toggleTerminal());
        // Lower case and nothing else. The current one is the lit block.
        serverTab.setText("server");
        packagesTab.setText("packages");
        filesButton.setText("files");
        servicesButton.setText("services");
        networkButton.setText("network");
        dockerButton.setText("docker");
        redisButton.setText("redis");
        logsButton.setText("logs");
        disconnectButton.setText("disconnect");
        localShellButton.setText("local shell");
        terminalButton.setText("shell");
        Region navGap = new Region();
        HBox.setHgrow(navGap, Priority.ALWAYS);
        HBox destinations = new HBox(20, tabs, filesButton, servicesButton, appsButton, networkButton, dockerButton, redisButton,
                navGap, terminalButton, localShellButton, logsButton);
        destinations.setAlignment(Pos.CENTER_LEFT);
        destinations.getStyleClass().add("home-nav");
        VBox top = new VBox(header, destinations, problemLabel);
        return top;
    }

    /**
     * The machine read out the way fastfetch reads one out: who and where on the first line, a rule, then
     * one fact per line with every value on the same column.
     */
    private Node buildMachine() {
        titleLabel.getStyleClass().setAll("label", "fetch-host");
        titleLabel.setMinHeight(Region.USE_PREF_SIZE);
        GridPane facts = new GridPane();
        facts.setHgap(18);
        facts.setVgap(4);
        String[] names = {"os", "kernel", "cpu", "memory", "uptime", "processes", "load", "net"};
        Label[] values = {osValue, kernelValue, cpuFact, memoryFact, uptimeValue, processesValue, loadValue,
                transferValue};
        int half = (names.length + 1) / 2;
        for (int i = 0; i < names.length; i++) {
            // Two columns of four, so the tile stays short and the dials keep the height.
            int column = (i / half) * 2;
            facts.add(label(names[i], "fetch-key"), column, i % half);
            values[i].getStyleClass().setAll("label", "fetch-value");
            values[i].setMinWidth(0);
            facts.add(values[i], column + 1, i % half);
        }
        for (int i = 0; i < 4; i++) {
            javafx.scene.layout.ColumnConstraints column = new javafx.scene.layout.ColumnConstraints();
            if (i % 2 == 0) {
                column.setMinWidth(i == 0 ? 76 : 96);
            } else {
                column.setHgrow(Priority.ALWAYS);
                column.setPercentWidth(i == 1 ? 46 : -1);
            }
            facts.getColumnConstraints().add(column);
        }
        logTrouble.setWrapText(true);
        logTrouble.setVisible(false);
        logTrouble.setManaged(false);
        HBox who = new HBox(16, titleLabel, badges);
        who.setAlignment(Pos.CENTER_LEFT);
        Region rule = new Region();
        rule.getStyleClass().add("fetch-rule");
        rule.setMinHeight(1);
        rule.setMaxHeight(1);
        VBox inside = new VBox(9, who, rule, logTrouble, facts);
        inside.getStyleClass().add("home-machine");
        return inside;
    }

    private Region buildJournal() {
        logOk.setGraphic(new Circle(3, LIVE));
        logFailed.setGraphic(new Circle(3, ALERT));
        logNotRun.setGraphic(new Circle(3, AMBER));
        HBox counts = new HBox(14, logOk, logFailed, logNotRun);
        counts.setAlignment(Pos.CENTER_LEFT);
        counts.setMinHeight(Region.USE_PREF_SIZE);
        logBox.setMinHeight(Region.USE_PREF_SIZE);
        ScrollPane history = scroll(logBox);
        history.setMinSize(0, 0);
        VBox.setVgrow(history, Priority.ALWAYS);
        Button all = new Button("open the full log →");
        all.getStyleClass().add("ghost-button");
        all.setOnAction(event -> openLogs());
        all.setMinHeight(Region.USE_PREF_SIZE);
        VBox content = new VBox(12, counts, history, all);
        content.setMinHeight(0);
        com.kubo.ui.Tile box = new com.kubo.ui.Tile("session.log", content).accent("tile-green");
        box.setMinHeight(0);
        box.setMinWidth(310);
        box.setMaxWidth(380);
        box.prefWidthProperty().bind(widthProperty().multiply(0.29));
        logBox.getChildren().add(label("waiting for commands…", "home-muted"));
        return box;
    }

    private HBox buildStatus() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        connectionState.getStyleClass().setAll("label", "status-mode", "status-mode-live");
        sampleTime.getStyleClass().setAll("label", "status-message");
        latencyLabel.getStyleClass().setAll("label", "status-fact");
        HBox status = new HBox(14, connectionState, sampleTime, spacer, latencyLabel,
                label("poll " + ServerMonitor.METRICS_INTERVAL.toSeconds() + "s", "status-fact"));
        status.setAlignment(Pos.CENTER_LEFT);
        status.getStyleClass().add("status-line");
        return status;
    }

    private void toggleTerminal() {
        if (console == null) return;
        terminalVisible = !terminalVisible;
        terminalButton.setText(terminalVisible ? "close shell" : "shell");
        serverBody.setRight(terminalVisible ? null : journal);
        if (terminalVisible) {
            console.setCollapsed(false);
            console.showShell();
            // A split, the way a terminal splits: the shell gets a frame of its own under the others and
            // the dials above it fold down to the dial alone. It used to slide up from the bottom edge of
            // the window in a different style and cut the tiles above it off at the knees.
            if (shellTile == null) {
                shellTile = new com.kubo.ui.Tile("shell", console).accent("tile-green");
                BorderPane.setMargin(shellTile, new Insets(20, 0, 0, 0));
            }
            console.setBare(true);
            // The shell is what somebody opened this for, so it gets the room: the tiles above keep what
            // they need and everything else is the terminal.
            serverBody.setCenter(shellTile);
            serverBody.setTop(machineArea);
        } else {
            serverBody.setTop(null);
            serverBody.setCenter(machineArea);
        }
    }

    private VBox pane(String name, Node content) {
        Label tab = label(name, "pane-tab");
        Region rule = new Region();
        rule.getStyleClass().add("terminal-rule");
        rule.setMaxHeight(1);
        HBox.setHgrow(rule, Priority.ALWAYS);
        HBox top = new HBox(12, tab, rule);
        top.setAlignment(Pos.CENTER_LEFT);
        top.getStyleClass().add("pane-top");
        VBox body = new VBox(content);
        body.setMinHeight(0);
        body.getStyleClass().add("pane-body");
        VBox.setVgrow(content, Priority.ALWAYS);
        VBox box = new VBox(top, body);
        VBox.setVgrow(body, Priority.ALWAYS);
        box.getStyleClass().add("pane");
        return box;
    }

    private VBox buildLostCard() {
        Button back = new Button("BACK TO CONNECTION");
        back.getStyleClass().add("primary-button");
        back.setOnAction(event -> leave());
        lostMessage.setWrapText(true);
        lostMessage.setMaxWidth(320);

        VBox card = new VBox(14, label("CONNECTION LOST", "series"), label("THE SERVER STOPPED ANSWERING", "host-key-host"),
                lostMessage, back);
        VBox.setMargin(back, new Insets(8, 0, 0, 0));
        card.getStyleClass().add("overlay-card");
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        card.setVisible(false);
        return card;
    }

    private static ScrollPane scroll(Node content) {
        ScrollPane pane = new ScrollPane(content);
        pane.setFitToWidth(true);
        pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return pane;
    }

    private static Label badge(String text, String styleClass) {
        Label badge = label(text, "badge");
        if (!styleClass.equals("badge")) {
            badge.getStyleClass().add(styleClass);
        }
        return badge;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static String web(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x",
                Math.round(color.getRed() * 255), Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
    }

    /** What one of the monitor's commands reads, in a few words, or null for one not worth a line. */
    static String words(String command) {
        if (command == null) {
            return null;
        }
        if (command.equals(com.kubo.monitor.parse.IdentityParser.COMMAND)) {
            return "who the machine is";
        }
        if (command.equals(com.kubo.monitor.parse.DisksParser.COMMAND)) {
            return "disks";
        }
        if (command.equals(com.kubo.monitor.parse.MetricsParser.COMMAND)) {
            return "processor, memory and network";
        }
        if (command.equals(com.kubo.monitor.parse.PortsParser.COMMAND)) {
            return "listening ports";
        }
        if (command.contains("sudo")) {
            return "how this account becomes root";
        }
        return null;
    }

    /** Only for the previews: the first reading of a session, part way through. */
    void firstReadingForPreview() {
        java.time.Instant now = java.time.Instant.now();
        bootJournal.showForPreview("reading", "xana@vmi2412345", "who the machine is, its disks, what it is doing",
                List.of(new CommandEntry(now, com.kubo.monitor.parse.IdentityParser.COMMAND, false, 0, "", "",
                                java.time.Duration.ofMillis(84)),
                        new CommandEntry(now, com.kubo.monitor.parse.DisksParser.COMMAND, false, 0, "", "",
                                java.time.Duration.ofMillis(31))));
    }
}
