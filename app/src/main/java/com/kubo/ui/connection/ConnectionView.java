package com.kubo.ui.connection;

import com.kubo.connection.SavedSession;
import com.kubo.connection.SavedSession.Auth;
import com.kubo.connection.SessionStore;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.connection.ssh.SshConnectionException;
import com.kubo.connection.ssh.SshCredentials;
import com.kubo.connection.ssh.SshTarget;
import com.kubo.ui.StatusLine;
import com.kubo.ui.Tile;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * First screen: choose a server, authenticate with a password or a private key, confirm the host key.
 *
 * <p>On the left the connection itself, drawn: this computer, the server, and the steps between them as the
 * attempt goes through them; under it the form. On the right the saved sessions, and what Kubo is.
 */
public final class ConnectionView extends StackPane {

    private enum Tone {
        NEUTRAL,
        PENDING,
        OK,
        ERROR
    }

    private static final Color DOT_IDLE = Color.web("#6e6e7d");
    private static final Color DOT_BUSY = Color.web("#e75879");
    private static final Color DOT_LIVE = Color.web("#35e0a1");
    private static final Color DOT_ALERT = Color.web("#ff2d55");
    private static final String READY = "type an address, or pick a saved session";

    private final ConnectionLink link = new ConnectionLink();
    private final StudioPanel studio = new StudioPanel();
    private final StatusLine statusLine = new StatusLine();
    private Tile savedTile;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-ssh");
        thread.setDaemon(true);
        return thread;
    });

    private final Circle stateDot = new Circle(3.5, DOT_IDLE);
    private final Label portLabel = label("port —", "series");
    private final Label tally = label("", "home-muted");

    private final ToggleGroup modeGroup = new ToggleGroup();
    private final ToggleButton passwordMode = chipToggle("password");
    private final ToggleButton keyMode = chipToggle("key file");
    private final TextField targetField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final TextField keyFileField = new TextField();
    private final Button browseButton = action("browse");
    private final PasswordField passphraseField = new PasswordField();
    private final VBox passwordGroup;
    private final VBox keyGroup;
    private final VBox form;
    private final Button connectButton = new Button("connect");
    private final Button cancelButton = action("cancel");
    private final Label statusLabel = label("", "card-problem");

    private final Label hostKeyHost = label("", "fetch-host");
    private final Label hostKeyAlgorithm = label("", "home-muted");
    private final TextField hostKeyFingerprint = new TextField();
    private final VBox hostKeyCard;

    private final SessionStore store;
    private final VBox sessionList = new VBox(2);
    private final ToggleButton saveToggle = chipToggle("save this session");
    private final ToggleButton rememberToggle = chipToggle("remember the password");
    private final Label rememberNote = label("", "home-muted");
    private final List<Button> sessionActions = new ArrayList<>();
    private Button connectingSessionButton;
    private SavedSession selectedSession;
    private boolean filling;

    private CompletableFuture<Boolean> hostKeyDecision;
    private volatile long promptNanos;
    private boolean busy;
    private SshConnection connection;
    private Consumer<SshConnection> onConnected;
    // Set when the view closes: a connection finishing afterwards is closed at once instead of being handed over.
    private volatile boolean closed;
    private long attempt;

    /**
     * Receives the open connection once the "connected" state has been shown; the receiver then owns it.
     */
    public void setOnConnected(Consumer<SshConnection> onConnected) {
        this.onConnected = onConnected;
    }

    public void setOnCheckUpdates(Runnable check) {
        studio.setOnCheckUpdates(check);
    }

    public ConnectionView() {
        this(SessionStore.standard());
    }

    public ConnectionView(SessionStore store) {
        this.store = store;
        getStyleClass().add("connection-view");
        heightProperty().addListener((observable, before, height) ->
                pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("compact"), height.doubleValue() < 720));

        targetField.setPromptText("user@host:port");
        passwordField.setPromptText("password");
        keyFileField.setPromptText("C:\\Users\\you\\.ssh\\key.ppk");
        passphraseField.setPromptText("empty if none");
        hostKeyFingerprint.setEditable(false);
        hostKeyFingerprint.getStyleClass().add("fingerprint");

        passwordMode.setToggleGroup(modeGroup);
        keyMode.setToggleGroup(modeGroup);
        passwordMode.setSelected(true);
        HBox modes = new HBox(8, passwordMode, keyMode);
        modes.setAlignment(Pos.CENTER_LEFT);

        browseButton.setOnAction(event -> browseKeyFile());
        HBox keyFileRow = new HBox(14, keyFileField, browseButton);
        keyFileRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(keyFileField, Priority.ALWAYS);

        passwordGroup = new VBox(promptRow("password", passwordField));
        keyGroup = new VBox(4, promptRow("key", keyFileRow), promptRow("phrase", passphraseField));

        connectButton.getStyleClass().addAll("primary-button", "compact-button");
        connectButton.setMinWidth(Region.USE_PREF_SIZE);
        connectButton.setDefaultButton(true);
        connectButton.setOnAction(event -> onConnectPressed());
        cancelButton.setOnAction(event -> cancelAttempt());
        showCancel(false);
        statusLabel.setWrapText(true);
        statusLabel.setMinHeight(Region.USE_PREF_SIZE);
        statusLabel.setMinWidth(0);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        saveToggle.setSelected(true);
        rememberNote.setText(store.protectionNote().toLowerCase(Locale.ROOT));
        rememberNote.setWrapText(true);
        rememberNote.setMinHeight(Region.USE_PREF_SIZE);
        saveToggle.selectedProperty().addListener((observable, previous, value) -> updateRemember());
        rememberToggle.selectedProperty().addListener((observable, previous, value) -> updateRemember());
        HBox keeping = new HBox(8, saveToggle, rememberToggle);
        keeping.setAlignment(Pos.CENTER_LEFT);

        HBox actions = new HBox(16, connectButton, cancelButton, statusLabel);
        actions.setAlignment(Pos.CENTER_LEFT);
        // What this form amounts to, as the command it stands in for.
        Label command = label("", "connection-command");
        command.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(() -> {
            SshTarget target = parseOrNull(targetField.getText());
            return target == null ? "$ ssh user@host"
                    : "$ ssh -p " + target.port() + " " + target.username() + "@" + target.host();
        }, targetField.textProperty()));
        form = new VBox(10, command, line("auth", modes), promptRow("ssh", targetField), passwordGroup, keyGroup,
                line("keep", new VBox(6, keeping, rememberNote)), actions);
        VBox.setMargin(actions, new Insets(8, 0, 0, 0));
        form.setMaxWidth(Double.MAX_VALUE);
        form.getStyleClass().add("connection-form");

        hostKeyCard = buildHostKeyCard();
        form.managedProperty().bind(form.visibleProperty());
        hostKeyCard.managedProperty().bind(hostKeyCard.visibleProperty());
        StackPane formArea = new StackPane(form, hostKeyCard);
        StackPane.setAlignment(form, Pos.TOP_LEFT);
        StackPane.setAlignment(hostKeyCard, Pos.TOP_LEFT);

        // The connection drawn on top, the form under it, the whole tile scrolling when the window is short.
        Region rule = new Region();
        rule.getStyleClass().add("fetch-rule");
        rule.setMinHeight(1);
        rule.setMaxHeight(1);
        VBox sshBody = new VBox(16, link, rule, formArea);
        ScrollPane formScroll = new ScrollPane(sshBody);
        formScroll.setFitToWidth(true);
        formScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        formScroll.getStyleClass().add("track-scroll");
        Tile ssh = new Tile("ssh", formScroll);
        ssh.getStyleClass().add("tile-active");

        ScrollPane sessionScroll = new ScrollPane(sessionList);
        sessionScroll.setFitToWidth(true);
        sessionScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sessionScroll.getStyleClass().add("track-scroll");
        savedTile = new Tile("saved", sessionScroll).accent("tile-cyan");
        VBox.setVgrow(savedTile, Priority.ALWAYS);

        Tile about = new Tile("about", studio).accent("tile-violet");
        about.setMinHeight(Region.USE_PREF_SIZE);

        VBox right = new VBox(18, savedTile, about);
        right.setMinWidth(0);
        HBox tiles = new HBox(18, ssh, right);
        tiles.getStyleClass().add("tiles");
        HBox.setHgrow(ssh, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        // Half each. Left to their content the form would take what it asked for and the list the rest,
        // and the split would move every time a session was saved.
        ssh.prefWidthProperty().bind(tiles.widthProperty().multiply(0.5));
        right.prefWidthProperty().bind(tiles.widthProperty().multiply(0.5));

        statusLine.fact("ssh");
        statusLine.fact("nothing is installed on the server");

        BorderPane workspace = new BorderPane(tiles);
        workspace.setTop(buildHeader());
        workspace.setBottom(statusLine);
        workspace.getStyleClass().add("connection-workspace");
        getChildren().add(workspace);

        modeGroup.selectedToggleProperty().addListener((observable, previous, selected) -> {
            if (selected == null) {
                previous.setSelected(true);
                return;
            }
            updateMode();
        });
        targetField.textProperty().addListener((observable, previous, text) -> formEdited());
        passwordField.textProperty().addListener((observable, previous, text) -> updateSteps());
        keyFileField.textProperty().addListener((observable, previous, text) -> formEdited());

        updateMode();
        updateRemember();
        refreshSessions();
        showStatus(READY, Tone.NEUTRAL);
    }

    /**
     * Closes the session and stops background work. Call when the window closes.
     */
    public void shutdown() {
        closed = true;
        link.stop();
        if (connection != null) {
            connection.close();
            connection = null;
        }
        if (hostKeyDecision != null) {
            hostKeyDecision.complete(false);
        }
        worker.shutdownNow();
    }

    /**
     * Shows a given state with sample data and no server, to render previews.
     */
    void showPreview(ConnectionLink.Phase phase, boolean useKey) {
        targetField.setText("deploy@203.0.113.10");
        (useKey ? keyMode : passwordMode).setSelected(true);
        if (useKey) {
            keyFileField.setText("C:\\Users\\you\\keys\\vps.ppk");
        } else {
            passwordField.setText("sample");
        }

        switch (phase) {
            case CONNECTING -> {
                showConnecting(SshTarget.parse(targetField.getText()));
                link.reach(ConnectionLink.SIGN_IN);
                link.timesForPreview(-1, 212, -1);
                // The connecting preview represents a click on the remembered session at the bottom of the list.
                sessionActions.stream()
                        .filter(button -> button.getStyleClass().contains("session-connect"))
                        .reduce((first, second) -> second)
                        .ifPresent(this::showSavedSessionProgress);
            }
            case HOST_KEY -> {
                showConnecting(SshTarget.parse(targetField.getText()));
                link.reach(ConnectionLink.REACH);
                showHostKey(new SshConnection.HostKey("203.0.113.10", "ssh-ed25519",
                        "SHA256:q3Xw1nK8v2Lr0Tz6yHc9PbE4mJ7sA5dFgU1iOoZlRkY"), new CompletableFuture<>());
                link.timesForPreview(6, 188, -1);
            }
            case CONNECTED -> {
                showConnecting(SshTarget.parse(targetField.getText()));
                showConnected(SshTarget.parse(targetField.getText()), 431);
                link.timesForPreview(6, 188, 237);
            }
            case FAILED -> {
                showConnecting(SshTarget.parse(targetField.getText()));
                link.reach(ConnectionLink.SIGN_IN);
                showFailure(new SshConnectionException(SshConnectionException.Reason.AUTH_FAILED,
                        "Methods accepted by the server: publickey"));
                link.timesForPreview(-1, 205, -1);
            }
            default -> {
            }
        }
        link.freezeForPreview(1.3, "workstation");
        applyCss();
        layout();
    }

    /**
     * The bar across the top, the way tmux keeps one: the session on the left, the window you are in, and
     * a few facts in blocks on the right. Under it the one action this screen has besides connecting.
     */
    private VBox buildHeader() {
        Label session = com.kubo.ui.RootButton.wordmark();
        Label window = label("connect", "term-window");
        Label quiet = label("ssh, nothing installed on the server", "term-window-quiet");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        portLabel.getStyleClass().setAll("label", "term-fact");
        HBox state = new HBox(8, stateDot, portLabel);
        state.setAlignment(Pos.CENTER_LEFT);
        state.getStyleClass().add("term-block");
        HBox bar = new HBox(14, session, window, quiet, spacer, state);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("term-bar");

        Button fresh = action("new connection");
        fresh.setOnAction(event -> clearForm());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        tally.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(18, fresh, gap, tally);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("home-nav", "redis-actions");
        return new VBox(bar, row);
    }

    /** Empties the form for a server that is not saved yet. */
    private void clearForm() {
        if (busy || connection != null) {
            return;
        }
        filling = true;
        selectedSession = null;
        targetField.clear();
        passwordField.clear();
        keyFileField.clear();
        passphraseField.clear();
        passwordMode.setSelected(true);
        saveToggle.setSelected(true);
        rememberToggle.setSelected(false);
        filling = false;
        updatePrompts();
        refreshSessions();
        updateSteps();
        link.reset();
        stateDot.setFill(DOT_IDLE);
        showStatus(READY, Tone.NEUTRAL);
        targetField.requestFocus();
    }

    private VBox buildHostKeyCard() {
        Label kicker = label("unknown server", "fetch-note");
        Label explanation = label("kubo has never seen this server. compare this fingerprint with the one your "
                + "host or PuTTY shows before trusting it. once trusted, it is checked at every connection, and a "
                + "server that changes it is refused.", "home-muted");
        explanation.setWrapText(true);
        explanation.setMinHeight(Region.USE_PREF_SIZE);

        Button trust = new Button("trust it");
        trust.getStyleClass().addAll("primary-button", "compact-button");
        trust.setOnAction(event -> resolveHostKey(true));
        Button reject = action("reject");
        reject.setOnAction(event -> resolveHostKey(false));
        HBox actions = new HBox(18, trust, reject);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(12, kicker, hostKeyHost, new VBox(4, hostKeyAlgorithm, hostKeyFingerprint),
                explanation, actions);
        VBox.setMargin(actions, new Insets(6, 0, 0, 0));
        card.setMaxWidth(Double.MAX_VALUE);
        card.setVisible(false);
        return card;
    }

    private void onConnectPressed() {
        // Disabling the button normally prevents a second click. Keep the state guard as well so pressing Enter,
        // or another control calling this method during the same attempt, can never open a parallel connection.
        if (busy) {
            return;
        }
        if (connection != null) {
            disconnect();
            return;
        }

        SshTarget target;
        try {
            target = SshTarget.parse(targetField.getText());
        } catch (IllegalArgumentException e) {
            showStatus("that address does not read as user@host or user@host:port: " + lower(e.getMessage()),
                    Tone.ERROR);
            return;
        }
        Auth auth = keyMode.isSelected() ? Auth.KEY : Auth.PASSWORD;
        String keyFile = auth == Auth.KEY ? unquote(keyFileField.getText().strip()) : null;
        String typed = auth == Auth.KEY ? passphraseField.getText() : passwordField.getText();
        // A remembered secret is used when nothing was typed for the session it belongs to.
        SavedSession stored = selectedSession != null && selectedSession.sameAs(target, auth, keyFile)
                && selectedSession.remembersSecret() && typed.isEmpty() ? selectedSession : null;

        Path keyPath = null;
        if (auth == Auth.KEY) {
            if (keyFile.isEmpty()) {
                showStatus("choose the private key file", Tone.ERROR);
                return;
            }
            try {
                keyPath = Path.of(keyFile);
            } catch (InvalidPathException e) {
                showStatus("that key file path is not a path: " + keyFile, Tone.ERROR);
                return;
            }
        } else if (typed.isEmpty() && stored == null) {
            showStatus("type the password", Tone.ERROR);
            passwordField.requestFocus();
            return;
        }

        boolean save = saveToggle.isSelected();
        boolean remember = save && rememberToggle.isSelected() && store.canRememberSecrets();
        // The fields hold Strings we cannot wipe; at least drop them as soon as the secret is copied.
        char[] typedSecret = typed.toCharArray();
        char[] toRemember = remember && typedSecret.length > 0 ? typedSecret.clone() : null;
        passwordField.clear();
        passphraseField.clear();

        showConnecting(target);
        promptNanos = 0;
        long started = System.nanoTime();
        long thisAttempt = ++attempt;
        Path finalKeyPath = keyPath;
        worker.execute(() -> {
            char[] secret = typedSecret;
            try {
                if (stored != null) {
                    Arrays.fill(typedSecret, '\0');
                    secret = store.secret(stored);
                }
            } catch (IOException e) {
                wipe(toRemember);
                Platform.runLater(() -> showStatusIfCurrent(thisAttempt,
                        "the saved secret could not be read, type it again (" + e.getMessage() + ")"));
                return;
            }
            SshCredentials credentials = auth == Auth.KEY
                    ? new SshCredentials.KeyFile(finalKeyPath, secret)
                    : new SshCredentials.Password(secret);
            SshConnection opened;
            try {
                opened = SshConnection.open(target, credentials, this::askHostKey,
                        step -> Platform.runLater(() -> showStep(thisAttempt, step)));
            } catch (SshConnectionException e) {
                wipe(toRemember);
                Platform.runLater(() -> {
                    if (!closed && thisAttempt == attempt) {
                        showFailure(e);
                    }
                });
                return;
            }
            if (closed) {
                // The window closed while connecting: nobody will use this connection.
                wipe(toRemember);
                opened.close();
                return;
            }
            long handshakeMs = (System.nanoTime() - started - promptNanos) / 1_000_000;
            String saveProblem = null;
            if (save) {
                try {
                    store.remember(target, auth, keyFile, remember, toRemember);
                } catch (IOException e) {
                    saveProblem = "the session was not saved: " + e.getMessage();
                }
            }
            wipe(toRemember);
            String problem = saveProblem;
            Platform.runLater(() -> {
                if (closed || thisAttempt != attempt) {
                    worker.execute(opened::close);
                    return;
                }
                connection = opened;
                refreshSessions();
                showConnected(target, handshakeMs);
                List<String> warnings = new ArrayList<>();
                opened.hostKeyWarning().ifPresent(warning -> warnings.add(lower(warning)
                        + ". you will be asked to confirm it again next time"));
                if (problem != null) {
                    warnings.add(problem);
                }
                if (!warnings.isEmpty()) {
                    showStatus("connected to " + target + "  ·  " + String.join("  ·  ", warnings), Tone.PENDING);
                }
                // Show the result before moving on; leave more time when there are warnings to read.
                PauseTransition pause = new PauseTransition(Duration.millis(warnings.isEmpty() ? 1400 : 6000));
                pause.setOnFinished(event -> handOver());
                pause.play();
            });
        });
    }

    private void showStatusIfCurrent(long thisAttempt, String message) {
        if (closed || thisAttempt != attempt) {
            return;
        }
        busy = false;
        setFormDisabled(false);
        showConnectionProgress(false);
        showCancel(false);
        connectButton.setText("connect");
        setSessionActionsDisabled(false);
        connectButton.setDisable(false);
        link.failed(ConnectionLink.KEY, "the saved secret could not be read");
        stateDot.setFill(DOT_ALERT);
        showStatus(message, Tone.ERROR);
        updateSteps();
    }

    private static void wipe(char[] secret) {
        if (secret != null) {
            Arrays.fill(secret, '\0');
        }
    }

    // Saved sessions

    private void refreshSessions() {
        List<SavedSession> sessions = store.list();
        long remembered = sessions.stream().filter(SavedSession::remembersSecret).count();
        tally.setText(sessions.isEmpty() ? "" : sessions.size() + (sessions.size() == 1 ? " saved" : " saved")
                + (remembered > 0 ? "  ·  " + remembered + " remembered" : ""));
        if (savedTile != null) {
            savedTile.setTag(sessions.isEmpty() ? "" : String.valueOf(sessions.size()));
        }
        sessionActions.clear();
        sessionList.getChildren().clear();
        if (sessions.isEmpty()) {
            Label none = label("no saved session yet. connect once with save this session on, and it waits "
                    + "here.", "home-muted");
            none.setWrapText(true);
            none.setPadding(new Insets(4, 0, 0, 0));
            sessionList.getChildren().add(none);
            return;
        }
        for (SavedSession session : sessions) {
            sessionList.getChildren().add(sessionRow(session));
        }
    }

    /**
     * One saved session, as a lane: a mark for how it signs in, the address, how, and what can be done with
     * it at the end. A key file that is not where it was has its own line, with the way to point at it again.
     */
    private HBox sessionRow(SavedSession session) {
        boolean key = session.auth() == Auth.KEY;
        boolean selected = selectedSession != null && selectedSession.id().equals(session.id());

        Region mark = new Region();
        mark.setMinSize(8, 8);
        mark.setMaxSize(8, 8);
        mark.setStyle("-fx-background-color: " + (key ? "#b58cff" : "#35e8ff") + ";");
        VBox markBox = new VBox(mark);
        markBox.setPadding(new Insets(5, 0, 0, 0));

        Label target = label(session.target().toString(), selected ? "apps-mini-name-on" : "apps-mini-name");
        target.setMinWidth(0);
        String how = key ? "key · " + keyFileName(session.keyFile()) : "password";
        if (session.remembersSecret()) {
            how = how + " · remembered";
        }
        VBox details = new VBox(4, target, label(how, "home-muted"));
        details.setMinWidth(0);
        details.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(details, Priority.ALWAYS);
        if (key && !keyFileExists(session.keyFile())) {
            Label warning = label("key file not found: " + session.keyFile(), "fetch-note");
            warning.setWrapText(true);
            warning.setMinWidth(0);
            warning.setTooltip(new Tooltip(session.keyFile()));
            Button find = action("find it");
            find.getStyleClass().addAll("apps-warn", "apps-inline");
            sessionActions.add(find);
            find.setOnAction(event -> {
                event.consume();
                relocateKey(session);
            });
            HBox warningRow = new HBox(10, warning, find);
            warningRow.setAlignment(Pos.TOP_LEFT);
            details.getChildren().add(warningRow);
        }

        HBox row = new HBox(12, markBox, details);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(10, 10, 10, 10));
        row.getStyleClass().add(selected ? "apps-mini-row-on" : "apps-mini-row");

        if (session.remembersSecret()) {
            Button forget = action("forget");
            sessionActions.add(forget);
            forget.setTooltip(new Tooltip("Erases the remembered " + (key ? "passphrase" : "password")
                    + " and keeps the session"));
            forget.setOnAction(event -> {
                event.consume();
                runStoreChange(() -> store.forgetSecret(session.id()));
                if (selectedSession != null && selectedSession.id().equals(session.id())) {
                    selectedSession = null;
                    rememberToggle.setSelected(false);
                    updatePrompts();
                }
            });
            row.getChildren().add(forget);
        }
        Button connect = action("connect");
        connect.getStyleClass().addAll("session-connect", "apps-lit");
        sessionActions.add(connect);
        connect.setOnAction(event -> {
            event.consume();
            fill(session);
            boolean needsTyping = !key && !session.remembersSecret();
            if (needsTyping) {
                passwordField.requestFocus();
                showStatus("type the password for " + session.target(), Tone.PENDING);
            } else if (connection == null && !busy) {
                onConnectPressed();
                if (busy) {
                    showSavedSessionProgress(connect);
                }
            }
        });
        Button delete = action("×");
        delete.getStyleClass().add("port-danger");
        delete.setTooltip(new Tooltip("Removes this session from the list"));
        sessionActions.add(delete);
        delete.setOnAction(event -> {
            event.consume();
            runStoreChange(() -> store.delete(session.id()));
            if (selectedSession != null && selectedSession.id().equals(session.id())) {
                selectedSession = null;
            }
        });
        row.getChildren().addAll(connect, delete);
        row.setOnMouseClicked(event -> {
            if (!busy && connection == null) {
                fill(session);
            }
        });
        return row;
    }

    /** Points a session at its key file where it is now, keeping its remembered passphrase. */
    private void relocateKey(SavedSession session) {
        File file = chooseKeyFile(session.keyFile());
        if (file == null) {
            return;
        }
        runStoreChange(() -> store.relocateKey(session.id(), file.getAbsolutePath()));
        if (selectedSession != null && selectedSession.id().equals(session.id())) {
            filling = true;
            keyFileField.setText(file.getAbsolutePath());
            filling = false;
            selectedSession = store.list().stream().filter(saved -> saved.id().equals(session.id())).findFirst()
                    .orElse(null);
            refreshSessions();
        }
        showStatus("the key of " + session.target() + " is now " + file.getAbsolutePath(), Tone.NEUTRAL);
    }

    /**
     * Puts a saved session in the form. The secret stays encrypted until the connection needs it.
     */
    private void fill(SavedSession session) {
        if (busy || connection != null) {
            return;
        }
        filling = true;
        selectedSession = session;
        targetField.setText(session.target().toString());
        (session.auth() == Auth.KEY ? keyMode : passwordMode).setSelected(true);
        keyFileField.setText(session.keyFile());
        passwordField.clear();
        passphraseField.clear();
        saveToggle.setSelected(true);
        rememberToggle.setSelected(session.remembersSecret());
        filling = false;
        updatePrompts();
        refreshSessions();
        updateSteps();
        link.reset();
        showStatus(session.target() + " is in the form", Tone.NEUTRAL);
    }

    /**
     * Editing the form away from the chosen session forgets that choice, so its secret is not used for another server.
     */
    private void formEdited() {
        if (!filling && selectedSession != null) {
            SshTarget target = parseOrNull(targetField.getText());
            Auth auth = keyMode.isSelected() ? Auth.KEY : Auth.PASSWORD;
            String keyFile = auth == Auth.KEY ? unquote(keyFileField.getText().strip()) : null;
            if (target == null || !selectedSession.sameAs(target, auth, keyFile)) {
                selectedSession = null;
                updatePrompts();
                refreshSessions();
            }
        }
        updateSteps();
    }

    private void updatePrompts() {
        boolean remembered = selectedSession != null && selectedSession.remembersSecret();
        passwordField.setPromptText(remembered && selectedSession.auth() == Auth.PASSWORD
                ? "remembered · leave empty to use it" : "password");
        passphraseField.setPromptText(remembered && selectedSession.auth() == Auth.KEY
                ? "remembered · leave empty to use it" : "empty if none");
    }

    private void updateRemember() {
        boolean canRemember = store.canRememberSecrets();
        rememberToggle.setText(keyMode.isSelected() ? "remember the passphrase" : "remember the password");
        rememberToggle.setDisable(!saveToggle.isSelected() || !canRemember || busy || connection != null);
        if (!saveToggle.isSelected()) {
            rememberToggle.setSelected(false);
        }
        boolean showNote = rememberToggle.isSelected() || !canRemember;
        rememberNote.setText(canRemember ? store.protectionNote().toLowerCase(Locale.ROOT)
                : "remembering a secret needs windows protection, which this account does not have");
        rememberNote.setVisible(showNote);
        rememberNote.setManaged(showNote);
    }

    private interface StoreChange {
        void run() throws IOException;
    }

    private void runStoreChange(StoreChange change) {
        try {
            change.run();
        } catch (IOException e) {
            showStatus("the sessions file was not written: " + e.getMessage(), Tone.ERROR);
        }
        refreshSessions();
    }

    private static String keyFileName(String keyFile) {
        try {
            Path name = Path.of(keyFile).getFileName();
            return name == null ? keyFile : name.toString();
        } catch (InvalidPathException e) {
            return keyFile;
        }
    }

    private static boolean keyFileExists(String keyFile) {
        try {
            return Files.isRegularFile(Path.of(keyFile));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private void handOver() {
        // The user may have disconnected during the pause.
        if (onConnected == null || connection == null) {
            return;
        }
        SshConnection handed = connection;
        connection = null;
        onConnected.accept(handed);
    }

    /**
     * Called by the SSH layer on its own thread; blocks it until the user trusts or rejects the server.
     */
    private boolean askHostKey(SshConnection.HostKey hostKey) {
        if (closed) {
            return false;
        }
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        long shownAt = System.nanoTime();
        Platform.runLater(() -> showHostKey(hostKey, decision));
        try {
            return decision.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        } finally {
            promptNanos += System.nanoTime() - shownAt;
        }
    }

    private void showHostKey(SshConnection.HostKey hostKey, CompletableFuture<Boolean> decision) {
        hostKeyDecision = decision;
        hostKeyHost.setText(hostKey.host());
        hostKeyAlgorithm.setText(hostKey.algorithm().toLowerCase(Locale.ROOT) + "  ·  fingerprint of its key");
        hostKeyFingerprint.setText(hostKey.fingerprint());
        form.setVisible(false);
        hostKeyCard.setVisible(true);
        link.reach(ConnectionLink.HOST_KEY);
        stateDot.setFill(DOT_ALERT);
        showStatus("the server answered with a key kubo has never seen: check it, then trust or reject it",
                Tone.PENDING);
        updateSteps();
    }

    private void resolveHostKey(boolean trusted) {
        if (hostKeyDecision != null) {
            hostKeyDecision.complete(trusted);
            hostKeyDecision = null;
        }
        hideHostKey();
        if (trusted) {
            link.trusted();
            stateDot.setFill(DOT_BUSY);
        }
    }

    private void hideHostKey() {
        hostKeyCard.setVisible(false);
        form.setVisible(true);
        updateSteps();
    }

    private void showConnecting(SshTarget target) {
        busy = true;
        setFormDisabled(true);
        setSessionActionsDisabled(true);
        showConnectionProgress(true);
        connectButton.setDisable(true);
        showCancel(true);
        link.begin(keyMode.isSelected());
        stateDot.setFill(DOT_BUSY);
        showStatus("opening a connection to " + target, Tone.PENDING);
        updateSteps();
    }

    /**
     * Names the wait the attempt is actually in, on the drawing and in the status line. Each step is reported
     * by the connection itself as it begins, not on a timer, so a slow server never looks like a missed click.
     */
    private void showStep(long thisAttempt, SshConnection.Step step) {
        if (closed || thisAttempt != attempt || !busy) {
            return;
        }
        switch (step) {
            case READING_THE_KEY -> link.reach(ConnectionLink.KEY);
            case REACHING_THE_SERVER -> link.reach(ConnectionLink.REACH);
            case AUTHENTICATING -> link.reach(ConnectionLink.SIGN_IN);
        }
        connectButton.setText(switch (step) {
            case READING_THE_KEY -> "unlocking the key";
            case REACHING_THE_SERVER -> "reaching the server";
            case AUTHENTICATING -> "signing in";
        });
        showStatus(explain(step), Tone.PENDING);
    }

    private static String explain(SshConnection.Step step) {
        return switch (step) {
            case READING_THE_KEY -> "unlocking your key file. nothing has been sent anywhere yet";
            case REACHING_THE_SERVER -> "waiting for the server to answer. a wrong address or a closed port spends "
                    + "its whole timeout here";
            case AUTHENTICATING -> "the server answered. proving who you are";
        };
    }

    /**
     * Gives up on the attempt in flight. The server may still answer, but that answer now belongs to nobody:
     * the attempt number has moved on, so the connection is closed when it arrives rather than used.
     */
    private void cancelAttempt() {
        attempt++;
        busy = false;
        if (hostKeyDecision != null) {
            hostKeyDecision.complete(false);
            hostKeyDecision = null;
        }
        hideHostKey();
        setFormDisabled(false);
        setSessionActionsDisabled(false);
        showConnectionProgress(false);
        showCancel(false);
        connectButton.setDisable(false);
        connectButton.setText("connect");
        link.reset();
        stateDot.setFill(DOT_IDLE);
        showStatus("attempt abandoned. if the server answers now, kubo closes it rather than use it", Tone.PENDING);
        updateSteps();
    }

    private void showCancel(boolean show) {
        cancelButton.setVisible(show);
        cancelButton.setManaged(show);
    }

    private void showConnected(SshTarget target, long handshakeMs) {
        busy = false;
        hideHostKey();
        setFormDisabled(true);
        showConnectionProgress(false);
        showCancel(false);
        setSessionActionsDisabled(true);
        connectButton.setDisable(false);
        connectButton.setText("disconnect");
        link.connected(handshakeMs);
        stateDot.setFill(DOT_LIVE);
        showStatus("connected to " + target, Tone.OK);
    }

    private void showFailure(SshConnectionException failure) {
        busy = false;
        if (hostKeyDecision != null) {
            hostKeyDecision.complete(false);
            hostKeyDecision = null;
        }
        hideHostKey();
        setFormDisabled(false);
        showConnectionProgress(false);
        setSessionActionsDisabled(false);
        showCancel(false);
        connectButton.setDisable(false);
        connectButton.setText("connect");
        stateDot.setFill(DOT_ALERT);

        String words = switch (failure.reason()) {
            case KEY_UNREADABLE -> "the key file could not be read";
            case UNREACHABLE -> "the server did not answer";
            case HOST_KEY_REJECTED -> "the server was not trusted";
            case HOST_KEY_CHANGED -> "the server's key changed since last time";
            case AUTH_FAILED -> "the server refused the sign in";
        };
        link.failed(switch (failure.reason()) {
            case KEY_UNREADABLE -> ConnectionLink.KEY;
            case UNREACHABLE -> ConnectionLink.REACH;
            case HOST_KEY_REJECTED, HOST_KEY_CHANGED -> ConnectionLink.HOST_KEY;
            case AUTH_FAILED -> ConnectionLink.SIGN_IN;
        }, words);
        String detail = failure.getMessage() == null || failure.getMessage().isBlank() ? ""
                : "  ·  " + lower(failure.getMessage());
        showStatus(words + detail, Tone.ERROR);
        updateSteps();
    }

    private void disconnect() {
        SshConnection closing = connection;
        connection = null;
        worker.execute(closing::close);
        setFormDisabled(false);
        showConnectionProgress(false);
        setSessionActionsDisabled(false);
        connectButton.setText("connect");
        link.reset();
        stateDot.setFill(DOT_IDLE);
        showStatus("disconnected", Tone.NEUTRAL);
        updateSteps();
    }

    private void browseKeyFile() {
        File file = chooseKeyFile(keyFileField.getText().strip());
        if (file != null) {
            keyFileField.setText(file.getAbsolutePath());
        }
    }

    /** This computer's file chooser, opened where the key was, or in ~/.ssh. */
    private File chooseKeyFile(String near) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a private key");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Private keys", "*.ppk", "*.pem", "*.key", "id_*"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File start = null;
        try {
            if (near != null && !near.isBlank()) {
                Path parent = Path.of(unquote(near)).getParent();
                if (parent != null && Files.isDirectory(parent)) {
                    start = parent.toFile();
                }
            }
        } catch (InvalidPathException e) {
            start = null;
        }
        if (start == null) {
            File sshDirectory = Path.of(System.getProperty("user.home"), ".ssh").toFile();
            start = sshDirectory.isDirectory() ? sshDirectory : null;
        }
        if (start != null) {
            chooser.setInitialDirectory(start);
        }
        return chooser.showOpenDialog(getScene().getWindow());
    }

    private void updateMode() {
        boolean useKey = keyMode.isSelected();
        passwordGroup.setVisible(!useKey);
        passwordGroup.setManaged(!useKey);
        keyGroup.setVisible(useKey);
        keyGroup.setManaged(useKey);
        if (!busy && connection == null) {
            link.setKey(useKey);
        }
        updateRemember();
        formEdited();
    }

    /** The port in the bar, and the server on the drawing, follow what is typed. */
    private void updateSteps() {
        SshTarget target = parseOrNull(targetField.getText());
        portLabel.setText(target == null ? "port —" : "port " + target.port());
        if (target == null) {
            link.setTarget("", "", 22);
        } else {
            link.setTarget(target.username(), target.host(), target.port());
        }
    }

    private void setFormDisabled(boolean disabled) {
        saveToggle.setDisable(disabled);
        rememberToggle.setDisable(disabled || !saveToggle.isSelected() || !store.canRememberSecrets());
        passwordMode.setDisable(disabled);
        keyMode.setDisable(disabled);
        targetField.setDisable(disabled);
        passwordField.setDisable(disabled);
        keyFileField.setDisable(disabled);
        browseButton.setDisable(disabled);
        passphraseField.setDisable(disabled);
    }

    private void showConnectionProgress(boolean visible) {
        connectButton.getStyleClass().remove("connection-busy");
        if (visible) {
            connectButton.getStyleClass().add("connection-busy");
            connectButton.setText("connecting");
        } else if (connection == null) {
            connectButton.setText("connect");
        }
        if (!visible) {
            hideSavedSessionProgress();
        }
    }

    private void showSavedSessionProgress(Button button) {
        hideSavedSessionProgress();
        com.kubo.ui.Spinner progress = new com.kubo.ui.Spinner();
        progress.getStyleClass().add("connection-progress");
        connectingSessionButton = button;
        button.getStyleClass().add("session-connecting");
        button.setGraphic(progress);
        button.setGraphicTextGap(7);
        button.setText("connecting");
    }

    private void hideSavedSessionProgress() {
        if (connectingSessionButton == null) {
            return;
        }
        connectingSessionButton.getStyleClass().remove("session-connecting");
        connectingSessionButton.setGraphic(null);
        connectingSessionButton.setText("connect");
        connectingSessionButton = null;
    }

    private void setSessionActionsDisabled(boolean disabled) {
        sessionActions.forEach(button -> button.setDisable(disabled));
    }

    private void showStatus(String message, Tone tone) {
        // Beside the button only when something went wrong and the whole sentence has to be read; the
        // line along the bottom says everything else.
        statusLabel.setText(message);
        statusLabel.setVisible(tone == Tone.ERROR);
        statusLabel.setManaged(tone == Tone.ERROR);
        statusLine.show(switch (tone) {
            case PENDING -> StatusLine.Mode.BUSY;
            case OK -> StatusLine.Mode.OK;
            case ERROR -> StatusLine.Mode.ERROR;
            default -> StatusLine.Mode.IDLE;
        }, switch (tone) {
            case PENDING -> "working";
            case OK -> "connected";
            case ERROR -> "failed";
            default -> "ready";
        }, message);
    }

    private static SshTarget parseOrNull(String text) {
        try {
            return SshTarget.parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Removes the quotes Windows adds with "Copy as path".
     */
    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** A sentence from another layer, started in lower case like every other line of the screen. */
    private static String lower(String sentence) {
        return sentence == null || sentence.isEmpty() ? ""
                : Character.toLowerCase(sentence.charAt(0)) + sentence.substring(1);
    }

    /** A line of the form: its word on the left, what it asks for on the right, with a rule under it. */
    private static HBox promptRow(String caption, Region input) {
        input.setMinWidth(0);
        input.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(input, Priority.ALWAYS);
        HBox row = line(caption, input);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("connection-prompt-row");
        return row;
    }

    /** A line of the form without a rule: its word on the left and what it holds. */
    private static HBox line(String caption, Node content) {
        Label key = label(caption, "fetch-key");
        key.setMinWidth(76);
        key.setPrefWidth(76);
        HBox row = new HBox(14, key, content);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static Button action(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("ghost-button");
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    /** A choice drawn as the chips every other screen uses, lit when it is on. */
    private static ToggleButton chipToggle(String text) {
        ToggleButton toggle = new ToggleButton(text);
        toggle.getStyleClass().add("service-chip");
        toggle.setFocusTraversable(false);
        toggle.setMinWidth(Region.USE_PREF_SIZE);
        return toggle;
    }
}
