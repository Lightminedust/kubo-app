package com.kubo.ui.network;

import com.kubo.model.network.AccessLine;
import com.kubo.model.network.Certificate;
import com.kubo.model.network.NginxSites;
import com.kubo.model.network.Rule;
import com.kubo.model.network.Upstream;
import com.kubo.model.network.Vhost;
import com.kubo.ui.Pulse;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything Nginx is doing on this server, as a screen of its own.
 *
 * <p>It used to be a card floating over the map, listing file names with a switch beside each. A file name is an
 * administrator's filing choice; what a visitor types is the domain, what a domain does is a handful of rules,
 * and none of that fits in a card. So clicking NGINX leaves the map behind entirely and this takes the window.
 *
 * <p>Three columns, and they answer three questions in order. Which sites are there. What the selected one does
 * with each path somebody asks for. What is true about it, and what can be done to it.
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

    /** What the bottom of the middle column is showing. */
    private enum Below { LOG, FILE }

    private final Hands hands;

    // Left
    private final VBox siteList = new VBox(1);
    // Middle
    private final Label domain = new Label();
    private final Label subtitle = new Label();
    private final VBox rules = new VBox(1);
    private final VBox pool = new VBox(8);
    private final VBox belowBox = new VBox(8);
    private final TextArea file = new TextArea();
    private final VBox logLines = new VBox(1);
    private final Pulse belowPulse = new Pulse();
    private final HBox belowTabs = new HBox(6);
    // Right
    private final VBox facts = new VBox(10);
    private final VBox actions = new VBox(8);
    private final Label status = new Label();
    private final Pulse working = new Pulse();
    // Head
    private final Label tally = new Label();

    private NginxSites sites = NginxSites.none();
    private List<Vhost> vhosts = List.of();
    private List<Upstream> upstreams = List.of();
    private List<Certificate> certificates = List.of();
    private List<com.kubo.model.network.Endpoint> listening = List.of();
    private final com.kubo.ui.Chain chain = new com.kubo.ui.Chain();
    private String selected = "";
    private Below below = Below.LOG;

    NginxScreen(Hands hands) {
        this.hands = hands;
        getStyleClass().add("scope-view");
        setTop(buildHead());
        setLeft(buildSites());
        setCenter(buildMiddle());
        setRight(buildFacts());
        draw();
    }

    /**
     * Fills the screen. The sites listing is what the left column is made of, because it is the only source
     * that knows about a site that exists and is switched off.
     */
    void show(NginxSites sites, List<Vhost> vhosts, List<Upstream> upstreams, List<Certificate> certificates,
              List<com.kubo.model.network.Endpoint> listening) {
        this.listening = listening == null ? List.of() : List.copyOf(listening);
        this.sites = sites == null ? NginxSites.none() : sites;
        this.vhosts = vhosts == null ? List.of() : List.copyOf(vhosts);
        this.upstreams = upstreams == null ? List.of() : List.copyOf(upstreams);
        this.certificates = certificates == null ? List.of() : List.copyOf(certificates);
        if (selected.isEmpty() || !this.sites.available().contains(selected)) {
            selected = this.sites.enabled().stream().findFirst()
                    .orElse(this.sites.available().stream().findFirst().orElse(""));
        }
        draw();
        if (!selected.isEmpty()) {
            loadBelow();
        }
    }

    /** The pools this server names, so the form can offer them as a destination. */
    List<Upstream> pools() {
        return upstreams;
    }

    /** Says a command came back: null for a success, otherwise what the server said. */
    void report(String trouble, String done) {
        working.setRunning(false);
        status.getStyleClass().setAll("label", trouble == null ? "card-success" : "card-problem");
        status.setText(trouble == null ? done : trouble);
        status.setVisible(true);
        status.setManaged(true);
    }

    // Building

    private VBox buildHead() {
        Label mark = label("NGINX", "sheet-title");
        tally.getStyleClass().add("lede");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button back = rail("← BACK TO THE MAP", event -> hands.back());
        Button reread = rail("↻ RE-READ", event -> hands.reread());
        Button test = rail("TEST THE CONFIGURATION", event -> {
            working.setRunning(true);
            hands.test(trouble -> report(trouble, "The configuration is valid. Nothing was changed."));
        });
        Button reload = rail("TEST AND RELOAD", event -> {
            working.setRunning(true);
            hands.reload(trouble -> report(trouble, "The configuration is valid and Nginx reloaded it. "
                    + "No request was dropped."));
        });

        Button add = rail("+ NEW SITE", event -> hands.newSite());
        HBox row = new HBox(8, back, separator(), add, separator(), reread, test, reload, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0, 0, 10, 0));

        VBox head = new VBox(4, mark, tally, row, working);
        head.setPadding(new Insets(16, 34, 0, 26));
        return head;
    }

    private VBox buildSites() {
        VBox column = new VBox(10, label("SITES", "series"),
                note("Every file in sites-available. A site that is switched off is invisible to Nginx, which "
                        + "is exactly the one somebody is looking for when a domain stopped answering."),
                siteList);
        column.setPadding(new Insets(18, 20, 20, 26));
        column.setPrefWidth(280);
        column.setMinWidth(280);
        column.getStyleClass().add("network-machine");
        return column;
    }

    private BorderPane buildMiddle() {
        domain.getStyleClass().add("host-key-host");
        domain.setWrapText(true);
        subtitle.getStyleClass().add("mono-muted");

        file.setEditable(false);
        file.setWrapText(false);
        file.getStyleClass().add("redis-diagnostic-output");
        file.setPrefRowCount(12);

        belowTabs.setAlignment(Pos.CENTER_LEFT);
        ScrollPane belowScroll = new ScrollPane(new VBox(2, logLines, file));
        belowScroll.setFitToWidth(true);
        belowScroll.getStyleClass().add("plain-scroll");
        belowScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        belowScroll.setPrefViewportHeight(150);
        belowScroll.setMinHeight(150);
        belowBox.getChildren().addAll(belowTabs, belowPulse, belowScroll);
        belowBox.setPadding(new Insets(14, 0, 0, 0));

        VBox body = new VBox(18,
                new VBox(2, domain, subtitle),
                new VBox(4, label("HOW A REQUEST GETS THERE", "series"), chain),
                new VBox(8, label("WHAT EACH PATH DOES", "series"), rules));
        body.setPadding(new Insets(18, 26, 12, 26));

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        // The log keeps its own room at the bottom rather than sitting under everything else and being the
        // first thing to fall off the screen. It is the part somebody watches.
        BorderPane middle = new BorderPane(scroll);
        belowBox.setPadding(new Insets(10, 26, 16, 26));
        middle.setBottom(belowBox);
        middle.getStyleClass().add("network-flow");
        return middle;
    }

    private VBox buildFacts() {
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        status.setVisible(false);
        status.setManaged(false);

        VBox column = new VBox(16, label("THIS SITE", "series"), facts, pool,
                label("WHAT CAN BE DONE", "series"), actions, status);
        column.setPadding(new Insets(18, 26, 20, 22));
        column.setPrefWidth(340);
        column.setMinWidth(340);
        column.getStyleClass().add("network-inspector");

        ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox holder = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        holder.setPrefWidth(356);
        holder.setMinWidth(356);
        return holder;
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
            tally.setText("Nothing was read. Nginx may not be installed, or its files need root.");
            return;
        }
        int off = sites.disabled().size();
        StringBuilder text = new StringBuilder();
        text.append(all).append(all == 1 ? " site" : " sites");
        text.append("  ·  ").append(on).append(" answering");
        if (off > 0) {
            text.append("  ·  ").append(off).append(" switched off");
        }
        if (!sites.dangling().isEmpty()) {
            text.append("  ·  ").append(sites.dangling().size()).append(" linked to a file that is gone");
        }
        tally.setText(text.toString());
    }

    private void drawSites() {
        siteList.getChildren().clear();
        List<String> order = new ArrayList<>(sites.enabled());
        for (String site : sites.available()) {
            if (!order.contains(site)) {
                order.add(site);
            }
        }
        if (order.isEmpty()) {
            siteList.getChildren().add(note("No site file was found."));
            return;
        }
        for (String site : order) {
            siteList.getChildren().add(siteRow(site));
        }
    }

    private VBox siteRow(String site) {
        boolean on = sites.isEnabled(site);
        boolean broken = sites.dangling().contains(site);

        Label name = label(firstName(site), "mono");
        name.setWrapText(true);
        Label state = label(broken ? "the file it points at is gone" : on ? "answering" : "switched off",
                broken ? "card-problem" : on ? "card-success" : "card-note");
        Label from = label(site, "tool-caveat");

        VBox row = new VBox(1, name, state);
        if (!firstName(site).equals(site)) {
            row.getChildren().add(from);
        }
        row.setPadding(new Insets(9, 12, 10, 12));
        row.getStyleClass().add(site.equals(selected) ? "site-row-open" : on ? "tool-row-here" : "tool-row");
        row.setOnMouseClicked(event -> {
            selected = site;
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

    /** The server blocks that came out of this file. */
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
        rules.getChildren().clear();
        pool.getChildren().clear();
        facts.getChildren().clear();
        actions.getChildren().clear();

        if (selected.isEmpty()) {
            domain.setText("No site selected");
            subtitle.setText("");
            rules.getChildren().add(note("Pick a site on the left."));
            belowBox.setVisible(false);
            belowBox.setManaged(false);
            return;
        }
        belowBox.setVisible(true);
        belowBox.setManaged(true);

        List<Vhost> blocks = vhostsOf(selected);
        boolean on = sites.isEnabled(selected);
        domain.setText(firstName(selected));
        subtitle.setText(subtitleFor(blocks, on));

        drawChain(blocks, on);
        drawRules(blocks, on);
        drawPool(blocks);
        drawFacts(blocks, on);
        drawActions(on);
        drawBelowTabs();
    }

    /**
     * The way in, one link at a time, for the rule that answers most of the site: the domain, the file, the
     * certificate, the rule, and what is behind it. The first link that does not hold is red, and so is the
     * spine below it, because that is where a request stops.
     */
    private void drawChain(List<Vhost> blocks, boolean on) {
        List<com.kubo.ui.Chain.Link> links = new ArrayList<>();
        links.add(new com.kubo.ui.Chain.Link("THE DOMAIN", firstName(selected),
                "What a visitor types. Kubo does not check where it points.", com.kubo.ui.Chain.State.UNKNOWN,
                null));

        boolean broken = sites.dangling().contains(selected);
        links.add(new com.kubo.ui.Chain.Link("NGINX", "sites-available/" + selected,
                // A link that holds says nothing more: the right-hand column already says it, and a chain where
                // every link talks is a chain nobody reads to the red one.
                broken ? "Linked to a file that is gone. Nginx will refuse to start with this link in place."
                        : on ? "" : "Switched off, so Nginx never reads it.",
                broken || !on ? com.kubo.ui.Chain.State.BROKEN : com.kubo.ui.Chain.State.WORKS, null));
        if (broken || !on || blocks.isEmpty()) {
            chain.setLinks(links);
            return;
        }

        Vhost serving = blocks.stream().filter(block -> !block.forcesHttps()).findFirst().orElse(blocks.get(0));
        if (serving.ssl()) {
            links.add(certificateLink(serving.certificate()));
        }

        Rule rule = serving.routes().stream().filter(Rule::catchAll).findFirst()
                .orElse(serving.routes().isEmpty() ? null : serving.routes().get(0));
        if (rule == null) {
            links.add(new com.kubo.ui.Chain.Link("THE RULE", "everything", serving.purpose(),
                    com.kubo.ui.Chain.State.UNKNOWN, null));
            chain.setLinks(links);
            return;
        }
        links.add(new com.kubo.ui.Chain.Link("THE RULE", rule.path() + "  " + rule.sentence(), "",
                rule.kind() == Rule.Kind.UNSET ? com.kubo.ui.Chain.State.UNKNOWN : com.kubo.ui.Chain.State.WORKS,
                null));

        int port = rule.localPort();
        if (port > 0) {
            addPortLinks(links, port);
        } else if (rule.kind() == Rule.Kind.FILES) {
            String folder = rule.target();
            links.add(new com.kubo.ui.Chain.Link("THE FOLDER", folder, "Open it to see what Nginx will serve.",
                    com.kubo.ui.Chain.State.UNKNOWN, () -> hands.browse(folder)));
        } else if (rule.kind() == Rule.Kind.PROXY) {
            addPoolLink(links, rule.target());
        }
        chain.setLinks(links);
    }

    private com.kubo.ui.Chain.Link certificateLink(String file) {
        Certificate certificate = certificates.stream().filter(one -> one.file().equals(file))
                .findFirst().orElse(null);
        if (certificate == null || !certificate.readable()) {
            return new com.kubo.ui.Chain.Link("THE CERTIFICATE", file,
                    certificate == null ? "Not read yet." : "Could not be read: " + certificate.problem(),
                    com.kubo.ui.Chain.State.UNKNOWN, null);
        }
        Instant now = Instant.now();
        if (certificate.expired(now)) {
            return new com.kubo.ui.Chain.Link("THE CERTIFICATE", "expired " + -certificate.daysLeft(now)
                    + " days ago", "Browsers refuse the site until it is renewed.",
                    com.kubo.ui.Chain.State.BROKEN, null);
        }
        long days = certificate.daysLeft(now);
        String note = certificate.expiringSoon(now) ? "Renewal is late. It usually happens at 30 days."
                : certificate.caveat();
        return new com.kubo.ui.Chain.Link("THE CERTIFICATE", days + (days == 1 ? " day left" : " days left"),
                note, note.isEmpty() ? com.kubo.ui.Chain.State.WORKS : com.kubo.ui.Chain.State.WARNS, null);
    }

    /** The port a rule forwards to, and whatever is holding it, or the fact that nothing is. */
    private void addPortLinks(List<com.kubo.ui.Chain.Link> links, int port) {
        com.kubo.model.network.Endpoint found = listening.stream()
                .filter(endpoint -> endpoint.socket().port() == port).findFirst().orElse(null);
        if (found == null) {
            links.add(new com.kubo.ui.Chain.Link("THE PORT", String.valueOf(port),
                    "Nothing is listening on " + port + ". Nginx will answer 502 Bad Gateway to every visitor.",
                    com.kubo.ui.Chain.State.BROKEN, null));
            return;
        }
        links.add(new com.kubo.ui.Chain.Link("THE PORT", String.valueOf(port), "",
                com.kubo.ui.Chain.State.WORKS, () -> hands.showPort(port)));
        com.kubo.model.network.ServiceOwner owner = found.owner();
        boolean named = owner.kind() != com.kubo.model.network.ServiceOwner.Kind.UNKNOWN;
        links.add(new com.kubo.ui.Chain.Link("THE PROGRAM", owner.label(), owner.detail(),
                named ? com.kubo.ui.Chain.State.WORKS : com.kubo.ui.Chain.State.UNKNOWN,
                () -> hands.showPort(port)));
    }

    /** A rule forwarding to a name: the pool, and how many of its servers are taking requests. */
    private void addPoolLink(List<com.kubo.ui.Chain.Link> links, String target) {
        for (Upstream upstream : upstreams) {
            if (target.contains("//" + upstream.name())) {
                long working = upstream.working();
                links.add(new com.kubo.ui.Chain.Link("THE POOL", upstream.name(),
                        working + " of " + upstream.servers().size() + " taking requests, " + upstream.sharing()
                                + ".", working == 0 ? com.kubo.ui.Chain.State.BROKEN
                                : com.kubo.ui.Chain.State.WORKS, null));
                return;
            }
        }
        links.add(new com.kubo.ui.Chain.Link("WHERE IT GOES", target, "Not a port on this server.",
                com.kubo.ui.Chain.State.UNKNOWN, null));
    }

    private String subtitleFor(List<Vhost> blocks, boolean on) {
        List<String> parts = new ArrayList<>();
        parts.add(on ? "answering" : "switched off");
        boolean secure = blocks.stream().anyMatch(Vhost::ssl);
        parts.add(secure ? "https" : "http only");
        parts.add("file " + selected);
        return String.join("  ·  ", parts);
    }

    private void drawRules(List<Vhost> blocks, boolean on) {
        if (blocks.isEmpty()) {
            rules.getChildren().add(note(on
                    ? "Nginx did not report a server block from this file. Turn on ROOT and re-read: without it "
                            + "Kubo can only see the files it is allowed to open."
                    : "This site is switched off, so Nginx is not reading it. Its file is below."));
            return;
        }
        for (Vhost block : blocks) {
            if (block.forcesHttps()) {
                rules.getChildren().add(ruleRow("everything", "sends visitors to the secure address", false,
                        "card-note"));
                continue;
            }
            if (block.routes().isEmpty()) {
                rules.getChildren().add(ruleRow("everything", block.purpose(), false, "card-note"));
                continue;
            }
            for (Rule rule : block.routes()) {
                rules.getChildren().add(ruleRow(rule.path(), rule.sentence(), rule.websocket(),
                        rule.kind() == Rule.Kind.UNSET ? "card-note" : "mono-muted"));
            }
        }
    }

    private HBox ruleRow(String path, String what, boolean websocket, String style) {
        Label left = label(path, "mono");
        left.setMinWidth(150);
        left.setPrefWidth(150);
        left.setWrapText(true);

        Label right = label(what, style);
        right.setWrapText(true);
        HBox.setHgrow(right, Priority.ALWAYS);

        HBox row = new HBox(14, left, right);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(8, 12, 9, 12));
        row.getStyleClass().add("tool-row");
        if (websocket) {
            Label mark = label("WEBSOCKET", "net-chip");
            mark.getStyleClass().add("net-chip-docker");
            row.getChildren().add(mark);
        }
        return row;
    }

    /**
     * The pool behind this site, when one of its rules forwards to a name rather than to a port.
     */
    private void drawPool(List<Vhost> blocks) {
        List<Upstream> used = new ArrayList<>();
        for (Vhost block : blocks) {
            for (Rule rule : block.routes()) {
                for (Upstream upstream : upstreams) {
                    if (rule.target().contains("//" + upstream.name()) && !used.contains(upstream)) {
                        used.add(upstream);
                    }
                }
            }
        }
        if (used.isEmpty()) {
            return;
        }
        pool.getChildren().add(label("THE POOL IT FORWARDS TO", "series"));
        for (Upstream upstream : used) {
            Label name = label(upstream.name(), "mono");
            Label how = label(upstream.working() + " of " + upstream.servers().size()
                    + " taking requests  ·  " + upstream.sharing(), "card-note");
            how.setWrapText(true);
            how.setMinHeight(Region.USE_PREF_SIZE);
            pool.getChildren().addAll(new VBox(2, name, how));
            for (Upstream.Member member : upstream.servers()) {
                Label address = label(member.address(), "mono-muted");
                address.setMinWidth(0);
                HBox.setHgrow(address, Priority.ALWAYS);
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                Label state = label(member.state(), member.down() ? "card-problem" : "mono-muted");
                HBox row = new HBox(12, address, spacer,
                        label(member.weight() == 1 ? "" : "×" + member.weight(), "mono-muted"), state);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(6, 12, 6, 12));
                row.getStyleClass().add("tool-row");
                pool.getChildren().add(row);
            }
        }
    }

    private void drawFacts(List<Vhost> blocks, boolean on) {
        facts.getChildren().add(fact("FILE", "/etc/nginx/sites-available/" + selected));
        facts.getChildren().add(fact("STATE", on
                ? "linked into sites-enabled, so Nginx reads it"
                : "not linked, so Nginx never sees it"));

        List<String> names = new ArrayList<>(sites.namesOf(selected));
        for (Vhost block : blocks) {
            for (String name : block.serverNames()) {
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
        }
        facts.getChildren().add(fact("ANSWERS TO", names.isEmpty() ? "nothing this file names" : String.join("  ", names)));

        List<Integer> ports = new ArrayList<>();
        for (Vhost block : blocks) {
            for (int port : block.listenPorts()) {
                if (!ports.contains(port)) {
                    ports.add(port);
                }
            }
        }
        if (!ports.isEmpty()) {
            facts.getChildren().add(fact("LISTENS ON", ports.stream().map(String::valueOf)
                    .reduce((a, b) -> a + "  " + b).orElse("")));
        }
        drawCertificate(blocks);
    }

    private void drawCertificate(List<Vhost> blocks) {
        String wanted = blocks.stream().map(Vhost::certificate).filter(name -> !name.isEmpty())
                .findFirst().orElse("");
        if (wanted.isEmpty()) {
            facts.getChildren().add(fact("CERTIFICATE", "none, so this site answers in plain HTTP"));
            return;
        }
        Certificate certificate = certificates.stream().filter(one -> one.file().equals(wanted))
                .findFirst().orElse(null);
        if (certificate == null) {
            facts.getChildren().add(fact("CERTIFICATE", wanted));
            return;
        }
        Instant now = Instant.now();
        String text;
        String style = "mono";
        if (!certificate.readable()) {
            text = "could not be read: " + certificate.problem();
            style = "card-problem";
        } else if (certificate.expired(now)) {
            text = "expired " + -certificate.daysLeft(now) + " days ago";
            style = "card-problem";
        } else {
            long days = certificate.daysLeft(now);
            text = days + (days == 1 ? " day left" : " days left");
            style = switch (certificate.urgency(now)) {
                case URGENT -> "card-problem";
                case SOON -> "card-warning";
                default -> "card-success";
            };
        }
        VBox box = new VBox(1, label("CERTIFICATE", "metric-label"), label(text, style),
                label(wanted, "tool-caveat"));
        if (!certificate.caveat().isEmpty()) {
            Label caveat = label(certificate.caveat(), "card-warning");
            caveat.setWrapText(true);
            caveat.setMinHeight(Region.USE_PREF_SIZE);
            box.getChildren().add(caveat);
        }
        facts.getChildren().add(box);
    }

    private void drawActions(boolean on) {
        if (on) {
            Button off = new Button("SWITCH OFF");
            off.getStyleClass().addAll("danger-button", "compact-button");
            off.setFocusTraversable(false);
            off.setOnAction(event -> {
                working.setRunning(true);
                hands.disable(selected, trouble -> report(trouble,
                        selected + " is switched off and Nginx reloaded."));
            });
            actions.getChildren().add(off);
            actions.getChildren().add(note("Switching off removes the link and reloads. The file stays where "
                    + "it is, so nothing is lost."));
        } else {
            Button turnOn = new Button("SWITCH ON");
            turnOn.getStyleClass().addAll("rail-button", "compact-button");
            turnOn.setFocusTraversable(false);
            turnOn.setOnAction(event -> {
                working.setRunning(true);
                hands.enable(selected, trouble -> report(trouble,
                        selected + " is switched on and Nginx reloaded."));
            });
            actions.getChildren().add(turnOn);
            actions.getChildren().add(note("The link is made, the configuration is tested, and the link is "
                    + "removed again if Nginx refuses it. A bad site never survives the click."));
        }
    }

    // The bottom of the middle column

    private void drawBelowTabs() {
        belowTabs.getChildren().clear();
        belowTabs.getChildren().addAll(
                tab("WHAT IT IS ANSWERING", Below.LOG),
                tab("THE FILE", Below.FILE));
        boolean showingLog = below == Below.LOG;
        logLines.setVisible(showingLog);
        logLines.setManaged(showingLog);
        file.setVisible(!showingLog);
        file.setManaged(!showingLog);
    }

    private Button tab(String text, Below which) {
        Button button = new Button(text);
        button.getStyleClass().add("service-chip");
        if (below == which) {
            button.getStyleClass().add("service-chip-active");
        }
        button.setFocusTraversable(false);
        button.setOnAction(event -> {
            below = which;
            drawBelowTabs();
            loadBelow();
        });
        return button;
    }

    /** Asks for whichever of the two the reader is looking at, and only that one. */
    private void loadBelow() {
        if (selected.isEmpty()) {
            return;
        }
        belowPulse.setRunning(true);
        if (below == Below.FILE) {
            file.setText("");
            hands.readFile("/etc/nginx/sites-available/" + selected, text -> {
                belowPulse.setRunning(false);
                file.setText(text);
            });
            return;
        }
        logLines.getChildren().clear();
        hands.readLog(accessLogOf(selected), text -> {
            belowPulse.setRunning(false);
            showLog(text);
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
        String log = accessLogOf(selected);
        logLines.getChildren().add(label(log, "tool-caveat"));
        if (text == null || text.isBlank()) {
            logLines.getChildren().add(note("Nothing came back. Either nobody has asked this site for anything "
                    + "yet, or reading this file needs root."));
            return;
        }
        String[] raw = text.split("\\R");
        int from = Math.max(0, raw.length - 60);
        for (int i = raw.length - 1; i >= from; i--) {
            if (raw[i].isBlank()) {
                continue;
            }
            AccessLine line = AccessLine.of(raw[i]);
            Label row = label(line.sentence(), "install-line");
            row.getStyleClass().add(switch (line.level()) {
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
        if (logLines.getChildren().size() == 1) {
            logLines.getChildren().add(note("The file is there and has nothing in it."));
        }
    }

    // Small pieces

    private static VBox fact(String name, String value) {
        Label label = label(value, "mono");
        label.setWrapText(true);
        return new VBox(1, label(name, "metric-label"), label);
    }

    private Button rail(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button button = new Button(text);
        button.getStyleClass().add("rail-button");
        button.setFocusTraversable(false);
        button.setOnAction(action);
        return button;
    }

    private static Region separator() {
        Region line = new Region();
        line.getStyleClass().add("rail-separator");
        line.setMinWidth(1);
        line.setPrefWidth(1);
        return line;
    }

    private static Label note(String text) {
        Label label = label(text, "card-note");
        label.setWrapText(true);
        label.setMaxWidth(560);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
