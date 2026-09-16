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
import javafx.scene.control.ScrollPane;
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
 * A console onto the server: everything Kubo sends, and a line to send your own.
 *
 * <p>The log lists commands only, never their output, which is the rule the whole application keeps. Commands you
 * type yourself do show their answer, since you asked for it. When root mode is on the prompt says so and your
 * commands run as root.
 */
public final class ServerConsole extends VBox {

    private static final double MIN_HEIGHT = 132;
    private static final double MAX_HEIGHT = 720;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final int MAX_BLOCKS = 400;
    private static final java.time.Duration TIMEOUT = java.time.Duration.ofSeconds(20);

    private enum Page { LOGS, SHELL }

    private final SshConnection connection;
    private final RootSession root;
    private final VBox logOutput = new VBox(3);
    private final VBox shellOutput = new VBox(3);
    private final ScrollPane logScroll = scroll(logOutput);
    private final ScrollPane shellScroll = scroll(shellOutput);
    private final StackPane pages = new StackPane(logScroll, shellScroll);
    private final TextField input = new TextField();
    private final Label sign = label("$", "prompt-sign");
    private final HBox inputRow;
    /** The edge you pull to make the console taller or shorter. */
    private final Region grip = new Region();
    private final Button fold = tab("▾");
    private final Button logsTab = tab("LOGS");
    private final Button shellTab = tab("SHELL");
    private final Label title = label("SERVER ACTIVITY", "column-heading");
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

    public ServerConsole(SshConnection connection, CommandService commands, RootSession root) {
        this.connection = connection;
        this.root = root;
        getStyleClass().addAll("prompt-panel", "redis-console");
        setPadding(new Insets(0, 34, 12, 36));
        setPrefHeight(expandedHeight);
        setMinHeight(MIN_HEIGHT);
        buildGrip();

        logsTab.setOnAction(event -> show(Page.LOGS));
        shellTab.setOnAction(event -> show(Page.SHELL));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label hint = label("THE LOG LISTS EVERY COMMAND KUBO SENDS, NEVER ITS OUTPUT  ·  YOUR OWN COMMANDS SHOW "
                + "THEIRS  ·  NON-INTERACTIVE, 20 S MAX  ·  ↑↓ HISTORY", "metric-label");
        fold.setOnAction(event -> setCollapsed(!collapsed));
        HBox header = new HBox(10, title, logsTab, shellTab, spacer, hint, fold);
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
                shellOutput.getChildren().clear();
                event.consume();
            }
        });
        HBox.setHgrow(input, Priority.ALWAYS);
        inputRow = new HBox(10, sign, input, rootNote);
        inputRow.setAlignment(Pos.CENTER_LEFT);

        VBox.setVgrow(pages, Priority.ALWAYS);
        getChildren().addAll(grip, header, pages, inputRow);
        append(logOutput, "EVERY COMMAND KUBO SENDS APPEARS HERE AS IT HAPPENS.", "prompt-meta", logScroll);
        append(shellOutput, "TYPE A COMMAND TO RUN IT ON THE SERVER.", "prompt-meta", shellScroll);

        stopListening = commands == null ? () -> {
        } : commands.addListener((entry, origin) -> Platform.runLater(() -> appendLog(entry, origin)));
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
        title.setText(page == Page.LOGS ? "SERVER ACTIVITY" : "SHELL  ·  THIS SERVER");
        logScroll.setVisible(page == Page.LOGS);
        logScroll.setManaged(page == Page.LOGS);
        shellScroll.setVisible(page == Page.SHELL);
        shellScroll.setManaged(page == Page.SHELL);
        inputRow.setVisible(page == Page.SHELL);
        inputRow.setManaged(page == Page.SHELL);
        if (page == Page.SHELL) {
            Platform.runLater(input::requestFocus);
        }
    }

    private void appendLog(CommandEntry entry, CommandService.Origin origin) {
        String state = entry.success() ? "OK" : entry.ran() ? "EXIT " + entry.exitCode() : "NOT RUN";
        append(logOutput, CLOCK.format(entry.executedAt()) + "  ·  " + origin + "  ·  " + state + "  ·  "
                        + entry.duration().toMillis() + " MS" + (entry.asRoot() ? "  ·  ROOT" : "")
                        + "\n" + entry.command(),
                entry.success() ? "prompt-command" : "prompt-error", logScroll);
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
        append(shellOutput, CLOCK.format(Instant.now()) + "  " + (elevated ? "# " : "$ ") + command,
                "prompt-command", shellScroll);
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
            CommandEntry result = entry;
            Platform.runLater(() -> {
                input.setDisable(false);
                if (result == null) {
                    append(shellOutput, "KUBO COULD NOT RUN THAT.", "prompt-error", shellScroll);
                } else {
                    if (!result.output().isBlank()) {
                        append(shellOutput, result.output().stripTrailing(), "prompt-output", shellScroll);
                    }
                    if (!result.error().isBlank()) {
                        append(shellOutput, result.error().stripTrailing(), "prompt-error", shellScroll);
                    }
                    append(shellOutput, "exit " + result.exitCode() + "  ·  " + result.duration().toMillis() + " ms",
                            result.success() ? "prompt-meta" : "prompt-error", shellScroll);
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
        inputRow.setVisible(!value);
        inputRow.setManaged(!value);
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

    private static void append(VBox output, String text, String style, ScrollPane scroll) {
        Label line = label(text, style);
        line.setWrapText(true);
        line.setMinHeight(Region.USE_PREF_SIZE);
        output.getChildren().add(line);
        while (output.getChildren().size() > MAX_BLOCKS) {
            output.getChildren().remove(0);
        }
        Platform.runLater(() -> {
            scroll.layout();
            scroll.setVvalue(1);
        });
    }

    private static ScrollPane scroll(VBox content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
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
