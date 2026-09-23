package com.kubo.ui.network;

import com.kubo.model.network.Firewall;
import com.kubo.model.network.FirewallRule;
import com.kubo.model.network.PortNames;
import com.kubo.model.server.ListeningPort.Protocol;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Every firewall rule, what happens to what no rule names, and the edits the map cannot make: a rule for one
 * source address, a refusal, and the removal of any rule at all.
 *
 * <p>UFW writes most rules twice, once per address family, so eleven lines are six decisions. They are shown as
 * six, with the families beside each, and deleting one deletes both halves.
 *
 * <p>Deleting the rule that lets SSH in is the one click on this screen that can cost the server. Kubo knows
 * which port it came in on, so it knows when that is what is being asked, and asks for the port to be typed back
 * before it does it.
 *
 * <p>Writing is offered for UFW only. Kubo reads firewalld, nftables and iptables, and editing those safely means
 * understanding zones and chains it does not model.
 */
final class FirewallCard extends VBox {

    interface Hands {
        void allowFrom(String source, int port, Protocol protocol);

        /** Refuses a port, to one source or, with an empty source, to everyone. */
        void denyFrom(String source, int port, Protocol protocol);

        /** Deletes both halves of a rule. */
        void delete(Firewall.RuleGroup group);
    }

    private final Label status = new Label();
    private final Firewall firewall;
    private final Hands hands;
    private final int sshPort;
    private final VBox confirmBox = new VBox(8);
    private boolean denying;
    private final Button tcp = new Button();
    private final Button udp = new Button();
    private final Button allowPill = new Button("ALLOW");
    private final Button denyPill = new Button("DENY");

    /**
     * @param sshPort the port Kubo's own connection came in on, which is what a lockout would close
     */
    FirewallCard(Firewall firewall, Hands hands, int sshPort, Runnable onClose) {
        this.firewall = firewall;
        this.hands = hands;
        this.sshPort = sshPort;
        getStyleClass().addAll("float-card", "help-card");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(14);
        setPadding(new Insets(24, 30, 24, 28));

        boolean writable = firewall.kind() == Firewall.Kind.UFW;

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox header = new HBox(12, label(firewall.title().toUpperCase(Locale.ROOT), "card-title"),
                label(firewall.shortState(), firewall.isEnforcing() ? "card-success" : "card-problem"),
                spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        status.getStyleClass().add("card-note");
        status.setWrapText(true);
        status.setMaxWidth(640);
        status.setMinHeight(Region.USE_PREF_SIZE);
        status.setVisible(false);
        status.setManaged(false);

        VBox body = new VBox(14);
        if (firewall.defaults().known()) {
            body.getChildren().add(defaults(firewall.defaults()));
        }
        if (writable) {
            body.getChildren().addAll(label("ADD A RULE", "series"),
                    note("A port opened to the world and a port opened to your office are the same click "
                            + "everywhere else. They are not the same decision."),
                    ruleForm());
        }

        body.getChildren().add(label("RULES", "series"));
        if (!firewall.isKnown()) {
            body.getChildren().add(note(firewall.problem()));
        } else if (firewall.rules().isEmpty()) {
            body.getChildren().add(note("This firewall is on and has no rule at all."));
        } else {
            body.getChildren().add(rules(writable));
        }
        if (!writable && firewall.isKnown()) {
            body.getChildren().add(note("Kubo reads " + firewall.title() + " but writes UFW rules only. "
                    + "Editing zones and chains it does not model could put a rule where you did not mean it."));
        }
        confirmBox.setVisible(false);
        confirmBox.setManaged(false);
        body.getChildren().addAll(confirmBox, status);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefViewportHeight(520);
        scroll.setMaxWidth(720);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(header, scroll);
        paintAction();
    }

    void report(String trouble, String done) {
        status.getStyleClass().setAll("label", trouble == null ? "card-success" : "card-problem");
        status.setText(trouble == null ? done : trouble);
        status.setVisible(true);
        status.setManaged(true);
    }

    /** What happens to everything no rule names, which is half of what a firewall does. */
    private HBox defaults(Firewall.Defaults defaults) {
        Label title = label("ANYTHING NOT LISTED", "metric-label");
        Label incoming = label("coming in  " + defaults.incoming().toUpperCase(Locale.ROOT),
                defaults.letsEverythingIn() ? "card-problem" : "card-success");
        Label outgoing = label("going out  " + defaults.outgoing().toUpperCase(Locale.ROOT), "mono-muted");
        HBox line = new HBox(18, title, incoming, outgoing);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("tools-summary");
        line.setPadding(new Insets(8, 12, 9, 12));
        if (defaults.letsEverythingIn()) {
            Label warning = label("so the rules below are exceptions, and every other port is open",
                    "card-problem");
            line.getChildren().add(warning);
        }
        return line;
    }

    private VBox ruleForm() {
        TextField source = new TextField();
        source.getStyleClass().add("card-field");
        source.setPromptText("203.0.113.9 or 10.0.0.0/8");
        source.setPrefColumnCount(16);

        TextField port = new TextField();
        port.getStyleClass().add("card-field");
        port.setPromptText("5432");
        port.setPrefColumnCount(5);

        for (Button pill : new Button[]{allowPill, denyPill}) {
            pill.getStyleClass().add("service-chip");
            pill.setFocusTraversable(false);
        }
        allowPill.setOnAction(event -> {
            denying = false;
            paintAction();
        });
        denyPill.setOnAction(event -> {
            denying = true;
            paintAction();
        });

        for (Button button : new Button[]{tcp, udp}) {
            button.getStyleClass().addAll("rail-button", "compact-button");
            button.setFocusTraversable(false);
        }
        tcp.setOnAction(event -> submit(source, port, Protocol.TCP));
        udp.setOnAction(event -> submit(source, port, Protocol.UDP));

        HBox actions = new HBox(6, allowPill, denyPill);
        actions.setAlignment(Pos.CENTER_LEFT);
        HBox row = new HBox(8, label("FROM", "metric-label"), source, label("TO PORT", "metric-label"), port,
                tcp, udp);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(8, actions, row);
    }

    private void paintAction() {
        allowPill.getStyleClass().remove("service-chip-active");
        denyPill.getStyleClass().remove("service-chip-active");
        (denying ? denyPill : allowPill).getStyleClass().add("service-chip-active");
        tcp.setText((denying ? "DENY" : "ALLOW") + " TCP");
        udp.setText((denying ? "DENY" : "ALLOW") + " UDP");
    }

    private void submit(TextField source, TextField port, Protocol protocol) {
        int number;
        try {
            number = Integer.parseInt(port.getText().strip());
        } catch (NumberFormatException e) {
            report("A port is a number between 1 and 65535.", null);
            return;
        }
        String from = source.getText().strip();
        if (denying) {
            hands.denyFrom(from, number, protocol);
        } else if (from.isEmpty()) {
            // Opening a port to the world is on the map, where it can be seen for what it is.
            report("Give the address to open it to. Opening a port to everyone is done from the map.", null);
        } else {
            hands.allowFrom(from, number, protocol);
        }
    }

    private GridPane rules(boolean writable) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(5);
        grid.add(label("TO", "metric-label"), 0, 0);
        grid.add(label("ACTION", "metric-label"), 1, 0);
        grid.add(label("FROM", "metric-label"), 2, 0);

        int row = 1;
        for (Firewall.RuleGroup group : firewall.grouped()) {
            FirewallRule rule = group.rule();
            Label to = label(bare(rule.to()), "mono");
            Label what = label(meaning(rule), "tool-caveat");
            Label families = label(group.families(), "mono-muted");
            VBox toBox = new VBox(1, to, new HBox(8, what, families));
            toBox.setMinWidth(220);
            grid.add(toBox, 0, row);
            grid.add(label(rule.action().name(),
                    rule.action().letsThrough() ? "card-success" : "card-problem"), 1, row);
            Label from = label(bare(rule.from()), "mono-muted");
            from.setMinWidth(150);
            grid.add(from, 2, row);
            if (writable) {
                Button delete = new Button("DELETE");
                delete.getStyleClass().add("danger-quiet");
                delete.setFocusTraversable(false);
                delete.setOnAction(event -> askToDelete(group));
                grid.add(delete, 3, row);
            }
            row++;
        }
        return grid;
    }

    /** What a rule's destination is, in words: "SSH · 22", "HTTP", or nothing when Kubo has no name for it. */
    private static String meaning(FirewallRule rule) {
        if (rule.ports().isEmpty()) {
            return "";
        }
        String numbers = rule.ports().stream().map(String::valueOf).collect(Collectors.joining(", "));
        String names = rule.ports().stream().map(PortNames::of).filter(name -> !name.isEmpty()).distinct()
                .collect(Collectors.joining(", "));
        boolean written = bare(rule.to()).replaceAll("[^0-9,]", "").equals(numbers.replace(" ", ""));
        // A rule written as "443" does not need "443" said again; one written as "OpenSSH" does.
        if (written) {
            return names;
        }
        return names.isEmpty() ? numbers : names + "  ·  " + numbers;
    }

    /**
     * Deletes at once when nothing hangs on it. When the rule is what lets SSH in, says so in full and asks for
     * the port to be typed back, because a wrong click here is fixed from a provider's console.
     */
    private void askToDelete(Firewall.RuleGroup group) {
        Firewall.Lockout lockout = firewall.ifDeleted(group, sshPort);
        if (!lockout.risky()) {
            confirmBox.setVisible(false);
            confirmBox.setManaged(false);
            if (!lockout.reason().isEmpty()) {
                report(null, lockout.reason());
            }
            hands.delete(group);
            return;
        }
        TextField typed = new TextField();
        typed.getStyleClass().add("card-field");
        typed.setPromptText(String.valueOf(sshPort));
        typed.setPrefColumnCount(6);
        Button anyway = new Button("DELETE IT ANYWAY");
        anyway.getStyleClass().addAll("danger-button", "compact-button");
        anyway.setFocusTraversable(false);
        anyway.setDisable(true);
        typed.textProperty().addListener((observable, before, now) ->
                anyway.setDisable(!now.strip().equals(String.valueOf(sshPort))));
        anyway.setOnAction(event -> {
            confirmBox.setVisible(false);
            confirmBox.setManaged(false);
            hands.delete(group);
        });
        Button keep = new Button("KEEP IT");
        keep.getStyleClass().addAll("ghost-button", "compact-button");
        keep.setFocusTraversable(false);
        keep.setOnAction(event -> {
            confirmBox.setVisible(false);
            confirmBox.setManaged(false);
        });

        Label reason = label(lockout.reason(), "card-problem");
        reason.setWrapText(true);
        reason.setMaxWidth(640);
        reason.setMinHeight(Region.USE_PREF_SIZE);
        HBox line = new HBox(10, label("TYPE " + sshPort + " TO DELETE " + bare(group.rule().to())
                .toUpperCase(Locale.ROOT), "metric-label"), typed, anyway, keep);
        line.setAlignment(Pos.CENTER_LEFT);
        confirmBox.getChildren().setAll(label("THIS RULE IS HOW SSH GETS IN", "series"), reason, line);
        confirmBox.getStyleClass().setAll("lockout-box");
        confirmBox.setPadding(new Insets(12, 14, 12, 14));
        confirmBox.setVisible(true);
        confirmBox.setManaged(true);
        typed.requestFocus();
    }

    /** Only for the previews: the confirmation, as it looks when SSH hangs on the rule. */
    void askForPreview(String to) {
        firewall.grouped().stream().filter(group -> group.rule().to().equals(to)).findFirst()
                .ifPresent(this::askToDelete);
    }

    private static String bare(String text) {
        return text.replace("(v6)", "").strip();
    }

    private static Label note(String text) {
        Label label = label(text, "card-note");
        label.setWrapText(true);
        label.setMaxWidth(660);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
