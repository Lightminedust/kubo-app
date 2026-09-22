package com.kubo.ui.network;

import com.kubo.model.network.Connection;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who is connected to this server right now.
 *
 * <p>The map shows the doors. This shows the people standing in them, which is the question asked during an
 * incident and the one the screen could not answer at all.
 *
 * <p>Calls coming in from the internet are listed first and separately from the rest. A connection from the
 * private network or one this server opened itself is ordinary; a stranger on a port is not.
 */
final class ConnectionsCard extends VBox {

    /** What the card can ask the screen to do with an address. */
    interface Hands {
        /** Bans the address in every jail that will take it. Null when fail2ban is not there. */
        void ban(String address);
    }

    ConnectionsCard(List<Connection> connections, Hands hands, boolean canBan, Runnable onClose) {
        getStyleClass().addAll("float-card", "help-card");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(14);
        setPadding(new Insets(24, 30, 24, 28));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox header = new HBox(12, label("OPEN CONNECTIONS", "card-title"), spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        List<Connection> inbound = connections.stream().filter(Connection::fromTheInternet).toList();
        List<Connection> others = connections.stream().filter(connection -> !connection.fromTheInternet()).toList();

        VBox body = new VBox(14,
                label("FROM THE INTERNET", "series"),
                summary(inbound, hands, canBan),
                table(inbound, hands, canBan),
                label("EVERYTHING ELSE", "series"),
                note("This server calling out, and calls from the private network."),
                table(others, hands, false));

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefViewportHeight(520);
        scroll.setMaxWidth(760);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(header, scroll);
    }

    /**
     * One line per remote address, with how many connections it holds.
     *
     * <p>Twenty connections from one address is a different thing from twenty addresses holding one each, and a
     * flat list hides which of the two is happening.
     */
    private Region summary(List<Connection> inbound, Hands hands, boolean canBan) {
        if (inbound.isEmpty()) {
            return note("Nobody from the internet is connected right now.");
        }
        Map<String, Integer> byAddress = new LinkedHashMap<>();
        for (Connection connection : inbound) {
            byAddress.merge(connection.remoteAddress(), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(byAddress.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());

        VBox rows = new VBox(6);
        for (Map.Entry<String, Integer> entry : sorted) {
            Label address = label(entry.getKey(), "mono");
            Label count = label(entry.getValue() == 1 ? "1 connection" : entry.getValue() + " connections",
                    "mono-muted");
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox row = new HBox(12, address, gap, count);
            row.setAlignment(Pos.CENTER_LEFT);
            if (canBan && hands != null) {
                Button ban = new Button("BAN");
                ban.getStyleClass().addAll("danger-button", "compact-button");
                ban.setFocusTraversable(false);
                ban.setOnAction(event -> hands.ban(entry.getKey()));
                row.getChildren().add(ban);
            }
            rows.getChildren().add(row);
        }
        return rows;
    }

    private Region table(List<Connection> connections, Hands hands, boolean canBan) {
        if (connections.isEmpty()) {
            return note("Nothing here.");
        }
        GridPane grid = new GridPane();
        grid.setHgap(18);
        grid.setVgap(5);
        grid.add(label("FROM", "metric-label"), 0, 0);
        grid.add(label("TO", "metric-label"), 1, 0);
        grid.add(label("SERVICE", "metric-label"), 2, 0);
        grid.add(label("PROCESS", "metric-label"), 3, 0);

        int row = 1;
        for (Connection connection : connections) {
            String from = connection.inbound()
                    ? connection.remoteAddress() + ":" + connection.remotePort()
                    : connection.localAddress() + ":" + connection.localPort();
            String to = connection.inbound()
                    ? connection.localAddress() + ":" + connection.localPort()
                    : connection.remoteAddress() + ":" + connection.remotePort();
            Label fromLabel = label(from, "mono");
            fromLabel.setMinWidth(200);
            grid.add(fromLabel, 0, row);
            grid.add(label(to, "mono-muted"), 1, row);
            grid.add(label(connection.protocol() + " " + connection.servicePort(), "mono-muted"), 2, row);
            grid.add(label(connection.named() ? connection.process() : "not named without root", "mono-muted"),
                    3, row);
            row++;
        }
        return grid;
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
