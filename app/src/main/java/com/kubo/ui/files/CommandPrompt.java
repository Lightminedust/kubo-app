package com.kubo.ui.files;

import com.kubo.command.CommandEntry;
import com.kubo.files.FileBrowser;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A one-line shell into the server, run from the folder open in the tree. Commands are not interactive
 * and stop after the connection's command timeout.
 */
public final class CommandPrompt extends VBox {

    private static final Pattern CD = Pattern.compile("^cd(\\s+.*)?$");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int MAX_BLOCKS = 500;
    private static final int MAX_CHARACTERS = 20_000;

    private final FileBrowser browser;
    private final Executor executor;
    private final VBox output = new VBox(3);
    private final ScrollPane scroll = new ScrollPane(output);
    private final Label directoryLabel = new Label();
    private final Label runningLabel = new Label();
    private final TextField input = new TextField();
    private final List<String> history = new ArrayList<>();
    private final Timeline runningAnimation;

    private static final double MIN_EXPANDED = 140;

    private final Label hint = new Label("EVERY COMMAND SENT FROM THIS WINDOW IS LISTED  ·  YOURS SHOW THEIR OUTPUT  ·  "
            + "NON-INTERACTIVE, 15 S MAX  ·  ↑↓ HISTORY  ·  CTRL+L CLEAR");
    private final Label lastLine = new Label();
    private final Button sizeButton = new Button();
    private final Region grip = new Region();

    private String directory = "~";
    private int historyIndex;
    private boolean expanded;
    private double expandedHeight = 260;
    private double gripStartY;
    private double gripStartHeight;
    private Consumer<String> onDirectoryChanged = path -> {
    };
    private Runnable onHide = () -> {
    };
    private Runnable onLogs = () -> {
    };
    private Runnable onActivity = () -> {
    };

    /**
     * @param browser null to show the prompt without a server (previews)
     */
    public CommandPrompt(FileBrowser browser, Executor executor) {
        this.browser = browser;
        this.executor = executor;
        getStyleClass().add("prompt-panel");

        Label title = new Label("SHELL");
        title.getStyleClass().add("series");
        directoryLabel.getStyleClass().add("mono-muted");
        directoryLabel.setMinWidth(Region.USE_PREF_SIZE);
        runningLabel.getStyleClass().add("prompt-running");
        runningLabel.setMinWidth(Region.USE_PREF_SIZE);
        hint.getStyleClass().add("metric-label");
        hint.setMinWidth(0);
        lastLine.getStyleClass().add("prompt-system");
        lastLine.setMinWidth(0);
        HBox.setHgrow(lastLine, Priority.ALWAYS);
        HBox.setHgrow(hint, Priority.ALWAYS);
        lastLine.setMaxWidth(Double.MAX_VALUE);
        hint.setMaxWidth(Double.MAX_VALUE);
        hint.setAlignment(Pos.CENTER_RIGHT);

        sizeButton.getStyleClass().add("ghost-button");
        sizeButton.setFocusTraversable(false);
        sizeButton.setMinWidth(Region.USE_PREF_SIZE);
        sizeButton.setOnAction(event -> setExpanded(!expanded));
        Button logsButton = headerButton("≡ LOGS", () -> onLogs.run());
        Button hideButton = headerButton("✕ HIDE", () -> onHide.run());
        HBox header = new HBox(14, title, directoryLabel, runningLabel, lastLine, hint, logsButton, sizeButton, hideButton);
        header.setAlignment(Pos.CENTER_LEFT);

        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Label promptSign = new Label("$");
        promptSign.getStyleClass().add("prompt-sign");
        input.getStyleClass().add("prompt-input");
        input.setPromptText("type a command and press Enter");
        HBox.setHgrow(input, Priority.ALWAYS);
        HBox inputRow = new HBox(10, promptSign, input);
        inputRow.setAlignment(Pos.CENTER_LEFT);

        // Dragging the top edge resizes the expanded shell.
        grip.getStyleClass().add("prompt-grip");
        grip.setCursor(Cursor.N_RESIZE);
        grip.setOnMousePressed(event -> {
            gripStartY = event.getScreenY();
            gripStartHeight = getHeight();
        });
        grip.setOnMouseDragged(event -> {
            double limit = getParent() instanceof Region parent ? parent.getHeight() - 180 : 600;
            expandedHeight = Math.max(MIN_EXPANDED, Math.min(limit, gripStartHeight - (event.getScreenY() - gripStartY)));
            setPrefHeight(expandedHeight);
        });

        getChildren().addAll(grip, header, scroll, inputRow);
        setSpacing(8);
        setPadding(new Insets(0, 40, 12, 36));
        setExpanded(false);

        input.setOnAction(event -> submit());
        input.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);

        String[] frames = {"RUNNING", "RUNNING.", "RUNNING..", "RUNNING..."};
        int[] frame = {0};
        runningAnimation = new Timeline(new KeyFrame(Duration.millis(300), event ->
                runningLabel.setText(frames[frame[0]++ % frames.length])));
        runningAnimation.setCycleCount(Timeline.INDEFINITE);
        setDirectory(directory);

        if (browser != null) {
            // For transparency, the commands Kubo sends on its own appear too, without their output.
            browser.addListener(entry -> Platform.runLater(() -> appendSystem(entry)));
        }
    }

    /**
     * Lists a command sent by Kubo itself: time, status, duration and the full command, never its output.
     */
    void appendSystem(CommandEntry entry) {
        String status = entry.success() ? "ok  " : entry.ran() ? "exit " + entry.exitCode() : "fail";
        String line = CLOCK.format(entry.executedAt()) + "  " + String.format("%-6s", status)
                + String.format("%5d ms  ", entry.duration().toMillis()) + entry.command();
        appendBlock(line, entry.success() ? "prompt-system" : entry.ran() ? "prompt-system-failed" : "prompt-system-error");
    }

    /**
     * Expanded shows the whole output and can be resized; reduced keeps the input and the latest line.
     */
    public void setExpanded(boolean value) {
        expanded = value;
        scroll.setVisible(value);
        scroll.setManaged(value);
        hint.setVisible(value);
        hint.setManaged(value);
        lastLine.setVisible(!value);
        lastLine.setManaged(!value);
        grip.setVisible(value);
        sizeButton.setText(value ? "▾ REDUCE" : "▴ EXPAND");
        setPrefHeight(value ? expandedHeight : Region.USE_COMPUTED_SIZE);
        setMinHeight(value ? MIN_EXPANDED : Region.USE_PREF_SIZE);
        setMaxHeight(value ? Double.MAX_VALUE : Region.USE_PREF_SIZE);
        if (value) {
            Platform.runLater(() -> {
                scroll.layout();
                scroll.setVvalue(1);
            });
        }
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setOnHide(Runnable onHide) {
        this.onHide = onHide;
    }

    public void setOnLogs(Runnable onLogs) {
        this.onLogs = onLogs;
    }

    /**
     * Called whenever a line is added, e.g. to count what arrived while the shell is hidden.
     */
    public void setOnActivity(Runnable onActivity) {
        this.onActivity = onActivity;
    }

    private static Button headerButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("ghost-button");
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(event -> action.run());
        return button;
    }

    public void setDirectory(String path) {
        directory = path;
        directoryLabel.setText(path);
    }

    /**
     * Called when {@code cd} moves the prompt to another folder.
     */
    public void setOnDirectoryChanged(Consumer<String> onDirectoryChanged) {
        this.onDirectoryChanged = onDirectoryChanged;
    }

    /**
     * Adds a finished command to the output without running it; for previews.
     */
    void appendResult(String command, CommandEntry entry) {
        appendCommand(command);
        appendEntry(entry);
    }

    private void handleKey(KeyEvent event) {
        if (event.getCode() == KeyCode.UP && !history.isEmpty()) {
            historyIndex = Math.max(0, historyIndex - 1);
            input.setText(history.get(historyIndex));
            input.end();
            event.consume();
        } else if (event.getCode() == KeyCode.DOWN && !history.isEmpty()) {
            historyIndex = Math.min(history.size(), historyIndex + 1);
            input.setText(historyIndex == history.size() ? "" : history.get(historyIndex));
            input.end();
            event.consume();
        } else if (event.getCode() == KeyCode.L && event.isControlDown()) {
            output.getChildren().clear();
            event.consume();
        } else if (event.getCode() == KeyCode.ESCAPE) {
            input.clear();
            event.consume();
        }
    }

    private void submit() {
        String command = input.getText().strip();
        if (command.isEmpty() || browser == null) {
            return;
        }
        history.add(command);
        historyIndex = history.size();
        input.clear();
        if (command.equals("clear")) {
            output.getChildren().clear();
            return;
        }

        appendCommand(command);
        setRunning(true);
        String from = directory;
        if (CD.matcher(command).matches()) {
            String target = command.substring(2).strip();
            executor.execute(() -> {
                var result = browser.changeDirectory(from, target);
                Platform.runLater(() -> {
                    setRunning(false);
                    if (result.isPresent()) {
                        setDirectory(result.get());
                        onDirectoryChanged.accept(result.get());
                    } else {
                        appendBlock("cd: cannot enter " + target, "prompt-error");
                    }
                });
            });
        } else {
            executor.execute(() -> {
                CommandEntry entry = browser.runIn(from, command);
                Platform.runLater(() -> {
                    setRunning(false);
                    appendEntry(entry);
                });
            });
        }
    }

    private void appendCommand(String command) {
        appendBlock(CLOCK.format(Instant.now()) + "  " + directory + " $ " + command, "prompt-command");
    }

    private void appendEntry(CommandEntry entry) {
        if (!entry.output().isEmpty()) {
            appendBlock(entry.output(), "prompt-output");
        }
        if (!entry.error().isBlank()) {
            appendBlock(entry.error(), "prompt-error");
        }
        String status = entry.ran() ? "exit " + entry.exitCode() : "not run";
        appendBlock(status + "  ·  " + entry.duration().toMillis() + " ms", "prompt-meta");
    }

    private void appendBlock(String text, String styleClass) {
        String shown = text.length() > MAX_CHARACTERS
                ? text.substring(0, MAX_CHARACTERS) + "\n… " + (text.length() - MAX_CHARACTERS) + " more characters"
                : text;
        Label block = new Label(shown.stripTrailing());
        block.getStyleClass().add(styleClass);
        block.setWrapText(true);
        block.setMinHeight(Region.USE_PREF_SIZE);
        output.getChildren().add(block);
        String first = shown.strip().lines().findFirst().orElse("");
        lastLine.setText(first);
        lastLine.getStyleClass().setAll("label", styleClass.equals("prompt-output") ? "prompt-system" : styleClass);
        onActivity.run();
        while (output.getChildren().size() > MAX_BLOCKS) {
            output.getChildren().remove(0);
        }
        Platform.runLater(() -> {
            scroll.layout();
            scroll.setVvalue(1);
        });
    }

    private void setRunning(boolean running) {
        input.setDisable(running);
        if (running) {
            runningAnimation.play();
        } else {
            runningAnimation.stop();
            runningLabel.setText("");
            input.requestFocus();
        }
    }
}
