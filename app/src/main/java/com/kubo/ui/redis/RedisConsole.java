package com.kubo.ui.redis;

import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;

/** Redis activity, an interactive redis-cli, and a deliberately local PowerShell runner. */
public final class RedisConsole extends VBox {

    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final int MAX_BLOCKS = 300;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private enum Page { LOGS, REDIS, POWERSHELL }

    private final VBox logOutput = new VBox(3);
    private final VBox cliOutput = new VBox(3);
    private final VBox powerShellOutput = new VBox(3);
    private final ScrollPane logScroll = scroll(logOutput);
    private final ScrollPane cliScroll = scroll(cliOutput);
    private final ScrollPane powerShellScroll = scroll(powerShellOutput);
    private final StackPane pages = new StackPane(logScroll, cliScroll, powerShellScroll);
    private final TextField input = new TextField();
    private final Label sign = label("R›", "prompt-sign");
    private final HBox inputRow;
    private final Button logsTab = tab("LOGS");
    private final Button cliTab = tab("REDIS CLI");
    private final Button powerShellTab = tab("POWERSHELL");
    private final Label title = label("REDIS ACTIVITY", "series");
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-powershell");
        thread.setDaemon(true);
        return thread;
    });
    private final Runnable stopListening;
    private volatile Process process;
    private boolean redisRunning;
    private Consumer<String> onRedisCommand = command -> completeRedis(command, null);
    private final List<String> redisHistory = new ArrayList<>();
    private int historyIndex;
    private Page currentPage = Page.LOGS;

    RedisConsole(CommandService commands) {
        this(commands, false);
    }

    /** Reuses the console as Kubo's standalone local PowerShell screen. */
    public RedisConsole(CommandService commands, boolean powerShellOnly) {
        getStyleClass().addAll("prompt-panel", "redis-console");
        setPadding(new Insets(10, 34, 12, 36));
        setPrefHeight(164);
        setMinHeight(130);

        logsTab.setOnAction(event -> show(Page.LOGS));
        cliTab.setOnAction(event -> show(Page.REDIS));
        powerShellTab.setOnAction(event -> show(Page.POWERSHELL));
        Label location = label("REDIS RUNS ON THE SERVER  ·  POWERSHELL STAYS ON THIS PC", "metric-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, title, logsTab, cliTab, powerShellTab, spacer, location);
        header.setAlignment(Pos.CENTER_LEFT);

        input.getStyleClass().add("prompt-input");
        input.setOnKeyPressed(event -> {
            if (currentPage != Page.REDIS || redisHistory.isEmpty()) return;
            if (event.getCode() == javafx.scene.input.KeyCode.UP) {
                historyIndex = Math.max(0, historyIndex - 1);
                input.setText(redisHistory.get(historyIndex));
                input.positionCaret(input.getText().length());
                event.consume();
            } else if (event.getCode() == javafx.scene.input.KeyCode.DOWN) {
                historyIndex = Math.min(redisHistory.size(), historyIndex + 1);
                input.setText(historyIndex == redisHistory.size() ? "" : redisHistory.get(historyIndex));
                input.positionCaret(input.getText().length());
                event.consume();
            }
        });
        HBox.setHgrow(input, Priority.ALWAYS);
        inputRow = new HBox(10, sign, input);
        inputRow.setAlignment(Pos.CENTER_LEFT);

        VBox.setVgrow(pages, Priority.ALWAYS);
        getChildren().addAll(header, pages, inputRow);
        append(logOutput, "REDIS COMMANDS SENT BY KUBO APPEAR HERE IN REAL TIME.", "prompt-meta", logScroll);
        append(cliOutput, "INTERACTIVE REDIS CONSOLE  ·  ACTIVE SERVICE AND DATABASE.", "prompt-meta", cliScroll);
        append(powerShellOutput, "LOCAL WINDOWS POWERSHELL  ·  EACH ENTRY IS A SEPARATE COMMAND  ·  60 S LIMIT",
                "prompt-meta", powerShellScroll);

        stopListening = powerShellOnly || commands == null ? () -> { } : commands.addListener((entry, origin) -> {
            if (origin == CommandService.Origin.REDIS) Platform.runLater(() -> appendRedis(entry));
        });
        if (powerShellOnly) {
            logsTab.setVisible(false);
            logsTab.setManaged(false);
            cliTab.setVisible(false);
            cliTab.setManaged(false);
            powerShellTab.setVisible(false);
            powerShellTab.setManaged(false);
        }
        show(powerShellOnly ? Page.POWERSHELL : Page.LOGS);
    }

    void setOnRedisCommand(Consumer<String> handler) {
        onRedisCommand = handler == null ? command -> completeRedis(command, null) : handler;
    }

    void showRedisCli() {
        show(Page.REDIS);
    }

    void completeRedis(String command, CommandEntry entry) {
        Platform.runLater(() -> {
            if (entry == null) {
                append(cliOutput, "KUBO COULD NOT EXECUTE THE COMMAND.", "prompt-error", cliScroll);
            } else {
                String answer = !entry.output().isBlank() ? entry.output().stripTrailing()
                        : !entry.error().isBlank() ? entry.error().stripTrailing() : "(nil)";
                append(cliOutput, answer, entry.success() ? "prompt-output" : "prompt-error", cliScroll);
                append(cliOutput, (entry.success() ? "OK" : "FAILED") + "  ·  " + entry.duration().toMillis() + " MS",
                        entry.success() ? "prompt-meta" : "prompt-error", cliScroll);
            }
            redisRunning = false;
            input.setDisable(false);
            input.requestFocus();
        });
    }

    public void shutdown() {
        stopListening.run();
        Process running = process;
        if (running != null && running.isAlive()) running.destroyForcibly();
        worker.shutdownNow();
    }

    private void show(Page page) {
        currentPage = page;
        logsTab.getStyleClass().remove("mode-toggle-active");
        cliTab.getStyleClass().remove("mode-toggle-active");
        powerShellTab.getStyleClass().remove("mode-toggle-active");
        (page == Page.LOGS ? logsTab : page == Page.REDIS ? cliTab : powerShellTab)
                .getStyleClass().add("mode-toggle-active");
        title.setText(switch (page) {
            case LOGS -> "REDIS ACTIVITY";
            case REDIS -> "REDIS CLI  ·  ACTIVE DB";
            case POWERSHELL -> "POWERSHELL  ·  LOCAL PC";
        });
        visible(logScroll, page == Page.LOGS);
        visible(cliScroll, page == Page.REDIS);
        visible(powerShellScroll, page == Page.POWERSHELL);
        visible(inputRow, page != Page.LOGS);
        if (page == Page.REDIS) {
            sign.setText("R›");
            input.setPromptText("HSET myhash field1 \"hello\"");
            input.setOnAction(event -> runRedis());
        } else if (page == Page.POWERSHELL) {
            sign.setText("PS›");
            input.setPromptText("run one PowerShell command on this Windows PC");
            input.setOnAction(event -> runPowerShell());
        }
        if (page != Page.LOGS) Platform.runLater(input::requestFocus);
    }

    private void appendRedis(CommandEntry entry) {
        String state = entry.success() ? "OK" : entry.ran() ? "EXIT " + entry.exitCode() : "NOT RUN";
        append(logOutput, CLOCK.format(entry.executedAt()) + "  ·  " + state + "  ·  "
                        + entry.duration().toMillis() + " MS\n" + entry.command(),
                entry.success() ? "prompt-command" : "prompt-error", logScroll);
    }

    private void runRedis() {
        String command = input.getText().strip();
        if (command.isEmpty() || input.isDisabled() || redisRunning) return;
        input.clear();
        input.setDisable(true);
        redisRunning = true;
        if (redisHistory.isEmpty() || !redisHistory.get(redisHistory.size() - 1).equals(command)) {
            redisHistory.add(command);
            if (redisHistory.size() > 100) redisHistory.remove(0);
        }
        historyIndex = redisHistory.size();
        append(cliOutput, CLOCK.format(Instant.now()) + "  R› " + command, "redis-cli-command", cliScroll);
        try {
            onRedisCommand.accept(command);
        } catch (RuntimeException e) {
            completeRedis(command, null);
        }
    }

    private void runPowerShell() {
        String command = input.getText().strip();
        if (command.isEmpty() || input.isDisabled()) return;
        input.clear();
        input.setDisable(true);
        append(powerShellOutput, CLOCK.format(Instant.now()) + "  PS› " + command, "prompt-command", powerShellScroll);
        worker.execute(() -> {
            Result result = execute(command);
            Platform.runLater(() -> {
                if (!result.output().isBlank()) {
                    append(powerShellOutput, result.output().stripTrailing(), result.exitCode() == 0
                            ? "prompt-output" : "prompt-error", powerShellScroll);
                }
                append(powerShellOutput, result.summary(), result.exitCode() == 0 ? "prompt-meta" : "prompt-error",
                        powerShellScroll);
                input.setDisable(false);
                input.requestFocus();
            });
        });
    }

    private Result execute(String command) {
        Path output = null;
        long started = System.nanoTime();
        try {
            output = Files.createTempFile("kubo-powershell-", ".log");
            ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-Command", "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); " + command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(output.toFile());
            process = builder.start();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new Result(-1, read(output), "STOPPED  ·  60 S LIMIT");
            }
            int exit = process.exitValue();
            long millis = (System.nanoTime() - started) / 1_000_000;
            return new Result(exit, read(output), "EXIT " + exit + "  ·  " + millis + " MS");
        } catch (IOException e) {
            return new Result(-1, "", "POWERSHELL COULD NOT START  ·  " + message(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "", "INTERRUPTED");
        } finally {
            process = null;
            if (output != null) try { Files.deleteIfExists(output); } catch (IOException ignored) { }
        }
    }

    private static String read(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_OUTPUT_BYTES + 1);
            int kept = Math.min(bytes.length, MAX_OUTPUT_BYTES);
            String text = new String(bytes, 0, kept, StandardCharsets.UTF_8);
            return bytes.length > MAX_OUTPUT_BYTES ? text + "\n[Kubo_ kept the first 1 MiB]" : text;
        }
    }

    private static String message(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static void append(VBox output, String text, String style, ScrollPane scroll) {
        Label line = label(text, style);
        line.setWrapText(true);
        line.setMinHeight(Region.USE_PREF_SIZE);
        output.getChildren().add(line);
        while (output.getChildren().size() > MAX_BLOCKS) output.getChildren().remove(0);
        Platform.runLater(() -> { scroll.layout(); scroll.setVvalue(1); });
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

    private static void visible(Region node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }

    private record Result(int exitCode, String output, String summary) { }
}
