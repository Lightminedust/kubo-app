package com.kubo.ui.network;

import com.kubo.model.network.Conversation;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Who is talking to this server right now, in sentences rather than in sockets.
 *
 * <p>An application holding a pool of connections opens twenty five of them and the raw list printed twenty five
 * rows that differed only by an ephemeral port number. They are one fact, so they are one row here, with the
 * count beside it.
 */
final class ConnectionsCard extends VBox {

    interface Hands {
        void ban(String address);

        /** Looks up who owns an address, and hands back what the server printed. */
        void whois(String address, java.util.function.Consumer<String> done);
    }

    private final TextArea lookup = new TextArea();
    private final com.kubo.ui.Pulse pulse = new com.kubo.ui.Pulse();

    /**
     * @param canWhois false when the server has no whois, in which case the button is not offered at all
     */
    ConnectionsCard(List<Conversation> conversations, Hands hands, boolean canBan, boolean canWhois,
                    Runnable onClose) {
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
        HBox header = new HBox(12, label("WHO IS TALKING TO THIS SERVER", "card-title"), spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        List<Conversation> internet = conversations.stream().filter(Conversation::fromTheInternet).toList();
        List<Conversation> inside = conversations.stream()
                .filter(conversation -> !conversation.fromTheInternet()).toList();

        lookup.setEditable(false);
        lookup.setWrapText(false);
        lookup.getStyleClass().add("redis-diagnostic-output");
        lookup.setPrefRowCount(9);
        lookup.setVisible(false);
        lookup.setManaged(false);

        VBox body = new VBox(16,
                label("FROM THE INTERNET", "series"),
                block(internet, hands, canBan, canWhois, "Nobody from the internet is connected right now."),
                pulse, lookup,
                label("INSIDE THIS SERVER", "series"),
                note("Its own programs talking to each other, to its containers, or out to something else."),
                block(inside, hands, false, false, "Nothing is connected."));

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefViewportHeight(520);
        scroll.setMaxWidth(760);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(header, scroll);
    }

    private Region block(List<Conversation> conversations, Hands hands, boolean canBan, boolean canWhois,
                         String whenEmpty) {
        if (conversations.isEmpty()) {
            return note(whenEmpty);
        }
        VBox rows = new VBox(10);
        for (Conversation conversation : conversations) {
            rows.getChildren().add(row(conversation, hands, canBan, canWhois));
        }
        return rows;
    }

    /**
     * One conversation, read as a sentence: who, on what, how many times.
     */
    private VBox row(Conversation conversation, Hands hands, boolean canBan, boolean canWhois) {
        Label who = label(conversation.remote(), "mono");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label count = label(conversation.count() == 1 ? "1 connection" : conversation.count() + " connections",
                "mono-muted");

        HBox head = new HBox(12, who, spacer, count);
        head.setAlignment(Pos.CENTER_LEFT);
        if (canWhois && hands != null) {
            // Before a ban, not after. An address is a number until somebody says whose it is.
            Button owner = new Button("WHO IS");
            owner.getStyleClass().addAll("ghost-button", "compact-button");
            owner.setFocusTraversable(false);
            owner.setOnAction(event -> {
                lookup.setText("");
                lookup.setVisible(true);
                lookup.setManaged(true);
                pulse.setRunning(true);
                hands.whois(conversation.remoteAddress(), answer -> {
                    pulse.setRunning(false);
                    lookup.setText(answer);
                });
            });
            head.getChildren().add(owner);
        }
        if (canBan && hands != null) {
            Button ban = new Button("BAN");
            ban.getStyleClass().addAll("danger-button", "compact-button");
            ban.setFocusTraversable(false);
            ban.setOnAction(event -> hands.ban(conversation.remoteAddress()));
            head.getChildren().add(ban);
        }

        // The sentence under the address: which way the call went, to what, held by what.
        String direction = conversation.inbound() ? "reached" : "calls";
        String held = conversation.named()
                ? " held by " + conversation.process()
                : " (the program could not be named: turn on ROOT)";
        Label what = label(direction + " " + conversation.service() + held, "card-note");
        what.setWrapText(true);
        what.setMaxWidth(700);

        return new VBox(2, head, what);
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
