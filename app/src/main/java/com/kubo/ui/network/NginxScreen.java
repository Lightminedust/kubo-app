package com.kubo.ui.network;

import com.kubo.model.network.AccessLine;
import com.kubo.model.network.Certificate;
import com.kubo.model.network.Endpoint;
import com.kubo.model.network.NginxSites;
import com.kubo.model.network.Rule;
import com.kubo.model.network.ServiceOwner;
import com.kubo.model.network.Upstream;
import com.kubo.model.network.Vhost;
import com.kubo.model.network.WebServer;
import com.kubo.ui.Pulse;
import com.kubo.ui.Tile;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything Nginx is doing on this server, as a screen of its own, in the same frames as the rest.
 *
 * <p>On the left the sites, switched on or not. In the middle the way a request reaches the chosen one, every
 * path of it and what answers each. On the right what is true about it and what can be done to it, and under
 * them what it has been answering lately, or its file.
 */
final class NginxScreen extends BorderPane {

    /** What the screen asks the scope to do. Every answer comes back on the JavaFX thread. */
    interface Hands {
        void enable(String site, java.util.function.Consumer<String> done);

        void disable(String site, java.util.function.Consumer<String> done);

        /** Checks the configuration and changes nothing. */
        void test(java.util.function.Consumer<String> done);

        /** Checks the configuration and reloads only if it is valid. */
        void reload(java.util.function.Consumer<String> done);

        /** One file as it is written on disk. */
        void readFile(String path, java.util.function.Consumer<String> done);

        /** The last lines of one log file. */
        void readLog(String path, java.util.function.Consumer<String> done);

        /** Reads everything again. */
        void reread();

        /** Opens the form that writes a new site. */
        void newSite();

        /** Goes back to the map with this port picked on it. */
        void showPort(int port);

        /** Opens a folder on the server to look at what is in it. */
        void browse(String path);

        /** Leaves this screen and puts the map back. */
        void back();
    }

    /** What the frame at the bottom is showing. */
    private enum Below { REQUESTS, FILE }

    private final Hands hands;

    private final VBox siteList = new VBox(2);
    private final SiteRoute route = new SiteRoute();
    private final Label routeEmpty = new Label();
    private final VBox facts = new VBox(6);
    private final VBox pool = new VBox(4);
    private final FlowPane actions = new FlowPane(16, 8);
    private final Label status = new Label();
    private final Pulse working = new Pulse();
    private final Label tally = new Label();
    private final TextArea file = new TextArea();
    private final VBox logLines = new VBox(2);
    private final Pulse belowPulse = new Pulse();
    private final Button requestsTab = tab("requests", Below.REQUESTS);
    private final Button fileTab = tab("file", Below.FILE);
    private final Label belowSource = new Label();
    private Tile routeTile;
    private Tile siteTile;
    private ScrollPane logScroll;

    private NginxSites sites = NginxSites.none();
    private List<Vhost> vhosts = List.of();
    private List<Upstream> upstreams = List.of();
    private List<Certificate> certificates = List.of();
    private List<Endpoint> listening = List.of();
    /** What else serves the web here. Only ever filled when there is no Nginx to show. */
    private List<WebServer> servers = List.of();
    /** The buttons that send an Nginx command, held so they can be shut off when there is no Nginx. */
    private final List<Button> nginxActions = new ArrayList<>();
    private String selected = "";
    private Below below = Below.REQUESTS;
    /** The chosen site's certificate in words, worked out once a drawing so every place says the same. */
    private String[] certificate = {"", "UNKNOWN"};
    /** What this machine is called, for the bottom of the route. */
    private String machineName = "this server";

    /** The key of the one node every program and folder on the route stands on. */
    private static final String MACHINE = "machine";

    NginxScreen(Hands hands) {
        this.hands = hands;
        getStyleClass().add("scope-view");
        setTop(buildBar());
        setCenter(buildBody());
        paintTabs();
        draw();
    }

    /**
     * Fills the screen. The sites listing is what the left column is made of, because it is the only source
     * that knows about a site that exists and is switched off.
     */
    void show(NginxSites sites, List<Vhost> vhosts, List<Upstream> upstreams, List<Certificate> certificates,
              List<Endpoint> listening) {
        this.listening = listening == null ? List.of() : List.copyOf(listening);
        this.sites = sites == null ? NginxSites.none() : sites;
        this.vhosts = vhosts == null ? List.of() : List.copyOf(vhosts);
        this.upstreams = upstreams == null ? List.of() : List.copyOf(upstreams);
        this.certificates = certificates == null ? List.of() : List.copyOf(certificates);
        if (selected.isEmpty() || !order().contains(selected)) {
            selected = this.sites.enabled().stream().findFirst()
                    .orElse(this.sites.available().stream().findFirst().orElse(""));
        }
        draw();
        loadBelow();
    }

    /**
     * What web servers the machine has, so that a screen with nothing on it can say why.
     */
    void setServers(List<WebServer> found) {
        servers = found == null ? List.of() : List.copyOf(found);
        boolean noNginx = !servers.isEmpty() && !WebServer.hasNginx(servers);
        for (Button action : nginxActions) {
            action.setDisable(noNginx);
        }
    }

    /** The pools this server names, so the form can offer them as a destination. */
    List<Upstream> pools() {
        return upstreams;
    }

    /** Kept for the scope that drives it; the reading is shown by the journal laid over the screen. */
    void reading(boolean yes) {
        if (yes) {
            tally.setText("reading…");
        }
    }

    /** Says a command came back: null for a success, otherwise what the server said. */
    void report(String trouble, String done) {
        working.setRunning(false);
        status.getStyleClass().setAll("label", trouble == null ? "card-success" : "card-problem");
        status.setText(trouble == null ? done : trouble);
        status.setVisible(true);
        status.setManaged(true);
    }

    /** Names the machine at the bottom of the route. */
    void setMachine(String name) {
        machineName = name == null || name.isBlank() ? "this server" : name;
        if (!selected.isEmpty()) {
            drawSelected();
        }
    }

    /** Only for the previews: picks one site as if it had been clicked. */
    void selectForPreview(String site) {
        selected = site;
        draw();
        loadBelow();
    }

    /** Only for the previews: the route as it stands once it has come in, with light part way down it. */
    void freezeForPreview() {
        route.freeze(2.7);
    }

    /** Only for the previews: the way through one node of the route lit, as under the pointer. */
    void pointForPreview(String key) {
        route.pointForPreview(key);
    }

    // Building

    /** The screen's own actions, under the window's bar, the way the map has its own. */
    private HBox buildBar() {
        Button back = bar("← map", event -> hands.back());
        Button add = bar("new site", event -> hands.newSite());
        Button reread = bar("re-read", event -> hands.reread());
        Button test = bar("test", event -> {
            working.setText("nginx -t");
            working.setRunning(true);
            hands.test(trouble -> report(trouble, "the configuration is valid, nothing was changed"));
        });
        Button reload = bar("test & reload", event -> {
            working.setText("nginx -t && systemctl reload nginx");
            working.setRunning(true);
            hands.reload(trouble -> report(trouble, "valid, and nginx reloaded it without dropping a request"));
        });
        // Every one of these sends nginx something. On a machine without it they would fail with a message
        // about a command that is not there, which explains nothing to the person reading it.
        nginxActions.addAll(List.of(add, test, reload));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        tally.getStyleClass().add("home-muted");
        tally.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(18, back, add, reread, test, reload, spacer, tally);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("home-nav", "redis-actions");
        return row;
    }

    /**
     * The sites on the left, the route the whole height of the middle, because it is drawn in layers top to
     * bottom and needs the height, and on the right the chosen site with its traffic under it.
     */
    private HBox buildBody() {
        ScrollPane sitesScroll = scroll(siteList);
        Tile sitesTile = new Tile("sites", sitesScroll).accent("tile-cyan");
        sitesTile.setMinWidth(190);
        sitesTile.setPrefWidth(220);
        sitesTile.setMaxWidth(250);

        ScrollPane routeScroll = scroll(route);
        routeScroll.setFitToHeight(true);
        routeEmpty.getStyleClass().add("home-muted");
        routeEmpty.setWrapText(true);
        routeEmpty.setMaxWidth(440);
        StackPane routeArea = new StackPane(routeScroll, routeEmpty);
        routeTile = new Tile("route", routeArea).accent("tile-amber");
        routeTile.setMinWidth(420);
        HBox.setHgrow(routeTile, Priority.ALWAYS);

        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        status.setVisible(false);
        status.setManaged(false);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox siteColumn = new VBox(16, facts, pool, actions, working, status);
        siteColumn.setMinWidth(0);
        siteTile = new Tile("site", scroll(siteColumn));
        siteTile.setMinHeight(200);
        siteTile.setMaxHeight(430);

        file.setEditable(false);
        file.setWrapText(false);
        file.getStyleClass().add("redis-diagnostic-output");
        belowSource.getStyleClass().add("home-muted");
        belowSource.setMinWidth(0);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox tabs = new HBox(16, requestsTab, fileTab, gap, belowSource);
        tabs.setAlignment(Pos.CENTER_LEFT);
        logScroll = scroll(logLines);
        VBox.setVgrow(logScroll, Priority.ALWAYS);
        VBox.setVgrow(file, Priority.ALWAYS);
        VBox belowBox = new VBox(8, tabs, belowPulse, logScroll, file);
        Tile belowTile = new Tile("traffic", belowBox).accent("tile-green");
        belowTile.setMinHeight(180);
        VBox.setVgrow(belowTile, Priority.ALWAYS);

        VBox right = new VBox(18, siteTile, belowTile);
        right.setMinWidth(300);
        right.setPrefWidth(340);
        right.setMaxWidth(380);

        HBox body = new HBox(18, sitesTile, routeTile, right);
        body.getStyleClass().add("tiles");
        return body;
    }

    // Drawing

    private void draw() {
        drawTally();
        drawSites();
        drawSelected();
    }

    private void drawTally() {
        int all = sites.available().size();
        int on = sites.enabled().size();
        if (all == 0 && on == 0) {
            tally.setText(WebServer.hasNginx(servers) || servers.isEmpty() ? "no site read" : "no nginx");
            return;
        }
        List<String> parts = new ArrayList<>();
        parts.add(all + (all == 1 ? " site" : " sites"));
        parts.add(on + " answering");
        if (!sites.disabled().isEmpty()) {
            parts.add(sites.disabled().size() + " off");
        }
        if (!sites.dangling().isEmpty()) {
            parts.add(sites.dangling().size() + " broken");
        }
        tally.setText(String.join("  ·  ", parts));
    }

    /** The sites switched on first, then the ones that are off. */
    private List<String> order() {
        List<String> order = new ArrayList<>(sites.enabled());
        for (String site : sites.available()) {
            if (!order.contains(site)) {
                order.add(site);
            }
        }
        return order;
    }

    private void drawSites() {
        siteList.getChildren().clear();
        List<String> order = order();
        if (order.isEmpty()) {
            if (WebServer.hasNginx(servers) || servers.isEmpty()) {
                siteList.getChildren().add(muted("no site file was read"));
            } else {
                for (WebServer server : servers) {
                    siteList.getChildren().add(otherServer(server));
                }
            }
            return;
        }
        for (String site : order) {
            siteList.getChildren().add(siteRow(site));
        }
    }

    private HBox siteRow(String site) {
        boolean on = sites.isEnabled(site);
        boolean broken = sites.dangling().contains(site);
        Label dot = new Label("●");
        dot.getStyleClass().add("port-dot");
        dot.setStyle("-fx-text-fill: " + (broken ? "#ff2d55" : on ? "#35e0a1" : "#5f5f72") + ";");
        Label name = new Label(firstName(site));
        name.getStyleClass().add(on ? "port-owner" : "port-what");
        name.setMinWidth(0);
        VBox words = new VBox(1, name);
        if (!firstName(site).equals(site)) {
            Label from = new Label(site);
            from.getStyleClass().add("port-quiet");
            words.getChildren().add(from);
        }
        if (broken) {
            Label why = new Label("its file is gone");
            why.getStyleClass().add("port-alert");
            words.getChildren().add(why);
        }
        HBox row = new HBox(10, dot, words);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("port-row");
        if (site.equals(selected)) {
            row.getStyleClass().add("port-row-chosen");
        }
        row.setOnMouseClicked(event -> {
            selected = site;
            status.setVisible(false);
            status.setManaged(false);
            draw();
            loadBelow();
        });
        return row;
    }

    /** The first domain a file answers to, or the file name when it names none. */
    private String firstName(String site) {
        for (String name : sites.namesOf(site)) {
            if (!name.equals("_") && !name.isBlank()) {
                return name;
            }
        }
        for (Vhost vhost : vhostsOf(site)) {
            String title = vhost.title();
            if (!title.startsWith("default")) {
                return title;
            }
        }
        return site;
    }

    /** The server blocks that came out of this file, whether Nginx read it or Kubo read it off the disk. */
    private List<Vhost> vhostsOf(String site) {
        List<Vhost> found = new ArrayList<>();
        for (Vhost vhost : vhosts) {
            if (vhost.file().endsWith("/" + site)) {
                found.add(vhost);
            }
        }
        return found;
    }

    private void drawSelected() {
        facts.getChildren().clear();
        pool.getChildren().clear();
        actions.getChildren().clear();
        if (selected.isEmpty()) {
            route.clear();
            routeTile.setTag("");
            routeEmpty.setText(order().isEmpty() && !servers.isEmpty() && !WebServer.hasNginx(servers)
                    ? "nginx is not on this machine, so there is no route to draw"
                    : "no site was read. reading /etc/nginx may need root: turn root on in the bar");
            routeEmpty.setVisible(true);
            return;
        }
        routeEmpty.setVisible(false);
        List<Vhost> blocks = vhostsOf(selected);
        boolean on = sites.isEnabled(selected);
        routeTile.setTag(firstName(selected));
        certificate = certificateWords(blocks);
        drawRoute(blocks, on);
        drawFacts(blocks, on);
        drawPool(blocks);
        drawActions(on);
        paintTabs();
    }

    // The route

    /**
     * The chosen site as layers: its names, Nginx, every path, where each path is sent, what holds that port,
     * and this machine. Each path is followed as far as Kubo can follow it.
     */
    private void drawRoute(List<Vhost> blocks, boolean on) {
        SiteRoute.Builder graph = new SiteRoute.Builder();
        boolean broken = sites.dangling().contains(selected);
        String front = graph.add(nginxNode(blocks, on, broken));
        for (String domain : domainsOf(blocks)) {
            graph.link(graph.add(new SiteRoute.Node("domain " + domain, SiteRoute.Layer.DOMAINS,
                    SiteRoute.Kind.DOMAIN, domain, "", SiteRoute.State.WORKS)), front);
        }
        int index = 0;
        for (Vhost block : blocks) {
            if (block.forcesHttps()) {
                continue;
            }
            if (block.routes().isEmpty()) {
                String key = "path " + index++;
                addPath(graph, front, key, "everything", false, wholeBlock(graph, key, block));
                continue;
            }
            for (Rule rule : block.routes()) {
                String key = "path " + index++;
                addPath(graph, front, key, rule.path(), rule.websocket(), follow(graph, key, rule, block));
            }
        }
        String note = blocks.isEmpty()
                ? "no server block was read from this file. with root on, kubo reads it the way nginx does"
                : "every request is sent on to the secure address";
        route.show(graph.build(on, broken, note));
    }

    private List<String> domainsOf(List<Vhost> blocks) {
        List<String> domains = new ArrayList<>();
        for (String name : sites.namesOf(selected)) {
            add(domains, name);
        }
        for (Vhost block : blocks) {
            block.serverNames().forEach(name -> add(domains, name));
        }
        if (domains.isEmpty()) {
            domains.add(selected);
        }
        return domains;
    }

    private static void add(List<String> names, String name) {
        if (!name.isBlank() && !name.equals("_") && !names.contains(name)) {
            names.add(name);
        }
    }

    /** Nginx as this site sees it: the ports, the way plain http is sent on, the certificate. */
    private SiteRoute.Node nginxNode(List<Vhost> blocks, boolean on, boolean broken) {
        List<Integer> ports = new ArrayList<>();
        for (Vhost block : blocks) {
            for (int port : block.listenPorts()) {
                if (!ports.contains(port)) {
                    ports.add(port);
                }
            }
        }
        boolean https = blocks.stream().anyMatch(Vhost::ssl);
        boolean redirects = blocks.stream().anyMatch(Vhost::forcesHttps);
        String listens;
        if (redirects && ports.contains(80) && ports.contains(443)) {
            List<String> others = ports.stream().filter(port -> port != 80 && port != 443)
                    .map(port -> ":" + port).toList();
            listens = ":80 \u2192 :443" + (others.isEmpty() ? "" : "  " + String.join("  ", others));
        } else {
            listens = String.join("  ", ports.stream().map(port -> ":" + port).toList());
        }
        String name = broken ? "nginx \u00b7 broken" : on ? "nginx" : "nginx \u00b7 off";
        SiteRoute.State state = broken ? SiteRoute.State.BROKEN : on ? SiteRoute.State.WORKS
                : SiteRoute.State.UNKNOWN;
        SiteRoute.Node node = new SiteRoute.Node("nginx", SiteRoute.Layer.NGINX, SiteRoute.Kind.NGINX, name,
                listens, state);
        if (https) {
            return node.badge(certificate[0].isEmpty() ? "https" : certificate[0],
                    SiteRoute.State.valueOf(certificate[1]));
        }
        return blocks.isEmpty() ? node : node.badge("http only", SiteRoute.State.UNKNOWN);
    }

    /** One path, coloured by where it ends: a path is as good as what answers it. */
    private static void addPath(SiteRoute.Builder graph, String front, String key, String path, boolean websocket,
                                SiteRoute.State end) {
        SiteRoute.Node node = new SiteRoute.Node(key, SiteRoute.Layer.PATHS, SiteRoute.Kind.PATH, path, "", end);
        graph.add(websocket ? node.badge("ws", SiteRoute.State.FILES) : node);
        graph.link(front, key);
    }

    /** A block with no location of its own: one path, everything, and what the block does with it. */
    private SiteRoute.State wholeBlock(SiteRoute.Builder graph, String from, Vhost block) {
        if (block.forwards()) {
            Vhost.ProxyTarget target = block.proxyTargets().get(0);
            if (target.isLocalPort()) {
                return port(graph, from, target.port());
            }
            Upstream upstream = poolOf(target.raw());
            return upstream != null ? pool(graph, from, upstream) : elsewhere(graph, from, target.raw());
        }
        if (!block.root().isEmpty()) {
            return folder(graph, from, block.root());
        }
        return end(graph, from, SiteRoute.Kind.ANSWER, "nothing named", "the block sends it nowhere");
    }

    /** One location, followed as far as Kubo can follow it. */
    private SiteRoute.State follow(SiteRoute.Builder graph, String from, Rule rule, Vhost block) {
        return switch (rule.kind()) {
            case PROXY -> {
                int port = rule.localPort();
                if (port > 0) {
                    yield port(graph, from, port);
                }
                Upstream upstream = poolOf(rule.target());
                yield upstream != null ? pool(graph, from, upstream) : elsewhere(graph, from, rule.target());
            }
            case FILES -> folder(graph, from, rule.target());
            case REDIRECT -> end(graph, from, SiteRoute.Kind.REDIRECT, rule.target(), "redirect");
            case ANSWER -> end(graph, from, SiteRoute.Kind.ANSWER, "answers " + rule.target(), "nginx itself");
            case UNSET -> block.root().isEmpty()
                    ? end(graph, from, SiteRoute.Kind.ANSWER, "same as the block", "")
                    : folder(graph, from, block.root());
        };
    }

    /** A local port, and whatever holds it, or the fact that nothing does and every visitor gets a 502. */
    private SiteRoute.State port(SiteRoute.Builder graph, String from, int port) {
        String key = "port " + port;
        Endpoint found = listeningOn(port);
        if (found == null) {
            graph.add(new SiteRoute.Node(key, SiteRoute.Layer.TARGETS, SiteRoute.Kind.PORT, "port " + port,
                    "nothing listens \u00b7 502", SiteRoute.State.BROKEN));
            graph.link(from, key);
            return SiteRoute.State.BROKEN;
        }
        graph.add(new SiteRoute.Node(key, SiteRoute.Layer.TARGETS, SiteRoute.Kind.PORT, "port " + port, "",
                SiteRoute.State.WORKS).opens(() -> hands.showPort(port)));
        graph.link(from, key);
        graph.link(key, program(graph, found));
        return SiteRoute.State.WORKS;
    }

    /**
     * A pool, each server in it followed to what answers there. A server marked down is drawn hollow; one on
     * this machine that nothing listens on is drawn red, because Nginx will try it and fail.
     */
    private SiteRoute.State pool(SiteRoute.Builder graph, String from, Upstream upstream) {
        String key = "pool " + upstream.name();
        List<SiteRoute.State> members = new ArrayList<>();
        int active = 0;
        int answering = 0;
        for (int i = 0; i < upstream.servers().size(); i++) {
            Upstream.Member member = upstream.servers().get(i);
            if (member.down()) {
                members.add(SiteRoute.State.UNKNOWN);
                continue;
            }
            active++;
            int port = localPortOf(member.address());
            Endpoint found = port > 0 ? listeningOn(port) : null;
            if (port > 0 && found == null) {
                members.add(SiteRoute.State.BROKEN);
                continue;
            }
            members.add(SiteRoute.State.WORKS);
            answering++;
            if (found != null) {
                graph.link(key, program(graph, found), i);
            }
        }
        SiteRoute.State state = answering == 0 ? SiteRoute.State.BROKEN
                : answering < active ? SiteRoute.State.WARNS : SiteRoute.State.WORKS;
        graph.add(new SiteRoute.Node(key, SiteRoute.Layer.TARGETS, SiteRoute.Kind.POOL, upstream.name(),
                answering + " of " + upstream.servers().size() + " up", state).members(members));
        graph.link(from, key);
        return state;
    }

    /** What holds a port: the PM2 app, the container, the daemon, or a program only root can name. */
    private String program(SiteRoute.Builder graph, Endpoint endpoint) {
        ServiceOwner owner = endpoint.owner();
        int port = endpoint.socket().port();
        boolean named = owner.kind() != ServiceOwner.Kind.UNKNOWN;
        String key = named ? "program " + owner.kind() + " " + owner.label() + " " + owner.pid()
                : "program on " + port;
        String detail = switch (owner.kind()) {
            case PM2 -> "pm2";
            case DOCKER -> "docker";
            case SYSTEM -> owner.pid() > 0 ? "pid " + owner.pid() : "system";
            case UNKNOWN -> "root can name it";
        };
        graph.add(new SiteRoute.Node(key, SiteRoute.Layer.PROGRAMS, SiteRoute.Kind.PROGRAM,
                named ? owner.label() : "a program", detail,
                named ? SiteRoute.State.WORKS : SiteRoute.State.UNKNOWN).opens(() -> hands.showPort(port)));
        graph.link(key, machine(graph));
        return key;
    }

    private String machine(SiteRoute.Builder graph) {
        return graph.add(new SiteRoute.Node(MACHINE, SiteRoute.Layer.MACHINE, SiteRoute.Kind.MACHINE, machineName,
                "", SiteRoute.State.WORKS));
    }

    private SiteRoute.State folder(SiteRoute.Builder graph, String from, String path) {
        String key = graph.add(new SiteRoute.Node("folder " + path, SiteRoute.Layer.TARGETS, SiteRoute.Kind.FOLDER,
                path, "files", SiteRoute.State.FILES).opens(() -> hands.browse(path)));
        graph.link(from, key);
        graph.link(key, machine(graph));
        return SiteRoute.State.FILES;
    }

    private static SiteRoute.State elsewhere(SiteRoute.Builder graph, String from, String raw) {
        String key = graph.add(new SiteRoute.Node("remote " + raw, SiteRoute.Layer.TARGETS, SiteRoute.Kind.REMOTE,
                raw.replaceFirst("^[a-z]+://", ""), "another machine", SiteRoute.State.UNKNOWN));
        graph.link(from, key);
        return SiteRoute.State.UNKNOWN;
    }

    /** Where the way stops inside Nginx: a redirect, an answer written in the file. */
    private static SiteRoute.State end(SiteRoute.Builder graph, String from, SiteRoute.Kind kind, String name,
                                       String detail) {
        String key = graph.add(new SiteRoute.Node(kind + " " + name, SiteRoute.Layer.TARGETS, kind, name, detail,
                SiteRoute.State.UNKNOWN));
        graph.link(from, key);
        return SiteRoute.State.UNKNOWN;
    }

    private Endpoint listeningOn(int port) {
        return listening.stream().filter(endpoint -> endpoint.socket().port() == port).findFirst().orElse(null);
    }

    /** The pool a proxy_pass names, or null when it names an address. */
    private Upstream poolOf(String target) {
        String host = com.kubo.network.NetworkCommands.target(target).host();
        for (Upstream upstream : upstreams) {
            if (upstream.name().equals(host)) {
                return upstream;
            }
        }
        return null;
    }

    /** The port a pool member is on when it is on this machine, -1 otherwise. */
    private static int localPortOf(String address) {
        Vhost.ProxyTarget target = com.kubo.network.NetworkCommands.target("http://" + address);
        return target.isLocalPort() ? target.port() : -1;
    }

    /** Whether a pool member takes requests: not marked down, and something listens there when it is local. */
    private boolean answers(Upstream.Member member) {
        if (member.down()) {
            return false;
        }
        int port = localPortOf(member.address());
        return port < 0 || listeningOn(port) != null;
    }

    /** The certificate in a few words, and how it holds. */
    private String[] certificateWords(List<Vhost> blocks) {
        String wanted = blocks.stream().map(Vhost::certificate).filter(name -> !name.isEmpty())
                .findFirst().orElse("");
        if (wanted.isEmpty()) {
            return new String[]{"", "UNKNOWN"};
        }
        Certificate certificate = certificates.stream().filter(one -> one.file().equals(wanted))
                .findFirst().orElse(null);
        if (certificate == null) {
            return new String[]{"https", "UNKNOWN"};
        }
        if (!certificate.readable()) {
            return new String[]{"certificate unreadable", "WARNS"};
        }
        Instant now = Instant.now();
        if (certificate.expired(now)) {
            return new String[]{"cert expired " + -certificate.daysLeft(now) + "d ago", "BROKEN"};
        }
        long days = certificate.daysLeft(now);
        return new String[]{"cert " + days + "d left", switch (certificate.urgency(now)) {
            case URGENT -> "BROKEN";
            case SOON -> "WARNS";
            default -> "WORKS";
        }};
    }

    // The right-hand frame

    private void drawFacts(List<Vhost> blocks, boolean on) {
        boolean broken = sites.dangling().contains(selected);
        facts.getChildren().add(fact("file", "sites-available/" + selected, "fetch-value"));
        facts.getChildren().add(fact("state", broken ? "link to a missing file" : on ? "answering" : "switched off",
                broken ? "port-alert" : on ? "journal-ok" : "port-what"));
        List<String> names = new ArrayList<>(sites.namesOf(selected));
        for (Vhost block : blocks) {
            block.serverNames().forEach(name -> add(names, name));
        }
        facts.getChildren().add(fact("answers", names.isEmpty() ? "—" : String.join("  ", names),
                "fetch-value"));
        List<String> ports = new ArrayList<>();
        for (Vhost block : blocks) {
            for (int port : block.listenPorts()) {
                if (!ports.contains(String.valueOf(port))) {
                    ports.add(String.valueOf(port));
                }
            }
        }
        if (!ports.isEmpty()) {
            facts.getChildren().add(fact("listens", String.join("  ", ports), "fetch-value"));
        }
        if (blocks.stream().anyMatch(Vhost::ssl)) {
            String style = switch (certificate[1]) {
                case "BROKEN" -> "port-alert";
                case "WARNS" -> "fetch-note";
                case "WORKS" -> "journal-ok";
                default -> "fetch-value";
            };
            facts.getChildren().add(fact("https", certificate[0].replace("cert ", "certificate, "), style));
        } else if (!blocks.isEmpty()) {
            facts.getChildren().add(fact("https", "no, plain http", "port-what"));
        }
        String log = accessLogOf(selected);
        facts.getChildren().add(fact("log", log, "port-quiet"));
    }

    /** The pools this site forwards to, and every server in each. */
    private void drawPool(List<Vhost> blocks) {
        List<Upstream> used = new ArrayList<>();
        for (Vhost block : blocks) {
            for (Rule rule : block.routes()) {
                Upstream upstream = rule.kind() == Rule.Kind.PROXY ? poolOf(rule.target()) : null;
                if (upstream != null && !used.contains(upstream)) {
                    used.add(upstream);
                }
            }
        }
        for (Upstream upstream : used) {
            long up = upstream.servers().stream().filter(this::answers).count();
            pool.getChildren().add(fact("pool", upstream.name() + "  ·  " + up + " of "
                    + upstream.servers().size() + " up", "fetch-value"));
            for (Upstream.Member member : upstream.servers()) {
                Label address = new Label(member.address());
                address.getStyleClass().add("port-what");
                address.setMinWidth(0);
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                Label weight = new Label(member.weight() == 1 ? "" : "×" + member.weight());
                weight.getStyleClass().add("port-quiet");
                boolean silent = !member.down() && !answers(member);
                Label state = new Label(silent ? "nothing listens" : member.state().toLowerCase(Locale.ROOT));
                state.getStyleClass().add(silent ? "port-alert" : "port-quiet");
                HBox row = new HBox(10, address, spacer, weight, state);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(0, 0, 0, 72));
                pool.getChildren().add(row);
            }
        }
    }

    private void drawActions(boolean on) {
        if (on) {
            Button off = bar("switch off", event -> {
                working.setText("unlinking " + selected + " and reloading");
                working.setRunning(true);
                hands.disable(selected, trouble -> report(trouble, selected + " is off, nginx reloaded"));
            });
            off.getStyleClass().add("port-danger");
            actions.getChildren().add(off);
        } else {
            actions.getChildren().add(bar("switch on", event -> {
                working.setText("linking " + selected + ", testing, reloading");
                working.setRunning(true);
                hands.enable(selected, trouble -> report(trouble, selected + " is on, nginx reloaded"));
            }));
        }
        actions.getChildren().add(bar("read the file", event -> {
            below = Below.FILE;
            paintTabs();
            loadBelow();
        }));
    }

    // The frame at the bottom

    private void paintTabs() {
        for (Button tab : List.of(requestsTab, fileTab)) {
            tab.getStyleClass().remove("tab-on");
        }
        (below == Below.REQUESTS ? requestsTab : fileTab).getStyleClass().add("tab-on");
        boolean showingLog = below == Below.REQUESTS;
        logScroll.setVisible(showingLog);
        logScroll.setManaged(showingLog);
        file.setVisible(!showingLog);
        file.setManaged(!showingLog);
    }

    private Button tab(String text, Below which) {
        Button button = new Button(text);
        button.getStyleClass().add("ghost-button");
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(event -> {
            below = which;
            paintTabs();
            loadBelow();
        });
        return button;
    }

    /** Asks for whichever of the two the reader is looking at, and only that one. */
    private void loadBelow() {
        if (selected.isEmpty()) {
            logLines.getChildren().clear();
            file.setText("");
            belowSource.setText("");
            return;
        }
        belowPulse.setText(below == Below.FILE ? "reading the file" : "reading the log");
        belowPulse.setRunning(true);
        String asked = selected;
        if (below == Below.FILE) {
            String path = "/etc/nginx/sites-available/" + selected;
            belowSource.setText(path);
            file.setText("");
            hands.readFile(path, text -> {
                if (asked.equals(selected)) {
                    belowPulse.setRunning(false);
                    file.setText(text);
                }
            });
            return;
        }
        String log = accessLogOf(selected);
        belowSource.setText(log);
        logLines.getChildren().clear();
        hands.readLog(log, text -> {
            if (asked.equals(selected)) {
                belowPulse.setRunning(false);
                showLog(text);
            }
        });
    }

    /** The log this site writes to, or Nginx's own when the file names none. */
    private String accessLogOf(String site) {
        for (Vhost block : vhostsOf(site)) {
            if (!block.accessLog().isEmpty()) {
                return block.accessLog();
            }
        }
        return com.kubo.network.NetworkCommands.DEFAULT_ACCESS_LOG;
    }

    private void showLog(String text) {
        logLines.getChildren().clear();
        if (text == null || text.isBlank()) {
            logLines.getChildren().add(muted("nothing yet, or the log needs root"));
            return;
        }
        String[] raw = text.split("\\R");
        int from = Math.max(0, raw.length - 80);
        for (int i = raw.length - 1; i >= from; i--) {
            if (raw[i].isBlank()) {
                continue;
            }
            AccessLine line = AccessLine.of(raw[i]);
            Label row = new Label(line.sentence());
            row.getStyleClass().addAll("install-line", switch (line.level()) {
                case OK -> "access-ok";
                case MOVED -> "access-moved";
                case REFUSED -> "access-refused";
                case BROKEN -> "access-broken";
                case UNKNOWN -> "access-unread";
            });
            row.setMinWidth(0);
            row.setTextOverrun(javafx.scene.control.OverrunStyle.CENTER_ELLIPSIS);
            logLines.getChildren().add(row);
        }
        if (logLines.getChildren().isEmpty()) {
            logLines.getChildren().add(muted("the log is there and empty"));
        }
    }

    // Small pieces

    /** One fact, the way the machine block reads one: a key in colour, its value beside it. */
    private static HBox fact(String key, String value, String style) {
        Label name = new Label(key);
        name.getStyleClass().add("fetch-key");
        name.setMinWidth(62);
        Label text = new Label(value);
        text.getStyleClass().add(style);
        text.setWrapText(true);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(10, name, text);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private static Button bar(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button button = new Button(text);
        button.getStyleClass().add("ghost-button");
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(action);
        return button;
    }

    private static ScrollPane scroll(javafx.scene.Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("track-scroll");
        return scroll;
    }

    /**
     * One of the others: what it is, where its configuration lives, and the command that checks it.
     */
    private static VBox otherServer(WebServer server) {
        Label name = new Label(server.label().toLowerCase(Locale.ROOT));
        name.getStyleClass().add("port-owner");
        name.setMinWidth(Region.USE_PREF_SIZE);
        Label state = new Label(server.running() ? "running" : "installed, not running");
        state.getStyleClass().add(server.running() ? "journal-ok" : "port-what");
        state.setWrapText(true);
        state.setMinWidth(0);
        HBox head = new HBox(10, name, state);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(3, head, muted(server.kind().configHome()), muted("$ " + server.kind().check()));
        box.setPadding(new Insets(6, 8, 8, 6));
        return box;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("home-muted");
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }
}
