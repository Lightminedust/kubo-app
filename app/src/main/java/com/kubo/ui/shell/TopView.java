package com.kubo.ui.shell;

import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.model.shell.ProcessReading;
import com.kubo.model.shell.SystemProcess;
import com.kubo.privilege.Privilege;
import com.kubo.privilege.RootSession;
import com.kubo.ui.Meter;
import com.kubo.ui.Pulse;
import com.kubo.ui.scope.Format;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What is running on the server.
 *
 * <p>Which is what htop is for, minus the drawing. htop paints a terminal screen and repaints it in place
 * several times a second; over a connection that shows lines of text, all that arrives is the painting. This
 * asks the machine the same question every few seconds, keeps the answer as data, and puts the button anybody
 * opens htop to press at the end of the row.
 *
 * <p>Bars rather than a column of percentages, because a column of numbers is read line by line and the same
 * column as bars is read at a glance. The numbers stay beside them: a bar says which is biggest, a number
 * says how big.
 */
public final class TopView extends VBox {

    private static final java.time.Duration READ = java.time.Duration.ofSeconds(20);
    private static final java.time.Duration SIGNAL = java.time.Duration.ofSeconds(20);
    private static final Duration EVERY = Duration.seconds(3);

    private final SshConnection connection;
    private final CommandService commands;
    private final RootSession root;
    private final ObservableList<SystemProcess> all = FXCollections.observableArrayList();
    private final FilteredList<SystemProcess> shown = new FilteredList<>(all);
    private final ListView<SystemProcess> table = new ListView<>(shown);

    private final Readout processes = new Readout("PROCESSES", false);
    private final Readout busy = new Readout("ON A CORE", false);
    private final Readout load = new Readout("LOAD", true);
    private final Readout memory = new Readout("MEMORY", true);

    private final Label status = new Label();
    private final TextField find = new TextField();
    private final ToggleButton following = new ToggleButton("LIVE");
    private final Pulse pulse = new Pulse();
    private final Button reread = rail("↻ RE-READ");
    private final AtomicBoolean reading = new AtomicBoolean();
    private final PauseTransition clock = new PauseTransition(EVERY);
    private final PauseTransition disarms = new PauseTransition(Duration.seconds(6));
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-top");
        thread.setDaemon(true);
        return thread;
    });

    /** The row waiting for a second press before it stops anything, or -1. */
    private int armed = -1;

    /**
     * @param connection null to build the view without a server, for the previews
     */
    public TopView(SshConnection connection, CommandService commands, RootSession root) {
        this.connection = connection;
        this.commands = commands;
        this.root = root;
        getStyleClass().addAll("files-view", "top-view");
        setSpacing(0);
        setPadding(new Insets(0));

        status.getStyleClass().add("card-note");
        status.setWrapText(true);
        status.setVisible(false);
        status.setManaged(false);

        find.getStyleClass().add("find-field");
        find.setPromptText("program, user or pid");
        find.setPrefColumnCount(20);
        find.textProperty().addListener((observable, before, text) -> applyFilter());

        following.getStyleClass().add("mode-toggle");
        following.setSelected(true);
        following.setTooltip(new javafx.scene.control.Tooltip("Asks again three seconds after each answer"));
        following.selectedProperty().addListener((observable, before, on) -> {
            if (on) {
                read();
            } else {
                clock.stop();
                pulse.setRunning(false);
            }
        });
        // Three seconds after the answer, not after the question. Started the other way, a slow link queues
        // requests behind each other until the connection is doing nothing but this.
        clock.setOnFinished(event -> read());
        disarms.setOnFinished(event -> {
            armed = -1;
            table.refresh();
        });
        reread.setOnAction(event -> read());

        table.getStyleClass().add("logs-list");
        table.setFixedCellSize(46);
        table.setCellFactory(view -> new Row());
        VBox.setVgrow(table, Priority.ALWAYS);

        VBox body = new VBox(10, actions(), header(), table, status);
        body.setPadding(new Insets(14, 26, 16, 26));
        VBox.setVgrow(body, Priority.ALWAYS);
        getChildren().addAll(band(), body);
    }

    /**
     * The machine's own figures, across the top.
     *
     * <p>Above the table rather than inside it: they are about the server, not about any one row, and a
     * process list with no total is a page somebody has to add up in their head.
     */
    private HBox band() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label mark = new Label("PROCESSES");
        mark.getStyleClass().add("top-mark");
        HBox row = new HBox(0, mark, spacer, processes, busy, load, memory, pulse);
        row.getStyleClass().add("top-band");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setSpacing(34);
        row.setPadding(new Insets(16, 26, 16, 26));
        return row;
    }

    private HBox actions() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(10, reread, following, spacer, find);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox header() {
        HBox row = new HBox(12,
                fixed("PID", 66), fixed("NAME", 150), fixed("USER", 80), fixed("CPU", 148),
                fixed("MEMORY", 148), fixed("RESIDENT", 80), fixed("STATE", 52), fixed("UP FOR", 88),
                fixed("COMMAND", 220));
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(2, 0, 2, 12));
        row.getStyleClass().add("top-header");
        return row;
    }

    /** Asks once, and books the next one if it is following. */
    public void read() {
        if (connection == null || !reading.compareAndSet(false, true)) {
            // Already asking. An answer that has not come back yet is not a reason to ask again.
            return;
        }
        pulse.setRunning(true);
        worker.execute(() -> {
            CommandEntry entry = run(ProcessReading.COMMAND, READ);
            ProcessReading found = ProcessReading.parse(entry.output());
            Platform.runLater(() -> {
                reading.set(false);
                if (entry.ran()) {
                    apply(found);
                } else {
                    status.setVisible(true);
                    status.setManaged(true);
                    status.setText("The server did not answer.");
                }
                if (following.isSelected()) {
                    clock.playFromStart();
                } else {
                    pulse.setRunning(false);
                }
            });
        });
    }

    private void apply(ProcessReading found) {
        // Kept rather than replaced where nothing moved: setAll on an unchanged list still drops the
        // selection, and a table that clears itself every three seconds cannot be read.
        if (!all.equals(found.processes())) {
            all.setAll(found.processes());
        }
        processes.set(String.valueOf(found.total()), 0);
        busy.set(found.running() + " / " + found.cores(), 0);
        load.set(String.format(Locale.ROOT, "%.2f", found.load()), found.loadShare());
        memory.set(found.memoryTotal() == 0 ? "—"
                        : Format.bytes(found.memoryUsed()) + " / " + Format.bytes(found.memoryTotal()),
                found.memoryShare());
    }

    /**
     * Ends a process.
     *
     * <p>The number comes from ps and goes into the command as a number. Nothing anybody typed reaches it,
     * which is the whole reason this table is read as data rather than off a drawn screen.
     */
    private void end(SystemProcess process) {
        worker.execute(() -> {
            CommandEntry entry = run("kill -TERM " + process.pid(), SIGNAL);
            Platform.runLater(() -> {
                say(entry.success()
                                ? "Asked " + process.program() + " (" + process.pid() + ") to stop"
                                : "Could not stop " + process.pid() + ": " + firstLine(entry),
                        !entry.success());
                read();
            });
        });
    }

    private CommandEntry run(String command, java.time.Duration timeout) {
        String sent = command;
        byte[] password = null;
        boolean elevated = root != null && root.isElevated();
        if (elevated) {
            Privilege.Command wrapped = root.privilege().asRoot(command);
            sent = wrapped.command();
            password = wrapped.input();
        }
        CommandEntry entry = connection.exec(sent, password, timeout);
        if (commands != null) {
            commands.add(new CommandEntry(entry.executedAt(), command, elevated, entry.exitCode(),
                    entry.output(), entry.error(), entry.duration()), CommandService.Origin.MONITOR, false);
        }
        return entry;
    }

    private static String firstLine(CommandEntry entry) {
        String said = entry.error().isBlank() ? entry.output() : entry.error();
        return said.lines().findFirst().orElse("exit " + entry.exitCode());
    }

    private void applyFilter() {
        String wanted = find.getText() == null ? "" : find.getText().strip().toLowerCase(Locale.ROOT);
        shown.setPredicate(wanted.isEmpty() ? null : process ->
                process.name().toLowerCase(Locale.ROOT).contains(wanted)
                        || process.command().toLowerCase(Locale.ROOT).contains(wanted)
                        || process.user().toLowerCase(Locale.ROOT).contains(wanted)
                        || String.valueOf(process.pid()).contains(wanted));
    }

    private void say(String text, boolean wrong) {
        status.getStyleClass().setAll("label", wrong ? "card-problem" : "card-note");
        status.setText(text);
        status.setVisible(true);
        status.setManaged(true);
    }

    public void shutdown() {
        disarms.stop();
        clock.stop();
        pulse.setRunning(false);
        worker.shutdownNow();
    }

    /** Only for the previews: a reading with no server behind it. */
    public void showForPreview(ProcessReading found) {
        following.setSelected(false);
        apply(found);
    }

    private static Button rail(String text) {
        Button button = new Button(text);
        button.getStyleClass().addAll("rail-button", "compact-button");
        button.setFocusTraversable(false);
        return button;
    }

    private static Label fixed(String text, double width) {
        Label label = new Label(text);
        label.getStyleClass().add("metric-label");
        label.setMinWidth(width);
        label.setPrefWidth(width);
        return label;
    }

    private static Label cell(String text, double width, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        label.setMinWidth(width);
        label.setPrefWidth(width);
        return label;
    }

    /** A bar and its number, which is what one column of this table is. */
    private static HBox measured(double share, String text) {
        Meter meter = new Meter(72, 6);
        meter.setShare(share);
        Label value = cell(text, 60, "card-note");
        HBox box = new HBox(10, meter, value);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMinWidth(148);
        box.setPrefWidth(148);
        return box;
    }

    /** How long it has been up, in the largest unit that still says something. */
    private static String since(java.time.Duration up) {
        long days = up.toDays();
        if (days > 0) {
            return days + "d " + up.toHoursPart() + "h";
        }
        return up.toHours() > 0 ? up.toHours() + "h " + up.toMinutesPart() + "m"
                : up.toMinutes() + "m " + up.toSecondsPart() + "s";
    }

    /** One of the machine's figures, in the band across the top. */
    private static final class Readout extends VBox {

        private final Label value = new Label("—");
        private final Meter meter;

        Readout(String name, boolean measured) {
            getStyleClass().add("top-readout");
            setSpacing(5);
            setAlignment(Pos.CENTER_LEFT);
            Label label = new Label(name);
            label.getStyleClass().add("metric-label");
            value.getStyleClass().add("top-value");
            meter = measured ? new Meter(120, 4) : null;
            getChildren().addAll(label, value);
            if (meter != null) {
                getChildren().add(meter);
            }
        }

        void set(String text, double share) {
            value.setText(text);
            if (meter != null) {
                meter.setShare(share);
            }
        }
    }

    private final class Row extends ListCell<SystemProcess> {

        @Override
        protected void updateItem(SystemProcess process, boolean empty) {
            super.updateItem(process, empty);
            setText(null);
            if (empty || process == null) {
                setGraphic(null);
                return;
            }
            Label command = cell(process.command(), 220, "top-command");
            command.setTooltip(new javafx.scene.control.Tooltip(process.command()));
            Label state = cell(process.state(), 52,
                    process.zombie() ? "pm2-broken" : process.running() ? "pm2-online" : "card-note");

            // Two presses, never one. There is no undo on a signal, and the row under the pointer moves every
            // three seconds while the table refreshes itself.
            boolean ready = armed == process.pid();
            Button stop = rail(ready ? "SURE?" : "STOP");
            stop.getStyleClass().add("danger-quiet");
            stop.setOnAction(event -> {
                if (ready) {
                    armed = -1;
                    disarms.stop();
                    end(process);
                } else {
                    armed = process.pid();
                    disarms.playFromStart();
                }
                table.refresh();
            });

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Label name = cell(process.name(), 150, "top-name");
            HBox row = new HBox(12,
                    cell(String.valueOf(process.pid()), 66, "metric-value"),
                    name,
                    cell(process.user(), 80, "card-note"),
                    measured(process.cpu() / 100, String.format(Locale.ROOT, "%.1f%%", process.cpu())),
                    measured(process.memoryShare() / 100,
                            String.format(Locale.ROOT, "%.1f%%", process.memoryShare())),
                    cell(Format.bytes(process.resident()), 80, "card-note"),
                    state,
                    cell(since(process.up()), 88, "card-note"),
                    command,
                    spacer, stop);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(4, 8, 4, 0));
            setGraphic(row);
        }
    }
}
