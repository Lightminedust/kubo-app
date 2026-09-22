package com.kubo.ui.network;

import com.kubo.model.network.Firewall;
import com.kubo.model.network.FirewallRule;
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

/**
 * Every firewall rule, with the two things the screen could not do: open a port to one source address, and remove
 * a rule that is not simply "allow this port".
 *
 * <p>Writing is offered for UFW only. Kubo reads firewalld, nftables and iptables, and editing those safely means
 * understanding zones and chains it does not model. Showing an edit button that writes a rule into the wrong
 * chain would be worse than showing none.
 */
final class FirewallCard extends VBox {

    interface Hands {
        void allowFrom(String source, int port, Protocol protocol);

        void delete(FirewallRule rule);
    }

    private final Label status = new Label();

    FirewallCard(Firewall firewall, Hands hands, Runnable onClose) {
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
        HBox header = new HBox(12, label(firewall.title().toUpperCase(java.util.Locale.ROOT), "card-title"),
                label(firewall.shortState(), firewall.isEnforcing() ? "card-success" : "card-problem"),
                spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        status.getStyleClass().add("card-note");
        status.setWrapText(true);
        status.setVisible(false);
        status.setManaged(false);

        VBox body = new VBox(14);
        if (writable) {
            body.getChildren().addAll(label("OPEN A PORT TO ONE ADDRESS", "series"),
                    note("A port opened to the world and a port opened to your office are the same click "
                            + "everywhere else. They are not the same decision."),
                    allowForm(hands));
        }

        body.getChildren().add(label("RULES", "series"));
        if (!firewall.isKnown()) {
            body.getChildren().add(note(firewall.problem()));
        } else if (firewall.rules().isEmpty()) {
            body.getChildren().add(note("This firewall is on and has no rule at all."));
        } else {
            body.getChildren().add(rules(firewall, hands, writable));
        }
        if (!writable && firewall.isKnown()) {
            body.getChildren().add(note("Kubo reads " + firewall.title() + " but writes UFW rules only. "
                    + "Editing zones and chains it does not model could put a rule where you did not mean it."));
        }
        body.getChildren().add(status);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefViewportHeight(500);
        scroll.setMaxWidth(720);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(header, scroll);
    }

    void report(String trouble, String done) {
        status.getStyleClass().setAll("label", trouble == null ? "card-success" : "card-problem");
        status.setText(trouble == null ? done : trouble);
        status.setVisible(true);
        status.setManaged(true);
    }

    private HBox allowForm(Hands hands) {
        TextField source = new TextField();
        source.getStyleClass().add("card-field");
        source.setPromptText("203.0.113.9 or 10.0.0.0/8");
        source.setPrefColumnCount(16);

        TextField port = new TextField();
        port.getStyleClass().add("card-field");
        port.setPromptText("5432");
        port.setPrefColumnCount(5);

        Button tcp = new Button("ALLOW TCP");
        tcp.getStyleClass().addAll("rail-button", "compact-button");
        tcp.setFocusTraversable(false);
        tcp.setOnAction(event -> submit(source, port, Protocol.TCP, hands));

        Button udp = new Button("ALLOW UDP");
        udp.getStyleClass().addAll("rail-button", "compact-button");
        udp.setFocusTraversable(false);
        udp.setOnAction(event -> submit(source, port, Protocol.UDP, hands));

        HBox row = new HBox(8, label("FROM", "metric-label"), source,
                label("TO PORT", "metric-label"), port, tcp, udp);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void submit(TextField source, TextField port, Protocol protocol, Hands hands) {
        int number;
        try {
            number = Integer.parseInt(port.getText().strip());
        } catch (NumberFormatException e) {
            report("A port is a number between 1 and 65535.", null);
            return;
        }
        hands.allowFrom(source.getText().strip(), number, protocol);
    }

    private GridPane rules(Firewall firewall, Hands hands, boolean writable) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(5);
        grid.add(label("#", "metric-label"), 0, 0);
        grid.add(label("TO", "metric-label"), 1, 0);
        grid.add(label("ACTION", "metric-label"), 2, 0);
        grid.add(label("FROM", "metric-label"), 3, 0);

        int row = 1;
        for (FirewallRule rule : firewall.rules()) {
            grid.add(label(String.valueOf(rule.index()), "mono-muted"), 0, row);
            Label to = label(rule.to() + (rule.v6() ? "  (v6)" : ""), "mono");
            to.setMinWidth(180);
            grid.add(to, 1, row);
            grid.add(label(rule.action().name(),
                    rule.action().letsThrough() ? "card-success" : "card-problem"), 2, row);
            Label from = label(rule.from(), "mono-muted");
            from.setMinWidth(160);
            grid.add(from, 3, row);
            if (writable) {
                Button delete = new Button("DELETE");
                delete.getStyleClass().addAll("danger-button", "compact-button");
                delete.setFocusTraversable(false);
                delete.setOnAction(event -> hands.delete(rule));
                grid.add(delete, 4, row);
            }
            row++;
        }
        return grid;
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
