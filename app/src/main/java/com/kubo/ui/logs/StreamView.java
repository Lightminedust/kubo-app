package com.kubo.ui.logs;

import com.kubo.connection.ssh.SshConnection;
import com.kubo.model.shell.LogLine;
import com.kubo.model.shell.Prompt;
import com.kubo.ui.LogLines;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A log being followed, on its own, for as long as somebody wants to watch it.
 *
 * <p>What this is instead of: the same command in the console, where it locks the field until it is stopped,
 * pushes every other line of the session out of a short list, and vanishes from view the moment somebody
 * opens another screen although it is still running. None of that is a log being read.
 */
public final class StreamView extends VBox {

    /** Long enough that nobody watching a log ever meets it, short enough that a forgotten one does. */
    private static final Duration LIMIT = Duration.ofHours(12);

    private static final int KEPT = 20_000;

    private final SshConnection connection;
    private final String command;
    private final LogLines lines = new LogLines(KEPT);
    private final TextField find = new TextField();
    private final ToggleButton onlyProblems = new ToggleButton("ONLY WHAT IS WRONG");
    private final Label count = new Label();
    private final Label state = new Label();
    private final AtomicReference<SshConnection.Live> live = new AtomicReference<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-stream");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean closed;
    private int errors;

    /**
     * @param connection the session this runs on; a channel of its own, so the screens behind keep working
     * @param command    the command as it was typed
     */
    /**
     * @param connection null to build the view without a server, for the previews
     */
    public StreamView(SshConnection connection, String command) {
        this.connection = connection;
        this.command = Objects.requireNonNull(command, "command");
        getStyleClass().add("stream-view");
        setSpacing(10);
        setPadding(new Insets(14, 20, 14, 20));

        Label typed = new Label(command);
        typed.getStyleClass().add("stream-command");

        count.getStyleClass().add("metric-label");
        state.getStyleClass().add("stream-state");

        find.getStyleClass().add("find-field");
        find.setPromptText("show only lines holding…");
        find.setPrefColumnCount(26);
        find.textProperty().addListener((observable, before, text) -> applyFilter());

        onlyProblems.getStyleClass().add("mode-toggle");
        onlyProblems.setSelected(false);
        onlyProblems.selectedProperty().addListener((observable, before, on) -> applyFilter());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(12, state, count, spacer, onlyProblems, find);
        bar.setAlignment(Pos.CENTER_LEFT);

        Button clear = new Button("CLEAR");
        clear.getStyleClass().addAll("rail-button", "compact-button");
        clear.setOnAction(event -> {
            lines.clearLines();
            errors = 0;
            tally();
        });
        bar.getChildren().add(clear);

        lines.getStyleClass().add("stream-lines");
        VBox.setVgrow(lines, Priority.ALWAYS);

        getChildren().addAll(typed, bar, lines);
        tally();
    }

    /** Starts the command. Called once, when the window opens. */
    public void start() {
        if (connection == null) {
            return;
        }
        state.setText("FOLLOWING");
        state.getStyleClass().setAll("stream-state", "stream-live");
        worker.execute(() -> {
            connection.execInteractive(command,
                    line -> {
                        String text = Prompt.tidy(line.text());
                        if (!text.isEmpty()) {
                            Platform.runLater(() -> heard(text));
                        }
                    },
                    handle -> live.set(handle),
                    LIMIT);
            Platform.runLater(this::ended);
        });
    }

    /**
     * Stops the command and lets the channel go.
     *
     * <p>Control-C first, which is what the program is waiting to be told. Then the channel, because a few
     * of them catch the interrupt and carry on, and a window that has been closed must not leave something
     * running on the server for twelve hours.
     */
    public void shutdown() {
        closed = true;
        SshConnection.Live running = live.getAndSet(null);
        if (running != null) {
            running.stop();
        }
        worker.shutdownNow();
    }

    /** Only for the previews: lines as they would have arrived, with no server behind them. */
    public void showForPreview(java.util.List<String> printed) {
        state.setText("FOLLOWING");
        state.getStyleClass().setAll("stream-state", "stream-live");
        printed.forEach(this::heard);
    }

    private void heard(String text) {
        LogLine line = LogLine.of(text);
        if (line.level() == LogLine.Level.ERROR) {
            errors++;
        }
        lines.appendLog(line);
        tally();
    }

    private void ended() {
        if (closed) {
            return;
        }
        state.setText("IT STOPPED");
        state.getStyleClass().setAll("stream-state", "stream-over");
    }

    private void applyFilter() {
        lines.setFilter(find.getText());
        lines.setStyleFilter(onlyProblems.isSelected()
                ? java.util.Set.of("log-error", "log-warn") : java.util.Set.of());
    }

    private void tally() {
        count.setText(errors == 0
                ? lines.total() + " LINES"
                : lines.total() + " LINES  ·  " + errors + (errors == 1 ? " PROBLEM" : " PROBLEMS"));
    }
}
