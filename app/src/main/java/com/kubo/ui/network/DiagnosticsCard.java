package com.kubo.ui.network;

import com.kubo.model.network.Identity;
import com.kubo.model.tools.Tool;
import com.kubo.ui.Pulse;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Set;

/**
 * Asking the questions the left panel could only answer with a list: does the gateway reply, do these resolvers
 * resolve, where does the traffic stop.
 *
 * <p>Every button here needs a tool the server may not have. A button whose tool is missing is disabled and says
 * which one and where to get it, rather than running something that fails with a message about a command not
 * being found.
 */
final class DiagnosticsCard extends VBox {

    interface Hands {
        /** Runs one probe and hands back what the server printed. */
        void probe(String title, java.util.function.Supplier<String> command,
                   java.util.function.Consumer<String> done);

        /** Opens the tools card, for the buttons that cannot work yet. */
        void openTools();
    }

    private final TextArea output = new TextArea();
    private final Pulse pulse = new Pulse();
    private final Label heading = new Label();

    DiagnosticsCard(Identity identity, Set<Tool> tools, Hands hands, Runnable onClose) {
        getStyleClass().addAll("float-card", "help-card");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(14);
        setPadding(new Insets(24, 30, 24, 28));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button tooling = new Button("TOOLS");
        tooling.getStyleClass().addAll("ghost-button", "compact-button");
        tooling.setFocusTraversable(false);
        tooling.setOnAction(event -> hands.openTools());
        Button close = new Button("✕");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox header = new HBox(12, label("CHECK THE SERVER'S OWN CONNECTION", "card-title"), spacer,
                tooling, close);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(16);

        // Reaching out
        body.getChildren().add(label("DOES IT ANSWER", "series"));
        FlowPane reach = new FlowPane(8, 8);
        String gateway = identity.defaultRoute().map(route -> route.gateway()).orElse("");
        if (!gateway.isBlank()) {
            reach.getChildren().add(probeButton("PING THE GATEWAY", Tool.PING, tools, hands,
                    () -> com.kubo.tools.ProbeCommands.ping(gateway),
                    "Ping the gateway " + gateway));
        }
        reach.getChildren().add(probeButton("PING 1.1.1.1", Tool.PING, tools, hands,
                () -> com.kubo.tools.ProbeCommands.ping("1.1.1.1"), "Ping 1.1.1.1"));
        body.getChildren().add(reach);
        body.getChildren().add(note(gateway.isBlank()
                ? "No default route, so there is no gateway to try."
                : "The gateway is the first thing outside this machine. If it does not answer, nothing else will."));

        // Resolvers
        body.getChildren().add(label("DO THE RESOLVERS RESOLVE", "series"));
        TextField name = new TextField("gemmi-e.com");
        name.getStyleClass().add("card-field");
        name.setPrefColumnCount(18);
        FlowPane resolvers = new FlowPane(8, 8);
        for (String server : identity.resolvers().servers()) {
            resolvers.getChildren().add(probeButton("ASK " + server, Tool.DIG, tools, hands,
                    () -> com.kubo.tools.ProbeCommands.dig(name.getText(), server),
                    "Ask " + server));
        }
        if (identity.resolvers().servers().isEmpty()) {
            resolvers.getChildren().add(probeButton("ASK THE DEFAULT RESOLVER", Tool.DIG, tools, hands,
                    () -> com.kubo.tools.ProbeCommands.dig(name.getText(), ""), "Ask the default resolver"));
        }
        HBox dnsRow = new HBox(10, label("NAME", "metric-label"), name);
        dnsRow.setAlignment(Pos.CENTER_LEFT);
        body.getChildren().addAll(dnsRow, resolvers);
        body.getChildren().add(note("The left panel lists these. This asks them a question and shows the answer."));

        // Anything else
        body.getChildren().add(label("SOMEWHERE ELSE", "series"));
        TextField target = new TextField();
        target.getStyleClass().add("card-field");
        target.setPromptText("an address or a host name");
        target.setPrefColumnCount(20);
        HBox row = new HBox(8, target,
                probeButton("PING", Tool.PING, tools, hands,
                        () -> com.kubo.tools.ProbeCommands.ping(target.getText()), "Ping"),
                probeButton("TRACE THE ROUTE", Tool.MTR, tools, hands,
                        () -> com.kubo.tools.ProbeCommands.route(target.getText()), "Trace the route"),
                probeButton("WHO OWNS IT", Tool.WHOIS, tools, hands,
                        () -> com.kubo.tools.ProbeCommands.whois(target.getText()), "Who owns it"));
        row.setAlignment(Pos.CENTER_LEFT);
        body.getChildren().add(row);

        heading.getStyleClass().add("metric-label");
        output.setEditable(false);
        output.setWrapText(false);
        output.getStyleClass().add("redis-diagnostic-output");
        output.setPrefRowCount(12);
        output.setText("Nothing asked yet.");
        body.getChildren().addAll(heading, pulse, output);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefViewportHeight(520);
        scroll.setMaxWidth(760);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(header, scroll);
    }

    /**
     * A button that runs a probe, or says which tool it is waiting for.
     */
    private Button probeButton(String text, Tool needs, Set<Tool> tools, Hands hands,
                               java.util.function.Supplier<String> command, String title) {
        Button button = new Button(text);
        button.getStyleClass().addAll("rail-button", "compact-button");
        button.setFocusTraversable(false);
        if (!tools.contains(needs)) {
            button.setDisable(true);
            button.setTooltip(new javafx.scene.control.Tooltip(
                    "This needs " + needs.binary() + ", which this server does not have. Open TOOLS to add it."));
            button.setText(text + "  ·  NEEDS " + needs.binary().toUpperCase(java.util.Locale.ROOT));
            return button;
        }
        button.setOnAction(event -> {
            heading.setText(title.toUpperCase(java.util.Locale.ROOT));
            output.setText("");
            pulse.setRunning(true);
            hands.probe(title, command, answer -> {
                pulse.setRunning(false);
                output.setText(answer);
            });
        });
        return button;
    }

    private static Label note(String text) {
        Label label = label(text, "card-note");
        label.setWrapText(true);
        label.setMaxWidth(700);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
