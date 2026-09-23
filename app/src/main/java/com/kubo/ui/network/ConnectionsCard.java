package com.kubo.ui.network;

import com.kubo.model.network.Conversation;
import com.kubo.model.network.Naming;
import com.kubo.model.network.Traffic;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Who is talking to this server right now, gathered under the service each conversation is on.
 *
 * <p>The flat list was right for a quiet server and unreadable for a busy one: nine addresses on 443, four
 * containers on 6379, all in one column. The question someone opens this card with is almost always "who is on
 * 443", so 443 is the row, and the addresses are folded under it.
 *
 * <p>Anything that came off the internet is open from the start. That is the row somebody opened this card to
 * act on, and it should not need a click to be seen.
 */
final class ConnectionsCard extends VBox {

    interface Hands {
        /** Bans an address, and reports with null once the server took it, or the reason it did not. */
        void ban(String address, java.util.function.Consumer<String> done);

        /** Looks up who owns an address, and hands back what the server printed. */
        void whois(String address, java.util.function.Consumer<String> done);
    }

    /** Which conversations the list is showing. */
    private enum Only {
        EVERYTHING("EVERYTHING"),
        INTERNET("FROM THE INTERNET"),
        INSIDE("INSIDE"),
        DOCKER("CONTAINERS");

        private final String label;

        Only(String label) {
            this.label = label;
        }

        boolean keeps(Conversation conversation) {
            return switch (this) {
                case EVERYTHING -> true;
                case INTERNET -> conversation.fromTheInternet();
                case INSIDE -> !conversation.fromTheInternet();
                case DOCKER -> conversation.where() == Naming.Place.DOCKER;
            };
        }
    }

    private final List<Conversation> conversations;
    private final Hands hands;
    private final boolean canBan;
    private final boolean canWhois;

    private final TextArea lookup = new TextArea();
    /** What the last lookup said, in one line above the raw answer. */
    private final Label lookupLine = new Label();
    /** Addresses banned: already, when the card opened, or from this card since. */
    private final java.util.Set<String> banned = new java.util.HashSet<>();
    /** Addresses a ban has been sent for and not answered yet. */
    private final java.util.Set<String> banning = new java.util.HashSet<>();
    /** What whois said about each address looked up, kept for the row. */
    private final java.util.Map<String, com.kubo.model.network.WhoisSummary> owners = new java.util.HashMap<>();
    private final com.kubo.ui.Pulse pulse = new com.kubo.ui.Pulse();
    private final TextField filter = new TextField();
    private final VBox list = new VBox(12);
    private final HBox pills = new HBox(6);
    private final Label tally = label("", "mono-muted");

    private Only only = Only.EVERYTHING;
    /** The services the reader opened or closed by hand, which survive a change of filter. */
    private final java.util.Set<String> opened = new java.util.HashSet<>();
    private final java.util.Set<String> closed = new java.util.HashSet<>();

    /**
     * @param canWhois false when the server has no whois, in which case the button is not offered at all
     */
    /**
     * @param alreadyBanned addresses fail2ban holds right now, so a row can say so before anybody bans it twice
     */
    ConnectionsCard(List<Conversation> conversations, Hands hands, boolean canBan, boolean canWhois,
                    java.util.Set<String> alreadyBanned, Runnable onClose) {
        this.conversations = List.copyOf(conversations);
        this.hands = hands;
        this.canBan = canBan;
        this.canWhois = canWhois;
        this.banned.addAll(alreadyBanned == null ? java.util.Set.of() : alreadyBanned);

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

        for (Only choice : Only.values()) {
            pills.getChildren().add(pill(choice));
        }
        pills.setAlignment(Pos.CENTER_LEFT);

        filter.setPromptText("an address, a port, a program");
        filter.setPrefColumnCount(18);
        filter.textProperty().addListener((observable, before, now) -> draw());

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox controls = new HBox(14, pills, gap, filter);
        controls.setAlignment(Pos.CENTER_LEFT);

        lookup.setEditable(false);
        lookup.setWrapText(false);
        lookup.getStyleClass().add("redis-diagnostic-output");
        lookup.setPrefRowCount(9);
        lookup.setVisible(false);
        lookup.setManaged(false);
        lookupLine.getStyleClass().add("mono");
        lookupLine.setWrapText(true);
        lookupLine.setMaxWidth(780);
        lookupLine.setVisible(false);
        lookupLine.setManaged(false);

        list.setMaxWidth(780);
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefViewportHeight(470);
        scroll.setPrefViewportWidth(790);
        scroll.setMaxWidth(800);
        // An address is never long enough to be worth a second scrollbar; a row that does not fit is clipped
        // at its right edge, where the least of it is.
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(header, controls, tally, scroll, pulse, lookupLine, lookup);
        draw();
    }

    /** Only for the previews: picks a filter the way a click would. */
    void showOnly(String label) {
        for (Only choice : Only.values()) {
            if (choice.label.equals(label)) {
                only = choice;
            }
        }
        draw();
    }

    private Button pill(Only choice) {
        Button button = new Button(choice.label);
        button.getStyleClass().add("service-chip");
        button.setFocusTraversable(false);
        button.setOnAction(event -> {
            only = choice;
            draw();
        });
        return button;
    }

    /**
     * Rebuilds the list for the filter and the search text. Cheap enough to do on every keystroke: this is a few
     * dozen rows, and nothing here asks the server anything.
     */
    private void draw() {
        for (javafx.scene.Node node : pills.getChildren()) {
            Button button = (Button) node;
            boolean active = button.getText().equals(only.label);
            button.getStyleClass().remove("service-chip-active");
            if (active) {
                button.getStyleClass().add("service-chip-active");
            }
        }

        String wanted = filter.getText() == null ? "" : filter.getText();
        List<Conversation> kept = new ArrayList<>();
        for (Conversation conversation : conversations) {
            if (only.keeps(conversation) && conversation.matches(wanted)) {
                kept.add(conversation);
            }
        }

        List<Traffic> services = Traffic.group(kept);
        int sockets = services.stream().mapToInt(Traffic::sockets).sum();
        tally.setText(services.isEmpty() ? "" : services.size() + (services.size() == 1 ? " service  ·  " : " services  ·  ")
                + sockets + (sockets == 1 ? " connection" : " connections"));

        list.getChildren().clear();
        if (services.isEmpty()) {
            list.getChildren().add(note(conversations.isEmpty()
                    ? "Nothing is connected right now."
                    : "Nothing here matches. Clear the filter to see everything again."));
            return;
        }
        for (Traffic service : services) {
            list.getChildren().add(block(service));
        }
    }

    /**
     * One service: what it is, who is on it, and the addresses when the reader wants them.
     */
    private VBox block(Traffic service) {
        String key = service.servicePort() + "|" + service.process() + "|" + service.inbound();
        // Open by default when it came off the internet, because that is the row somebody came here to act on.
        boolean open = opened.contains(key) || (service.fromTheInternet() && !closed.contains(key));

        Label name = label(service.service(), "mono");
        Label who = label(service.named()
                ? "held by " + service.process()
                : "the program could not be named: turn on ROOT", "card-note");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label count = label(service.sentence(), "mono-muted");

        Button fold = new Button(open ? "▾" : "▸");
        fold.getStyleClass().addAll("ghost-button", "compact-button");
        fold.setFocusTraversable(false);
        fold.setOnAction(event -> {
            if (open) {
                closed.add(key);
                opened.remove(key);
            } else {
                opened.add(key);
                closed.remove(key);
            }
            draw();
        });

        HBox head = new HBox(12, name, mark(service), spacer, count, fold);
        head.setAlignment(Pos.CENTER_LEFT);

        VBox block = new VBox(3, head, who);
        block.getStyleClass().add(service.fromTheInternet() ? "traffic-open" : "traffic-block");
        block.setPadding(new Insets(9, 12, 10, 12));
        if (open) {
            VBox rows = new VBox(8);
            rows.setPadding(new Insets(9, 0, 2, 0));
            for (Conversation party : service.parties()) {
                rows.getChildren().add(row(party));
            }
            block.getChildren().add(rows);
        }
        return block;
    }

    /** Which way the call went, as a mark rather than a sentence repeated on every row. */
    private static Label mark(Traffic service) {
        Label label = new Label(service.inbound() ? "CALLED IN" : "CALLS OUT");
        label.getStyleClass().addAll("net-chip", service.fromTheInternet() ? "net-chip-internet" : "net-chip-quiet");
        return label;
    }

    /**
     * One other end: its address, where that is, and what can be done about it.
     */
    private VBox row(Conversation party) {
        Label who = label(party.remoteAddress(), "mono");
        Label place = label(party.remotePlace(), "card-note");

        Label kind = new Label(party.mark());
        kind.getStyleClass().addAll("net-chip", switch (party.where()) {
            case INTERNET -> "net-chip-internet";
            case DOCKER -> "net-chip-docker";
            case LOOPBACK, THIS_SERVER, PRIVATE -> "net-chip-quiet";
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label count = label(party.count() == 1 ? "1" : String.valueOf(party.count()), "mono-muted");

        String address = party.remoteAddress();
        boolean isBanned = banned.contains(address);
        if (isBanned) {
            who.getStyleClass().setAll("label", "chain-value-broken");
        }
        HBox row = new HBox(10, kind, who, place, spacer, count);
        row.setAlignment(Pos.CENTER_LEFT);
        // What whois said, on a line of its own under the address, so it never squeezes the buttons.
        VBox block = new VBox(2, row);
        com.kubo.model.network.WhoisSummary owner = owners.get(address);
        if (owner != null) {
            Label whose = label(owner.line(), "card-note");
            whose.setWrapText(true);
            whose.setMaxWidth(640);
            VBox.setMargin(whose, new Insets(0, 0, 0, 96));
            block.getChildren().add(whose);
        }

        // Only a public address has an owner to look up. A loopback, a container or a private network has no
        // record anywhere, and asking gave a page of "reserved" that looked like an answer.
        if (canWhois && hands != null && party.where() == Naming.Place.INTERNET) {
            // Before a ban, not after. An address is a number until somebody says whose it is.
            Button lookUp = new Button("WHO IS");
            lookUp.getStyleClass().addAll("ghost-button", "compact-button");
            lookUp.setFocusTraversable(false);
            lookUp.setOnAction(event -> whois(address));
            row.getChildren().add(lookUp);
        }
        if (canBan && hands != null && party.fromTheInternet()) {
            row.getChildren().add(banMark(address, isBanned));
        }
        return block;
    }

    /**
     * BAN, or what came of it. The address turns red and the button becomes BANNED once the server has taken
     * the ban, and not before: a click is not a ban until fail2ban says it is.
     */
    private javafx.scene.Node banMark(String address, boolean isBanned) {
        if (isBanned) {
            Label mark = new Label("BANNED");
            mark.getStyleClass().addAll("net-chip", "net-chip-internet");
            return mark;
        }
        Button ban = new Button(banning.contains(address) ? "BANNING…" : "BAN");
        ban.getStyleClass().add("danger-quiet");
        ban.setFocusTraversable(false);
        ban.setDisable(banning.contains(address));
        ban.setOnAction(event -> {
            banning.add(address);
            draw();
            hands.ban(address, trouble -> {
                banning.remove(address);
                if (trouble == null) {
                    banned.add(address);
                } else {
                    say(address + " was not banned: " + trouble, true);
                }
                draw();
            });
        });
        return ban;
    }

    private void whois(String address) {
        lookup.setText("");
        lookup.setVisible(true);
        lookup.setManaged(true);
        say("Asking who owns " + address + "…", false);
        pulse.setRunning(true);
        hands.whois(address, answer -> {
            pulse.setRunning(false);
            lookup.setText(answer);
            com.kubo.model.network.WhoisSummary summary = com.kubo.model.network.WhoisSummary.parse(answer);
            owners.put(address, summary);
            say(address + "  ·  " + summary.line(), false);
            draw();
        });
    }

    /** One line above the lookup: what it found, or what went wrong. */
    private void say(String text, boolean problem) {
        lookupLine.setText(text);
        lookupLine.getStyleClass().setAll("label", problem ? "card-problem" : "mono");
        lookupLine.setVisible(true);
        lookupLine.setManaged(true);
    }

    /** Only for the previews: a lookup already answered, and an address already banned. */
    void showForPreview(String lookedUp, String answer, String bannedAddress) {
        if (bannedAddress != null) {
            banned.add(bannedAddress);
        }
        if (lookedUp != null) {
            lookup.setText(answer);
            lookup.setVisible(true);
            lookup.setManaged(true);
            com.kubo.model.network.WhoisSummary summary = com.kubo.model.network.WhoisSummary.parse(answer);
            owners.put(lookedUp, summary);
            say(lookedUp + "  ·  " + summary.line(), false);
        }
        draw();
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
