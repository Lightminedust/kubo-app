package com.kubo.ui.packages;

import com.kubo.model.packages.AptSource;
import com.kubo.model.packages.SigningKey;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

/**
 * Where this machine gets its software, and what it checks the signatures against.
 *
 * <p>Everything installed came from one of these, so the list is really the list of who is allowed to put
 * software on the server. A repository added years ago for one tool is still being asked every time, and is
 * still allowed to ship whatever it likes. Nobody looks at this file until something breaks.
 *
 * <p>The keys are here rather than on their own screen because they are the same subject seen from the other
 * end: when one expires, the repository it belongs to silently stops updating, and the only sign is an
 * {@code apt update} that fails on one line somebody scrolled past.
 */
final class SourcesCard extends VBox {

    /** How the card reaches the server. */
    interface Hands {

        /**
         * Comments a source line out, or takes the comment off.
         *
         * @param done called on the UI thread with null on success, or what went wrong
         */
        void switchSource(AptSource source, boolean enable, Consumer<String> done);

        /** Writes a new source as its own file under sources.list.d. */
        default void addSource(String name, String uri, String suite, String components, String signedBy,
                               Consumer<String> done) {
            done.accept("This screen was opened without a way to write.");
        }
    }

    private final Hands hands;
    private final LocalDate today;
    private final List<AptSource> sources;
    private final Label status = new Label();
    private final VBox form = new VBox(10);

    SourcesCard(List<AptSource> sources, List<SigningKey> keys, LocalDate today, long cacheBytes,
                Hands hands, Runnable onClose) {
        this.hands = hands;
        this.today = today;
        this.sources = List.copyOf(sources);
        getStyleClass().addAll("float-card", "wide-card");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(14);
        setPadding(new Insets(24, 30, 24, 28));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox header = new HBox(12, label("WHERE THE SOFTWARE COMES FROM", "card-title"), spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        List<AptSource> programs = sources.stream().filter(source -> !source.sourceCode()).toList();
        long added = programs.stream().filter(source -> !source.fromTheDistribution()).count();
        VBox body = new VBox(14);
        body.getChildren().add(lede(programs.size() + (programs.size() == 1 ? " place" : " places")
                + " apt is allowed to fetch from" + (added == 0 ? ", all of them the distribution's own."
                        : ", " + added + " of them added to this machine afterwards.")));
        body.getChildren().add(note("Everything installed here came from one of these, so this is the list of "
                + "who may put software on the server."
                + (cacheBytes <= 0 ? ""
                        : "  The downloaded index and package cache take "
                                + com.kubo.model.packages.PackageSheet.bytes(cacheBytes)
                                + "; CLEAN CACHE gives that back.")));

        Button add = new Button("+ ADD A SOURCE");
        add.getStyleClass().addAll("rail-button", "compact-button");
        add.setFocusTraversable(false);
        add.setOnAction(event -> showForm());
        form.setVisible(false);
        form.setManaged(false);
        body.getChildren().addAll(add, form);

        // Only keys apt would actually check against. The retired ones Debian keeps for the archive are not
        // a problem to be solved, and listing twenty of them from 2005 hides the one that matters.
        List<SigningKey> trouble = keys.stream().filter(SigningKey::inUse)
                .filter(key -> key.expiredOn(today) || key.expiringSoon(today)).toList();
        if (!trouble.isEmpty()) {
            VBox box = new VBox(6, label("A SIGNING KEY NEEDS ATTENTION", "series"));
            for (SigningKey key : trouble) {
                box.getChildren().add(note(key.identity() + "  ·  " + key.shortId()));
                box.getChildren().add(label(key.note(today), key.expiredOn(today)
                        ? "card-problem" : "card-warning"));
                // Which repository it belongs to, which is what somebody has to go and fix. There is no
                // command that renews a key: each publisher hands theirs out from their own address, so the
                // useful thing Kubo can do is say whose it is and let their instructions do the rest.
                String uses = uses(key);
                box.getChildren().add(note(key.keyring()
                        + (uses.isEmpty() ? "   ·   no source here names this keyring"
                                : "   ·   used by " + uses)));
            }
            box.getStyleClass().add("lockout-box");
            box.setPadding(new Insets(12, 14, 12, 14));
            body.getChildren().add(box);
        }

        body.getChildren().addAll(label("THE DISTRIBUTION'S OWN", "series"),
                note("What this machine is built out of. Switching one of these off stops it receiving the "
                        + "updates it was installed with, security ones included."));
        body.getChildren().add(rows(programs.stream().filter(AptSource::fromTheDistribution).toList()));

        List<AptSource> third = programs.stream().filter(source -> !source.fromTheDistribution()).toList();
        body.getChildren().addAll(label("ADDED AFTERWARDS  ·  " + third.size(), "series"),
                note(third.isEmpty() ? "Nothing was added to this machine."
                        : "Each of these was added for something. One that no longer has a reason is one more "
                                + "place that can ship a package to this server."));
        if (!third.isEmpty()) {
            body.getChildren().add(rows(third));
        }

        List<AptSource> code = sources.stream().filter(AptSource::sourceCode).toList();
        if (!code.isEmpty()) {
            body.getChildren().addAll(label("SOURCE CODE  ·  " + code.size(), "series"),
                    note("Nothing on this machine came from these. They exist for building packages, and can "
                            + "be switched off on a server that does not."));
            body.getChildren().add(rows(code));
        }

        if (keys.isEmpty()) {
            body.getChildren().add(note("Kubo could not read any signing key. gpg may not be installed, or the "
                    + "keyrings may be readable only by root. This is not the same as there being none."));
        }

        status.getStyleClass().add("card-note");
        status.setWrapText(true);
        status.setMaxWidth(760);
        status.setMinHeight(Region.USE_PREF_SIZE);
        status.setVisible(false);
        status.setManaged(false);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefViewportHeight(460);
        scroll.setMaxWidth(880);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(header, scroll, status);
    }

    /**
     * The form that writes a new source.
     *
     * <p>Its own file under sources.list.d, named by the operator, so switching it off or deleting it later
     * touches nothing anybody else wrote. Every field is checked before a command exists, and the line itself
     * is written through standard input rather than built into one.
     */
    private void showForm() {
        TextField name = field("docker", 14);
        TextField uri = field("https://download.docker.com/linux/debian", 34);
        TextField suite = field("bookworm", 14);
        TextField components = field("stable", 16);
        TextField keyring = field("/etc/apt/keyrings/docker.gpg", 30);

        Button write = new Button("WRITE IT");
        write.getStyleClass().addAll("primary-button", "compact-button");
        write.setFocusTraversable(false);
        write.setOnAction(event -> {
            write.setDisable(true);
            hands.addSource(name.getText(), uri.getText(), suite.getText(), components.getText(),
                    keyring.getText(), trouble -> {
                        write.setDisable(false);
                        if (trouble == null) {
                            form.setVisible(false);
                            form.setManaged(false);
                            report("Written. Read the index again for apt to use it.", false);
                        } else {
                            report(trouble, true);
                        }
                    });
        });
        Button cancel = new Button("CANCEL");
        cancel.getStyleClass().addAll("ghost-button", "compact-button");
        cancel.setFocusTraversable(false);
        cancel.setOnAction(event -> {
            form.setVisible(false);
            form.setManaged(false);
        });

        form.getChildren().setAll(
                note("A new source goes in its own file under /etc/apt/sources.list.d, so it can be switched "
                        + "off or thrown away later without touching anything else."),
                row("NAME FOR THE FILE", name),
                row("ADDRESS", uri),
                row("SUITE", suite),
                row("COMPONENTS", components),
                row("KEYRING", keyring),
                note("Without a keyring, apt refuses anything this source offers. The publisher says where to "
                        + "get theirs; Kubo does not fetch keys for you."),
                new HBox(10, write, cancel));
        form.getStyleClass().setAll("server-card");
        form.setPadding(new Insets(12, 14, 12, 14));
        form.setVisible(true);
        form.setManaged(true);
    }

    private static HBox row(String name, TextField field) {
        Label label = label(name, "metric-label");
        label.setMinWidth(180);
        HBox row = new HBox(10, label, field);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static TextField field(String hint, int columns) {
        TextField field = new TextField();
        field.getStyleClass().add("card-field");
        field.setPromptText(hint);
        field.setPrefColumnCount(columns);
        return field;
    }

    /** Which repositories point at this keyring, by name. */
    private String uses(SigningKey key) {
        List<String> hosts = sources.stream()
                .filter(source -> source.signedBy().equals(key.keyring()))
                .map(AptSource::host).distinct().toList();
        return String.join(", ", hosts);
    }

    private VBox rows(List<AptSource> sources) {
        VBox rows = new VBox(4);
        if (sources.isEmpty()) {
            rows.getChildren().add(note("None."));
            return rows;
        }
        for (AptSource source : sources) {
            rows.getChildren().add(row(source));
        }
        return rows;
    }

    private VBox row(AptSource source) {
        Label where = label(source.host(), "log-command");
        where.setMinWidth(260);
        Label what = label(source.suites() + (source.components().isBlank() ? ""
                : "  " + source.components()), "mono-muted");
        Label state = label(source.enabled() ? "ON" : "OFF",
                source.enabled() ? "card-success" : "card-note");
        state.setMinWidth(48);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button toggle = new Button(source.enabled() ? "SWITCH OFF" : "SWITCH ON");
        toggle.getStyleClass().addAll(source.enabled() && source.fromTheDistribution()
                ? "danger-quiet" : "rail-button", "compact-button");
        toggle.setFocusTraversable(false);
        if (source.deb822()) {
            // Commenting a line does not switch off a deb822 paragraph; that needs Enabled: no, which is a
            // different edit. Saying so beats offering a button that would quietly do nothing.
            toggle.setDisable(true);
            toggle.setText("EDIT THE FILE");
            toggle.setTooltip(new javafx.scene.control.Tooltip(
                    "This one is written in the newer format, which Kubo reads but does not switch. "
                            + "Its file is " + source.file() + "."));
        } else {
            toggle.setOnAction(event -> {
                toggle.setDisable(true);
                hands.switchSource(source, !source.enabled(), trouble -> {
                    if (trouble == null) {
                        state.getStyleClass().setAll("label", "card-note");
                        state.setText(source.enabled() ? "OFF" : "ON");
                        report("Changed. Read the index again for apt to notice.", false);
                    } else {
                        toggle.setDisable(false);
                        report(trouble, true);
                    }
                });
            });
        }

        HBox line = new HBox(10, where, state, what, spacer, toggle);
        line.setAlignment(Pos.CENTER_LEFT);
        Label origin = label(source.file() + "  line " + source.line()
                + (source.signedBy().isBlank() ? "   ·   no keyring named"
                        : "   ·   signed by " + source.signedBy()), "metric-label");
        VBox box = new VBox(2, line, origin);
        box.setPadding(new Insets(4, 0, 4, 0));
        return box;
    }

    private void report(String text, boolean bad) {
        status.getStyleClass().setAll("label", bad ? "card-problem" : "card-note");
        status.setText(text);
        status.setVisible(true);
        status.setManaged(true);
    }

    private static Label lede(String text) {
        Label label = label(text, "lede");
        label.setWrapText(true);
        label.setMaxWidth(780);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label note(String text) {
        Label label = label(text, "card-note");
        label.setWrapText(true);
        label.setMaxWidth(780);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
