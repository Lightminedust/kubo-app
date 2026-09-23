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
    /** The answer in one line, coloured, above the raw text. */
    private final Label verdict = new Label();
    private ScrollPane scroll;
    /** A trace, hop by hop, when the question was the route. */
    private final com.kubo.ui.Chain route = new com.kubo.ui.Chain();

    /** How to read what a probe printed. */
    private enum Reading { PING, ROUTE, PORT, RAW }

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
            // The address on the button: which gateway is being asked is half of what the answer means.
            reach.getChildren().add(probeButton("PING THE GATEWAY  ·  " + gateway, Tool.PING, tools, hands,
                    () -> com.kubo.tools.ProbeCommands.ping(gateway),
                    "Ping the gateway " + gateway, Reading.PING));
        }
        reach.getChildren().add(probeButton("PING 1.1.1.1", Tool.PING, tools, hands,
                () -> com.kubo.tools.ProbeCommands.ping("1.1.1.1"), "Ping 1.1.1.1", Reading.PING));
        body.getChildren().add(reach);
        body.getChildren().add(note(gateway.isBlank()
                ? "No default route, so there is no gateway to try."
                : "The gateway is the first thing outside this machine. If it does not answer, nothing else will."));

        // Resolvers
        body.getChildren().add(label("DO THE RESOLVERS RESOLVE", "series"));
        TextField name = new TextField("example.com");
        name.getStyleClass().add("card-field");
        name.setPrefColumnCount(18);
        FlowPane resolvers = new FlowPane(8, 8);
        for (String server : identity.resolvers().servers()) {
            resolvers.getChildren().add(probeButton("ASK " + server, Tool.DIG, tools, hands,
                    () -> com.kubo.tools.ProbeCommands.dig(name.getText(), server),
                    "Ask " + server, Reading.RAW));
        }
        if (identity.resolvers().servers().isEmpty()) {
            resolvers.getChildren().add(probeButton("ASK THE DEFAULT RESOLVER", Tool.DIG, tools, hands,
                    () -> com.kubo.tools.ProbeCommands.dig(name.getText(), ""), "Ask the default resolver",
                    Reading.RAW));
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
                        () -> com.kubo.tools.ProbeCommands.ping(target.getText()), "Ping", Reading.PING),
                probeButton("TRACE THE ROUTE", Tool.MTR, tools, hands,
                        () -> com.kubo.tools.ProbeCommands.route(target.getText()), "Trace the route",
                        Reading.ROUTE),
                probeButton("WHO OWNS IT", Tool.WHOIS, tools, hands,
                        () -> com.kubo.tools.ProbeCommands.whois(target.getText()), "Who owns it", Reading.RAW));
        row.setAlignment(Pos.CENTER_LEFT);
        body.getChildren().add(row);

        // One port, which tells a service that is down from a firewall that is in the way.
        TextField port = new TextField();
        port.getStyleClass().add("card-field");
        port.setPromptText("443");
        port.setPrefColumnCount(5);
        HBox portRow = new HBox(8, label("PORT", "metric-label"), port,
                probeButton("CHECK THE PORT", null, tools, hands,
                        () -> com.kubo.tools.ProbeCommands.checkPort(target.getText(), portNumber(port)),
                        "Check the port", Reading.PORT));
        portRow.setAlignment(Pos.CENTER_LEFT);
        body.getChildren().addAll(portRow, note("A refusal means the machine answered and nothing listens there. "
                + "No answer at all means something in between is dropping it. Needs no tool: bash does it."));

        heading.getStyleClass().add("metric-label");
        output.setEditable(false);
        output.setWrapText(false);
        output.getStyleClass().add("redis-diagnostic-output");
        output.setPrefRowCount(12);
        output.setText("Nothing asked yet.");
        verdict.setWrapText(true);
        verdict.setMaxWidth(700);
        verdict.setMinHeight(Region.USE_PREF_SIZE);
        verdict.setVisible(false);
        verdict.setManaged(false);
        route.setVisible(false);
        route.setManaged(false);
        body.getChildren().addAll(heading, pulse, verdict, route, output);

        scroll = new ScrollPane(body);
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
                               java.util.function.Supplier<String> command, String title, Reading reading) {
        Button button = new Button(text);
        button.getStyleClass().addAll("rail-button", "compact-button");
        button.setFocusTraversable(false);
        if (needs != null && !tools.contains(needs)) {
            button.setDisable(true);
            button.setTooltip(new javafx.scene.control.Tooltip(
                    "This needs " + needs.binary() + ", which this server does not have. Open TOOLS to add it."));
            button.setText(text + "  ·  NEEDS " + needs.binary().toUpperCase(java.util.Locale.ROOT));
            return button;
        }
        button.setOnAction(event -> {
            heading.setText(title.toUpperCase(java.util.Locale.ROOT));
            output.setText("");
            verdict.setVisible(false);
            verdict.setManaged(false);
            route.setVisible(false);
            route.setManaged(false);
            pulse.setRunning(true);
            hands.probe(title, command, answer -> {
                pulse.setRunning(false);
                show(reading, answer);
            });
        });
        return button;
    }

    private static int portNumber(TextField field) {
        try {
            return Integer.parseInt(field.getText().strip());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /**
     * The raw answer, and above it what it means: a coloured line for a ping or a port, the route as a chain of
     * hops for a trace.
     */
    private void show(Reading reading, String answer) {
        output.setText(answer);
        switch (reading) {
            case PING -> {
                com.kubo.model.network.ProbeReading.Ping ping = com.kubo.model.network.ProbeReading.Ping.parse(answer);
                say(ping.sentence(), ping.verdict());
            }
            case PORT -> {
                com.kubo.model.network.ProbeReading.PortState state =
                        com.kubo.model.network.ProbeReading.PortState.parse(answer);
                say(state.word() + "  ·  " + state.meaning(), state.verdict());
            }
            case ROUTE -> drawRoute(answer);
            case RAW -> {
                // A resolver's answer or a whois is read by the person asking; nothing to judge.
            }
        }
    }

    private void say(String text, com.kubo.model.network.ProbeReading.Verdict how) {
        verdict.setText(text);
        verdict.getStyleClass().setAll("label", switch (how) {
            case GOOD -> "card-success";
            case SLOW -> "card-warning";
            case BAD -> "card-problem";
            case UNKNOWN -> "card-note";
        });
        verdict.setVisible(true);
        verdict.setManaged(true);
    }

    /** The trace as a chain: this server, then each hop, red from where nothing answers any more. */
    private void drawRoute(String answer) {
        java.util.List<com.kubo.model.network.ProbeReading.Hop> hops =
                com.kubo.model.network.ProbeReading.hops(answer);
        if (hops.isEmpty()) {
            say("mtr printed no hop Kubo could read. The answer is below.",
                    com.kubo.model.network.ProbeReading.Verdict.UNKNOWN);
            return;
        }
        java.util.List<com.kubo.ui.Chain.Link> links = new java.util.ArrayList<>();
        links.add(new com.kubo.ui.Chain.Link("FROM", "this server", "", com.kubo.ui.Chain.State.WORKS, null));
        boolean stopped = false;
        for (int i = 0; i < hops.size(); i++) {
            com.kubo.model.network.ProbeReading.Hop hop = hops.get(i);
            com.kubo.model.network.ProbeReading.Verdict judged = com.kubo.model.network.ProbeReading.judge(hops, i);
            if (judged == com.kubo.model.network.ProbeReading.Verdict.BAD) {
                // Every silent hop after the break says the same thing; one red link is the answer.
                links.add(new com.kubo.ui.Chain.Link("HOP " + hop.number(), "no answer",
                        com.kubo.model.network.ProbeReading.note(hops, i), com.kubo.ui.Chain.State.BROKEN, null));
                stopped = true;
                break;
            }
            links.add(new com.kubo.ui.Chain.Link("HOP " + hop.number(), hop.silent() ? "a quiet router" : hop.host(),
                    com.kubo.model.network.ProbeReading.note(hops, i), switch (judged) {
                        case GOOD -> com.kubo.ui.Chain.State.WORKS;
                        case SLOW -> com.kubo.ui.Chain.State.WARNS;
                        default -> com.kubo.ui.Chain.State.UNKNOWN;
                    }, null));
        }
        route.setLinks(links);
        route.setVisible(true);
        route.setManaged(true);
        say(stopped ? "The route stops before it gets there: nothing answers from the red hop on. The hop above it is the "
                        + "last one that did."
                        : "It gets there, in " + hops.size() + (hops.size() == 1 ? " hop." : " hops."),
                stopped ? com.kubo.model.network.ProbeReading.Verdict.BAD
                        : com.kubo.model.network.ProbeReading.Verdict.GOOD);
    }

    /** Only for the previews: an answer already in, without a server. */
    void showForPreview(String title, boolean trace, String answer) {
        heading.setText(title.toUpperCase(java.util.Locale.ROOT));
        show(trace ? Reading.ROUTE : Reading.PING, answer);
        scroll.setVvalue(1);
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
