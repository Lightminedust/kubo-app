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
    /**
     * Long, because a command with a terminal may be sitting there waiting for somebody to type something,
     * and twenty seconds is not enough to find a passphrase. STOP is the way out before this.
     */
    private static final java.time.Duration TIMEOUT = java.time.Duration.ofMinutes(15);

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
    /** The command still running, or null. While it is there, the field answers instead of starting one. */
    private volatile SshConnection.Live live;
    /** The same field, masked. Swapped in rather than toggled, because a text field cannot become a password
     * field after the fact. */
    private final javafx.scene.control.PasswordField hidden = new javafx.scene.control.PasswordField();
    private final EyeButton reveal = new EyeButton();
    /** Says whether the console is waiting for you or you for it. */
    private final RunningMark mark = new RunningMark();
    private final Button stop = new Button("STOP");
    private boolean masked;
    /** True between pressing Enter and the command coming back, whether or not the handle has arrived yet. */
    private boolean busy;
    /** True when the field takes what is typed. Idle it always does; running it does only while asked. */
    private boolean open = true;
    private Page current = Page.LOGS;
    private double expandedHeight = 178;
    private double dragFromY;
    private double dragFromHeight;
    private boolean collapsed;
    /** Built the first time a file is opened, and kept: it is the same connection either way. */
    private com.kubo.files.FileBrowser browser;
    /** One window per file, the way the files screen does it, so the same file is never open twice. */
    private final java.util.Map<String, com.kubo.ui.files.EditorWindow> editors = new java.util.HashMap<>();
    /** One window per followed command, so typing it twice brings the first one back rather than doubling it. */
    private final java.util.Map<String, com.kubo.ui.logs.StreamWindow> streams = new java.util.HashMap<>();
    private com.kubo.ui.shell.ProcessWindow processes;
    private com.kubo.ui.shell.TopWindow running;
    /**
     * Where the interactive shell Kubo is relaying for has got to, or null when what is waiting is not a
     * shell at all. Read out of the prompt, which is the only place it is written down.
     */
    private String shellFolder;

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
                + "IT CAN ANSWER WHAT A COMMAND ASKS  ·  AN EDITOR OPENS THE FILE  ·  ↑↓ HISTORY",
                "metric-label");
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
        // A filter, not a handler: it has to run before the one below, which silences the keys while a
        // command is running, and it has to be on both fields.
        input.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, this::typedKey);
        hidden.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, this::typedKey);
        input.setOnKeyPressed(event -> {
            if (live != null) {
                // While a command is waiting, the arrows are not a history: what is being typed may be a
                // secret, and pulling the last command over it would be the least of the surprises.
                return;
            }
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
        HBox.setHgrow(hidden, Priority.ALWAYS);
        hidden.getStyleClass().add("prompt-input");
        hidden.setOnAction(event -> run());
        hidden.setVisible(false);
        hidden.setManaged(false);

        // The only way to type at a command that has not asked anything Kubo could see. A button, not a
        // click in the field: a field is clicked by accident all day long, and having that quietly open a
        // hidden input was worse than the problem it solved.
        reveal.setVisible(false);
        reveal.setManaged(false);
        reveal.setOnAction(event -> {
            if (!open) {
                // Opened hidden. Not knowing what is being asked for is the reason to hide it, not a reason
                // to show it; one more press reveals it if it turns out to be a user name.
                mask(true);
                listening(true);
                return;
            }
            mask(!masked);
        });

        stop.getStyleClass().addAll("danger-quiet", "compact-button");
        stop.setFocusTraversable(false);
        stop.setVisible(false);
        stop.setManaged(false);
        stop.setTooltip(new javafx.scene.control.Tooltip("Sends Control-C, the way a terminal does"));
        stop.setOnAction(event -> {
            SshConnection.Live running = live;
            if (running != null) {
                running.interrupt();
            }
        });

        inputRow = new HBox(10, mark, input, hidden, reveal, stop, rootNote);
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

    /**
     * Only for the previews: the shell partway through a command that is asking for something.
     *
     * @param printed the first line is what was typed, the rest is what came back
     */
    public void showShellForPreview(java.util.List<String> printed) {
        show(Page.SHELL);
        working(true);
        for (int at = 0; at < printed.size(); at++) {
            if (at == 0) {
                shellOutput.append(printed.get(at), "shell-sent");
            } else {
                heardForPreview(printed.get(at));
            }
        }
    }

    private void heardForPreview(String line) {
        boolean question = com.kubo.model.shell.Prompt.asksSomething(line);
        shellOutput.append(line, question ? "shell-asking" : "prompt-output");
        if (!question) {
            mark.set(RunningMark.State.RUNNING, false);
            listening(false);
            return;
        }
        mark.set(RunningMark.State.ASKING, false);
        mask(com.kubo.model.shell.Prompt.asksForASecret(line));
        listening(true);
    }

    public void shutdown() {
        stopListening.run();
        editors.values().forEach(com.kubo.ui.files.EditorWindow::close);
        editors.clear();
        streams.values().forEach(com.kubo.ui.logs.StreamWindow::close);
        streams.clear();
        if (processes != null) {
            processes.close();
        }
        if (running != null) {
            running.close();
        }
        worker.shutdownNow();
    }

    private void updateRootMark() {
        boolean elevated = root != null && root.isElevated();
        mark.set(mark.state(), elevated);
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
                !entry.success() ? "prompt-error"
                        : logged.typed() ? "shell-sent" : "prompt-command");
    }

    private void sayCopied(int count) {
        copied.setText(count == 1 ? "COPIED 1 LINE" : "COPIED " + count + " LINES");
        copiedFades.playFromStart();
    }

    /**
     * Sends what was typed: a new command, or an answer to the one already running.
     *
     * <p>The difference matters beyond the plumbing. A command is logged; an answer is not. A passphrase typed
     * because ssh-keygen asked for one is not something Kubo sent, it is something somebody told the server,
     * and it has no business in a history, in an arrow-up list, or in a file on disk.
     */
    private void run() {
        SshConnection.Live running = live;
        if (running != null) {
            if (input.isEditable() || hidden.isEditable()) {
                answer(running);
            }
            return;
        }
        if (busy) {
            // Enter pressed in the moment between a command leaving and its handle coming back. It is not an
            // invitation to run it a second time.
            return;
        }
        String command = input.getText().strip();
        if (command.isEmpty() || connection == null) {
            return;
        }
        input.clear();
        if (history.isEmpty() || !history.get(history.size() - 1).equals(command)) {
            history.add(command);
            if (history.size() > 100) {
                history.remove(0);
            }
        }
        historyIndex = history.size();
        java.util.Optional<com.kubo.model.shell.EditorCall> opening =
                com.kubo.model.shell.EditorCall.of(command);
        if (opening.isPresent()) {
            openEditor(command, opening.get());
            return;
        }
        java.util.Optional<com.kubo.model.shell.TableCall> table =
                com.kubo.model.shell.TableCall.of(command);
        if (table.isPresent() && connection != null) {
            openTable(command, table.get());
            return;
        }
        java.util.Optional<com.kubo.model.shell.StreamCall> following =
                com.kubo.model.shell.StreamCall.of(command);
        if (following.isPresent() && connection != null) {
            openStream(command, following.get());
            return;
        }
        boolean elevated = root != null && root.isElevated();
        shellOutput.append(CLOCK.format(Instant.now()) + "  " + (elevated ? "# " : "$ ") + command,
                elevated ? "shell-sent-root" : "shell-sent");
        // Whatever the reader was looking at, they just pressed Enter: what happens next is the answer to it.
        shellOutput.follow();
        working(true);

        worker.execute(() -> {
            CommandEntry entry;
            try {
                String sent = command;
                byte[] password = null;
                if (elevated) {
                    Privilege.Command wrapped = root.privilege().asRoot(command);
                    sent = wrapped.command();
                    password = wrapped.input();
                }
                byte[] first = password;
                entry = connection.execInteractive(sent,
                        line -> Platform.runLater(() -> heard(line)),
                        handle -> {
                            live = handle;
                            // The sudo password goes the way it always has: straight in, never typed.
                            if (first != null && first.length > 0) {
                                handle.answer(new String(first, java.nio.charset.StandardCharsets.UTF_8)
                                        .stripTrailing().toCharArray());
                            }
                        },
                        TIMEOUT);
            } catch (RuntimeException e) {
                entry = null;
            }
            if (entry != null && commands != null) {
                // Logged as typed, under the command as it was typed: the sudo wrapping is how, not what, and
                // what was answered while it ran is not part of it at all.
                commands.add(new CommandEntry(entry.executedAt(), command, elevated, entry.exitCode(),
                        entry.output(), entry.error(), entry.duration()), typedAs, true);
            }
            CommandEntry result = entry;
            Platform.runLater(() -> {
                live = null;
                working(false);
                if (result == null) {
                    shellOutput.append("KUBO COULD NOT RUN THAT.", "prompt-error");
                } else {
                    shellOutput.append("exit " + result.exitCode() + "  \u00b7  "
                                    + result.duration().toMillis() + " ms",
                            result.success() ? "shell-done" : "shell-failed");
                }
                shellOutput.follow();
                focusField();
            });
        });
    }

    /** One line the command answered with, and what it tells the field to do. */
    private void heard(SshConnection.Line line) {
        String text = com.kubo.model.shell.Prompt.tidy(line.text());
        // Not read out of the words. The command stopped on this line without ending it, which is what asking
        // looks like from here whether the line says "Password:" or draws a shell prompt.
        boolean question = line.waiting();
        if (!text.isEmpty()) {
            // A line that wants something back is not the same as a line reporting what happened, and reading
            // a wall of output to find the one that stopped is exactly what nobody should have to do.
            shellOutput.append(text, question ? "shell-asking" : "prompt-output");
        }
        if (live == null) {
            return;
        }
        shellOutput.follow();
        if (question) {
            shellFolder = com.kubo.model.shell.Prompt.shellFolder(text).orElse(null);
            mark.set(RunningMark.State.ASKING, root != null && root.isElevated());
            // Only where it starts. One press either way, and the guess never overrides a person twice.
            mask(com.kubo.model.shell.Prompt.asksForASecret(text));
            listening(true);
        } else if (!typing()) {
            // This line finished, so nothing is waiting on it any more. Output that arrives in pieces goes
            // quiet mid-line often enough to look like a question for a moment, and that moment must not
            // leave the field open and amber for the rest of the command. Never while somebody is mid-word.
            mark.set(RunningMark.State.RUNNING, root != null && root.isElevated());
            listening(false);
        }
    }

    /** Sends one answer, and keeps no trace of it. */
    private void answer(SshConnection.Live running) {
        javafx.scene.control.TextField field = masked ? hidden : input;
        String typed = field.getText();
        if (!masked && shellFolder != null && intercepted(typed)) {
            // Typed at a shell prompt, so it is a command to that shell and deserves what a command typed
            // anywhere else here gets. Nothing is sent: the shell stays at its prompt, waiting.
            field.clear();
            shellOutput.follow();
            return;
        }
        running.answer(typed.toCharArray());
        field.clear();
        // Never echoed by Kubo: if the far end echoes it, that is the far end's business and it is a terminal.
        // Never in the history either, whatever it was.
        if (masked) {
            // Echo is off at the far end, so a hidden answer leaves no trace anywhere and somebody who has just
            // typed a password has nothing at all telling them it went. Say that it did, never what it was.
            shellOutput.append("answer sent", "prompt-meta");
        }
        mark.set(RunningMark.State.RUNNING, root != null && root.isElevated());
        listening(false);
        shellOutput.follow();
    }

    /**
     * Opens or closes the field, which is the whole of the protection here.
     *
     * <p>Closed means a command is running and nothing has asked for anything. Typing then is typing into the
     * dark, and the dark has two exits. The command may read it later as the answer to a question that has not
     * been printed yet, which is how somebody ends up sending a password to the wrong prompt. Or the command
     * ends first, the field goes idle with the text still in it, and the next Enter runs it as a command and
     * writes it to the log. A password typed one second early is a password in a log file.
     *
     * <p>So it is only open when something has actually asked, or when nothing is running at all.
     */
    private void listening(boolean wanted) {
        boolean was = open;
        open = wanted;
        input.setEditable(open);
        hidden.setEditable(open);
        // Always there while something runs, open or not: it is both the switch for hiding what is typed and
        // the way to start typing at all when Kubo did not see the question.
        reveal.setVisible(busy);
        reveal.setManaged(busy);
        if (!busy) {
            input.setPromptText("run a command on the server");
            hidden.setPromptText("");
        } else if (open) {
            input.setPromptText("answer it");
            hidden.setPromptText("answer it, nothing is shown");
        } else {
            input.setPromptText("it is running, nothing is being asked");
            hidden.setPromptText("it is running, nothing is being asked");
        }
        if (open && !was) {
            focusField();
        }
    }

    /**
     * Control-C, from the keyboard, which is where anybody would reach for it.
     *
     * <p>It was reaching nothing. The keys are silenced while a command runs, so that the arrows cannot pull
     * the history over an answer somebody is typing, and Control-C was being silenced with them. The STOP
     * button did work, which is not much comfort to a pair of hands already on the keys.
     *
     * <p>A selection still copies. Control-C over selected text has meant copy for thirty years, and a
     * console is the last place to start arguing about it.
     */
    private void typedKey(javafx.scene.input.KeyEvent event) {
        if (event.getCode() != javafx.scene.input.KeyCode.C || !event.isControlDown()) {
            return;
        }
        SshConnection.Live running = live;
        javafx.scene.control.TextField field = masked ? hidden : input;
        if (running == null || !field.getSelectedText().isEmpty()) {
            return;
        }
        running.interrupt();
        shellOutput.append("^C", "prompt-meta");
        shellOutput.follow();
        event.consume();
    }

    /**
     * Opens Kubo's editor on the file somebody just asked an editor for.
     *
     * <p>Not logged as a command, because none was sent. What the editor does is: it reads and writes over
     * the same connection, and those commands turn up in the log under FILES, which is where they belong.
     */
    private void openEditor(String typed, com.kubo.model.shell.EditorCall call) {
        shellOutput.append(CLOCK.format(Instant.now()) + "  $ " + typed, "shell-sent");
        if (connection == null || commands == null) {
            return;
        }
        if (call.paths().isEmpty()) {
            shellOutput.append("KUBO EDIT OPENS A FILE. NAME ONE: " + call.program() + " /etc/hosts",
                    "prompt-meta");
            shellOutput.follow();
            return;
        }
        if (call.paths().size() > 1) {
            shellOutput.append("ONE FILE AT A TIME. OPENING " + call.paths().get(0), "prompt-meta");
        }
        if (call.asRoot() && (root == null || !root.isElevated())) {
            // Said once, and it does not stop the file opening: plenty of them are readable either way, and
            // finding out on the save is worse than being told now.
            shellOutput.append("ROOT IS THE SWITCH ON THIS SCREEN, NOT THE WORD SUDO. WITHOUT IT THIS SAVES "
                    + "ONLY WHAT YOU CAN ALREADY WRITE.", "prompt-meta");
        }
        String wanted = call.paths().get(0);
        shellOutput.follow();
        if (wanted.startsWith("/")) {
            openFile(wanted);
            return;
        }
        // A relative name has to be turned into a real path, and only the server knows where home is.
        worker.execute(() -> {
            String home = browser().account().map(com.kubo.model.files.Account::home).orElse("");
            String path = com.kubo.model.shell.EditorCall.resolve(wanted, home);
            Platform.runLater(() -> openFile(path));
        });
    }

    private void openFile(String path) {
        com.kubo.ui.files.EditorWindow window = editors.get(path);
        if (window == null || !window.isShowing()) {
            // Never closed to make room for another. Somebody may be halfway through a change in it.
            window = new com.kubo.ui.files.EditorWindow(browser(), path, saved -> { });
            editors.put(path, window);
        }
        window.show();
    }

    private com.kubo.files.FileBrowser browser() {
        if (browser == null) {
            browser = new com.kubo.files.FileBrowser(connection, commands);
            if (root != null) {
                browser.setRootSession(root);
            }
        }
        return browser;
    }

    /**
     * Sends a command that follows a log to a window of its own.
     *
     * <p>It keeps running there while somebody works anywhere else in Kubo, on its own channel, and closing
     * the window is what stops it. The console gets its field back immediately, which it would not if this
     * ran here: a command that never ends holds the field until somebody remembers to press STOP.
     */
    private void openStream(String typed, com.kubo.model.shell.StreamCall call) {
        shellOutput.append(CLOCK.format(Instant.now()) + "  $ " + typed, "shell-sent");
        shellOutput.append("IT FOLLOWS A LOG, SO IT OPENED IN ITS OWN WINDOW. CLOSING THAT STOPS IT.",
                "prompt-meta");
        shellOutput.follow();
        com.kubo.ui.logs.StreamWindow window = streams.get(call.command());
        if (window == null || !window.isShowing()) {
            window = new com.kubo.ui.logs.StreamWindow(connection, call);
            streams.put(call.command(), window);
        }
        window.show();
    }

    /** Sends a command whose output is a drawn table to the table Kubo draws itself. */
    private void openTable(String typed, com.kubo.model.shell.TableCall call) {
        shellOutput.append(CLOCK.format(Instant.now()) + "  $ " + typed, "shell-sent");
        shellOutput.append(call.kind() == com.kubo.model.shell.TableCall.Kind.PROCESSES
                        ? "IT DRAWS A SCREEN, SO KUBO ASKED PS THE SAME QUESTION AND DREW THE ANSWER."
                        : "READ AS DATA RATHER THAN AS A DRAWING, IN ITS OWN WINDOW.",
                "prompt-meta");
        shellOutput.follow();
        if (call.kind() == com.kubo.model.shell.TableCall.Kind.PM2) {
            if (processes == null || !processes.isShowing()) {
                processes = new com.kubo.ui.shell.ProcessWindow(connection, commands);
            }
            processes.show();
            return;
        }
        if (running == null || !running.isShowing()) {
            running = new com.kubo.ui.shell.TopWindow(connection, commands, root);
        }
        running.show();
    }

    /**
     * Answers a command typed inside an interactive shell with the screen Kubo has for it, if it has one.
     *
     * <p>Inside "sudo su" every line goes to that shell as input, so nothing typed there was reaching any of
     * this: nano drew its screen into the console exactly as it did before there was an editor, because the
     * word never passed through the place that catches it.
     *
     * @return true when it was handled here and must not be sent on
     */
    private boolean intercepted(String typed) {
        String command = typed.strip();
        if (command.isEmpty() || connection == null) {
            return false;
        }
        java.util.Optional<com.kubo.model.shell.EditorCall> opening =
                com.kubo.model.shell.EditorCall.of(command);
        if (opening.isPresent()) {
            return openFromShell(command, opening.get());
        }
        java.util.Optional<com.kubo.model.shell.TableCall> table =
                com.kubo.model.shell.TableCall.of(command);
        if (table.isPresent()) {
            openTable(command, table.get());
            return true;
        }
        java.util.Optional<com.kubo.model.shell.StreamCall> following =
                com.kubo.model.shell.StreamCall.of(command);
        if (following.isPresent()) {
            openStream(command, following.get());
            return true;
        }
        return false;
    }

    /**
     * Opens a file named inside an interactive shell.
     *
     * <p>A relative name only means something against the folder that shell is in, and Kubo only knows that
     * when the prompt writes it down, which the default one on Debian and its family does. Against a prompt
     * somebody has rewritten, it says so instead of guessing: opening the wrong file is worse than opening
     * none at all.
     */
    private boolean openFromShell(String command, com.kubo.model.shell.EditorCall call) {
        if (call.paths().isEmpty()) {
            return false;
        }
        String wanted = call.paths().get(0);
        String folder = shellFolder;
        if (!wanted.startsWith("/") && !wanted.startsWith("~") && (folder == null || folder.startsWith("~"))) {
            shellOutput.append(CLOCK.format(Instant.now()) + "  $ " + command, "shell-sent");
            shellOutput.append("KUBO CANNOT SEE WHERE THIS SHELL IS. GIVE THE WHOLE PATH, OR LEAVE THE SHELL "
                    + "AND TYPE IT HERE.", "prompt-meta");
            shellOutput.follow();
            return true;
        }
        shellOutput.append(CLOCK.format(Instant.now()) + "  $ " + command, "shell-sent");
        shellOutput.append("IT DRAWS A SCREEN, SO KUBO OPENED THE FILE INSTEAD.", "prompt-meta");
        shellOutput.follow();
        openFile(com.kubo.model.shell.EditorCall.resolve(wanted, folder));
        return true;
    }

    /** Puts the caret back where somebody is about to type. */
    private void focusField() {
        javafx.scene.control.TextField field = masked ? hidden : input;
        // Deferred. Showing one field and hiding the other settles over the rest of this pulse, and a focus
        // asked for in the middle of that does not stick: the caret ends up nowhere and the next thing typed
        // goes nowhere with it. Which is why every command had to be followed by a click in the field.
        Platform.runLater(field::requestFocus);
    }

    /** Whether there is something in the field that a person put there. */
    private boolean typing() {
        return !input.getText().isEmpty() || !hidden.getText().isEmpty();
    }

    /** Swaps the plain field for the masked one, and back. */
    private void mask(boolean on) {
        if (masked == on) {
            return;
        }
        masked = on;
        input.setVisible(!on);
        input.setManaged(!on);
        hidden.setVisible(on);
        hidden.setManaged(on);
        reveal.setHiding(on);
        focusField();
    }

    /** What the row looks like while a command is running, and afterwards. */
    private void working(boolean yes) {
        busy = yes;
        stop.setVisible(yes);
        stop.setManaged(yes);
        boolean elevated = root != null && root.isElevated();
        if (!yes) {
            // Nothing half-typed survives the command it was meant for. Left there, it would be sitting in an
            // idle field one Enter away from being run as a command, and logged as one.
            input.clear();
            hidden.clear();
            mask(false);
            shellFolder = null;
        }
        mark.set(yes ? RunningMark.State.RUNNING : RunningMark.State.IDLE, elevated);
        listening(!yes);
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
