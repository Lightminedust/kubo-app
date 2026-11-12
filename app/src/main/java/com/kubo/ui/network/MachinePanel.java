package com.kubo.ui.network;

import com.kubo.model.network.Counter;
import com.kubo.model.network.Identity;
import com.kubo.model.network.Link;
import com.kubo.model.network.Naming;
import com.kubo.model.network.Route;
import com.kubo.model.server.ListeningPort;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;

/**
 * Where this server sits on the network, read out the way fastfetch reads out a machine: one key, one value,
 * one line. The interfaces with their addresses and how much is moving through them, the way out, the
 * resolvers, and at the bottom whatever Kubo could not read.
 */
final class MachinePanel extends ScrollPane {

    private final VBox column = new VBox(5);
    private final VBox notes = new VBox(4);
    /** The container interfaces stay folded: a server with twenty containers has forty of them. */
    private boolean showVirtual;
    private Identity identity = Identity.empty();
    private Map<String, Counter.Rate> rates = Map.of();
    private Naming naming = Naming.none();
    /**
     * What the panel is currently drawn from. Everything here changes once in a blue moon and the screen
     * re-reads every twelve seconds, so rebuilding the whole column each time made it blink for no reason.
     */
    private String drawnFrom = "";
    private final Map<String, Label> speeds = new java.util.LinkedHashMap<>();

    MachinePanel() {
        getStyleClass().add("track-scroll");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        column.setPadding(new Insets(0, 8, 8, 0));
        column.setMinWidth(0);
        notes.setMinWidth(0);
        VBox holder = new VBox(14, column, notes);
        setContent(holder);
        show(Identity.empty(), Map.of(), Naming.none());
    }

    /**
     * @param rates bytes per second per interface, empty on the first read of a session
     */
    void show(Identity identity, Map<String, Counter.Rate> rates, Naming naming) {
        this.identity = identity;
        this.rates = rates;
        this.naming = naming;

        // The rate is the only thing that really moves between two readings. When nothing else changed, only
        // the rate is written, and the panel stops flickering.
        String signature = signature(identity);
        if (signature.equals(drawnFrom)) {
            writeSpeeds(rates);
            return;
        }
        drawnFrom = signature;
        speeds.clear();
        column.getChildren().clear();

        column.getChildren().add(fact("reached", identity.reachedAt().isBlank() ? "—" : identity.reachedAt()));
        column.getChildren().add(gap());

        List<Link> real = identity.realLinks().stream().filter(link -> !Naming.virtual(link)).toList();
        List<Link> virtual = identity.realLinks().stream().filter(Naming::virtual).toList();
        if (identity.links().isEmpty()) {
            column.getChildren().add(fact("links", "—"));
        }
        for (Link link : real) {
            linkLines(link);
        }
        if (!virtual.isEmpty()) {
            Button fold = new Button(showVirtual ? "hide " + virtual.size() + " container links"
                    : virtual.size() + " container links");
            fold.getStyleClass().add("track-more");
            fold.setFocusTraversable(false);
            fold.setOnAction(event -> {
                showVirtual = !showVirtual;
                // The reading did not change, only the question did: without this the button did nothing.
                drawnFrom = "";
                show(this.identity, this.rates, this.naming);
            });
            column.getChildren().add(fold);
            if (showVirtual) {
                virtual.forEach(this::linkLines);
            }
        }
        column.getChildren().add(gap());

        identity.defaultRoute().ifPresentOrElse(
                route -> column.getChildren().add(fact("route", describe(route))),
                () -> column.getChildren().add(fact("route", "none, local network only")));
        if (!identity.resolvers().known()) {
            column.getChildren().add(fact("dns", "—"));
        } else {
            boolean firstServer = true;
            for (String server : identity.resolvers().servers()) {
                column.getChildren().add(fact(firstServer ? "dns" : "", server));
                firstServer = false;
            }
            if (!identity.resolvers().searchDomains().isEmpty()) {
                column.getChildren().add(fact("search", String.join(" ", identity.resolvers().searchDomains())));
            }
        }
    }

    /** What Kubo could not read on the last reading, at the bottom, short and in amber. */
    void setNotes(List<String> said) {
        notes.getChildren().clear();
        for (String note : said) {
            Label line = new Label(note);
            line.getStyleClass().add("fetch-note");
            line.setWrapText(true);
            line.setMinHeight(Region.USE_PREF_SIZE);
            notes.getChildren().add(line);
        }
    }

    /** Kept for the screen that drives it; reading is said once, in the status line. */
    void setReading(boolean reading) {
        // Nothing here moves while the server is being read.
    }

    private void linkLines(Link link) {
        Label name = new Label(link.name());
        name.getStyleClass().add("fetch-key");
        name.setMinWidth(KEY_WIDTH);
        Label state = new Label(link.up() ? "up" : "down");
        state.getStyleClass().add(link.up() ? "journal-ok" : "journal-error");
        state.setMinWidth(Region.USE_PREF_SIZE);
        HBox head = new HBox(10, name, state);
        head.setAlignment(Pos.CENTER_LEFT);
        String role = naming.role(link);
        if (!role.isEmpty()) {
            Tooltip.install(head, new Tooltip(role));
        }
        column.getChildren().add(head);
        for (Link.Address address : link.addresses()) {
            // A public address is the one the internet can reach, so it carries the colour of "open".
            boolean open = address.exposure() == ListeningPort.Exposure.PUBLIC;
            Label value = new Label(address.cidr());
            value.getStyleClass().add("fetch-value");
            if (open) {
                value.setStyle("-fx-text-fill: #ffb347;");
                value.setTooltip(new Tooltip("public: reachable from the internet"));
            }
            value.setMinWidth(0);
            value.setPadding(new Insets(0, 0, 0, KEY_WIDTH + 10));
            column.getChildren().add(value);
        }
        // How much is moving through it, under its addresses.
        Label speed = new Label("");
        speed.getStyleClass().add("port-quiet");
        speed.setPadding(new Insets(0, 0, 0, KEY_WIDTH + 10));
        speeds.put(link.name(), speed);
        Counter.Rate rate = rates.get(link.name());
        speed.setText(rate == null ? "" : speed(rate));
        speed.setVisible(rate != null);
        speed.setManaged(rate != null);
        column.getChildren().add(speed);
    }

    private static final double KEY_WIDTH = 64;

    private static HBox fact(String key, String value) {
        Label name = new Label(key);
        name.getStyleClass().add("fetch-key");
        name.setMinWidth(KEY_WIDTH);
        Label text = new Label(value);
        text.getStyleClass().add("fetch-value");
        text.setMinWidth(0);
        text.setTooltip(new Tooltip(value));
        HBox row = new HBox(10, name, text);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Region gap() {
        Region gap = new Region();
        gap.setMinHeight(6);
        return gap;
    }

    /**
     * Everything on this panel except the rate. Two readings with the same one draw the same picture.
     */
    private static String signature(Identity identity) {
        StringBuilder text = new StringBuilder(identity.reachedAt());
        for (Link link : identity.links()) {
            text.append('|').append(link.name()).append(link.state()).append(link.mtu()).append(link.mac());
            for (Link.Address address : link.addresses()) {
                text.append(',').append(address.cidr());
            }
        }
        for (Route route : identity.routes()) {
            text.append('|').append(route.destination()).append(route.gateway()).append(route.device());
        }
        text.append('|').append(identity.resolvers().servers())
                .append(identity.resolvers().searchDomains()).append(identity.resolvers().source());
        return text.toString();
    }

    private void writeSpeeds(Map<String, Counter.Rate> rates) {
        for (var entry : speeds.entrySet()) {
            Counter.Rate rate = rates.get(entry.getKey());
            entry.getValue().setText(rate == null ? "" : speed(rate));
            entry.getValue().setVisible(rate != null);
            entry.getValue().setManaged(rate != null);
        }
    }

    private static String describe(Route route) {
        String gateway = route.gateway().isBlank() ? "" : "via " + route.gateway() + " ";
        return gateway + "on " + route.device();
    }

    /** Bytes per second, in the unit a reader can hold in their head. */
    static String speed(Counter.Rate rate) {
        return "↓ " + perSecond(rate.received()) + "  ↑ " + perSecond(rate.sent());
    }

    private static String perSecond(double bytes) {
        if (bytes < 1024) {
            return String.format(java.util.Locale.ROOT, "%.0f B/s", bytes);
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB/s", bytes / 1024);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB/s", bytes / (1024 * 1024));
    }
}
