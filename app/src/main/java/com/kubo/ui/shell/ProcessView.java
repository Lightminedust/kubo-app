package com.kubo.ui.shell;

import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.model.shell.Pm2Process;
import com.kubo.ui.WorkTrail;
import com.kubo.ui.scope.Format;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What pm2 is running, as a table rather than as a drawing of one.
 *
 * <p>{@code pm2 list} prints a grid of box characters padded with spaces. Reading that back means counting
 * spaces and trusting that the next version of pm2 pads them identically, and it still would not give a
 * process id you could put a button on. {@code pm2 jlist} gives the same information already separated, so
 * every row here knows which process it is and a button on it can say so.
 */
public final class ProcessView extends VBox {

    private static final Duration READ = Duration.ofSeconds(20);
    private static final Duration ACT = Duration.ofSeconds(40);

    private final SshConnection connection;
    private final CommandService commands;
    private final ObservableList<Pm2Process> rows = FXCollections.observableArrayList();
    private final ListView<Pm2Process> table = new ListView<>(rows);
    private final Label tally = new Label();
    private final Label status = new Label();
    private final WorkTrail trail = new WorkTrail();
    private final Button reread = rail("↻ RE-READ");
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-pm2");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * @param connection null to build the view without a server, for the previews
     */
    public ProcessView(SshConnection connection, CommandService commands) {
        this.connection = connection;
        this.commands = commands;
        getStyleClass().add("files-view");
        setSpacing(12);
        setPadding(new Insets(16, 26, 16, 26));

        Label mark = new Label("PM2");
        mark.getStyleClass().add("sheet-title");
        tally.getStyleClass().add("lede");
        status.getStyleClass().add("card-note");
        status.setWrapText(true);
        status.setVisible(false);
        status.setManaged(false);
        trail.setVisible(false);
        trail.setManaged(false);

        reread.setOnAction(event -> read());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, reread, spacer);
        actions.setAlignment(Pos.CENTER_LEFT);

        table.getStyleClass().add("logs-list");
        // Said once. Left to guess, the list measures the first row and draws every other one on top of it.
        table.setFixedCellSize(52);
        table.setCellFactory(view -> new Row());
        VBox.setVgrow(table, Priority.ALWAYS);

        getChildren().addAll(mark, tally, actions, trail, header(), table, status);
    }

    /** The column names, above the rows rather than repeated in every one of them. */
    private HBox header() {
        HBox row = new HBox(12,
                fixed("ID", 44), fixed("NAME", 200), fixed("MODE", 110), fixed("STATUS", 90),
                fixed("RESTARTS", 90), fixed("CPU", 70), fixed("MEMORY", 100), fixed("UP FOR", 110));
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0, 0, 2, 12));
        return row;
    }

    public void read() {
        if (connection == null) {
            return;
        }
        working(true, "Asking pm2 what it is running…");
        worker.execute(() -> {
            CommandEntry entry = run("pm2 jlist", READ);
            List<Pm2Process> found = Pm2Process.parse(entry.output());
            Platform.runLater(() -> {
                working(false, null);
                if (!entry.ran()) {
                    rows.clear();
                    tally.setText("pm2 did not answer.");
                    say(entry.error().isBlank() ? "pm2 could not be run on this server." : entry.error(), true);
                    return;
                }
                rows.setAll(found);
                int up = (int) found.stream().filter(Pm2Process::online).count();
                tally.setText(found.isEmpty()
                        ? "pm2 is installed and has nothing to run."
                        : found.size() + (found.size() == 1 ? " process, " : " processes, ") + up + " online");
            });
        });
    }

    private void act(String verb, Pm2Process process) {
        working(true, verb + "ing " + process.name() + "…");
        worker.execute(() -> {
            // The number, never the name. pm2 gives it, so nothing typed by anybody goes into this command.
            CommandEntry entry = run("pm2 " + verb + " " + process.id(), ACT);
            Platform.runLater(() -> {
                working(false, null);
                say(entry.success()
                        ? verb.toUpperCase(java.util.Locale.ROOT) + " " + process.name() + " — done"
                        : "pm2 " + verb + " failed: " + firstLine(entry), !entry.success());
                read();
            });
        });
    }

    private CommandEntry run(String command, Duration timeout) {
        Platform.runLater(() -> trail.add(command));
        CommandEntry entry = connection.exec(command, null, timeout);
        if (commands != null) {
            commands.add(entry, CommandService.Origin.MONITOR, false);
        }
        return entry;
    }

    private static String firstLine(CommandEntry entry) {
        String said = entry.error().isBlank() ? entry.output() : entry.error();
        return said.lines().findFirst().orElse("exit " + entry.exitCode());
    }

    private void working(boolean yes, String said) {
        reread.setDisable(yes);
        trail.setVisible(yes);
        trail.setManaged(yes);
        if (yes) {
            tally.setText(said);
            trail.start();
        } else {
            trail.finish();
        }
    }

    private void say(String text, boolean wrong) {
        status.setText(text);
        status.getStyleClass().setAll("label", wrong ? "card-problem" : "card-note");
        status.setVisible(true);
        status.setManaged(true);
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    /** Only for the previews: the table with processes in it and no server behind it. */
    public void showForPreview(List<Pm2Process> found) {
        rows.setAll(found);
        int up = (int) found.stream().filter(Pm2Process::online).count();
        tally.setText(found.size() + " processes, " + up + " online");
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

    /** A tenth of a percent matters at the bottom of the scale and nothing at the top. */
    private static String cpu(double share) {
        return share > 0 && share < 10
                ? String.format(java.util.Locale.ROOT, "%.1f%%", share) : Math.round(share) + "%";
    }

    /** How long something has been up, in the largest unit that still says something. */
    private static String since(Pm2Process process, Instant now) {
        Duration up = process.uptime(now);
        if (!process.online() || up.isZero()) {
            return "—";
        }
        long days = up.toDays();
        if (days > 0) {
            return days + "d " + up.toHoursPart() + "h";
        }
        return up.toHours() > 0 ? up.toHours() + "h " + up.toMinutesPart() + "m"
                : up.toMinutes() + "m " + up.toSecondsPart() + "s";
    }

    private final class Row extends ListCell<Pm2Process> {

        @Override
        protected void updateItem(Pm2Process process, boolean empty) {
            super.updateItem(process, empty);
            setText(null);
            if (empty || process == null) {
                setGraphic(null);
                return;
            }
            Label state = cell(process.status().toUpperCase(java.util.Locale.ROOT), 90,
                    process.online() ? "pm2-online" : process.broken() ? "pm2-broken" : "pm2-stopped");

            Button restart = rail("RESTART");
            restart.setOnAction(event -> act("restart", process));
            Button toggle = rail(process.online() ? "STOP" : "START");
            toggle.getStyleClass().add(process.online() ? "danger-quiet" : "rail-button");
            toggle.setOnAction(event -> act(process.online() ? "stop" : "start", process));

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(12,
                    cell(String.valueOf(process.id()), 44, "metric-value"),
                    cell(process.name(), 200, "pm2-name"),
                    cell(process.mode(), 110, "card-note"),
                    state,
                    cell(process.restarts() == 0 ? "—" : String.valueOf(process.restarts()), 90,
                            process.restarts() > 0 ? "pm2-restarts" : "card-note"),
                    cell(cpu(process.cpu()), 70, "card-note"),
                    cell(process.memory() == 0 ? "—" : Format.bytes(process.memory()), 100, "card-note"),
                    cell(since(process, Instant.now()), 110, "card-note"),
                    spacer, restart, toggle);
            row.setAlignment(Pos.CENTER_LEFT);
            // No left padding: the cell already has twelve, and the header outside the list has twelve.
            row.setPadding(new Insets(4, 8, 4, 0));
            setGraphic(row);
        }
    }
}
