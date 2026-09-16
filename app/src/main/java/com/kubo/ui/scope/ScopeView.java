package com.kubo.ui.scope;

import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.logs.SessionLog;
import com.kubo.model.server.DiskPartition;
import com.kubo.model.server.ListeningPort;
import com.kubo.model.server.Server;
import com.kubo.model.server.ServerIdentity;
import com.kubo.model.server.ServerIdentity.UserIdentity;
import com.kubo.model.server.ServerMetrics;
import com.kubo.monitor.ServerMonitor;
import com.kubo.monitor.parse.MetricsParser;
import com.kubo.ui.files.FilesWindow;
import com.kubo.ui.logs.LogsWindow;
import com.kubo.ui.powershell.PowerShellWindow;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The live picture of one server: identity and figures on the left, the core system in the middle,
 * exposure and the session log on the right, recent history at the bottom.
 */
public final class ScopeView extends StackPane {

    private static final Color LIVE = Color.web("#35e0a1");
    private static final Color ALERT = Color.web("#ff2d55");
    private static final Color IDLE = Color.web("#6e6e7d");
    private static final Color INTERNAL = Color.web("#8a4dff");
    private static final Color AMBER = Color.web("#ffb347");
    private static final Color CYAN = Color.web("#35e8ff");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int LOG_ROWS = 60;
    private static final double BAR_MIN = 36;
    private static final double BAR_RANGE = 170;

    private final CoreField core = new CoreField();
    private final ScopeTimeline timeline = new ScopeTimeline();
    private final CommandService commands = new CommandService();

    private final Circle stateDot = new Circle(3.5, IDLE);
    private final Label targetLabel = label("", "series");
    private final Label latencyLabel = label("LATENCY —", "header-note");
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
    private com.kubo.ui.network.NetworkWindow networkWindow;
    /** One root state for the whole session, so the sudo password is asked for once, not per window. */
    private volatile com.kubo.privilege.RootSession rootSession;
    /** Holds the root button once the server has said whether root is possible at all. */
    private final HBox rootSlot = new HBox();
    private StackPane rootOverlay;
    private volatile Thread disconnect;
    private final Button powerShellButton = new Button("POWERSHELL");
    private PowerShellWindow powerShellWindow;
    private final Button serverTab = new Button("SERVER");
    private final Button packagesTab = new Button("PACKAGES");
    private BorderPane layout;
    private BorderPane serverBody;
    private com.kubo.ui.ServerConsole console;
    private com.kubo.ui.packages.PackagesView packagesView;

    private final Label titleLabel = label("READING", "scope-title");
    private final Label ledeLabel = label("WAITING FOR THE FIRST ANSWER FROM THE SERVER.", "lede");
    private final HBox badges = new HBox(8);
    private final Label cpuValue = label("—", "metric-value");
    private final Label stealValue = label("—", "metric-value");
    private final Label loadValue = label("—", "metric-value");
    private final Label memoryValue = label("—", "metric-value");
    private final Label swapValue = label("—", "metric-value");
    private final Label processesValue = label("—", "metric-value");
    private final Label uptimeValue = label("—", "metric-value");
    private final VBox disksBox = new VBox(14);

    private final VBox logBox = new VBox(5);

    private final Region cpuBar = bar("bar-cpu");
    private final Region memoryBar = bar("bar-memory");
    private final Region diskBar = bar("bar-disk");

    private final Label lostMessage = label("", "lede");
    private final VBox lostCard;

    private final Label logFileLabel = label("", "mono-muted");

    private SshConnection connection;
    private ServerMonitor monitor;
    private SessionLog sessionLog;
    private Runnable onClosed = () -> {
    };
    private Instant lastMetricsTime;
    private boolean closed;

    public ScopeView() {
        getStyleClass().add("scope-view");

        StackPane centerCore = new StackPane(core);
        VBox center = new VBox(centerCore, timeline);
        VBox.setVgrow(centerCore, Priority.ALWAYS);
        timeline.setMinHeight(92);
        timeline.setPrefHeight(92);
        VBox.setMargin(timeline, new Insets(4, 8, 22, 8));

        serverBody = new BorderPane(center);
        serverBody.setLeft(buildLeftColumn());
        serverBody.setRight(buildRightColumn());
        layout = new BorderPane(serverBody);
        layout.setTop(buildHeader());

        VBox colorBar = new VBox(diskBar, memoryBar, cpuBar);
        colorBar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        colorBar.setMouseTransparent(true);
        StackPane.setAlignment(colorBar, Pos.BOTTOM_LEFT);

        Label note = label("READ ONLY  —  EVERY " + ServerMonitor.METRICS_INTERVAL.toSeconds() + " S", "vertical-note");
        note.setRotate(-90);
        Group noteGroup = new Group(note);
        noteGroup.setMouseTransparent(true);
        StackPane.setAlignment(noteGroup, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(noteGroup, new Insets(0, 14, 40, 0));

        lostCard = buildLostCard();
        StackPane.setAlignment(lostCard, Pos.CENTER);

        getChildren().addAll(layout, colorBar, noteGroup, lostCard);
        updateBars(0, 0, 0);
    }

    /**
     * Starts monitoring over an open connection. The view owns the connection from now on and closes it
     * when the user leaves; {@code onClosed} is then called on the JavaFX thread.
     */
    public void attach(SshConnection connection, Runnable onClosed) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
        detectRootAccess(connection);
        console = new com.kubo.ui.ServerConsole(connection, commands, null);
        layout.setBottom(console);
        targetLabel.setText(connection.target().toString());
        stateDot.setFill(AMBER);
        try {
            sessionLog = SessionLog.start(connection.target().toString());
            commands.addListener(sessionLog::append);
            logFileLabel.setText(sessionLog.path().getFileName().toString());
            Tooltip.install(logFileLabel, new Tooltip(sessionLog.path().toString()));
        } catch (IOException e) {
            logFileLabel.setText("LOG FILE UNAVAILABLE: " + e.getMessage());
        }
        monitor = new ServerMonitor(connection, commands,
                server -> Platform.runLater(() -> apply(server, commands.getHistory())),
                message -> Platform.runLater(() -> showLost(message)));
        monitor.setOnProblem(message -> Platform.runLater(() -> {
            problemLabel.setText(message.isEmpty() ? "" : "DEGRADED  ·  " + message);
            problemLabel.setVisible(!message.isEmpty());
            problemLabel.setManaged(!message.isEmpty());
        }));
        monitor.start();
    }

    /**
     * Stops monitoring and closes the connection in the background. Safe to call more than once.
     */
    public void shutdown() {
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
        if (powerShellWindow != null) {
            powerShellWindow.close();
            powerShellWindow = null;
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
        core.stop();
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
                     boolean lost, boolean consoleCollapsed) {
        // The preview shows the real screen, console and root button included, so their place is checked too.
        rootSession = new com.kubo.privilege.RootSession(com.kubo.privilege.RootAccess.SUDO_PASSWORD);
        installRootButton();
        console = new com.kubo.ui.ServerConsole(null, commands, null);
        console.setCollapsed(consoleCollapsed);
        layout.setBottom(console);
        targetLabel.setText(target);
        for (ServerMetrics metrics : history) {
            timeline.push(metrics);
            lastMetricsTime = metrics.timestamp();
        }
        apply(server, log);
        if (lost) {
            showLost("Connection lost: Broken pipe");
            updateLog(log);
        }
        core.stop();
        applyCss();
        layout();
        core.snapToState();
        core.simulate(6);
    }

    private void apply(Server server, List<CommandEntry> log) {
        if (closed) {
            return;
        }
        ServerIdentity identity = server.identity();
        titleLabel.setText(identity.hostname().toUpperCase(Locale.ROOT));
        ledeLabel.setText((identity.osRelease() + "  ·  KERNEL " + identity.kernelVersion() + "  ·  "
                + identity.cores() + " × " + identity.cpuModel() + "  ·  "
                + Format.bytes(identity.totalMemoryBytes()) + " RAM").toUpperCase(Locale.ROOT));
        updateBadges(identity.user());

        double cpuLevel = 0;
        double memoryLevel = 0;
        if (server.metrics().isPresent()) {
            ServerMetrics metrics = server.metrics().get();
            core.setMetrics(metrics, identity.cores());
            if (!metrics.timestamp().equals(lastMetricsTime)) {
                timeline.push(metrics);
                lastMetricsTime = metrics.timestamp();
            }
            updateFigures(metrics, identity.cores());
            cpuLevel = metrics.cpuPercent() / 100;
            memoryLevel = metrics.memory().usagePercentage() / 100;
        } else {
            core.setWaiting();
        }

        double diskLevel = updateDisks(server.disks());
        updateBars(cpuLevel, memoryLevel, diskLevel);
        updateLog(log);
        stateDot.setFill(LIVE);
    }

    private void updateBadges(UserIdentity user) {
        badges.getChildren().setAll(badge(user.username().toUpperCase(Locale.ROOT), "badge"));
        if (user.isRoot()) {
            badges.getChildren().add(badge("ROOT", "badge-alert"));
        }
        if (user.inSudoGroup()) {
            badges.getChildren().add(badge("SUDO", "badge"));
        }
        if (user.inDockerGroup()) {
            badges.getChildren().add(badge("DOCKER", "badge-accent"));
        }
    }

    private void updateFigures(ServerMetrics metrics, int cores) {
        cpuValue.setText(Format.percent(metrics.cpuPercent()));
        cpuValue.setStyle("-fx-text-fill: " + web(CoreField.heat(metrics.cpuPercent() / 100)) + ";");
        stealValue.setText(Format.percent(metrics.stealPercent()));
        stealValue.setStyle(metrics.stealPercent() >= 5 ? "-fx-text-fill: " + web(ALERT) + ";" : "");

        ServerMetrics.LoadAverage load = metrics.loadAverage();
        loadValue.setText(String.format(Locale.ROOT, "%.2f  %.2f  %.2f", load.oneMin(), load.fiveMin(), load.fifteenMin()));
        loadValue.setStyle(load.oneMin() >= cores ? "-fx-text-fill: " + web(ALERT) + ";" : "");

        ServerMetrics.MemoryMetrics memory = metrics.memory();
        memoryValue.setText(Format.bytes(memory.usedBytes()) + " / " + Format.bytes(memory.totalBytes()));
        swapValue.setText(memory.totalSwapBytes() == 0
                ? "NONE"
                : Format.bytes(memory.usedSwapBytes()) + " / " + Format.bytes(memory.totalSwapBytes()));
        processesValue.setText(String.valueOf(metrics.processCount()));
        uptimeValue.setText(Format.uptime(metrics.uptimeSeconds()));
    }

    private double updateDisks(List<DiskPartition> disks) {
        disksBox.getChildren().clear();
        double fullest = 0;
        for (DiskPartition disk : disks) {
            double used = disk.usedPercentage() / 100;
            fullest = Math.max(fullest, used);

            Label mount = label(disk.mountPoint(), "mono");
            mount.setMinWidth(0);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Label amount = label(Format.bytes(disk.usedBytes()) + " / " + Format.bytes(disk.totalBytes()), "mono-muted");
            HBox header = new HBox(8, mount, spacer, amount);

            Region track = new Region();
            track.getStyleClass().add("disk-track");
            Region fill = new Region();
            fill.setMaxHeight(2);
            fill.setStyle("-fx-background-color: " + web(used < 0.7 ? CYAN : used < 0.9 ? AMBER : ALERT) + ";");
            StackPane gauge = new StackPane(track, fill);
            StackPane.setAlignment(fill, Pos.CENTER_LEFT);
            fill.maxWidthProperty().bind(gauge.widthProperty().multiply(used));

            disksBox.getChildren().add(new VBox(6, header, gauge));
        }
        if (disks.isEmpty()) {
            disksBox.getChildren().add(label("NO LOCAL DISK FOUND", "mono-muted"));
        }
        return fullest;
    }

    private void updateLog(List<CommandEntry> log) {
        logBox.getChildren().clear();
        for (int i = log.size() - 1; i >= Math.max(0, log.size() - LOG_ROWS); i--) {
            CommandEntry entry = log.get(i);
            Color color = entry.success() ? LIVE : entry.ran() ? AMBER : ALERT;

            Label time = label(CLOCK.format(entry.executedAt()), "mono-muted");
            time.setMinWidth(Region.USE_PREF_SIZE);
            Label duration = label(entry.duration().toMillis() + " MS", "mono-muted");
            duration.setMinWidth(46);
            // Probe scripts start each command with a section marker; the log shows the commands themselves.
            Label command = label(entry.command().replaceAll("echo @@\\w+; ", ""), "mono");
            command.setMinWidth(0);
            HBox.setHgrow(command, Priority.ALWAYS);

            HBox row = new HBox(8, time, new Circle(2.5, color), duration, command);
            row.setAlignment(Pos.CENTER_LEFT);
            String details = entry.command() + "\n\nexit " + entry.exitCode()
                    + (entry.error().isBlank() ? "" : "\n" + entry.error().strip());
            Tooltip.install(row, new Tooltip(details));
            logBox.getChildren().add(row);
        }

        for (int i = log.size() - 1; i >= 0; i--) {
            CommandEntry entry = log.get(i);
            if (entry.ran() && entry.command().equals(MetricsParser.COMMAND)) {
                latencyLabel.setText("LATENCY " + entry.duration().toMillis() + " MS");
                break;
            }
        }
    }

    private void updateBars(double cpuLevel, double memoryLevel, double diskLevel) {
        cpuBar.setPrefHeight(BAR_MIN + cpuLevel * BAR_RANGE);
        memoryBar.setPrefHeight(BAR_MIN + memoryLevel * BAR_RANGE);
        diskBar.setPrefHeight(BAR_MIN + diskLevel * BAR_RANGE);
    }

    private void showLost(String message) {
        if (closed) {
            return;
        }
        core.setOffline(true);
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
            filesWindow = new FilesWindow(connection, commands, this::openLogs);
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
            layout.setCenter(serverBody);
            return;
        }
        if (packagesView == null) {
            if (connection == null) {
                return;
            }
            packagesView = new com.kubo.ui.packages.PackagesView(
                    new com.kubo.packages.AptService(connection, commands,
                            rootSession == null ? null : rootSession.privilege()),
                    rootSession);
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
        if (rootSession == null) {
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

    private void openPowerShell() {
        if (closed) {
            return;
        }
        if (powerShellWindow == null || !powerShellWindow.isShowing()) {
            powerShellWindow = new PowerShellWindow(commands);
        }
        powerShellWindow.show();
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

    private HBox buildHeader() {
        Label wordmark = com.kubo.ui.RootButton.wordmark();
        HBox brand = new HBox(wordmark);
        brand.setAlignment(Pos.BOTTOM_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Region rule = new Region();
        rule.getStyleClass().add("series-rule");

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
        serverTab.getStyleClass().addAll("scope-tab", "scope-tab-active");
        serverTab.setFocusTraversable(false);
        serverTab.setOnAction(event -> showPackages(false));
        packagesTab.getStyleClass().add("scope-tab");
        packagesTab.setFocusTraversable(false);
        packagesTab.setOnAction(event -> showPackages(true));
        powerShellButton.getStyleClass().add("ghost-button");
        powerShellButton.setOnAction(event -> openPowerShell());

        problemLabel.setVisible(false);
        problemLabel.setManaged(false);
        problemLabel.setMaxWidth(420);
        HBox tabs = new HBox(2, serverTab, packagesTab);
        tabs.getStyleClass().add("scope-switch");
        tabs.setAlignment(Pos.CENTER_LEFT);
        rootSlot.setAlignment(Pos.CENTER_LEFT);

        // The other screens are somewhere to go, not the thing you are looking at: one quiet group, set apart.
        HBox destinations = new HBox(16, filesButton, networkButton, dockerButton, redisButton, powerShellButton,
                logsButton);
        destinations.getStyleClass().add("destination-group");
        destinations.setAlignment(Pos.CENTER_LEFT);

        HBox header = new HBox(18, brand, tabs, spacer, rule, targetLabel, stateDot, latencyLabel, problemLabel,
                rootSlot, destinations, disconnectButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(22, 40, 6, 36));
        HBox.setMargin(tabs, new Insets(0, 0, 0, 10));
        HBox.setMargin(disconnectButton, new Insets(0, 0, 0, 14));
        return header;
    }

    private Node buildLeftColumn() {
        Label kicker = label("SERVER.SCOPE", "series");
        Region dash = new Region();
        dash.getStyleClass().add("dash");
        titleLabel.setWrapText(true);
        titleLabel.setMinHeight(Region.USE_PREF_SIZE);
        ledeLabel.setWrapText(true);
        ledeLabel.setMinHeight(Region.USE_PREF_SIZE);

        GridPane figures = new GridPane();
        figures.setHgap(18);
        figures.setVgap(10);
        String[] names = {"PROCESSES", "UPTIME"};
        Label[] values = {processesValue, uptimeValue};
        for (int i = 0; i < names.length; i++) {
            figures.add(label(names[i], "metric-label"), 0, i);
            figures.add(values[i], 1, i);
        }

        // One red kicker on the screen, and the rest of the headings quiet, so the eye is not called four times.
        Label capacity = label("RUNNING", "column-heading");
        VBox column = new VBox(kicker, dash, titleLabel, ledeLabel, badges, capacity, figures,
                label("DISKS", "column-heading"), disksBox);
        VBox.setMargin(dash, new Insets(16, 0, 0, 0));
        VBox.setMargin(titleLabel, new Insets(16, 0, 10, 0));
        VBox.setMargin(badges, new Insets(16, 0, 0, 0));
        VBox.setMargin(capacity, new Insets(34, 0, 0, 0));
        VBox.setMargin(figures, new Insets(12, 0, 0, 0));
        VBox.setMargin(column.getChildren().get(7), new Insets(34, 0, 0, 0));
        VBox.setMargin(disksBox, new Insets(12, 0, 0, 0));
        column.setPadding(new Insets(20, 12, 24, 56));
        column.setPrefWidth(310);
        column.setMaxWidth(310);
        ScrollPane pane = scroll(column);
        pane.setMinWidth(310);
        pane.setPrefWidth(310);
        return pane;
    }

    /**
     * What the server is doing right now, and the trace of what it has sent. What the network exposes lives in
     * NETWORK.SCOPE, so this screen stays about the machine itself.
     */
    private Node buildRightColumn() {
        ScrollPane logScroll = scroll(logBox);
        VBox.setVgrow(logScroll, Priority.ALWAYS);

        Label logKicker = label("SESSION LOG", "column-heading");
        logFileLabel.setMinWidth(0);
        VBox column = new VBox(logKicker, logFileLabel, logScroll);
        VBox.setMargin(logFileLabel, new Insets(0, 0, 12, 0));
        column.setPadding(new Insets(20, 44, 24, 8));
        column.setPrefWidth(320);
        column.setMinWidth(260);
        return column;
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

    private static Region bar(String styleClass) {
        Region region = new Region();
        region.getStyleClass().addAll("bar", styleClass);
        return region;
    }

    private static String web(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x",
                Math.round(color.getRed() * 255), Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
    }
}
