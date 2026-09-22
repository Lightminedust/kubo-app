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

    private final NetworkService network;
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
    private SitesCard sitesCard;
    private Endpoint selected;
    private StackPane overlay;
    private ContextMenu menu;

    public NetworkScope(NetworkService network, RootSession root, SshConnection connection, CommandService commands) {
        this.network = network;
        this.root = root;
        this.console = new ServerConsole(connection, commands, root);
        getStyleClass().add("scope-view");

        flow.setOnSelect(this::showEndpoint);
        flow.setOnMenu(request -> showMenu(request.endpoint(), request.screenX(), request.screenY()));
        if (connection != null) {
            flow.setHostname(hostOf(String.valueOf(connection.target())));
        }

        BorderPane body = new BorderPane(flow);
        body.setLeft(machine);
        body.setRight(buildInspector());

        BorderPane layout = new BorderPane(body);
        layout.setTop(buildMasthead());
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
        refresh(true);
        poll.play();
    }

    public void shutdown() {
        poll.stop();
        flow.stop();
        console.shutdown();
        worker.shutdownNow();
    }

    // Reading

    private void refresh(boolean announce) {
        if (network == null) {
            return;
        }
        if (announce) {
            flow.setLoading(true);
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
                    showReadFailure(e);
                });
                return;
            }
            Platform.runLater(() -> {
                flow.setLoading(false);
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
        flow.setMap(read);
        long exposed = read.openToTheWorld();
        long alerts = read.alerts();

        headline.setText(alerts > 0 ? "EXPOSED" : exposed > 0 ? "REACHABLE" : "CLOSED");
        headline.setStyle("-fx-text-fill: " + web(alerts > 0 ? ALERT : exposed > 0 ? OPEN : SAFE) + ";");
        caption.setText((read.endpoints().size() + " LISTENING PORTS   ·   " + read.vhosts().size()
                + " NGINX SERVER BLOCKS   ·   " + read.pm2().size() + " PM2 APPS").toUpperCase(Locale.ROOT));

        machine.show(read.identity(), read.rates());

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
                        refresh(false);
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
                    refresh(false);
                });
            });
        });
    }

    private void finish(CommandEntry result, String success, java.util.function.Consumer<String> done) {
        if (result.success()) {
            hideOverlay();
            status.getStyleClass().setAll("label", "card-success");
            status.setText(success);
            refresh(false);
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
        if (network != null && network.needsPassword()) {
            showOverlay(new SudoPrompt(root.privilege(), verification(),
                    () -> {
                        root.refresh();
                        hideOverlay();
                        action.run();
                    },
                    this::hideOverlay));
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
        showOverlay(new ConnectionsCard(map.connections(),
                address -> banEverywhere(address), canBan, this::hideOverlay));
    }

    private void showEdge() {
        guard = new GuardCard(map.jails(), map.certificates(), java.time.Instant.now(), new GuardCard.Hands() {
            @Override
            public void ban(String jail, String address) {
                sendGuard(() -> network.ban(jail, address), address + " banned in " + jail);
            }

            @Override
            public void unban(String jail, String address) {
                sendGuard(() -> network.unban(jail, address), "Ban lifted on " + address);
            }
        }, this::hideOverlay);
        showOverlay(guard);
    }

    /**
     * Bans an address in the first jail there is.
     *
     * <p>fail2ban bans per jail, and from the connection list there is no way to know which jail a stranger
     * belongs in. The first one is used, which on nearly every server is sshd, and the edge card is where a
     * specific jail can be chosen.
     */
    private void banEverywhere(String address) {
        if (map.jails().isEmpty() || network == null) {
            return;
        }
        String jail = map.jails().get(0).name();
        sendGuard(() -> network.ban(jail, address), address + " banned in " + jail);
    }

    /**
     * Runs a ban or an unban and reports into the card it was asked from.
     */
    private void sendGuard(java.util.function.Supplier<CommandEntry> work, String done) {
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
                if (said == null) {
                    refresh(false);
                }
            });
        }));
    }

    /**
     * Every firewall rule, with the two edits the screen could not make.
     */
    private void showFirewall() {
        firewallCard = new FirewallCard(map.firewall(), new FirewallCard.Hands() {
            @Override
            public void allowFrom(String source, int port, Protocol protocol) {
                send(result -> firewallCard.report(result, "Port " + port + " opened to " + source),
                        () -> network.allowFrom(source, port, protocol));
            }

            @Override
            public void delete(FirewallRule rule) {
                // The numbers move as soon as one rule goes, so the card is rebuilt from the next reading
                // rather than left showing a list that is one rule out of date.
                send(result -> {
                    firewallCard.report(result, "Rule " + rule.index() + " deleted");
                    if (result == null) {
                        hideOverlay();
                    }
                }, () -> network.deleteRule(rule.index()));
            }
        }, this::hideOverlay);
        showOverlay(firewallCard);
    }

    /**
     * The Nginx sites. The listing is read when the card opens rather than on every poll: it changes only when
     * somebody changes it, and the refresh already costs enough.
     */
    private void showSites() {
        if (network == null) {
            showOverlay(new SitesCard(NginxSites.none(), noSites(), this::hideOverlay));
            return;
        }
        runWithRoot(() -> worker.execute(() -> {
            NginxSites read;
            try {
                read = NetworkCommands.parseSites(network.nginxSites().output());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Reading the Nginx sites failed", e);
                read = NginxSites.none();
            }
            NginxSites sites = read;
            javafx.application.Platform.runLater(() -> {
                sitesCard = new SitesCard(sites, new SitesCard.Hands() {
                    @Override
                    public void enable(String site) {
                        send(result -> sitesCard.report(result, site + " switched on and Nginx reloaded"),
                                () -> network.enableSite(site));
                    }

                    @Override
                    public void disable(String site) {
                        send(result -> sitesCard.report(result, site + " switched off and Nginx reloaded"),
                                () -> network.disableSite(site));
                    }

                    @Override
                    public void test() {
                        send(result -> sitesCard.report(result, "The configuration is valid"),
                                () -> network.testNginx());
                    }
                }, this::hideOverlay);
                showOverlay(sitesCard);
            });
        }));
    }

    private static SitesCard.Hands noSites() {
        return new SitesCard.Hands() {
            @Override
            public void enable(String site) {
            }

            @Override
            public void disable(String site) {
            }

            @Override
            public void test() {
            }
        };
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
                    refresh(false);
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
        RootButton rootButton = new RootButton(root,
                network == null ? RootButton.noVerification() : verification(), this::showOverlay, this::hideOverlay);
        HBox identity = new HBox(12, wordmark, mark, kicker, spacer, rootButton, camera);
        identity.setAlignment(Pos.CENTER_LEFT);

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
        Button refresh = rail("↻ RE-READ", event -> refresh(true));
        isolatedToggle.getStyleClass().add("rail-button");
        isolatedToggle.setFocusTraversable(false);
        isolatedToggle.setOnAction(event -> {
            flow.setShowIsolated(!flow.showsIsolated());
            isolatedToggle.setText(flow.showsIsolated() ? "HIDE LOOPBACK" : "SHOW LOOPBACK");
        });
        Button connections = rail("CONNECTIONS", event -> showConnections());
        Button edge = rail("THE EDGE", event -> showEdge());
        Button rules = rail("FIREWALL RULES", event -> showFirewall());
        Button sites = rail("NGINX SITES", event -> showSites());
        Button reload = rail("TEST & RELOAD NGINX", event -> reloadNginx());
        Button shell = rail("SHELL", event -> console.showShell());
        Button help = rail("?", event -> showOverlay(new NetworkHelp(this::hideOverlay)));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox rail = new HBox(8, refresh, isolatedToggle, separator(), connections, edge, rules, sites,
                separator(), reload, shell, spacer, help);
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

    NetworkTree flow() {
        return flow;
    }

    void showForPreview(NetworkMap read, int selectRow) {
        apply(read);
        if (selectRow >= 0 && selectRow < read.endpoints().size()) {
            showEndpoint(read.endpoints().get(selectRow));
        }
    }
}
