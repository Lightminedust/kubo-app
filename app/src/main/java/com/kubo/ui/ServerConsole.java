package com.kubo.ui;

import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.privilege.Privilege;
import com.kubo.privilege.RootSession;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.Cursor;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A console onto the server: what this screen sends, and a line to send your own.
 *
 * <p>Each screen shows its own commands only: the network screen what the network screen sent, never the
 * readings of the main screen in between. The whole session, every screen together, is in LOGS.
 *
 * <p>The log lists commands only, never their output, which is the rule the whole application keeps. Commands you
 * type yourself do show their answer, since you asked for it, and are logged like any other, marked as typed.
 * When root mode is on the prompt says so and your commands run as root. Any line can be selected and copied.
 */
public final class ServerConsole extends VBox {

    private static final double MIN_HEIGHT = 132;
    private static final double MAX_HEIGHT = 720;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final int MAX_BLOCKS = 400;
    /** Lines of typed commands' answers kept: enough for a long listing, not a log file read to the end. */
    private static final int MAX_LINES = 5000;
    private static final java.time.Duration TIMEOUT = java.time.Duration.ofSeconds(20);

    private enum Page { LOGS, SHELL }

    private final SshConnection connection;
    private final RootSession root;
    private final CommandService commands;
    /** What typed commands are logged under: the screen this console sits in. */
    private final CommandService.Origin typedAs;
    private final java.util.Set<CommandService.Origin> shows;
    private final String activity;
    private final LogLines logOutput = new LogLines(MAX_BLOCKS);
    private final LogLines shellOutput = new LogLines(MAX_LINES);
    private final StackPane pages = new StackPane(logOutput, shellOutput);
    private final Label copied = label("", "prompt-meta");
    private final javafx.animation.PauseTransition copiedFades =
            new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
    private final TextField input = new TextField();
    private final Label sign = label("$", "prompt-sign");
    private final HBox inputRow;
    /** The edge you pull to make the console taller or shorter. */
    private final Region grip = new Region();
    private final Button fold = tab("▾");
    private final Button logsTab = tab("LOGS");
    private final Button shellTab = tab("SHELL");
    private final Label title = label("", "column-heading");
    private final Label rootNote = label("", "prompt-meta");
    private final List<String> history = new ArrayList<>();
    private final Runnable stopListening;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-console");
        thread.setDaemon(true);
        return thread;
    });

    private int historyIndex;
    private Page current = Page.LOGS;
    private double expandedHeight = 178;
    private double dragFromY;
    private double dragFromHeight;
    private boolean collapsed;

    /**
     * @param service what the title calls this screen, NETWORK for "NETWORK ACTIVITY"
     * @param typedAs the origin commands typed here are logged under
     * @param shows   the origins whose commands this console lists; typedAs is always one of them
     */
    public ServerConsole(SshConnection connection, CommandService commands, RootSession root, String service,
                         CommandService.Origin typedAs, java.util.Set<CommandService.Origin> shows) {
        this.connection = connection;
        this.root = root;
        this.commands = commands;
        this.typedAs = java.util.Objects.requireNonNull(typedAs, "typedAs");
        java.util.Set<CommandService.Origin> all = java.util.EnumSet.of(typedAs);
        all.addAll(shows);
        this.shows = java.util.Collections.unmodifiableSet(all);
        this.activity = service + " ACTIVITY";
        getStyleClass().addAll("prompt-panel", "redis-console");
        setPadding(new Insets(0, 34, 12, 36));
        setPrefHeight(expandedHeight);
        setMinHeight(MIN_HEIGHT);
        buildGrip();

        logsTab.setOnAction(event -> show(Page.LOGS));
        shellTab.setOnAction(event -> show(Page.SHELL));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label hint = label("ONLY THIS SCREEN'S COMMANDS, NEVER THEIR OUTPUT  ·  EVERY SCREEN IS IN LOGS  ·  "
                + "SELECT LINES, CTRL+C COPIES  ·  ↑↓ HISTORY", "metric-label");
        hint.setMinWidth(0);
        fold.setOnAction(event -> setCollapsed(!collapsed));
        copiedFades.setOnFinished(event -> copied.setText(""));
        logOutput.setOnCopied(this::sayCopied);
        shellOutput.setOnCopied(this::sayCopied);
        HBox header = new HBox(10, title, logsTab, shellTab, copied, spacer, hint, fold);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(6, 0, 0, 0));
        // Double-clicking the bar is the quick way out of the way.
        header.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                setCollapsed(!collapsed);
            }
        });

        input.getStyleClass().add("prompt-input");
        input.setPromptText("run a command on the server");
        input.setOnAction(event -> run());
        input.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.UP && !history.isEmpty()) {
                historyIndex = Math.max(0, historyIndex - 1);
                input.setText(history.get(historyIndex));
                input.positionCaret(input.getText().length());
                event.consume();
            } else if (event.getCode() == KeyCode.DOWN && !history.isEmpty()) {
                historyIndex = Math.min(history.size(), historyIndex + 1);
                input.setText(historyIndex == history.size() ? "" : history.get(historyIndex));
                input.positionCaret(input.getText().length());
                event.consume();
            } else if (event.getCode() == KeyCode.L && event.isControlDown()) {
                shellOutput.clearLines();
                event.consume();
            }
        });
        HBox.setHgrow(input, Priority.ALWAYS);
        inputRow = new HBox(10, sign, input, rootNote);
        inputRow.setAlignment(Pos.CENTER_LEFT);

        VBox.setVgrow(pages, Priority.ALWAYS);
        getChildren().addAll(grip, header, pages, inputRow);
        logOutput.append("EVERY COMMAND THIS SCREEN SENDS APPEARS HERE AS IT HAPPENS.", "prompt-meta");
        shellOutput.append("TYPE A COMMAND TO RUN IT ON THE SERVER.", "prompt-meta");

        stopListening = commands == null ? () -> {
        } : commands.addLoggedListener(logged -> {
            if (this.shows.contains(logged.origin())) {
                Platform.runLater(() -> appendLog(logged));
            }
        });
        RootSession.elevatedProperty().addListener((observable, before, value) -> updateRootMark());
        updateRootMark();
        show(Page.LOGS);
    }

    /**
     * Brings the command line forward.
     */
    public void showShell() {
        show(Page.SHELL);
    }

    /** Only for the previews: commands already in the log, without a server, and the first two selected. */
    public void showForPreview(List<CommandService.Logged> sent) {
        sent.forEach(this::appendLog);
        logOutput.getSelectionModel().selectRange(1, Math.min(3, logOutput.getItems().size()));
    }

    public void shutdown() {
        stopListening.run();
        worker.shutdownNow();
    }

    private void updateRootMark() {
        boolean elevated = root != null && root.isElevated();
        sign.setText(elevated ? "#" : "$");
        sign.setStyle(elevated ? "-fx-text-fill: #ff2d55;" : "");
        rootNote.setText(elevated ? "RUNS AS ROOT" : "");
    }

    private void show(Page page) {
        current = page;
        logsTab.getStyleClass().remove("mode-toggle-active");
        shellTab.getStyleClass().remove("mode-toggle-active");
        (page == Page.LOGS ? logsTab : shellTab).getStyleClass().add("mode-toggle-active");
        title.setText(page == Page.LOGS ? activity : "SHELL  ·  THIS SERVER");
        logOutput.setVisible(page == Page.LOGS);
        logOutput.setManaged(page == Page.LOGS);
        shellOutput.setVisible(page == Page.SHELL);
        shellOutput.setManaged(page == Page.SHELL);
        inputRow.setVisible(page == Page.SHELL);
        inputRow.setManaged(page == Page.SHELL);
        if (page == Page.SHELL) {
            Platform.runLater(input::requestFocus);
        }
    }

    private void appendLog(CommandService.Logged logged) {
        CommandEntry entry = logged.entry();
        String state = entry.success() ? "OK" : entry.ran() ? "EXIT " + entry.exitCode() : "NOT RUN";
        // The origin only says something when this console lists more than one.
        String origin = shows.size() > 1 ? "  ·  " + (logged.origin() == CommandService.Origin.USER ? "SHELL"
                : logged.origin().name()) : "";
        logOutput.append(CLOCK.format(entry.executedAt()) + origin + "  ·  " + state + "  ·  "
                        + entry.duration().toMillis() + " MS" + (entry.asRoot() ? "  ·  ROOT" : "")
                        + (logged.typed() ? "  ·  TYPED" : "") + "\n" + entry.command(),
                entry.success() ? "prompt-command" : "prompt-error");
    }

    private void sayCopied(int count) {
        copied.setText(count == 1 ? "COPIED 1 LINE" : "COPIED " + count + " LINES");
        copiedFades.playFromStart();
    }

    private void run() {
        String command = input.getText().strip();
        if (command.isEmpty() || input.isDisabled() || connection == null) {
            return;
        }
        input.clear();
        input.setDisable(true);
        if (history.isEmpty() || !history.get(history.size() - 1).equals(command)) {
            history.add(command);
            if (history.size() > 100) {
                history.remove(0);
            }
        }
        historyIndex = history.size();
        boolean elevated = root != null && root.isElevated();
        shellOutput.append(CLOCK.format(Instant.now()) + "  " + (elevated ? "# " : "$ ") + command,
                "prompt-command");
        worker.execute(() -> {
            CommandEntry entry;
            try {
                if (elevated) {
                    Privilege.Command wrapped = root.privilege().asRoot(command);
                    entry = connection.exec(wrapped.command(), wrapped.input(), TIMEOUT);
                } else {
                    entry = connection.exec(command, null, TIMEOUT);
                }
            } catch (RuntimeException e) {
                entry = null;
            }
            if (entry != null && commands != null) {
                // Logged as typed, under the command as it was typed: the sudo wrapping is how, not what. Its
                // answer stays on this screen and goes nowhere else.
                commands.add(new CommandEntry(entry.executedAt(), command, elevated, entry.exitCode(),
                        entry.output(), entry.error(), entry.duration()), typedAs, true);
            }
            CommandEntry result = entry;
            Platform.runLater(() -> {
                input.setDisable(false);
                if (result == null) {
                    shellOutput.append("KUBO COULD NOT RUN THAT.", "prompt-error");
                } else {
                    if (!result.output().isBlank()) {
                        shellOutput.appendLines(result.output().stripTrailing(), "prompt-output");
                    }
                    if (!result.error().isBlank()) {
                        shellOutput.appendLines(result.error().stripTrailing(), "prompt-error");
                    }
                    shellOutput.append("exit " + result.exitCode() + "  ·  " + result.duration().toMillis() + " ms",
                            result.success() ? "prompt-meta" : "prompt-error");
                }
                input.requestFocus();
            });
        });
    }

    /**
     * Folds the console down to its bar, and back to the height it had.
     */
    public void setCollapsed(boolean value) {
        collapsed = value;
        pages.setVisible(!value);
        pages.setManaged(!value);
        // The command line belongs to the shell page: unfolding onto the log must not bring it back.
        inputRow.setVisible(!value && current == Page.SHELL);
        inputRow.setManaged(!value && current == Page.SHELL);
        grip.setVisible(!value);
        grip.setManaged(!value);
        fold.setText(value ? "▴" : "▾");
        setMinHeight(value ? 0 : MIN_HEIGHT);
        setPrefHeight(value ? Region.USE_COMPUTED_SIZE : expandedHeight);
        setMaxHeight(value ? Region.USE_PREF_SIZE : Double.MAX_VALUE);
    }

    public boolean collapsed() {
        return collapsed;
    }

    private void buildGrip() {
        grip.getStyleClass().add("console-grip");
        grip.setPrefHeight(6);
        grip.setMinHeight(6);
        grip.setMaxWidth(Double.MAX_VALUE);
        grip.setCursor(Cursor.N_RESIZE);
        grip.setOnMousePressed(event -> {
            dragFromY = event.getScreenY();
            dragFromHeight = getHeight();
        });
        grip.setOnMouseDragged(event -> {
            double next = Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, dragFromHeight - (event.getScreenY() - dragFromY)));
            expandedHeight = next;
            setPrefHeight(next);
        });
    }

    private static Button tab(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("mode-toggle");
        button.setFocusTraversable(false);
        return button;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }
}
