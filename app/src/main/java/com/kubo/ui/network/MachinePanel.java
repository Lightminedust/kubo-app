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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;

/**
 * Where this server sits on the network: its interfaces and addresses, what leaves by which route, the resolvers
 * it asks, and how much is moving through each interface right now.
 *
 * <p>It sits beside the map instead of behind a button because it is the answer to "which machine am I looking
 * at", and that question comes before every other one on this screen.
 */
final class MachinePanel extends VBox {

    private final VBox links = new VBox(10);
    private final VBox routing = new VBox(4);
    private final VBox dns = new VBox(4);
    private final Label reached = label("", "mono");
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
    private final java.util.Map<String, Label> speeds = new java.util.LinkedHashMap<>();
    private final com.kubo.ui.Pulse pulse = new com.kubo.ui.Pulse();

    MachinePanel() {
        VBox column = new VBox(12,
                label("THE MACHINE", "series"), pulse,
                caption("REACHED AT"), reached,
                caption("INTERFACES"), links,
                caption("ROUTING"), routing,
                caption("DNS"), dns);
        column.setPadding(new Insets(16, 20, 16, 24));

        ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("redis-inspector");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().add(scroll);
        getStyleClass().add("redis-inspector");
        setPrefWidth(320);
        setMinWidth(280);
        show(Identity.empty(), Map.of(), Naming.none());
    }

    /**
     * @param rates bytes per second per interface, empty on the first read of a session
     */
    void show(Identity identity, Map<String, Counter.Rate> rates, Naming naming) {
        this.identity = identity;
        this.rates = rates;
        this.naming = naming;
        reached.setText(identity.reachedAt().isBlank() ? "unknown" : identity.reachedAt());

        // The rate is the only thing that really moves between two readings. When nothing else changed, only
        // the rate is written, and the panel stops flickering.
        String signature = signature(identity);
        if (signature.equals(drawnFrom)) {
            writeSpeeds(rates);
            return;
        }
        drawnFrom = signature;
        speeds.clear();

        links.getChildren().clear();
        List<Link> real = identity.realLinks().stream().filter(link -> !Naming.virtual(link)).toList();
        List<Link> virtual = identity.realLinks().stream().filter(Naming::virtual).toList();
        if (identity.links().isEmpty()) {
            links.getChildren().add(note("No interface could be read. Kubo needs the ip command."));
        }
        for (Link link : real) {
            links.getChildren().add(linkBlock(link, rates.get(link.name())));
        }
        if (!virtual.isEmpty()) {
            // Folded by default. On a server running twenty containers these are forty interfaces, and none of
            // them is the one somebody opened this panel to find.
            Button fold = new Button(showVirtual
                    ? "HIDE THE " + virtual.size() + " CONTAINER INTERFACES"
                    : "SHOW " + virtual.size() + " CONTAINER INTERFACES");
            fold.getStyleClass().addAll("ghost-button", "compact-button");
            fold.setFocusTraversable(false);
            fold.setOnAction(event -> {
                showVirtual = !showVirtual;
                show(this.identity, this.rates, this.naming);
            });
            links.getChildren().add(fold);
            if (showVirtual) {
                for (Link link : virtual) {
                    links.getChildren().add(linkBlock(link, rates.get(link.name())));
                }
            }
        }

        routing.getChildren().clear();
        identity.defaultRoute().ifPresentOrElse(
                route -> routing.getChildren().add(row("default", describe(route))),
                () -> routing.getChildren().add(note("No default route. This server reaches its own network only.")));
        for (Route route : identity.routes()) {
            if (!route.isDefault() && !route.device().isBlank()) {
                routing.getChildren().add(row(route.destination(), "via " + route.device()));
            }
        }

        dns.getChildren().clear();
        if (!identity.resolvers().known()) {
            dns.getChildren().add(note("No resolver could be read."));
        } else {
            for (String server : identity.resolvers().servers()) {
                dns.getChildren().add(row(server, ""));
            }
            if (!identity.resolvers().searchDomains().isEmpty()) {
                dns.getChildren().add(row("search", String.join(" ", identity.resolvers().searchDomains())));
            }
            dns.getChildren().add(note("Read from " + identity.resolvers().source() + "."));
        }
    }

    /** Says the panel is waiting on the server, in the panel. */
    void setReading(boolean reading) {
        pulse.setRunning(reading);
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
        }
    }

    private static String describe(Route route) {
        String gateway = route.gateway().isBlank() ? "" : "via " + route.gateway() + " ";
        return gateway + "on " + route.device();
    }

    private VBox linkBlock(Link link, Counter.Rate rate) {
        Label name = label(link.name(), "card-title");
        Label state = label(link.up() ? "UP" : "DOWN", link.up() ? "card-success" : "card-problem");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label speed = label(rate == null ? "" : speed(rate), "mono-muted");
        speeds.put(link.name(), speed);
        HBox head = new HBox(10, name, state, spacer, speed);
        head.setAlignment(Pos.CENTER_LEFT);

        VBox block = new VBox(3, head);
        // What this interface is for, in words. eth0 means nothing to somebody who did not name it.
        String role = naming.role(link);
        if (!role.isEmpty()) {
            block.getChildren().add(note(role));
        }
        for (Link.Address address : link.addresses()) {
            // A public address is the one that matters on this screen, so it is the one that is marked.
            boolean open = address.exposure() == ListeningPort.Exposure.PUBLIC;
            block.getChildren().add(row(address.cidr(), open ? "reachable" : ""));
        }
        if (link.addresses().isEmpty()) {
            block.getChildren().add(note("No address."));
        }
        if (link.mtu() != Link.UNKNOWN_MTU || !link.mac().isBlank()) {
            String detail = (link.mac().isBlank() ? "" : link.mac() + "  ")
                    + (link.mtu() == Link.UNKNOWN_MTU ? "" : "MTU " + link.mtu());
            block.getChildren().add(note(detail.strip()));
        }
        return block;
    }

    /** Bytes per second, in the unit a reader can hold in their head. */
    static String speed(Counter.Rate rate) {
        return "↓ " + perSecond(rate.received()) + "   ↑ " + perSecond(rate.sent());
    }

    private static String perSecond(double bytes) {
        if (bytes < 1024) {
            return String.format(java.util.Locale.ROOT, "%.0f B/s", bytes);
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f kB/s", bytes / 1024);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB/s", bytes / (1024 * 1024));
    }

    private static HBox row(String left, String right) {
        Label name = label(left, "mono");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(10, name, spacer, label(right, "mono-muted"));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Label caption(String text) {
        return label(text, "metric-label");
    }

    private static Label note(String text) {
        Label label = label(text, "card-note");
        label.setWrapText(true);
        label.setMaxWidth(280);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
