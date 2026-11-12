package com.kubo.ui.network;

import com.kubo.command.CommandEntry;
import com.kubo.model.network.Endpoint;
import com.kubo.model.network.Firewall;
import com.kubo.model.network.NetworkMap;
import com.kubo.model.network.NginxSites;
import com.kubo.network.NetworkCommands;
import com.kubo.model.network.Risk;
import com.kubo.model.network.ServiceOwner;
import com.kubo.model.network.Vhost;
import com.kubo.model.server.ListeningPort.Protocol;
import com.kubo.command.CommandService;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.network.NetworkService;
import com.kubo.privilege.RootSession;
import com.kubo.ui.PromptCard;
import com.kubo.ui.RootButton;
import com.kubo.ui.ServerConsole;
import com.kubo.ui.docker.SudoPrompt;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The network screen, in the same tiles as the others: the machine on the left, every listening port in the
 * middle grouped by how far it can be reached, and on the right the chosen port, what stands in front of it
 * and what can be done to it. The log is out of the way until somebody asks for it.
 */
public final class NetworkScope extends StackPane {

    /** The name this screen answers to in {@link com.kubo.command.Attention}. */
    public static final String ATTENTION = "NETWORK";

    private final NetworkService network;
    /** The map or the nginx screen, whichever the operator asked for. */
    private final javafx.scene.layout.StackPane screens = new javafx.scene.layout.StackPane();
    private javafx.scene.Node mapView;
    private NginxScreen nginx;
    private final VBox masthead = new VBox();
    /** Only for the tools card: what this server already has, and what could install the rest. */
    private final com.kubo.tools.ToolService tools;
    /**
     * What the last look found, kept so a card can open on it instead of waiting for a round trip. Tools do
     * not come and go on their own: the only thing that changes this is an install, from this very screen.
     */
    private java.util.Set<com.kubo.model.tools.Tool> knownTools = java.util.Set.of();
    private final RootSession root;
    private BlockedScreen blockedScreen;
    private final String target;
    /** Only to show what is being asked while a screen waits. Null in the previews. */
    private final CommandService commands;
    private final SshConnection connection;
    private final ServerConsole console;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-network");
        thread.setDaemon(true);
        return thread;
    });

    private final NetworkTree ports = new NetworkTree();
    /**
     * The quiet re-reading, so a port opened or closed outside Kubo shows up. It shows nothing while it runs:
     * nothing is happening that anybody needs to watch.
     */
    private final Timeline poll = new Timeline(new KeyFrame(Duration.seconds(20), event -> refresh(false)));
    /** True while a reading is on its way back. A poll that finds one in flight does not queue a second. */
    private boolean reading;
    /** Somebody asked for a fresh reading while one was in flight: it starts as soon as that one lands. */
    private boolean readAgain;
    /** Re-reads the moment root is turned on or off, since that changes what can be read at all. */
    private final javafx.beans.value.ChangeListener<Boolean> onRoot = (observable, before, now) ->
            Platform.runLater(() -> rootChanged(now));
    /** The journal of a reading somebody asked for, over the map. */
    private final com.kubo.ui.Journal readingOverlay = new com.kubo.ui.Journal(NetworkScope::words);
    /** The same journal over the Nginx and blocked screens, which have no map to lay it on. */
    private final com.kubo.ui.Journal screenJournal = new com.kubo.ui.Journal(NetworkScope::words);
    /** True while a reading somebody asked for is out, and so is shown. */
    private boolean watched;
    /** Why the next shown reading happens, when there is something to say about it. */
    private String readingNote = "";
    private Runnable stopWatching = () -> { };

    // The bar, the actions and the status line
    private HBox actionsBar;
    private final Label statusMode = label("network", "status-mode");
    private final Label statusMessage = label("", "status-message");
    private final Label firewallFact = label("", "status-fact");
    private final Label listeningFact = label("", "status-fact");
    private final Label readFact = label("", "status-fact");
    private com.kubo.ui.Tile consoleTile;

    // The chosen port
    private final VBox portBox = new VBox(10);
    private final FlowPane actions = new FlowPane(16, 8);
    private final Label status = label("", "card-note");

    private static final System.Logger LOG = System.getLogger(NetworkScope.class.getName());

    private NetworkMap map = NetworkMap.empty();
    private final MachinePanel machine = new MachinePanel();
    private static final java.time.format.DateTimeFormatter CLOCK =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault());
    /** The edge card while it is open, so a ban can report into the card it was clicked in. */
    private GuardCard guard;
    private FirewallCard firewallCard;
    /**
     * True between a firewall change and the reading after it, so the card comes back with the new rules rather
     * than closing. Its numbers are only right for the reading they were printed in.
     */
    private boolean reopenFirewall;
    /** What the change said, carried into the card that comes back. */
    private String firewallNote = "";
    /** The port Kubo's own connection came in on, which is what deleting the wrong UFW rule would close. */
    private final int sshPort;
    private Endpoint selected;
    private StackPane overlay;
    private ContextMenu menu;

    public NetworkScope(NetworkService network, RootSession root, SshConnection connection, CommandService commands) {
        this.network = network;
        this.root = root;
        this.sshPort = connection == null ? 22 : connection.target().port();
        // Kept for the exported journal, which is read away from this screen and has to say which server.
        this.target = connection == null ? "?" : connection.target().toString();
        this.tools = connection == null || root == null ? null
                : new com.kubo.tools.ToolService(connection, commands, root.privilege());
        this.commands = commands;
        this.connection = connection;
        this.console = new ServerConsole(connection, commands, root, "NETWORK",
                CommandService.Origin.NETWORK, java.util.Set.of());
        getStyleClass().add("scope-view");

        ports.setOnSelect(this::showEndpoint);
        ports.setOnMenu(request -> showMenu(request.endpoint(), request.screenX(), request.screenY()));
        ports.setOnSpaceMenu(request -> showSpaceMenu(request.screenX(), request.screenY()));
        if (connection != null) {
            ports.setHostname(hostOf(String.valueOf(connection.target())));
        }

        com.kubo.ui.Tile machineTile = new com.kubo.ui.Tile("machine", machine).accent("tile-cyan");
        // The map's own camera, small, in its corner: the wheel and a drag do the same, this is for finding it.
        HBox camera = new HBox(12, bar("−", event -> ports.zoomOut()), bar("fit", event -> ports.fit()),
                bar("+", event -> ports.zoomIn()));
        camera.getStyleClass().add("map-camera");
        camera.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(camera, Pos.BOTTOM_RIGHT);
        com.kubo.ui.Tile portsTile = new com.kubo.ui.Tile("map", new StackPane(ports, camera, readingOverlay))
                .accent("tile-amber");
        com.kubo.ui.Tile portTile = new com.kubo.ui.Tile("port", buildInspector());
        machineTile.setMinWidth(230);
        machineTile.setPrefWidth(280);
        machineTile.setMaxWidth(300);
        portsTile.setMinWidth(380);
        portsTile.setPrefWidth(500);
        HBox.setHgrow(portsTile, Priority.ALWAYS);
        portTile.setMinWidth(290);
        portTile.setPrefWidth(340);
        portTile.setMaxWidth(360);
        HBox frames = new HBox(18, machineTile, portsTile, portTile);
        VBox.setVgrow(frames, Priority.ALWAYS);

        // The log and the shell: there when asked for, not before. Most visits never need either.
        console.setFramed();
        consoleTile = new com.kubo.ui.Tile("log", console).accent("tile-green");
        consoleTile.setMinHeight(160);
        consoleTile.setPrefHeight(200);
        consoleTile.setMaxHeight(240);
        consoleTile.setVisible(false);
        consoleTile.setManaged(false);

        VBox body = new VBox(22, frames, consoleTile);
        body.getStyleClass().add("tiles");
        mapView = body;

        // The ports and the nginx screen take turns in the same place. Nginx is not a card over the ports: it
        // is a screen of its own, with its own rail, and it needs the whole window to say anything useful.
        screens.getChildren().add(body);

        masthead.getChildren().setAll(buildMasthead());
        BorderPane layout = new BorderPane(new StackPane(screens, screenJournal));
        layout.setTop(masthead);
        layout.setBottom(buildStatus());
        getChildren().add(layout);

        // While a reading somebody asked for is out, each answer is shown as it comes back: what was asked, and
        // that something is being asked at all.
        if (commands != null) {
            stopWatching = commands.addLoggedListener(logged -> {
                if (logged.origin() == CommandService.Origin.NETWORK) {
                    CommandEntry answer = logged.entry();
                    Platform.runLater(() -> answered(answer));
                }
            });
        }
        RootSession.elevatedProperty().addListener(onRoot);

        showEndpoint(null);
        poll.setCycleCount(Timeline.INDEFINITE);
    }

    /**
     * Builds the screen without a live service, for previews.
     */
    NetworkScope() {
        this(null, null, null, new CommandService());
    }

    private static String hostOf(String target) {
        if (target == null || target.isBlank()) {
            return "SERVER";
        }
        String host = target.contains("@") ? target.substring(target.indexOf('@') + 1) : target;
        return host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
    }

    public void start() {
        network.forget();
        lookAtTools(found -> { });
        refresh(true);
        poll.play();
    }

    /**
     * Which measuring tools this server has, asked once at the start and again after an install.
     *
     * <p>A card that needs the answer used to ask for it as it opened, which meant a round trip behind whatever
     * the poll was already doing before the card could appear. It is asked once instead, and every card reads
     * what was found.
     */
    private void lookAtTools(java.util.function.Consumer<java.util.Set<com.kubo.model.tools.Tool>> then) {
        if (tools == null) {
            then.accept(knownTools);
            return;
        }
        worker.execute(() -> {
            java.util.Set<com.kubo.model.tools.Tool> present;
            try {
                present = tools.present();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Reading the tools failed", e);
                return;
            }
            javafx.application.Platform.runLater(() -> {
                boolean changed = !present.equals(knownTools);
                knownTools = present;
                if (changed) {
                    then.accept(present);
                }
            });
        });
    }

    /**
     * Called when this window comes back to the front. Its poll was held back while it was behind, so it reads
     * once now rather than leaving the operator looking at a stale screen until the next tick.
     */
    public void resume() {
        refresh(false);
    }

    /**
     * Reads everything again, including what is normally read once a session: which firewall this server uses,
     * what nginx serves, whether fail2ban is there. The poll leaves those alone, so this is how a change made
     * outside Kubo reaches the screen.
     */
    private void reread() {
        reread(true);
    }

    private void reread(boolean announce) {
        network.forget();
        refresh(announce);
    }

    public void shutdown() {
        poll.stop();
        ports.stop();
        stopWatching.run();
        RootSession.elevatedProperty().removeListener(onRoot);
        console.shutdown();
        worker.shutdownNow();
    }

    // Reading

    private void refresh(boolean announce) {
        // A poll from a window nobody is looking at takes the connection from the one they are. A click still
        // goes through: announce is true when a person asked for it.
        if (!announce && !com.kubo.command.Attention.holds(ATTENTION)) {
            return;
        }
        if (network == null || !onMap()) {
            // Nothing of the map is on screen, so re-reading it would only take the connection from the screen
            // that is.
            return;
        }
        // One reading at a time. The poll used to queue a new one every twelve seconds whether the last had
        // come back or not; with root, a reading takes longer than that, so they piled up behind each other
        // and a click waited for every one queued before it. Now a poll that finds one out does nothing, and a
        // click asks for one more as soon as it lands.
        if (announce && !watched) {
            // Somebody is waiting on this one: it is shown, answer by answer, until the last lands.
            watched = true;
            readingOverlay.start("reading", who(), readingNote);
            readingNote = "";
            showReading();
        }
        if (reading) {
            readAgain |= announce;
            return;
        }
        reading = true;
        worker.execute(() -> {
            NetworkMap read;
            try {
                read = network.read();
            } catch (RuntimeException e) {
                // Anything unexpected is shown, not swallowed: a screen stuck reading tells the user nothing,
                // and the stack trace goes to a terminal they may never see.
                LOG.log(System.Logger.Level.ERROR, "Reading the network failed", e);
                Platform.runLater(() -> {
                    reading = false;
                    showReadFailure(e);
                    if (!readAgain && watched) {
                        watched = false;
                        readingOverlay.finish("read", "the reading stopped part way");
                    }
                    again();
                });
                return;
            }
            Platform.runLater(() -> {
                reading = false;
                apply(read);
                if (!readAgain && watched) {
                    watched = false;
                    readingOverlay.finish("read", null);
                }
                again();
            });
        });
    }

    private void again() {
        if (readAgain) {
            readAgain = false;
            refresh(true);
        }
    }

    /**
     * Root was turned on or off. What only root may read becomes readable, or stops being, so everything kept
     * from the last reading is thrown away and the server is read now rather than on the next tick.
     */
    private void rootChanged(boolean elevated) {
        if (network == null) {
            return;
        }
        readingNote = elevated ? "root is on: reading what only root can see"
                : "root is off: reading again as " + who();
        network.forget();
        // Whichever screen is in front is the one read again. The map used to be the only one, so turning
        // root on from the Nginx screen changed nothing on it until somebody went back to the map.
        if (nginx != null && screens.getChildren().contains(nginx)) {
            String note = readingNote;
            readingNote = "";
            readNginx(note);
            return;
        }
        if (blockedScreen != null && screens.getChildren().contains(blockedScreen)) {
            readingNote = "";
            readBlocked();
            return;
        }
        refresh(true);
    }

    /** Who is reading what, the way a prompt writes it. */
    private String who() {
        return "?".equals(target) ? "you@server" : target.replaceFirst(":\\d+$", "");
    }

    private void showReading() {
        statusMode.setText("reading");
        statusMode.getStyleClass().setAll("label", "status-mode", "status-mode-busy");
        statusMessage.setText("asking the server\u2026");
    }

    /** One answer came back. Shown only while somebody is watching the reading. */
    private void answered(CommandEntry entry) {
        if (!watched) {
            return;
        }
        String shown = entry.command().replaceAll("echo '?@@\\w+'?;\\s*", "").replaceAll("\\s+", " ").strip();
        statusMessage.setText("$ " + (shown.length() > 90 ? shown.substring(0, 89) + "\u2026" : shown));
        readingOverlay.answered(entry);
    }

    /**
     * The read could not be finished at all. The ports stay as they were; the reason is in the status line.
     */
    private void showReadFailure(RuntimeException problem) {
        String reason = problem.getMessage() == null || problem.getMessage().isBlank()
                ? problem.getClass().getSimpleName() : problem.getMessage();
        statusMode.setText("failed");
        statusMode.getStyleClass().setAll("label", "status-mode", "status-mode-error");
        statusMessage.setText("the reading stopped part way: " + reason);
    }

    private void apply(NetworkMap read) {
        this.map = read;
        if (reopenFirewall) {
            reopenFirewall = false;
            showFirewall();
            if (!firewallNote.isEmpty()) {
                firewallCard.report(null, firewallNote);
                firewallNote = "";
            }
        }
        ports.setMap(read);
        long open = read.endpoints().stream().filter(Endpoint::isOpenToTheWorld)
                .map(Endpoint::port).distinct().count();
        long alerts = read.alerts();

        statusMode.setText(alerts > 0 ? "exposed" : "network");
        statusMode.getStyleClass().setAll("label", "status-mode",
                alerts > 0 ? "status-mode-error" : "status-mode-live");
        statusMessage.setText(alerts > 0
                ? alerts + (alerts == 1 ? " port needs" : " ports need") + " attention, open to the internet"
                : open == 0 ? "nothing is open to the internet"
                : open + (open == 1 ? " port is" : " ports are") + " open to the internet");
        firewallFact.setText(read.firewall().title().toLowerCase(Locale.ROOT) + " "
                + read.firewall().shortState().toLowerCase(Locale.ROOT));
        firewallFact.setStyle("-fx-text-fill: " + web(NetworkTree.colourOfFirewall(read.firewall())) + ";");
        long listening = read.endpoints().stream().map(Endpoint::port).distinct().count();
        listeningFact.setText(listening + " listening");
        readFact.setText("read " + CLOCK.format(java.time.Instant.now()));

        machine.show(read.identity(), read.rates(), read.naming());
        machine.setNotes(read.notes());

        if (selected == null) {
            showEndpoint(null);
        } else {
            read.endpoints().stream()
                    .filter(endpoint -> endpoint.port() == selected.port()
                            && endpoint.socket().bindAddress().equals(selected.socket().bindAddress()))
                    .findFirst()
                    .ifPresentOrElse(this::showEndpoint, () -> showEndpoint(null));
        }
    }

    // Inspector

    private void showEndpoint(Endpoint endpoint) {
        selected = endpoint;
        ports.select(endpoint);
        status.setText("");
        actions.getChildren().clear();
        portBox.getChildren().clear();
        if (endpoint == null) {
            Label hint = label("click a port to see who holds it and what stands in front of it", "home-muted");
            hint.setWrapText(true);
            portBox.getChildren().add(hint);
            if (canChangeFirewall()) {
                actions.getChildren().addAll(
                        action("open a port", "ghost-button", event -> showPortCard(true, -1, Protocol.TCP)),
                        action("block a port", "ghost-button", event -> showPortCard(false, -1, Protocol.TCP)));
            }
            return;
        }
        Color colour = NetworkTree.colourOf(endpoint);
        Label title = label(endpoint.port() + "/" + endpoint.socket().protocol().name().toLowerCase(Locale.ROOT),
                "fetch-host");
        title.setStyle("-fx-text-fill: " + web(colour) + ";");
        Label reach = label(switch (endpoint.reach()) {
            case OPEN -> "open to the internet";
            case SHIELDED -> "behind the firewall";
            case PRIVATE -> "private network";
            case ISOLATED -> "this machine only";
        }, "fetch-value");
        reach.setStyle("-fx-text-fill: " + web(colour) + ";");
        HBox head = new HBox(12, title, reach);
        head.setAlignment(Pos.BASELINE_LEFT);
        Region rule = new Region();
        rule.getStyleClass().add("fetch-rule");
        rule.setMinHeight(1);
        rule.setMaxHeight(1);

        VBox facts = new VBox(6,
                step("process", endpoint.owner().kind() == ServiceOwner.Kind.UNKNOWN
                        ? "unknown, root can name it" : endpoint.owner().label()
                        + (endpoint.socket().hasPid() ? "  pid " + endpoint.socket().pid() : "")),
                step("binds", endpoint.socket().bindAddress()),
                step("firewall", firewallLine(endpoint)),
                step("nginx", endpoint.isBehindNginx()
                        ? endpoint.servedBy().stream().map(Vhost::url).collect(java.util.stream.Collectors.joining("  "))
                        : "\u2014"));
        portBox.getChildren().addAll(head, rule, facts);

        Risk.of(endpoint).ifPresent(risk -> {
            Label said = label(risk.title() + ". " + risk.detail(),
                    risk.level() == Risk.Level.ALERT ? "port-alert" : "home-muted");
            said.setWrapText(true);
            said.setMinHeight(Region.USE_PREF_SIZE);
            portBox.getChildren().add(said);
        });

        buildActions(endpoint);
    }

    /**
     * True when the firewall in front of this server is one Kubo can write to, which today means UFW.
     */
    private boolean canChangeFirewall() {
        return map != null && map.firewall().kind() == com.kubo.model.network.Firewall.Kind.UFW
                && map.firewall().isEnforcing();
    }

    private String firewallLine(Endpoint endpoint) {
        Firewall firewall = map.firewall();
        if (!firewall.isEnforcing()) {
            return firewall.title().toLowerCase(Locale.ROOT) + " " + firewall.shortState().toLowerCase(Locale.ROOT);
        }
        return firewall.ruleFor(endpoint.port())
                .map(rule -> rule.action().toString().toLowerCase(Locale.ROOT) + " from " + rule.from()
                        + "  (rule " + rule.index() + ")")
                .orElse("no rule, the default denies it");
    }

    private void buildActions(Endpoint endpoint) {
        ServiceOwner owner = endpoint.owner();
        // Kubo only writes firewall rules through UFW. On firewalld, nftables or raw iptables it reads and shows
        // what it finds, but offering a button that would run the wrong tool is worse than offering none.
        if (canChangeFirewall()) {
            if (endpoint.isOpenToTheWorld()) {
                actions.getChildren().add(action("close port", "port-danger",
                        event -> confirmClose(endpoint)));
            } else if (endpoint.reach() == Endpoint.Reach.SHIELDED) {
                actions.getChildren().add(action("open port", "ghost-button",
                        event -> confirmOpen(endpoint)));
            }
        }
        if (owner.kind() == ServiceOwner.Kind.PM2) {
            actions.getChildren().addAll(
                    action("restart", "ghost-button", event -> act("restarted",
                            () -> network.restartApp(owner.handle()))),
                    action("stop", "ghost-button", event -> act("stopped",
                            () -> network.stopApp(owner.handle()))),
                    action("app logs", "ghost-button", event -> showAppLogs(owner.handle())),
                    action("in apps", "ghost-button", event -> showApps(owner.handle())));
        }
        if (endpoint.isBehindNginx()) {
            actions.getChildren().add(action("test & reload nginx", "ghost-button", event -> reloadNginx()));
        }
        if (endpoint.socket().hasPid() && owner.kind() != ServiceOwner.Kind.PM2) {
            actions.getChildren().add(action("kill " + endpoint.socket().pid(), "port-danger",
                    event -> confirmKill(endpoint)));
        }
    }

    private HBox step(String name, String value) {
        Label caption = label(name, "fetch-key");
        caption.setMinWidth(70);
        Label text = label(value, "fetch-value");
        text.setWrapText(true);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(10, caption, text);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    // Actions

    private void confirmOpen(Endpoint endpoint) {
        String expected = String.valueOf(endpoint.port());
        showOverlay(new PromptCard("FIREWALL", "OPEN PORT " + expected,
                "This lets the whole internet reach " + endpoint.owner().label() + " on port " + expected
                        + ". Type the port number to confirm.", expected, "OPEN PORT", true, (typed, done) -> {
            if (!typed.strip().equals(expected)) {
                done.accept("Type " + expected + " exactly to confirm.");
                return;
            }
            runWithRoot(() -> worker.execute(() -> {
                CommandEntry result = network.allow(endpoint.port(), endpoint.socket().protocol());
                Platform.runLater(() -> finish(result, "port " + expected + " opened", done));
            }));
        }, this::hideOverlay));
    }

    private void confirmClose(Endpoint endpoint) {
        String expected = String.valueOf(endpoint.port());
        boolean ssh = endpoint.port() == 22;
        showOverlay(new PromptCard("FIREWALL", "CLOSE PORT " + expected,
                (ssh ? "PORT 22 IS YOUR SSH WAY IN. Closing it can lock you out of this server for good. "
                        : "This stops the internet reaching port " + expected + ". ")
                        + "Type the port number to confirm.", expected, "CLOSE PORT", true, (typed, done) -> {
            if (!typed.strip().equals(expected)) {
                done.accept("Type " + expected + " exactly to confirm.");
                return;
            }
            runWithRoot(() -> worker.execute(() -> {
                CommandEntry result = network.closePort(endpoint.port(), endpoint.socket().protocol());
                Platform.runLater(() -> finish(result, "port " + expected + " closed", done));
            }));
        }, this::hideOverlay));
    }

    private void confirmKill(Endpoint endpoint) {
        int pid = endpoint.socket().pid();
        String expected = String.valueOf(pid);
        showOverlay(new PromptCard("DANGER ZONE", "KILL PID " + pid,
                "This ends " + endpoint.owner().label() + " at once with kill -9. Anything it had not saved is "
                        + "lost, and nothing will restart it unless a service manager does. Type the PID to confirm.",
                expected, "KILL", true, (typed, done) -> {
            if (!typed.strip().equals(expected)) {
                done.accept("Type " + expected + " exactly to confirm.");
                return;
            }
            runWithRoot(() -> worker.execute(() -> {
                CommandEntry result = network.kill(pid);
                Platform.runLater(() -> finish(result, "pid " + pid + " killed", done));
            }));
        }, this::hideOverlay));
    }

    private void reloadNginx() {
        runWithRoot(() -> {
            status.getStyleClass().setAll("label", "card-note");
            status.setText("testing the configuration\u2026");
            worker.execute(() -> {
                CommandEntry result = network.reloadNginx();
                Platform.runLater(() -> {
                    String text = result.output().isBlank() ? result.error() : result.output();
                    if (result.success()) {
                        status.getStyleClass().setAll("label", "card-success");
                        status.setText("configuration valid, nginx reloaded");
                        reread();
                    } else {
                        status.getStyleClass().setAll("label", "card-problem");
                        status.setText("not reloaded: " + firstLine(text));
                        showOverlay(PromptCard.output("NETWORK", "NGINX -T", text, this::hideOverlay));
                    }
                });
            });
        });
    }

    private void showAppLogs(String app) {
        runWithRoot(() -> {
            status.getStyleClass().setAll("label", "card-note");
            status.setText("reading logs\u2026");
            worker.execute(() -> {
                CommandEntry result = network.appLogs(app, 200);
                String text = result.output().isBlank() ? result.error() : result.output();
                Platform.runLater(() -> {
                    status.setText("");
                    showOverlay(PromptCard.output("NETWORK", "PM2 LOGS · " + app.toUpperCase(Locale.ROOT),
                            text.isBlank() ? "No output." : text, this::hideOverlay));
                });
            });
        });
    }

    private void act(String done, java.util.function.Supplier<CommandEntry> action) {
        runWithRoot(() -> {
            status.getStyleClass().setAll("label", "card-note");
            status.setText("working\u2026");
            worker.execute(() -> {
                CommandEntry result = action.get();
                Platform.runLater(() -> {
                    if (result.success()) {
                        status.getStyleClass().setAll("label", "card-success");
                        status.setText(done);
                    } else {
                        status.getStyleClass().setAll("label", "card-problem");
                        status.setText(firstLine(result.error().isBlank() ? result.output() : result.error()));
                    }
                    reread();
                });
            });
        });
    }

    private void finish(CommandEntry result, String success, java.util.function.Consumer<String> done) {
        if (result.success()) {
            hideOverlay();
            status.getStyleClass().setAll("label", "card-success");
            status.setText(success);
            reread();
        } else {
            done.accept(firstLine(result.error().isBlank() ? result.output() : result.error()));
        }
    }

    private static String firstLine(String text) {
        return text == null ? "failed" : text.strip().lines().findFirst().orElse("failed");
    }

    private void showMenu(Endpoint endpoint, double screenX, double screenY) {
        if (menu != null) {
            menu.hide();
        }
        List<MenuItem> items = new java.util.ArrayList<>();
        items.add(item("open", event -> showEndpoint(endpoint)));
        if (canChangeFirewall()) {
            if (endpoint.isOpenToTheWorld()) {
                items.add(item("close port", event -> confirmClose(endpoint)));
            } else if (endpoint.reach() == Endpoint.Reach.SHIELDED) {
                items.add(item("open port", event -> confirmOpen(endpoint)));
            }
        }
        if (canChangeFirewall()) {
            items.add(item("a rule for this port\u2026", event -> showPortCard(!endpoint.isOpenToTheWorld(),
                    endpoint.port(), endpoint.socket().protocol())));
        }
        if (endpoint.owner().kind() == ServiceOwner.Kind.PM2) {
            items.add(new SeparatorMenuItem());
            items.add(item("restart " + endpoint.owner().label(),
                    event -> act("restarted", () -> network.restartApp(endpoint.owner().handle()))));
            items.add(item("stop " + endpoint.owner().label(),
                    event -> act("stopped", () -> network.stopApp(endpoint.owner().handle()))));
            items.add(item("app logs", event -> showAppLogs(endpoint.owner().handle())));
        }
        if (endpoint.socket().hasPid() && endpoint.owner().kind() != ServiceOwner.Kind.PM2) {
            items.add(new SeparatorMenuItem());
            MenuItem kill = item("kill " + endpoint.socket().pid(), event -> confirmKill(endpoint));
            kill.getStyleClass().add("redis-menu-danger");
            items.add(kill);
        }
        menu = new ContextMenu(items.toArray(new MenuItem[0]));
        menu.getStyleClass().add("redis-context-menu");
        menu.show(this, screenX, screenY);
    }

    /**
     * A right-click anywhere on the map that is not a port: what can be done to the firewall from here.
     */
    private void showSpaceMenu(double screenX, double screenY) {
        if (menu != null) {
            menu.hide();
        }
        boolean writable = canChangeFirewall();
        MenuItem open = item("open a port\u2026", event -> showPortCard(true, -1, Protocol.TCP));
        MenuItem block = item("block a port\u2026", event -> showPortCard(false, -1, Protocol.TCP));
        open.setDisable(!writable);
        block.setDisable(!writable);
        List<MenuItem> items = new java.util.ArrayList<>(List.of(open, block));
        if (!writable) {
            MenuItem why = new MenuItem(map.firewall().isEnforcing()
                    ? "only ufw rules are written from here" : "the firewall is not on");
            why.setDisable(true);
            items.add(why);
        }
        items.add(new SeparatorMenuItem());
        items.add(item("firewall rules", event -> showFirewall()));
        items.add(item("re-read", event -> reread(true)));
        menu = new ContextMenu(items.toArray(MenuItem[]::new));
        menu.getStyleClass().add("redis-context-menu");
        menu.show(this, screenX, screenY);
    }

    /**
     * The form that opens or blocks a port. What it sends is one of the firewall's fixed commands, with the port
     * and the address checked before anything leaves.
     */
    private void showPortCard(boolean open, int port, Protocol protocol) {
        showOverlay(new PortCard(open, port, protocol, sshPort, (opening, number, proto, source, done) ->
                send(trouble -> {
                    done.accept(trouble);
                    if (trouble == null) {
                        hideOverlay();
                        status.getStyleClass().setAll("label", "card-success");
                        status.setText((opening ? "opened " : "blocked ") + number + "/"
                                + proto.name().toLowerCase(Locale.ROOT) + (source.isEmpty() ? "" : " for " + source));
                    }
                }, () -> opening
                        ? (source.isEmpty() ? network.allow(number, proto) : network.allowFrom(source, number, proto))
                        : network.denyFrom(source, number, proto)), this::hideOverlay));
    }

    private static MenuItem item(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(action);
        return item;
    }

    // Root

    private void runWithRoot(Runnable action) {
        runWithRoot(action, () -> { });
    }

    /**
     * Runs an action that needs root, asking for the password first when it is not known yet.
     *
     * <p>The prompt goes over whatever card asked for it rather than in its place. Replacing the card meant a BAN
     * clicked on the connections card closed the connections card, and the answer then went to a card nobody
     * could see.
     *
     * @param cancelled called when the password is not given, so whoever was waiting stops waiting
     */
    private void runWithRoot(Runnable action, Runnable cancelled) {
        if (network != null && network.needsPassword()) {
            showOverlayOnTop(new SudoPrompt(root.privilege(), verification(),
                    () -> {
                        root.refresh();
                        hideTopOverlay();
                        action.run();
                    },
                    () -> {
                        hideTopOverlay();
                        cancelled.run();
                    }));
        } else {
            action.run();
        }
    }

    /**
     * Checks a sudo password against this server, for the root button and the prompts.
     */
    private java.util.function.BiConsumer<com.kubo.privilege.Privilege.Command,
            java.util.function.Consumer<Boolean>> verification() {
        return (command, done) -> worker.execute(() -> {
            CommandEntry entry = network.verify(command);
            Platform.runLater(() -> done.accept(entry.success()));
        });
    }

    /** The apps screen as a card over this one: what PM2 runs, without leaving the map. */
    private void showApps(String app) {
        if (connection != null) {
            showOverlay(com.kubo.ui.apps.AppsMini.open(connection, commands, root, app, this::hideOverlay));
        }
    }

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
        if (overlay != null) {
            getChildren().remove(overlay);
            overlay = null;
        }
    }

    /**
     * Who is connected right now. Banning is offered only when fail2ban answered, because a ban command sent to
     * a server that does not run fail2ban fails with a message nobody can act on.
     */
    private void showConnections() {
        boolean canBan = !map.jails().isEmpty();
        ConnectionsCard.Hands hands = new ConnectionsCard.Hands() {
            @Override
            public void ban(String address, java.util.function.Consumer<String> done) {
                banEverywhere(address, done);
            }

            @Override
            public void whois(String address, java.util.function.Consumer<String> done) {
                runProbe(() -> com.kubo.tools.ProbeCommands.whois(address), done);
            }
        };
        // No round trip to open a card. Whether whois is there was answered the last time anything looked.
        java.util.Set<String> held = new java.util.HashSet<>();
        map.jails().forEach(jail -> held.addAll(jail.banned()));
        showOverlay(new ConnectionsCard(map.conversations(), hands, canBan,
                knownTools.contains(com.kubo.model.tools.Tool.WHOIS), held, this::hideOverlay));
    }

    /**
     * Every address the server has shut out, as a table.
     *
     * <p>Its own screen rather than a card, because two hundred rows sorted three ways is not something that
     * belongs over the top of a map.
     */
    private void showBlocked() {
        if (network == null) {
            return;
        }
        if (blockedScreen == null) {
            blockedScreen = new BlockedScreen(new BlockedScreen.Hands() {
                @Override
                public void whois(String address,
                                  java.util.function.Consumer<com.kubo.model.network.WhoisSummary> done) {
                    // Only if the server has whois; otherwise the column simply stays empty rather than
                    // filling with failures.
                    if (!knownTools.contains(com.kubo.model.tools.Tool.WHOIS)) {
                        return;
                    }
                    runProbe(() -> com.kubo.tools.ProbeCommands.whois(address),
                            said -> done.accept(com.kubo.model.network.WhoisSummary.parse(said)));
                }

                @Override
                public void release(com.kubo.model.network.BlockedAddress one,
                                    java.util.function.Consumer<String> done) {
                    // A fail2ban ban is lifted in its jail; a firewall rule is a rule and is removed where the
                    // firewall card removes rules, which is a different act and is not offered here.
                    if (one.source() != com.kubo.model.network.BlockedAddress.Source.FAIL2BAN) {
                        done.accept("This one is a firewall rule, not a ban. Remove it from the firewall card.");
                        return;
                    }
                    String jail = one.jail().contains(",") ? one.jail().split(",")[0].strip() : one.jail();
                    send(trouble -> {
                        done.accept(trouble);
                        readBlocked();
                    }, () -> network.unban(jail, one.address()));
                }

                @Override
                public void export(java.util.List<com.kubo.model.network.BlockedAddress> blocked,
                                   java.util.function.BiConsumer<Boolean, String> done) {
                    exportBlocked(blocked, done);
                }

                @Override
                public void back() {
                    showMap();
                }

                @Override
                public void reread() {
                    network.forget();
                    readBlocked();
                }
            }, java.time.Instant.now());
        }
        screens.getChildren().setAll(blockedScreen);
        showActions(false);
        readBlocked();
    }

    private void readBlocked() {
        if (network == null || blockedScreen == null) {
            return;
        }
        BlockedScreen screen = blockedScreen;
        // The answers as they come back: the jails, the database, the firewall rules. A blank table for four
        // seconds looks exactly like a server with nothing to show.
        screen.reading(true);
        screenJournal.start("reading", who(), "what this server has shut out");
        Runnable stopWatching = screenJournal.follow(commands, CommandService.Origin.NETWORK);
        runWithRoot(() -> worker.execute(() -> {
            java.util.List<String> notes = new java.util.ArrayList<>();
            java.util.List<com.kubo.model.network.BlockedAddress> blocked;
            try {
                blocked = network.blocked(notes);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Reading the blocked addresses failed", e);
                blocked = java.util.List.of();
                notes.add("Kubo could not read the blocked addresses: " + e.getMessage());
            }
            java.util.List<com.kubo.model.network.BlockedAddress> found = blocked;
            String problem = String.join("  ", notes);
            javafx.application.Platform.runLater(() -> {
                stopWatching.run();
                screen.reading(false);
                screenJournal.finish("read", null);
                screen.show(found, problem);
            });
        }));
    }

    /**
     * Writes the journal wherever the operator wants it, and says so.
     *
     * <p>Through the shared save card rather than a status line: a save that says nothing is a save nobody
     * trusts, and on a slow disk it looks exactly like one that failed.
     */
    private void exportBlocked(java.util.List<com.kubo.model.network.BlockedAddress> blocked,
                               java.util.function.BiConsumer<Boolean, String> done) {
        com.kubo.ui.SaveCard card = com.kubo.ui.SaveCard.ask(
                getScene() == null ? null : getScene().getWindow(),
                "THE BLOCKED ADDRESSES", "kubo-blocked.json",
                file -> com.kubo.model.network.BlockedExport.write(target, blocked, file,
                        java.time.Instant.now()),
                worker, this::hideOverlay);
        if (card == null) {
            // Cancelling the chooser is an answer, not an error.
            return;
        }
        done.accept(true, "");
        showOverlay(card);
    }

    private void showEdge() {
        java.time.Instant now = java.time.Instant.now();
        guard = new GuardCard(map.jails(), now, new GuardCard.Hands() {
            @Override
            public void ban(String jail, String address) {
                sendGuard(() -> network.ban(jail, address), address + " banned in " + jail);
            }

            @Override
            public void unban(String jail, String address) {
                sendGuard(() -> network.unban(jail, address), "Ban lifted on " + address);
            }

            @Override
            public void whois(String address, java.util.function.Consumer<String> done) {
                runProbe(() -> com.kubo.tools.ProbeCommands.whois(address), done);
            }
        }, this::hideOverlay);
        guard.setCanWhois(knownTools.contains(com.kubo.model.tools.Tool.WHOIS));
        showOverlay(guard);

        if (network == null) {
            guard.showCertificates(java.util.List.of(), now);
            return;
        }
        java.util.List<com.kubo.model.network.Vhost> vhosts = map.vhosts();
        GuardCard opened = guard;
        runWithRoot(() -> worker.execute(() -> {
            java.util.List<com.kubo.model.network.Certificate> found;
            try {
                found = network.certificates(vhosts);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Reading the certificates failed", e);
                found = java.util.List.of();
            }
            java.util.List<com.kubo.model.network.Certificate> read = found;
            javafx.application.Platform.runLater(() -> opened.showCertificates(read, now));
        }));
    }

    /**
     * Bans an address in the first jail there is.
     *
     * <p>fail2ban bans per jail, and from the connection list there is no way to know which jail a stranger
     * belongs in. The first one is used, which on nearly every server is sshd, and the edge card is where a
     * specific jail can be chosen.
     */
    /**
     * Bans an address in the first jail, and tells the card that asked, whichever card it is.
     *
     * <p>It used to report only into THE EDGE, so a ban from the connections card, with THE EDGE closed, said
     * nothing to anybody: not that it worked, not that it failed.
     */
    private void banEverywhere(String address, java.util.function.Consumer<String> done) {
        if (map.jails().isEmpty() || network == null) {
            done.accept("fail2ban has no jail to put it in.");
            return;
        }
        String jail = map.jails().get(0).name();
        sendGuard(() -> network.ban(jail, address), address + " banned in " + jail, done);
    }

    /**
     * Runs a ban or an unban and reports into the card it was asked from.
     */
    private void sendGuard(java.util.function.Supplier<CommandEntry> work, String done) {
        sendGuard(work, done, trouble -> { });
    }

    /**
     * @param answered also told how it went, with null for a success, for a card other than THE EDGE
     */
    private void sendGuard(java.util.function.Supplier<CommandEntry> work, String done,
                           java.util.function.Consumer<String> answered) {
        if (network == null) {
            answered.accept("No connection.");
            return;
        }
        runWithRoot(() -> worker.execute(() -> {
            String trouble;
            try {
                CommandEntry result = work.get();
                trouble = result.success() ? null
                        : firstLine(result.error().isBlank() ? result.output() : result.error());
            } catch (IllegalArgumentException e) {
                // Kubo refusing to send it: its own words, not the server's.
                trouble = e.getMessage();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "A fail2ban action failed", e);
                trouble = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            String said = trouble;
            javafx.application.Platform.runLater(() -> {
                if (guard != null) {
                    guard.report(said, done);
                }
                answered.accept(said);
                if (said == null) {
                    reread();
                }
            });
        }), () -> answered.accept("Root was not given, so nothing was sent."));
    }

    /**
     * Every firewall rule, with the two edits the screen could not make.
     */
    private void showFirewall() {
        firewallCard = new FirewallCard(map.firewall(), new FirewallCard.Hands() {
            @Override
            public void allowFrom(String source, int port, Protocol protocol) {
                send(result -> changed(result, "Port " + port + " opened to " + source),
                        () -> network.allowFrom(source, port, protocol));
            }

            @Override
            public void denyFrom(String source, int port, Protocol protocol) {
                send(result -> changed(result, "Port " + port + " refused to "
                                + (source.isBlank() ? "everyone" : source)),
                        () -> network.denyFrom(source, port, protocol));
            }

            @Override
            public void delete(com.kubo.model.network.Firewall.RuleGroup group) {
                send(result -> changed(result, "Rule deleted"), () -> network.deleteRules(group.numbers()));
            }

            @Override
            public void setOn(boolean on) {
                com.kubo.model.network.Firewall.Kind kind = map.firewall().kind();
                send(result -> changed(result, on ? "The firewall is on" : "The firewall is off"),
                        () -> network.switchFirewall(kind, on));
            }
        }, sshPort, this::hideOverlay);
        showOverlay(firewallCard);
    }

    /**
     * After a firewall change: says how it went, and on success brings the card back with the rules read again.
     *
     * <p>The card used to close after a delete. Left open it would have shown numbers that had just moved, and a
     * second delete from that list would have removed the wrong rule. Closed, nobody saw the rule go. Reopened
     * on the next reading, it shows the list as it now is.
     */
    private void changed(String trouble, String done) {
        firewallCard.report(trouble, done);
        if (trouble == null) {
            reopenFirewall = true;
            firewallNote = done;
            reread();
        }
    }

    /**
     * Leaves the map and gives the window to Nginx.
     *
     * <p>Not a card over the map. What a domain does is a list of rules, a pool behind it, a certificate with a
     * date on it and a log, and none of that reads in a box floating over something else.
     */
    private void showNginx() {
        if (nginx == null) {
            nginx = new NginxScreen(new NginxScreen.Hands() {
                @Override
                public void enable(String site, java.util.function.Consumer<String> done) {
                    send(trouble -> {
                        done.accept(trouble);
                        readNginx();
                    }, () -> network.enableSite(site));
                }

                @Override
                public void disable(String site, java.util.function.Consumer<String> done) {
                    send(trouble -> {
                        done.accept(trouble);
                        readNginx();
                    }, () -> network.disableSite(site));
                }

                @Override
                public void test(java.util.function.Consumer<String> done) {
                    send(done, () -> network.testNginx());
                }

                @Override
                public void reload(java.util.function.Consumer<String> done) {
                    send(trouble -> {
                        done.accept(trouble);
                        readNginx();
                    }, () -> network.reloadNginx());
                }

                @Override
                public void readFile(String path, java.util.function.Consumer<String> done) {
                    read(path, done, () -> network.nginxFile(path));
                }

                @Override
                public void readLog(String path, java.util.function.Consumer<String> done) {
                    read(path, done, () -> network.nginxLog(path, 300));
                }

                @Override
                public void reread() {
                    network.forget();
                    readNginx();
                }

                @Override
                public void newSite() {
                    showNewSite();
                }

                @Override
                public void showPort(int port) {
                    showPortOnMap(port);
                }

                @Override
                public void browse(String path) {
                    openPathPicker("What is in this folder", com.kubo.model.files.PathKind.FOLDER, path,
                            null);
                }

                @Override
                public void back() {
                    showMap();
                }
            });
        }
        if (!"?".equals(target)) {
            nginx.setMachine(hostOf(target));
        }
        screens.getChildren().setAll(nginx);
        // The ports' own actions go with the ports. What stays is the bar, which belongs to the window.
        showActions(false);
        readNginx();
    }

    /**
     * The form that writes a new site.
     *
     * <p>The one thing in Kubo that writes a configuration file, so it shows the file before it writes it,
     * refuses to write over one that is already there, and leaves a refused site switched off rather than
     * deleting the only thing that says why Nginx refused it.
     */
    private void showNewSite() {
        if (network == null || nginx == null) {
            return;
        }
        showOverlay(new NewSiteCard(new NewSiteCard.Hands() {
            @Override
            public void exists(String site, java.util.function.Consumer<Boolean> done) {
                runWithRoot(() -> worker.execute(() -> {
                    boolean there;
                    try {
                        there = network.siteExists(site);
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.ERROR, "Looking for the site file failed", e);
                        // Treated as present: refusing to write is the safe way to be wrong here.
                        there = true;
                    }
                    boolean answer = there;
                    javafx.application.Platform.runLater(() -> done.accept(answer));
                }));
            }

            @Override
            public void create(com.kubo.model.network.SiteDraft draft,
                               java.util.function.Consumer<String> done) {
                send(trouble -> {
                    done.accept(trouble);
                    if (trouble == null) {
                        hideOverlay();
                    }
                    readNginx();
                }, () -> network.createSite(draft));
            }

            @Override
            public void delete(String site, java.util.function.Consumer<String> done) {
                send(trouble -> {
                    done.accept(trouble);
                    readNginx();
                }, () -> network.deleteSite(site));
            }

            @Override
            public void pick(com.kubo.model.files.PathKind kind, String start,
                             java.util.function.Consumer<String> chosen) {
                openPathPicker(switch (kind) {
                    case FOLDER -> "Pick the site's folder";
                    case CERTIFICATE -> "Pick the certificate";
                    case PRIVATE_KEY -> "Pick its private key";
                    case SOCKET -> "Pick the PHP-FPM socket";
                    case SCRIPT -> "Pick the script";
                    case PROGRAM -> "Pick the program";
                }, kind, start, chosen);
            }
        }, nginx.pools(), map.endpoints(), this::readNginx, this::hideOverlay));
    }

    /**
     * Opens a path picker over whatever is already open, listing through this screen's connection.
     *
     * @param chosen called with the path, or null to only look
     */
    private void openPathPicker(String title, com.kubo.model.files.PathKind kind, String start,
                                java.util.function.Consumer<String> chosen) {
        if (network == null) {
            return;
        }
        com.kubo.ui.PathPicker picker = new com.kubo.ui.PathPicker(title, kind,
                (path, done) -> worker.execute(() -> {
                    com.kubo.model.files.DirectoryListing listing;
                    try {
                        listing = network.listFolder(path);
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.ERROR, "Listing a folder failed", e);
                        listing = com.kubo.model.files.DirectoryListing.failed(path,
                                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    }
                    com.kubo.model.files.DirectoryListing read = listing;
                    javafx.application.Platform.runLater(() -> done.accept(read));
                }),
                start,
                folder -> {
                    hideTopOverlay();
                    if (chosen != null) {
                        chosen.accept(folder);
                    }
                },
                this::hideTopOverlay);
        if (kind.readsFirstLine()) {
            picker.setPeeker((path, done) -> worker.execute(() -> {
                String armour;
                try {
                    armour = network.pemArmour(path);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "Reading the first line of a file failed", e);
                    armour = "";
                }
                String line = armour;
                javafx.application.Platform.runLater(() -> done.accept(line));
            }));
        }
        showOverlayOnTop(picker);
    }

    /** A second card over the first, closed on its own without closing the one under it. */
    private StackPane topOverlay;

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

    /** Leaves the Nginx screen for the map, with this port picked on it. */
    private void showPortOnMap(int port) {
        showMap();
        for (com.kubo.model.network.Endpoint endpoint : map.endpoints()) {
            if (endpoint.socket().port() == port) {
                showEndpoint(endpoint);
                return;
            }
        }
    }

    /** Puts the map back, with its own masthead. */
    private void showMap() {
        screens.getChildren().setAll(mapView);
        screenJournal.dismiss();
        showActions(true);
        refresh(true);
    }

    /** True while the ports are on screen rather than the Nginx or the blocked screen. */
    private boolean onMap() {
        return !screens.getChildren().isEmpty() && screens.getChildren().get(0) == mapView;
    }

    private void showActions(boolean shown) {
        actionsBar.setVisible(shown);
        actionsBar.setManaged(shown);
    }

    /**
     * Reads everything the Nginx screen shows, in one go on the worker: the site files, the configuration and
     * the certificates it points at.
     */
    private void readNginx() {
        readNginx("");
    }

    /**
     * Reads everything the Nginx screen shows, in one go on the worker: the machine afresh, so the ports and
     * the programs behind them are today's; what Nginx reads; and every site file as it is written, so a site
     * that is switched off still shows what it would do.
     *
     * @param note why it is being read, for the journal, or empty
     */
    private void readNginx(String note) {
        if (network == null || nginx == null) {
            return;
        }
        NginxScreen screen = nginx;
        screen.reading(true);
        screenJournal.start("reading", who(), note.isEmpty()
                ? "the sites nginx serves, where they send traffic, their certificates" : note);
        Runnable stopWatching = screenJournal.follow(commands, CommandService.Origin.NETWORK);
        Runnable work = () -> {
            NetworkMap fresh;
            try {
                fresh = network.read();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Reading the network for Nginx failed", e);
                fresh = null;
            }
            NetworkMap base = fresh == null ? map : fresh;
            NginxSites read;
            try {
                read = NetworkCommands.parseSites(network.nginxSites().output());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Reading the Nginx sites failed", e);
                read = NginxSites.none();
            }
            // What Nginx reads first, then the files it does not: a switched-off site is only in its file.
            java.util.List<com.kubo.model.network.Vhost> blocks = new java.util.ArrayList<>(base.vhosts());
            java.util.List<com.kubo.model.network.Upstream> pools = new java.util.ArrayList<>(network.upstreams());
            try {
                String files = network.nginxSiteFiles().output();
                java.util.Set<String> known = new java.util.HashSet<>();
                for (com.kubo.model.network.Vhost block : blocks) {
                    known.add(block.fileName());
                }
                for (com.kubo.model.network.Vhost block : NetworkCommands.parseNginx(files)) {
                    if (!known.contains(block.fileName())) {
                        blocks.add(block);
                    }
                }
                for (com.kubo.model.network.Upstream upstream : NetworkCommands.parseUpstreams(files)) {
                    if (pools.stream().noneMatch(other -> other.name().equals(upstream.name()))) {
                        pools.add(upstream);
                    }
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Reading the site files failed", e);
            }
            java.util.List<com.kubo.model.network.Certificate> certs;
            try {
                certs = network.certificates(blocks);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Reading the certificates failed", e);
                certs = java.util.List.of();
            }
            NginxSites sites = read;
            java.util.List<com.kubo.model.network.Certificate> found = certs;
            java.util.List<com.kubo.model.network.Endpoint> listening = base.endpoints();
            // What else serves the web here, so a screen with no sites on it can say why rather than look broken.
            java.util.List<com.kubo.model.network.WebServer> servers = network.webServers();
            NetworkMap answered = fresh;
            javafx.application.Platform.runLater(() -> {
                stopWatching.run();
                if (answered != null) {
                    apply(answered);
                }
                screen.reading(false);
                screenJournal.finish("read", null);
                screen.setServers(servers);
                screen.show(sites, blocks, pools, found, listening);
            });
        };
        // Without root, what can be read is still read: the files are usually anybody's to open.
        runWithRoot(() -> worker.execute(work), () -> worker.execute(work));
    }

    /**
     * Runs one read and hands back what the server printed, whether the command succeeded or not.
     */
    private void read(String what, java.util.function.Consumer<String> done,
                      java.util.function.Supplier<CommandEntry> work) {
        if (network == null) {
            done.accept("No connection.");
            return;
        }
        runWithRoot(() -> worker.execute(() -> {
            String text;
            try {
                CommandEntry result = work.get();
                text = result.output().isBlank() ? result.error() : result.output();
            } catch (IllegalArgumentException e) {
                // Kubo refusing to send it, in its own words.
                text = e.getMessage();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Reading " + what + " failed", e);
                text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            String answer = text;
            javafx.application.Platform.runLater(() -> done.accept(answer));
        }));
    }

    /**
     * Runs one command for a card and hands it back the reason it failed, or null.
     */
    private void send(java.util.function.Consumer<String> done, java.util.function.Supplier<CommandEntry> work) {
        if (network == null) {
            return;
        }
        runWithRoot(() -> worker.execute(() -> {
            String trouble;
            try {
                CommandEntry result = work.get();
                trouble = result.success() ? null
                        : firstLine(result.error().isBlank() ? result.output() : result.error());
            } catch (IllegalArgumentException e) {
                // Kubo refusing to send it, in its own words.
                trouble = e.getMessage();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "A network action failed", e);
                trouble = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            String said = trouble;
            javafx.application.Platform.runLater(() -> {
                done.accept(said);
                if (said == null) {
                    reread();
                }
            });
        }));
    }

    /**
     * The probes: does the gateway answer, do the resolvers resolve, who owns this address.
     *
     * <p>Which buttons work depends on what the server has, so the tools are read first. The card opens
     * straight away all the same, with everything disabled until the answer comes.
     */
    private void showDiagnostics() {
        // Opens with what is already known about this server's tools. Waiting for a fresh look first meant the
        // card did not appear at all while the connection was busy, which reads as a button that does nothing.
        DiagnosticsCard[] card = new DiagnosticsCard[1];
        java.util.function.Consumer<java.util.Set<com.kubo.model.tools.Tool>> open = present -> {
            card[0] = new DiagnosticsCard(map.identity(), present, new DiagnosticsCard.Hands() {
                @Override
                public void probe(String title, java.util.function.Supplier<String> command,
                                  java.util.function.Consumer<String> done) {
                    runProbe(command, done);
                }

                @Override
                public void openTools() {
                    showTools();
                }
            }, NetworkScope.this::hideOverlay);
            showOverlay(card[0]);
        };
        open.accept(knownTools);
        // Then look again, in the background, and redraw the card only if the answer changed something.
        lookAtTools(found -> {
            if (overlay != null) {
                open.accept(found);
            }
        });
    }

    /**
     * Runs one probe. What the server printed is shown whether the command succeeded or not: a ping that gets
     * no reply fails, and its output is exactly the answer somebody clicked for.
     */
    private void runProbe(java.util.function.Supplier<String> command,
                          java.util.function.Consumer<String> done) {
        if (tools == null) {
            done.accept("No connection.");
            return;
        }
        String built;
        try {
            built = command.get();
        } catch (IllegalArgumentException e) {
            // Kubo refusing to send it, in its own words, before anything leaves.
            done.accept(e.getMessage());
            return;
        }
        worker.execute(() -> {
            String answer;
            try {
                CommandEntry result = tools.probe(built);
                String text = result.output().isBlank() ? result.error() : result.output();
                answer = text.isBlank() ? "The command printed nothing." : text.strip();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "A probe failed", e);
                answer = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            String said = answer;
            javafx.application.Platform.runLater(() -> done.accept(said));
        });
    }

    /**
     * What this server already has, and what Kubo could install on it.
     *
     * <p>The card opens now and fills when the server answers, like the others. Installing needs root whoever
     * is connected: no package manager writes to /usr as an ordinary user, and asking as one would only
     * produce a refusal nobody can act on.
     */
    private void showTools() {
        boolean canInstall = tools != null && tools.canInstall();
        com.kubo.ui.ToolsCard card = new com.kubo.ui.ToolsCard(
                new com.kubo.ui.ToolsCard.Hands() {
                    @Override
                    public void install(com.kubo.model.tools.Tool tool,
                                        java.util.function.Consumer<String> done) {
                        installTool(tool, line -> { }, done);
                    }

                    @Override
                    public void install(com.kubo.model.tools.Tool tool,
                                        java.util.function.Consumer<String> onLine,
                                        java.util.function.Consumer<String> done) {
                        installTool(tool, onLine, done);
                    }
                }, canInstall,
                "Installing writes to the system, so it needs root. Turn on ROOT in the header, then open this "
                        + "again.", this::hideOverlay);
        showOverlay(card);
        if (tools == null) {
            card.show(java.util.Set.of(), com.kubo.model.tools.PackageManager.NONE);
            return;
        }
        worker.execute(() -> {
            com.kubo.tools.ToolService.Reading read;
            try {
                read = tools.read();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Reading the tools failed", e);
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                javafx.application.Platform.runLater(() -> card.showProblem(reason));
                return;
            }
            com.kubo.tools.ToolService.Reading answer = read;
            javafx.application.Platform.runLater(() -> card.show(answer.present(), answer.manager()));
        });
    }

    /**
     * Installs one tool, then re-reads: a tool that just arrived changes what the next reading can say.
     *
     * @param onLine every line the package manager prints, as it prints it, on the JavaFX thread
     */
    private void installTool(com.kubo.model.tools.Tool tool, java.util.function.Consumer<String> onLine,
                             java.util.function.Consumer<String> done) {
        if (tools == null) {
            done.accept("No connection.");
            return;
        }
        runWithRoot(() -> worker.execute(() -> {
            String trouble;
            try {
                com.kubo.tools.ToolService.Reading before = tools.read();
                CommandEntry result = tools.install(tool, before.manager(), onLine);
                trouble = result.success() ? null
                        : firstLine(result.error().isBlank() ? result.output() : result.error());
            } catch (IllegalStateException | IllegalArgumentException e) {
                // Kubo refusing, in its own words.
                trouble = e.getMessage();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Installing a tool failed", e);
                trouble = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            String said = trouble;
            javafx.application.Platform.runLater(() -> {
                done.accept(said);
                if (said == null) {
                    // A tool that was not there is there now: every card may offer what it needs from here on.
                    lookAtTools(found -> { });
                    reread();
                }
            });
        }));
    }

    // Building

    /**
     * The bar every screen has, then the actions. On the Nginx and blocked screens only the bar stays.
     */
    private VBox buildMasthead() {
        Label wordmark = RootButton.wordmark();
        Label window = label("network", "term-window");
        Label host = label("?".equals(target) ? "" : target, "term-window-quiet");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        RootButton rootButton = new RootButton(root,
                network == null ? RootButton.noVerification() : verification(), this::showOverlay, this::hideOverlay);
        // Beside root, because installing needs root and the two questions are asked in the same breath.
        Button toolbox = bar("tools", event -> showTools());
        Button help = bar("help", event -> showOverlay(new NetworkHelp(this::hideOverlay)));
        HBox identity = new HBox(14, wordmark, window, host, spacer, toolbox, help, rootButton);
        identity.setAlignment(Pos.CENTER_LEFT);
        identity.getStyleClass().add("term-bar");

        actionsBar = buildRail();
        return new VBox(identity, actionsBar);
    }

    private HBox buildRail() {
        Button refresh = bar("re-read", event -> reread(true));
        Button connections = bar("connections", event -> showConnections());
        Button blocked = bar("blocked", event -> showBlocked());
        Button rules = bar("firewall", event -> showFirewall());
        Button sites = bar("nginx", event -> showNginx());
        Button apps = bar("apps", event -> showApps(null));
        Button edge = bar("bans & certificates", event -> showEdge());
        Button diagnose = bar("diagnose", event -> showDiagnostics());
        Button log = bar("log", event -> toggleConsole(false));
        Button shell = bar("shell", event -> toggleConsole(true));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox rail = new HBox(18, refresh, connections, blocked, rules, sites, apps, edge, diagnose, spacer, log, shell);
        rail.setAlignment(Pos.CENTER_LEFT);
        rail.getStyleClass().addAll("home-nav", "redis-actions");
        return rail;
    }

    /**
     * Shows the log, or the shell, under the other frames; the same button again puts it away.
     */
    private void toggleConsole(boolean shell) {
        boolean shown = consoleTile.isVisible();
        boolean onShell = console.showingShell();
        if (shown && onShell == shell) {
            consoleTile.setVisible(false);
            consoleTile.setManaged(false);
            return;
        }
        if (shell) {
            console.showShell();
        } else {
            console.showLogs();
        }
        consoleTile.setVisible(true);
        consoleTile.setManaged(true);
    }

    private VBox buildInspector() {
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setHgap(16);
        actions.setVgap(8);
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        VBox column = new VBox(16, portBox, actions, status);
        ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("track-scroll");
        VBox holder = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return holder;
    }

    private HBox buildStatus() {
        statusMode.getStyleClass().add("status-mode-live");
        statusMessage.setMinWidth(0);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox line = new HBox(14, statusMode, statusMessage, spacer, firewallFact, listeningFact, readFact);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("status-line");
        return line;
    }

    private static Button bar(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button button = new Button(text);
        button.getStyleClass().add("ghost-button");
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(action);
        return button;
    }

    private static Button action(String text, String styleClass,
                                 javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        Button button = new Button(text);
        button.getStyleClass().add(styleClass);
        if (styleClass.equals("port-danger")) {
            button.getStyleClass().add("ghost-button");
        }
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(handler);
        return button;
    }

    private static String web(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", Math.round(color.getRed() * 255),
                Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    // Previews reach in through these.

    NetworkTree ports() {
        return ports;
    }

    /** Only for the previews: a reading somebody asked for, part way through. */
    void readingForPreview(String note, List<CommandEntry> answered) {
        watched = true;
        showReading();
        readingOverlay.showForPreview("reading", who(), note, answered);
        if (!answered.isEmpty()) {
            statusMessage.setText("$ " + answered.getLast().command());
        }
    }

    /** Only for the previews: the Nginx screen part way through its reading. */
    void screenReadingForPreview(String reason, List<CommandEntry> answered) {
        screenJournal.showForPreview("reading", who(), reason, answered);
    }

    /** Only for the previews: the card that opens a port. */
    void portCardForPreview() {
        showPortCard(true, 8080, Protocol.TCP);
    }

    /** Only for the previews: the log open under the frames. */
    void showLogForPreview() {
        toggleConsole(false);
    }

    void showForPreview(NetworkMap read, int selectRow) {
        apply(read);
        if (selectRow >= 0 && selectRow < read.endpoints().size()) {
            showEndpoint(read.endpoints().get(selectRow));
        }
    }

    /** Only for the previews: puts the Nginx screen in the window, filled, without a server. */
    void showNginxForPreview(NginxScreen screen) {
        nginx = screen;
        screens.getChildren().setAll(screen);
        showActions(false);
    }

    /** What a command reads, in a few words, for somebody who did not write it. */
    static String words(String command) {
        String text = command == null ? "" : command;
        if (text.startsWith("echo '@@links'")) {
            return "interfaces, routes and sockets";
        }
        if (text.contains("@@present")) {
            return "fail2ban";
        }
        if (text.contains("fail2ban.sqlite3")) {
            return "ban history";
        }
        if (text.startsWith("for T in ufw")) {
            return "which firewall";
        }
        if (text.contains("ufw status")) {
            return "ufw rules";
        }
        if (text.contains("firewall-cmd")) {
            return "firewalld rules";
        }
        if (text.contains("nft list")) {
            return "nftables rules";
        }
        if (text.contains("iptables")) {
            return "iptables rules";
        }
        if (text.contains("is-active")) {
            return "firewall state";
        }
        if (text.contains("nginx -T")) {
            return "nginx configuration";
        }
        if (text.contains("sites-enabled") || text.contains("/etc/nginx")) {
            return "nginx files";
        }
        if (text.contains("caddy") || text.contains("apache2")) {
            return "web servers";
        }
        if (text.contains("pm2")) {
            return "pm2 apps";
        }
        if (text.contains("state established")) {
            return "open connections";
        }
        if (text.contains("docker")) {
            return "containers";
        }
        if (text.contains("openssl")) {
            return "certificates";
        }
        if (text.contains("ss -") && text.contains("l")) {
            return "listening sockets";
        }
        if (text.contains("ip -j -o addr") || text.contains("ip -j addr") || text.contains("ip addr")) {
            return "addresses";
        }
        if (text.contains("ip -j route") || text.contains("ip route")) {
            return "routes";
        }
        if (text.contains("resolv.conf") || text.contains("resolvectl")) {
            return "dns resolvers";
        }
        // Otherwise the program's own name, past any VAR=value in front of it.
        for (String part : text.strip().split("\\s+")) {
            if (!part.contains("=") && !part.equals("sudo")) {
                return part;
            }
        }
        return "command";
    }
}
