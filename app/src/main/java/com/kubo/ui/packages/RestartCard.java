package com.kubo.ui.packages;

import com.kubo.model.packages.RestartNeeds;
import com.kubo.model.packages.ServiceRisk;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * What is still running on libraries that were replaced under it, and the smallest thing that fixes each.
 *
 * <p>The screen said RESTART REQUIRED, which is true and useless: it reads as "reboot this server", and on a
 * machine serving traffic that is a decision somebody puts off for weeks. Almost always the honest answer is
 * smaller, and this is where it is said.
 *
 * <p>Two things this card refuses to do. It does not present seventeen identical buttons, because a few of them
 * are not like the others and a flat list invites somebody to work down it without noticing. And it does not
 * put the kernel among them, because no service restart replaces a running kernel and a button there would be
 * promising something it cannot do.
 */
final class RestartCard extends VBox {

    /** How the card reaches the server. */
    interface Hands {

        /**
         * Restarts one systemd unit.
         *
         * @param done called on the UI thread with null on success, or what went wrong
         */
        void restart(String unit, Consumer<String> done);

        /** Reboots the whole machine, which is the only thing that changes a running kernel. */
        default void reboot(Consumer<String> done) {
            done.accept("This screen was opened without a way to reboot.");
        }
    }

    private final Hands hands;
    private final RestartNeeds needs;
    private final Label status = new Label();
    private final Label counter = new Label();
    private final Button batch = new Button();
    private final VBox confirmRow = new VBox(8);
    private final List<Row> rows = new ArrayList<>();
    private int left;

    RestartCard(RestartNeeds needs, Hands hands, Runnable onClose) {
        this.hands = hands;
        this.needs = needs;
        this.left = needs.services().size();
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
        counter.getStyleClass().add("mono-muted");
        HBox header = new HBox(12, label("WHAT IS STILL RUNNING ON OLD LIBRARIES", "card-title"),
                counter, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(12);
        body.getChildren().add(lede(needs.headline()));
        if (!needs.available()) {
            body.getChildren().addAll(
                    note("needrestart is what answers this, and it is not installed here. Without it Kubo can "
                            + "only repeat what the system file says, which is that a restart is due somewhere."),
                    note("To install it yourself:  apt-get install needrestart"));
        } else {
            body.getChildren().add(note("Upgrading a library writes a new file. Anything already running keeps "
                    + "using the old one, still in memory, until it is restarted. Until then the machine is "
                    + "patched on disk and not in fact."));
        }

        if (!needs.kernelNote().isEmpty()) {
            body.getChildren().add(kernelBlock());
        }

        List<String> ordinary = needs.services().stream().filter(ServiceRisk::safeToBatch).toList();
        List<String> careful = needs.services().stream().filter(unit -> !ServiceRisk.safeToBatch(unit)).toList();

        if (!careful.isEmpty()) {
            body.getChildren().addAll(label("THESE ARE NOT LIKE THE OTHERS  ·  " + careful.size(), "series"),
                    note("Each of these is something the machine, or your way into it, is standing on. They "
                            + "are asked about one at a time and never included in the batch below."));
            VBox list = new VBox(4);
            for (String unit : careful) {
                list.getChildren().add(add(new Row(unit)));
            }
            body.getChildren().add(list);
        }

        if (!ordinary.isEmpty()) {
            batch.setText("RESTART ALL " + ordinary.size());
            batch.getStyleClass().addAll("rail-button", "compact-button");
            batch.setFocusTraversable(false);
            batch.setOnAction(event -> runBatch(ordinary));
            HBox heading = new HBox(12, label("RESTART THESE  ·  " + ordinary.size(), "series"), batch);
            heading.setAlignment(Pos.CENTER_LEFT);
            body.getChildren().addAll(heading,
                    note("Each takes about a second, and drops whatever that service was doing at that "
                            + "instant. A busy one is worth doing when it suits you."));
            body.getChildren().add(grid(ordinary));
        }

        if (!needs.sessions().isEmpty()) {
            body.getChildren().addAll(label("LOGGED IN, AND IN THE SAME POSITION", "series"),
                    note("These are people's own sessions, and only they can end them. Yours is one of them "
                            + "if you are reading this over SSH."));
            VBox list = new VBox(2);
            for (String session : needs.sessions()) {
                list.getChildren().add(label(session, "mono-muted"));
            }
            body.getChildren().add(list);
        }

        status.getStyleClass().add("card-note");
        status.setWrapText(true);
        status.setMaxWidth(780);
        status.setMinHeight(Region.USE_PREF_SIZE);
        status.setVisible(false);
        status.setManaged(false);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setPrefViewportHeight(430);
        scroll.setMaxWidth(900);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(header, scroll, confirmRow, status);
        countDown(0);
    }

    /** The kernel, kept apart, with the only thing that actually changes it. */
    private VBox kernelBlock() {
        VBox box = new VBox(8, label("THE KERNEL", "series"));
        if (!needs.running().isBlank() && !needs.expected().isBlank()) {
            HBox versions = new HBox(10, label(needs.running(), "mono-muted"), label("→", "mono-muted"),
                    label(needs.expected(), "card-success"));
            versions.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(versions);
        }
        box.getChildren().add(note(needs.kernelNote()));
        if (needs.needsReboot()) {
            Button reboot = new Button("REBOOT THE SERVER");
            reboot.getStyleClass().addAll("danger-quiet", "compact-button");
            reboot.setFocusTraversable(false);
            reboot.setOnAction(event -> askToReboot());
            box.getChildren().add(reboot);
        }
        box.getStyleClass().add(needs.needsReboot() ? "lockout-box" : "server-card");
        box.setPadding(new Insets(12, 14, 12, 14));
        return box;
    }

    /**
     * Rebooting is the one action here that ends every session on the machine, including this one, and takes
     * it away for as long as it takes to come back. It is typed out in full.
     */
    private void askToReboot() {
        TextField typed = new TextField();
        typed.getStyleClass().add("card-field");
        typed.setPromptText("REBOOT");
        typed.setPrefColumnCount(8);
        Button go = new Button("REBOOT IT");
        go.getStyleClass().addAll("danger-button", "compact-button");
        go.setFocusTraversable(false);
        go.setDisable(true);
        typed.textProperty().addListener((observable, before, now) ->
                go.setDisable(!now.strip().equalsIgnoreCase("REBOOT")));
        go.setOnAction(event -> {
            hideConfirm();
            report("Asking the server to reboot. The connection will drop; that is what is meant to happen.",
                    false);
            hands.reboot(trouble -> {
                if (trouble != null) {
                    report(trouble, true);
                }
            });
        });
        Button keep = new Button("NOT NOW");
        keep.getStyleClass().addAll("ghost-button", "compact-button");
        keep.setFocusTraversable(false);
        keep.setOnAction(event -> hideConfirm());

        Label why = label("Every session on this machine ends, this one included, and it is gone until it "
                + "comes back. Anything it was serving stops for that long. Nothing else runs the new kernel.",
                "card-problem");
        why.setWrapText(true);
        why.setMaxWidth(700);
        why.setMinHeight(Region.USE_PREF_SIZE);
        HBox line = new HBox(10, label("TYPE REBOOT TO CONFIRM", "metric-label"), typed, go, keep);
        line.setAlignment(Pos.CENTER_LEFT);
        confirmRow.getChildren().setAll(label("THIS TAKES THE WHOLE MACHINE AWAY", "series"), why, line);
        confirmRow.getStyleClass().setAll("lockout-box");
        confirmRow.setPadding(new Insets(12, 14, 12, 14));
        confirmRow.setVisible(true);
        confirmRow.setManaged(true);
    }

    private void hideConfirm() {
        confirmRow.setVisible(false);
        confirmRow.setManaged(false);
    }

    /** Two columns: one long thin list left a wide empty half beside it. */
    private GridPane grid(List<String> units) {
        GridPane grid = new GridPane();
        grid.setHgap(20);
        grid.setVgap(4);
        for (int column = 0; column < 2; column++) {
            ColumnConstraints width = new ColumnConstraints();
            width.setPercentWidth(50);
            grid.getColumnConstraints().add(width);
        }
        for (int i = 0; i < units.size(); i++) {
            grid.add(add(new Row(units.get(i))), i % 2, i / 2);
        }
        return grid;
    }

    private HBox add(Row row) {
        rows.add(row);
        return row.node;
    }

    /**
     * Restarts the ordinary ones one after another rather than all at once.
     *
     * <p>One connection carries one command at a time, and a server asked to restart fifteen services in
     * parallel is a server doing fifteen things at once for no reason.
     */
    private void runBatch(List<String> units) {
        batch.setDisable(true);
        Deque<String> queue = new ArrayDeque<>(units);
        report("Restarting " + units.size() + ", one after another.", false);
        next(queue, units.size(), 0);
    }

    private void next(Deque<String> queue, int total, int failed) {
        String unit = queue.poll();
        if (unit == null) {
            report(failed == 0 ? "All " + total + " restarted."
                    : total - failed + " restarted, " + failed + " refused. Their rows say which.", failed > 0);
            batch.setDisable(false);
            return;
        }
        Row row = rows.stream().filter(each -> each.unit.equals(unit)).findFirst().orElse(null);
        if (row == null || row.done) {
            next(queue, total, failed);
            return;
        }
        row.working();
        hands.restart(unit, trouble -> {
            row.finished(trouble);
            if (trouble == null) {
                countDown(1);
            }
            next(queue, total, trouble == null ? failed : failed + 1);
        });
    }

    private void countDown(int by) {
        left -= by;
        counter.setText(needs.services().isEmpty() ? ""
                : left == 0 ? "ALL DONE" : left + " LEFT");
    }

    private void report(String text, boolean bad) {
        status.getStyleClass().setAll("label", bad ? "card-problem" : "card-note");
        status.setText(text);
        status.setVisible(true);
        status.setManaged(true);
    }

    /** One service, its risk, and the button. */
    private final class Row {

        private final String unit;
        private final HBox node;
        private final Label state = label("", "card-note");
        private final Button button = new Button("RESTART");
        private boolean done;
        private boolean confirming;

        Row(String unit) {
            this.unit = unit;
            ServiceRisk risk = ServiceRisk.of(unit);
            Label name = label(unit, "log-command");
            name.setMinWidth(220);
            state.setMinWidth(110);
            button.getStyleClass().addAll(risk.asks() ? "danger-quiet" : "rail-button", "compact-button");
            button.setFocusTraversable(false);
            button.setOnAction(event -> {
                if (risk.asks() && !confirming) {
                    confirming = true;
                    button.setText("RESTART IT ANYWAY");
                    state.getStyleClass().setAll("label", "card-problem");
                    state.setText(risk.badge());
                    report(risk.warning(), true);
                    return;
                }
                working();
                hands.restart(unit, trouble -> {
                    finished(trouble);
                    if (trouble == null) {
                        countDown(1);
                    }
                });
            });

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            node = new HBox(10, name, state, spacer, button);
            node.setAlignment(Pos.CENTER_LEFT);
            if (risk.asks()) {
                state.getStyleClass().setAll("label", "card-warning");
                state.setText(risk.badge());
            }
        }

        void working() {
            button.setDisable(true);
            state.getStyleClass().setAll("label", "card-note");
            state.setText("RESTARTING…");
        }

        void finished(String trouble) {
            done = trouble == null;
            state.getStyleClass().setAll("label", trouble == null ? "card-success" : "card-problem");
            state.setText(trouble == null ? "RESTARTED" : "REFUSED");
            button.setDisable(trouble == null);
            if (trouble == null) {
                // It is settled, so it stops competing for attention with the ones that are not.
                FadeTransition fade = new FadeTransition(Duration.millis(600), node);
                fade.setToValue(0.45);
                fade.play();
            } else {
                report(trouble, true);
            }
        }
    }

    /** Only for the previews: one unit shown as already restarted. */
    void markForPreview(String unit) {
        rows.stream().filter(row -> row.unit.equals(unit)).findFirst()
                .ifPresent(row -> {
                    row.finished(null);
                    countDown(1);
                });
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
