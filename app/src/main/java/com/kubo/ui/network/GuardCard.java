package com.kubo.ui.network;

import com.kubo.model.network.Certificate;
import com.kubo.model.network.Jail;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.util.List;

/**
 * The two things standing at the edge: fail2ban, and the certificates.
 *
 * <p>They are on one card because they answer the same question. A brute force that nothing bans and a
 * certificate nobody renewed both end the same way, with the site unreachable and no warning beforehand.
 */
final class GuardCard extends VBox {

    interface Hands {
        void ban(String jail, String address);

        void unban(String jail, String address);

        /** Looks up who owns an address, and hands back what the server printed. */
        default void whois(String address, java.util.function.Consumer<String> done) {
            done.accept("");
        }
    }

    private final Label status = new Label();
    private final VBox certificates = new VBox(8);

    /** What the last lookup said: one line, then the raw answer. */
    private final Label lookupLine = new Label();
    private final javafx.scene.control.TextArea lookup = new javafx.scene.control.TextArea();
    private boolean canWhois;

    /** Only when whois is on the server: an address becomes a thing to look up before a ban is lifted. */
    void setCanWhois(boolean canWhois) {
        this.canWhois = canWhois;
    }

    GuardCard(List<Jail> jails, Instant now, Hands hands, Runnable onClose) {
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
        HBox header = new HBox(12, label("THE EDGE", "card-title"), spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        status.getStyleClass().add("card-note");
        status.setWrapText(true);
        status.setVisible(false);
        status.setManaged(false);

        VBox body = new VBox(14, label("FAIL2BAN", "series"));
        if (jails.isEmpty()) {
            body.getChildren().add(note("No jail could be read. Either fail2ban is not installed, or reading it "
                    + "needs root and root is off."));
        } else {
            for (Jail jail : jails) {
                body.getChildren().add(jailBlock(jail, hands));
            }
        }

        // Certificates are read when this card opens rather than on every poll: they change once every few
        // months and the screen re-reads every twelve seconds.
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.getStyleClass().add("connection-progress");
        spinner.setPrefSize(14, 14);
        spinner.setMaxSize(14, 14);
        HBox reading = new HBox(10, spinner, label("READING THE CERTIFICATES", "card-note"));
        reading.setAlignment(Pos.CENTER_LEFT);
        certificates.getChildren().add(reading);

        lookupLine.getStyleClass().add("mono");
        lookupLine.setWrapText(true);
        lookupLine.setMaxWidth(640);
        lookupLine.setVisible(false);
        lookupLine.setManaged(false);
        lookup.setEditable(false);
        lookup.getStyleClass().add("redis-diagnostic-output");
        lookup.setPrefRowCount(7);
        lookup.setVisible(false);
        lookup.setManaged(false);
        body.getChildren().addAll(lookupLine, lookup);
        body.getChildren().addAll(label("CERTIFICATES", "series"), certificates);
        body.getChildren().add(status);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefViewportHeight(520);
        scroll.setMaxWidth(720);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(header, scroll);
    }

    /** Fills the certificates once they come back. */
    void showCertificates(List<Certificate> found, Instant now) {
        certificates.getChildren().setAll(certificateList(found, now));
    }

    /** What came of the last ban or unban, kept until the next one. */
    void report(String trouble, String done) {
        status.getStyleClass().setAll("label", trouble == null ? "card-success" : "card-problem");
        status.setText(trouble == null ? done : trouble);
        status.setVisible(true);
        status.setManaged(true);
    }

    private VBox jailBlock(Jail jail, Hands hands) {
        Label name = label(jail.name(), "card-title");
        Label counts = label(jail.currentlyBanned() + " banned now, " + jail.totalBanned() + " in total, "
                + jail.currentlyFailed() + " failing", "mono-muted");
        VBox block = new VBox(8, name, counts);

        if (jail.banned().isEmpty()) {
            block.getChildren().add(note("Nobody is banned in this jail."));
        } else {
            FlowPane banned = new FlowPane(8, 8);
            fillBanned(banned, jail, hands, "");
            // A jail that has been busy holds dozens. Past a dozen, a field narrows them down.
            if (jail.banned().size() > 12) {
                TextField filter = new TextField();
                filter.getStyleClass().add("card-field");
                filter.setPromptText("find an address among the " + jail.banned().size());
                filter.setPrefColumnCount(22);
                filter.textProperty().addListener((observable, before, typed) ->
                        fillBanned(banned, jail, hands, typed.strip()));
                block.getChildren().add(filter);
            }
            block.getChildren().add(banned);
        }

        TextField address = new TextField();
        address.getStyleClass().add("card-field");
        address.setPromptText("45.135.232.17");
        Button ban = new Button("BAN IN " + jail.name().toUpperCase(java.util.Locale.ROOT));
        ban.getStyleClass().addAll("danger-button", "compact-button");
        ban.setFocusTraversable(false);
        ban.setOnAction(event -> {
            String typed = address.getText().strip();
            if (!typed.isEmpty()) {
                hands.ban(jail.name(), typed);
            }
        });
        HBox row = new HBox(8, address, ban);
        row.setAlignment(Pos.CENTER_LEFT);
        block.getChildren().add(row);
        return block;
    }

    /**
     * One pill per banned address: the address, which asks who owns it, and a cross, which lifts the ban.
     *
     * <p>The whole pill used to lift the ban, so a click meant to read the address let it back in. Now the two
     * do different things, and the one that lets somebody back in is the small one.
     */
    private void fillBanned(FlowPane banned, Jail jail, Hands hands, String filter) {
        banned.getChildren().clear();
        for (String address : jail.banned()) {
            if (!filter.isEmpty() && !address.contains(filter)) {
                continue;
            }
            Button who = new Button(address);
            who.getStyleClass().addAll("banned-address");
            who.setFocusTraversable(false);
            who.setTooltip(new javafx.scene.control.Tooltip("Who owns " + address));
            who.setOnAction(event -> whois(hands, address));
            Button lift = new Button("✕");
            lift.getStyleClass().add("banned-lift");
            lift.setFocusTraversable(false);
            lift.setTooltip(new javafx.scene.control.Tooltip("Lift the ban on " + address));
            lift.setOnAction(event -> hands.unban(jail.name(), address));
            HBox pill = new HBox(0, who, lift);
            pill.getStyleClass().add("banned-pill");
            pill.setAlignment(Pos.CENTER_LEFT);
            banned.getChildren().add(pill);
        }
    }

    private void whois(Hands hands, String address) {
        lookupLine.setVisible(true);
        lookupLine.setManaged(true);
        if (!canWhois) {
            // Decided at the click rather than when the pills were drawn: whether whois is there is known a
            // moment after the card opens, not before.
            lookupLine.setText("whois is not installed on this server. It is one click in TOOLS.");
            return;
        }
        lookupLine.setText("Asking who owns " + address + "…");
        hands.whois(address, answer -> {
            com.kubo.model.network.WhoisSummary summary = com.kubo.model.network.WhoisSummary.parse(answer);
            lookupLine.setText(address + "  ·  " + summary.line());
            lookup.setText(answer);
            lookup.setVisible(true);
            lookup.setManaged(true);
        });
    }

    private Region certificateList(List<Certificate> certificates, Instant now) {
        if (certificates.isEmpty()) {
            return note("No certificate found. Either no Nginx server block uses TLS, or the configuration could "
                    + "not be read.");
        }
        VBox rows = new VBox(8);
        for (Certificate certificate : certificates) {
            Label file = label(certificate.file(), "mono");
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);

            String verdict = switch (certificate.urgency(now)) {
                case UNREAD -> "could not be read";
                case EXPIRED -> "EXPIRED " + Math.abs(certificate.daysLeft(now)) + " days ago";
                default -> certificate.daysLeft(now) + " days left";
            };
            String style = switch (certificate.urgency(now)) {
                case UNREAD, EXPIRED, URGENT -> "card-problem";
                case SOON -> "card-warning";
                case FINE -> "card-success";
            };

            HBox row = new HBox(12, file, gap, label(verdict, style));
            row.setAlignment(Pos.CENTER_LEFT);
            VBox block = new VBox(2, row);
            if (!certificate.subject().isBlank()) {
                block.getChildren().add(label(certificate.subject(), "mono-muted"));
            }
            if (!certificate.problem().isBlank()) {
                block.getChildren().add(note(certificate.problem()));
            }
            if (!certificate.caveat().isEmpty()) {
                Label caveat = note(certificate.caveat());
                caveat.getStyleClass().setAll("label", "card-warning");
                block.getChildren().add(caveat);
            }
            rows.getChildren().add(block);
        }
        return rows;
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
