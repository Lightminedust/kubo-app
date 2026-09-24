package com.kubo.ui.packages;

import com.kubo.model.packages.PackageEntry;
import com.kubo.ui.InstallReadout;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Any apt job worth watching: what it is about to do, then what it is doing.
 *
 * <p>Every one of these used to be a word typed into a prompt and then a blank screen for however long apt
 * took. On a server with forty waiting packages that is minutes of downloading with no way to tell a slow
 * mirror from a hung command.
 *
 * <p>One card rather than one per action, because they differ in exactly two places: what is worth showing
 * beforehand, and whether a word has to be typed first. Reading the index shows nothing and asks nothing;
 * an upgrade lists the versions; installing and removing show what apt said it would do when asked to
 * pretend. Everything after the confirmation is identical, and is the readout the tools card uses.
 */
final class AptWorkCard extends VBox {

    /** How the card reaches the server. */
    interface Hands {

        /**
         * @param onLine called on a reader thread, never the UI thread
         * @param done   called on the UI thread with null on success, or what went wrong
         */
        void run(Consumer<String> onLine, Consumer<String> done);
    }

    /** Longer than this and the list stops being something anybody reads. */
    private static final int LISTED = 40;

    private final Runnable onClose;
    private final InstallReadout readout;
    private final Label status = new Label();
    private final VBox confirmRow = new VBox(8);
    private final Button close = new Button("✕");
    private final Hands hands;

    /**
     * @param what    one sentence under the title, saying plainly what this will do
     * @param before  what is worth reading before it starts, or null for nothing
     * @param confirm the word that has to be typed, or null to start the moment the card opens
     */
    AptWorkCard(String title, String what, Node before, String confirm, boolean danger,
                Hands hands, Runnable onClose) {
        this.hands = hands;
        this.onClose = onClose;
        this.readout = new InstallReadout(title.toLowerCase(java.util.Locale.ROOT), "apt");
        getStyleClass().addAll("float-card", "wide-card");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(14);
        setPadding(new Insets(24, 30, 24, 28));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox header = new HBox(12, label(title, "card-title"), spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        status.getStyleClass().add("card-note");
        status.setWrapText(true);
        status.setMaxWidth(760);
        status.setMinHeight(Region.USE_PREF_SIZE);
        status.setVisible(false);
        status.setManaged(false);

        readout.setVisible(false);
        readout.setManaged(false);
        getChildren().addAll(header, note(what));

        if (before != null) {
            // Only this scrolls. A long list used to push the button that starts the job off the bottom of the
            // card, which is the one part of it that has to be reachable without hunting for it.
            ScrollPane scroll = new ScrollPane(before);
            scroll.setFitToWidth(true);
            scroll.getStyleClass().add("plain-scroll");
            scroll.setPrefViewportHeight(300);
            scroll.setMaxWidth(860);
            VBox.setVgrow(scroll, Priority.ALWAYS);
            getChildren().add(scroll);
        }
        getChildren().addAll(readout, confirmRow, status);

        if (confirm == null) {
            start();
        } else {
            askToConfirm(confirm, danger);
        }
    }

    /** The word to type, which is the guard this screen uses for anything that writes. */
    private void askToConfirm(String word, boolean danger) {
        TextField typed = new TextField();
        typed.getStyleClass().add("card-field");
        typed.setPromptText(word);
        typed.setPrefColumnCount(Math.max(8, word.length() + 2));
        Button go = new Button(word);
        go.getStyleClass().addAll(danger ? "danger-button" : "primary-button", "compact-button");
        go.setFocusTraversable(false);
        go.setDisable(true);
        typed.textProperty().addListener((observable, before, now) ->
                go.setDisable(!now.strip().equalsIgnoreCase(word)));
        go.setOnAction(event -> start());
        Button cancel = new Button("CANCEL");
        cancel.getStyleClass().addAll("ghost-button", "compact-button");
        cancel.setFocusTraversable(false);
        cancel.setOnAction(event -> onClose.run());

        HBox line = new HBox(10, label("TYPE " + word + " TO CONFIRM", "metric-label"), typed, go, cancel);
        line.setAlignment(Pos.CENTER_LEFT);
        confirmRow.getChildren().setAll(line);
    }

    /** Hands over to the server; from here the card only reports what comes back. */
    private void start() {
        confirmRow.setVisible(false);
        confirmRow.setManaged(false);
        readout.setVisible(true);
        readout.setManaged(true);
        readout.start();
        // The close button stays live. The command runs on the server, not in this card, so closing it is
        // walking away from a window rather than cancelling anything, and a card that cannot be closed for
        // three minutes is a card in the way.
        report("Working. You can close this and come back; it runs on the server either way, and the screen "
                + "says when it is done.", false);
        hands.run(
                line -> Platform.runLater(() -> readout.accept(line)),
                trouble -> {
                    readout.finish(trouble == null);
                    report(trouble == null ? "Done." : trouble, trouble != null);
                });
    }

    private void report(String text, boolean bad) {
        status.getStyleClass().setAll("label", bad ? "card-problem" : "card-note");
        status.setText(text);
        status.setVisible(true);
        status.setManaged(true);
    }

    // What goes above the confirmation, per job.

    /**
     * The waiting versions, security first: they are why somebody opened this, and they are what should still
     * be on screen when the list is too long to read to the end.
     */
    static VBox waitingList(List<PackageEntry> waiting) {
        List<PackageEntry> ordered = waiting.stream()
                .sorted(Comparator.comparing((PackageEntry entry) -> !entry.security())
                        .thenComparing(PackageEntry::name))
                .toList();
        VBox rows = new VBox(2);
        if (ordered.isEmpty()) {
            rows.getChildren().add(note("There is nothing waiting. Read the index again if you expected "
                    + "something: apt only knows what its last update told it."));
            return rows;
        }
        for (PackageEntry entry : ordered.subList(0, Math.min(LISTED, ordered.size()))) {
            Label name = label(entry.name(), "log-command");
            name.setMinWidth(220);
            Label from = label(entry.version(), "mono-muted");
            from.setMinWidth(160);
            Label to = label(entry.candidate(), "card-success");
            to.setMinWidth(160);
            HBox row = new HBox(10, name, from, label("→", "mono-muted"), to);
            row.setAlignment(Pos.CENTER_LEFT);
            if (entry.security()) {
                row.getChildren().add(label("SECURITY", "log-mark-root"));
            }
            rows.getChildren().add(row);
        }
        if (ordered.size() > LISTED) {
            rows.getChildren().add(note("… and " + (ordered.size() - LISTED) + " more. Apt names every one of "
                    + "them below as it works through them."));
        }
        return rows;
    }

    /** How many are waiting and how many of those are security, for the sentence under the title. */
    static String waitingHeadline(List<PackageEntry> waiting) {
        long security = waiting.stream().filter(PackageEntry::security).count();
        if (waiting.isEmpty()) {
            return "Nothing is waiting.";
        }
        String line = waiting.size() + (waiting.size() == 1 ? " package is waiting." : " packages are waiting.");
        return security == 0 ? line
                : line + " " + security + (security == 1 ? " of them is a security update."
                        : " of them are security updates.");
    }

    /** Only for the previews: the card partway through, with apt's own lines already in. */
    void showForPreview(List<String> printed, boolean finished, boolean worked) {
        confirmRow.setVisible(false);
        confirmRow.setManaged(false);
        readout.setVisible(true);
        readout.setManaged(true);
        readout.showForPreview(printed, finished, worked);
        close.setDisable(!finished);
        report(!finished ? "Apt is working. Leaving this card open is fine; it runs on the server either way."
                : worked ? "Done." : "Apt refused to finish. Its own words are above.", finished && !worked);
    }

    private static Label note(String text) {
        Label label = label(text, "card-note");
        label.setWrapText(true);
        label.setMaxWidth(760);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
