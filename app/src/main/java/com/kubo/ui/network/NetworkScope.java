package com.kubo.ui.network;

import com.kubo.command.CommandEntry;
import com.kubo.model.network.Endpoint;
import com.kubo.model.network.Firewall;
import com.kubo.model.network.FirewallRule;
import com.kubo.model.network.NetworkMap;
import com.kubo.model.network.NginxSites;
import com.kubo.network.NetworkCommands;
import com.kubo.model.network.PmApp;
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
import javafx.geometry.Insets;
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
 * The network screen: a masthead stating how exposed the server is, one rail of actions, the flow map from the
 * internet to each process, and an inspector that acts on whichever port is selected — open or close it at the
 * firewall, restart or stop the application behind it, or end the process outright.
 */
public final class NetworkScope extends StackPane {

    private static final Color ALERT = Color.web("#ff2d55");
    private static final Color OPEN = Color.web("#ffb347");
    private static final Color SAFE = Color.web("#35e0a1");
    private static final Color CYAN = Color.web("#35e8ff");

    /** The name this screen answers to in {@link com.kubo.command.Attention}. */
    public static final String ATTENTION = "NETWORK";

    private final NetworkService network;
    /** The map or the nginx screen, whichever the operator asked for. */
    private final javafx.scene.layout.StackPane screens = new javafx.scene.layout.StackPane();
    private javafx.scene.Node mapView;
    /**
     * The wordmark, TOOLS and ROOT. Kept apart from the rest of the masthead because it belongs to the window
     * and not to the map: reading a log needs root whichever screen is asking.
     */
    private HBox identityRow;
    /** The zoom controls, which move the map and mean nothing on any other screen. */
    private HBox cameraBar;
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
    private final ServerConsole console;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-network");
        thread.setDaemon(true);
        return thread;
    });

    private final NetworkTree flow = new NetworkTree();
    private final Timeline poll = new Timeline(new KeyFrame(Duration.seconds(12), event -> refresh(false)));

    // Masthead
    private final Label headline = label("NETWORK", "sheet-title");
    private final Label caption = label("", "sheet-caption");
    private final HBox tagRow = new HBox(6);
    private final Label exposedValue = label("—", "readout-value");
    private final Label firewallValue = label("—", "readout-value");
    private final Label alertsValue = label("—", "readout-value");
    private final Button isolatedToggle = new Button("HIDE LOOPBACK");

    // Inspector
    private final Label portTitle = label("NO PORT SELECTED", "inspector-key");
    private final Label reachBadge = label("", "badge");
    private final VBox chain = new VBox(6);
    private final VBox riskBox = new VBox(6);
    private final FlowPane actions = new FlowPane(8, 8);
    private final Label status = label("", "card-note");
    private final VBox notesBox = new VBox(4);

    private static final System.Logger LOG = System.getLogger(NetworkScope.class.getName());

    private NetworkMap map = NetworkMap.empty();
    private final MachinePanel machine = new MachinePanel();
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
        this.tools = connection == null || root == null ? null
                : new com.kubo.tools.ToolService(connection, commands, root.privilege());
        this.console = new ServerConsole(connection, commands, root, "NETWORK",
                CommandService.Origin.NETWORK, java.util.Set.of());
        getStyleClass().add("scope-view");

        flow.setOnSelect(this::showEndpoint);
        flow.setOnMenu(request -> showMenu(request.endpoint(), request.screenX(), request.screenY()));
        if (connection != null) {
            flow.setHostname(hostOf(String.valueOf(connection.target())));
        }

        BorderPane body = new BorderPane(flow);
        body.setLeft(machine);
        body.setRight(buildInspector());
        mapView = body;

        // The map and the nginx screen take turns in the same place. Nginx is not a card over the map: it is a
        // screen of its own, with its own rail, and it needs the whole window to say anything useful.
        screens.getChildren().add(body);

        masthead.getChildren().setAll(buildMasthead());
        BorderPane layout = new BorderPane(screens);
        layout.setTop(masthead);
        layout.setBottom(console);
        getChildren().add(layout);

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
        reread(false);
    }

    private void reread(boolean announce) {
        network.forget();
        refresh(announce);
    }

    public void shutdown() {
        poll.stop();
        flow.stop();
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
        if (network == null || onNginx()) {
            // Nothing on the map is on screen, so re-reading it would only take the connection from the screen
            // that is.
            return;
        }
        if (announce) {
            flow.setLoading(true);
            machine.setReading(true);
        }
        worker.execute(() -> {
            NetworkMap read;
            try {
                read = network.read();
            } catch (RuntimeException e) {
                // Anything unexpected is shown, not swallowed: a screen stuck on "listening to the server" tells
                // the user nothing, and the stack trace goes to a terminal they may never see.
                LOG.log(System.Logger.Level.ERROR, "Reading the network failed", e);
                Platform.runLater(() -> {
                    flow.setLoading(false);
                    machine.setReading(false);
                    showReadFailure(e);
                });
                return;
            }
            Platform.runLater(() -> {
                flow.setLoading(false);
                machine.setReading(false);
                apply(read);
            });
        });
    }

    /**
     * The read could not be finished at all. The map stays as it was; the reason is written where the other
     * unreadable things are.
     */
    private void showReadFailure(RuntimeException problem) {
        String reason = problem.getMessage() == null || problem.getMessage().isBlank()
                ? problem.getClass().getSimpleName() : problem.getMessage();
        headline.setText("COULD NOT READ");
        headline.setStyle("-fx-text-fill: " + web(ALERT) + ";");
        caption.setText("THE NETWORK READING STOPPED PART WAY THROUGH");
        Label line = label("▸ " + reason, "card-problem");
        line.setWrapText(true);
        notesBox.getChildren().setAll(line);
        notesBox.setVisible(true);
        notesBox.setManaged(true);
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
        flow.setMap(read);
        long exposed = read.openToTheWorld();
        long alerts = read.alerts();

        headline.setText(alerts > 0 ? "EXPOSED" : exposed > 0 ? "REACHABLE" : "CLOSED");
        headline.setStyle("-fx-text-fill: " + web(alerts > 0 ? ALERT : exposed > 0 ? OPEN : SAFE) + ";");
        caption.setText((read.endpoints().size() + " LISTENING PORTS   ·   " + read.vhosts().size()
                + " NGINX SERVER BLOCKS   ·   " + read.pm2().size() + " PM2 APPS").toUpperCase(Locale.ROOT));

        machine.show(read.identity(), read.rates(), read.naming());

        exposedValue.setText(String.valueOf(exposed));
        exposedValue.setStyle("-fx-text-fill: " + web(exposed > 0 ? OPEN : SAFE) + ";");
        alertsValue.setText(String.valueOf(alerts));
        alertsValue.setStyle("-fx-text-fill: " + web(alerts > 0 ? ALERT : SAFE) + ";");
        firewallValue.setText(read.firewall().shortState());
        firewallValue.setStyle("-fx-text-fill: " + web(NetworkTree.colourOfFirewall(read.firewall())) + ";");

        tagRow.getChildren().setAll(
                tag(read.firewall().isEnforcing()
                        ? (read.openPorts().isEmpty() ? "NO PORT OPENED BY " + read.firewall().title()
                                : read.firewall().title() + " OPENS " + join(read.openPorts()))
                        : read.firewall().title() + " " + read.firewall().shortState()),
                tag(read.pm2().stream().filter(PmApp::isOnline).count() + " PM2 APPS ONLINE"),
                tag(read.endpoints().stream().filter(Endpoint::isBehindNginx).count() + " PORTS BEHIND NGINX"));

        notesBox.getChildren().clear();
        for (String note : read.notes()) {
            Label line = label("▸ " + note, "card-note");
            line.setWrapText(true);
            notesBox.getChildren().add(line);
        }
        notesBox.setVisible(!read.notes().isEmpty());
        notesBox.setManaged(!read.notes().isEmpty());

        if (selected != null) {
            read.endpoints().stream()
                    .filter(endpoint -> endpoint.port() == selected.port()
                            && endpoint.socket().bindAddress().equals(selected.socket().bindAddress()))
                    .findFirst()
                    .ifPresentOrElse(this::showEndpoint, () -> showEndpoint(null));
        }
    }

    private static String join(List<Integer> ports) {
        return ports.stream().limit(6).map(String::valueOf).collect(java.util.stream.Collectors.joining(", "))
                + (ports.size() > 6 ? "…" : "");
    }

    // Inspector

    private void showEndpoint(Endpoint endpoint) {
        selected = endpoint;
        flow.select(endpoint);
        status.setText("");
        chain.getChildren().clear();
        riskBox.getChildren().clear();
        actions.getChildren().clear();
        if (endpoint == null) {
            portTitle.setText("NO PORT SELECTED");
            reachBadge.setVisible(false);
            reachBadge.setManaged(false);
            chain.getChildren().add(label("PICK A ROW ON THE MAP TO SEE ITS WHOLE PATH AND ACT ON IT.",
                    "metric-label"));
            return;
        }
        portTitle.setText("PORT " + endpoint.port());
        Color colour = NetworkTree.colourOf(endpoint);
        reachBadge.setVisible(true);
        reachBadge.setManaged(true);
        reachBadge.setText(switch (endpoint.reach()) {
            case OPEN -> "OPEN TO THE INTERNET";
            case SHIELDED -> "BEHIND THE FIREWALL";
            case PRIVATE -> "PRIVATE NETWORK";
            case ISOLATED -> "LOOPBACK ONLY";
        });
        reachBadge.setStyle("-fx-text-fill: " + web(colour) + "; -fx-border-color: " + web(colour) + ";");

        chain.getChildren().addAll(
                step("BINDS", endpoint.socket().bindAddress() + ":" + endpoint.port()
                        + "  " + endpoint.socket().protocol()),
                step("FIREWALL", firewallLine(endpoint)),
                step("NGINX", endpoint.isBehindNginx()
                        ? endpoint.servedBy().stream().map(Vhost::url).collect(java.util.stream.Collectors.joining(", "))
                        : "no server block forwards to this port"),
                step("PROCESS", endpoint.owner().label() + "   " + endpoint.owner().detail()));

        Risk.of(endpoint).ifPresent(risk -> {
            Label title = label(risk.label() + "  ·  " + risk.title(), "card-problem");
            title.setWrapText(true);
            Label detail = label(risk.detail(), "card-note");
            detail.setWrapText(true);
            riskBox.getChildren().addAll(title, detail);
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
            return firewall.problem();
        }
        return firewall.ruleFor(endpoint.port())
                .map(rule -> "rule [" + rule.index() + "] " + rule.action() + " from " + rule.from())
                .orElse("no rule names this port, so " + firewall.title() + "'s default denies it");
    }

    private void buildActions(Endpoint endpoint) {
        ServiceOwner owner = endpoint.owner();
        // Kubo only writes firewall rules through UFW. On firewalld, nftables or raw iptables it reads and shows
        // what it finds, but offering a button that would run the wrong tool is worse than offering none.
        if (canChangeFirewall()) {
            if (endpoint.isOpenToTheWorld()) {
                actions.getChildren().add(action("CLOSE PORT " + endpoint.port(), "danger-button",
                        event -> confirmClose(endpoint)));
            } else if (endpoint.reach() == Endpoint.Reach.SHIELDED) {
                actions.getChildren().add(action("OPEN PORT " + endpoint.port(), "rail-button",
                        event -> confirmOpen(endpoint)));
            }
        }
        if (owner.kind() == ServiceOwner.Kind.PM2) {
            actions.getChildren().addAll(
                    action("RESTART APP", "primary-button", event -> act("RESTARTED",
                            () -> network.restartApp(owner.handle()))),
                    action("STOP APP", "rail-button", event -> act("STOPPED",
                            () -> network.stopApp(owner.handle()))),
                    action("APP LOGS", "rail-button", event -> showAppLogs(owner.handle())));
        }
        if (endpoint.isBehindNginx()) {
            actions.getChildren().add(action("TEST & RELOAD NGINX", "rail-button", event -> reloadNginx()));
        }
        if (endpoint.socket().hasPid() && owner.kind() != ServiceOwner.Kind.PM2) {
            actions.getChildren().add(action("KILL PID " + endpoint.socket().pid(), "danger-button",
                    event -> confirmKill(endpoint)));
        }
    }

    private HBox step(String name, String value) {
        Label caption = label(name, "rail-label");
        caption.setMinWidth(72);
        Label text = label(value, "redis-row-name");
        text.setWrapText(true);
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
                Platform.runLater(() -> finish(result, "PORT " + expected + " OPENED", done));
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
                Platform.runLater(() -> finish(result, "PORT " + expected + " CLOSED", done));
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
                Platform.runLater(() -> finish(result, "PID " + pid + " KILLED", done));
            }));
        }, this::hideOverlay));
    }

    private void reloadNginx() {
        runWithRoot(() -> {
            status.getStyleClass().setAll("label", "card-note");
            status.setText("TESTING THE CONFIGURATION…");
            worker.execute(() -> {
                CommandEntry result = network.reloadNginx();
                Platform.runLater(() -> {
                    String text = result.output().isBlank() ? result.error() : result.output();
                    if (result.success()) {
                        status.getStyleClass().setAll("label", "card-success");
                        status.setText("CONFIGURATION VALID  ·  NGINX RELOADED");
                        reread();
                    } else {
                        status.getStyleClass().setAll("label", "card-problem");
                        status.setText("NOT RELOADED  ·  " + firstLine(text));
                        showOverlay(PromptCard.output("NETWORK", "NGINX -T", text, this::hideOverlay));
                    }
                });
            });
        });
    }

    private void showAppLogs(String app) {
        runWithRoot(() -> {
            status.getStyleClass().setAll("label", "card-note");
            status.setText("READING LOGS…");
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
            status.setText("WORKING…");
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
        return text == null ? "FAILED" : text.strip().lines().findFirst().orElse("FAILED").toUpperCase(Locale.ROOT);
    }

    private void showMenu(Endpoint endpoint, double screenX, double screenY) {
        if (menu != null) {
            menu.hide();
        }
        List<MenuItem> items = new java.util.ArrayList<>();
        items.add(item("INSPECT THIS PORT", event -> showEndpoint(endpoint)));
        if (canChangeFirewall()) {
            if (endpoint.isOpenToTheWorld()) {
                items.add(item("CLOSE PORT AT THE FIREWALL", event -> confirmClose(endpoint)));
            } else if (endpoint.reach() == Endpoint.Reach.SHIELDED) {
                items.add(item("OPEN PORT AT THE FIREWALL", event -> confirmOpen(endpoint)));
            }
        }
        if (endpoint.owner().kind() == ServiceOwner.Kind.PM2) {
            items.add(new SeparatorMenuItem());
            items.add(item("RESTART " + endpoint.owner().label(),
                    event -> act("RESTARTED", () -> network.restartApp(endpoint.owner().handle()))));
            items.add(item("STOP " + endpoint.owner().label(),
                    event -> act("STOPPED", () -> network.stopApp(endpoint.owner().handle()))));
            items.add(item("LOGS", event -> showAppLogs(endpoint.owner().handle())));
        }
        if (endpoint.socket().hasPid() && endpoint.owner().kind() != ServiceOwner.Kind.PM2) {
            items.add(new SeparatorMenuItem());
            MenuItem kill = item("KILL PID " + endpoint.socket().pid(), event -> confirmKill(endpoint));
            kill.getStyleClass().add("redis-menu-danger");
            items.add(kill);
        }
        menu = new ContextMenu(items.toArray(new MenuItem[0]));
        menu.getStyleClass().add("redis-context-menu");
        menu.show(this, screenX, screenY);
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
        screens.getChildren().setAll(nginx);
        // The map's own rail goes with the map. What stays is the part that belongs to the window, less the
        // zoom, which moves a map that is no longer there.
        masthead.getChildren().setAll(identityRow);
        cameraBar.setVisible(false);
        cameraBar.setManaged(false);
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
                flow.select(endpoint);
                showEndpoint(endpoint);
                return;
            }
        }
    }

    /** Puts the map back, with its own masthead. */
    private void showMap() {
        screens.getChildren().setAll(mapView);
        masthead.getChildren().setAll(buildMasthead());
        refresh(true);
    }

    /** True while the operator is on the Nginx screen rather than the map. */
    private boolean onNginx() {
        return !screens.getChildren().isEmpty() && screens.getChildren().get(0) == nginx;
    }

    /**
     * Reads everything the Nginx screen shows, in one go on the worker: the site files, the configuration and
     * the certificates it points at.
     */
    private void readNginx() {
        if (network == null || nginx == null) {
            return;
        }
        NginxScreen screen = nginx;
        runWithRoot(() -> worker.execute(() -> {
            NginxSites read;
            try {
                read = NetworkCommands.parseSites(network.nginxSites().output());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Reading the Nginx sites failed", e);
                read = NginxSites.none();
            }
            java.util.List<com.kubo.model.network.Vhost> blocks = map.vhosts();
            java.util.List<com.kubo.model.network.Upstream> pools = network.upstreams();
            java.util.List<com.kubo.model.network.Certificate> certs;
            try {
                certs = network.certificates(blocks);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Reading the certificates failed", e);
                certs = java.util.List.of();
            }
            NginxSites sites = read;
            java.util.List<com.kubo.model.network.Certificate> found = certs;
            java.util.List<com.kubo.model.network.Endpoint> listening = map.endpoints();
            javafx.application.Platform.runLater(() -> screen.show(sites, blocks, pools, found, listening));
        }));
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

    private VBox buildMasthead() {
        Label wordmark = RootButton.wordmark();
        Label mark = label("N", "sheet-mark");
        Label kicker = label("NETWORK.SCOPE", "series");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button zoomOut = rail("−", event -> flow.zoomOut());
        Button fit = rail("FIT", event -> flow.fit());
        Button zoomIn = rail("+", event -> flow.zoomIn());
        HBox camera = new HBox(2, zoomOut, fit, zoomIn);
        camera.getStyleClass().add("zoom-bar");
        cameraBar = camera;
        RootButton rootButton = new RootButton(root,
                network == null ? RootButton.noVerification() : verification(), this::showOverlay, this::hideOverlay);
        // Beside ROOT, because installing needs root and the two questions are asked in the same breath.
        Button toolbox = rail("TOOLS", event -> showTools());
        HBox identity = new HBox(12, wordmark, mark, kicker, spacer, toolbox, rootButton, camera);
        identity.setAlignment(Pos.CENTER_LEFT);
        identity.setPadding(new Insets(16, 34, 0, 26));
        identityRow = identity;

        VBox naming = new VBox(2, headline, caption);
        tagRow.setAlignment(Pos.CENTER_LEFT);
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox readout = new HBox(0, readout("OPEN TO THE WORLD", exposedValue),
                rule(), readout("ALERTS", alertsValue), rule(), readout("FIREWALL", firewallValue));
        readout.getStyleClass().add("readout-bar");
        readout.setAlignment(Pos.CENTER_LEFT);
        HBox title = new HBox(26, naming, tagRow, titleSpacer, readout);
        title.setAlignment(Pos.CENTER_LEFT);

        VBox masthead = new VBox(12, identity, title, buildRail());
        masthead.getStyleClass().add("sheet-masthead");
        masthead.setPadding(new Insets(16, 34, 0, 26));
        return masthead;
    }

    private HBox buildRail() {
        Button refresh = rail("↻ RE-READ", event -> reread(true));
        isolatedToggle.getStyleClass().add("rail-button");
        isolatedToggle.setFocusTraversable(false);
        isolatedToggle.setOnAction(event -> {
            flow.setShowIsolated(!flow.showsIsolated());
            isolatedToggle.setText(flow.showsIsolated() ? "HIDE LOOPBACK" : "SHOW LOOPBACK");
        });
        Button connections = rail("CONNECTIONS", event -> showConnections());
        Button edge = rail("THE EDGE", event -> showEdge());
        Button rules = rail("FIREWALL RULES", event -> showFirewall());
        Button sites = rail("NGINX", event -> showNginx());
        Button diagnose = rail("DIAGNOSE", event -> showDiagnostics());
        Button shell = rail("SHELL", event -> console.showShell());
        Button help = rail("?", event -> showOverlay(new NetworkHelp(this::hideOverlay)));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox rail = new HBox(8, refresh, isolatedToggle, separator(), connections, edge, rules, sites,
                separator(), diagnose, shell, spacer, help);
        rail.setAlignment(Pos.CENTER_LEFT);
        rail.setPadding(new Insets(0, 0, 10, 0));
        return rail;
    }

    private VBox buildInspector() {
        portTitle.setWrapText(true);
        HBox head = new HBox(10, label("INSPECTOR", "series"), reachBadge);
        head.setAlignment(Pos.CENTER_LEFT);
        actions.setAlignment(Pos.CENTER_LEFT);
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        notesBox.setVisible(false);
        notesBox.setManaged(false);

        VBox column = new VBox(10, head, portTitle,
                label("THE PATH IN", "series"), chain,
                riskBox,
                label("ACTIONS", "series"), actions, status,
                label("WHAT KUBO COULD NOT READ", "series"), notesBox);
        column.setPadding(new Insets(16, 26, 16, 20));
        ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("redis-inspector");
        VBox holder = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        holder.getStyleClass().add("redis-inspector");
        holder.setPrefWidth(410);
        holder.setMinWidth(340);
        return holder;
    }

    private VBox readout(String caption, Label value) {
        VBox box = new VBox(0, label(caption, "readout-caption"), value);
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

    private static Button rail(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button button = new Button(text);
        button.getStyleClass().add("rail-button");
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(action);
        return button;
    }

    private static Button action(String text, String styleClass,
                                 javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        Button button = new Button(text);
        button.getStyleClass().add(styleClass);
        if (styleClass.equals("primary-button") || styleClass.equals("danger-button")) {
            button.getStyleClass().add("compact-button");
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

    /** For the previews: the panel down the left, so a still picture can show it mid-read. */
    MachinePanel machine() {
        return machine;
    }

    NetworkTree flow() {
        return flow;
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
        masthead.getChildren().setAll(identityRow);
        cameraBar.setVisible(false);
        cameraBar.setManaged(false);
    }
}
