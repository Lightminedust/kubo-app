package com.kubo.ui.connection;

import com.kubo.connection.SavedSession;
import com.kubo.connection.SavedSession.Auth;
import com.kubo.connection.SessionStore;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.connection.ssh.SshConnectionException;
import com.kubo.connection.ssh.SshCredentials;
import com.kubo.connection.ssh.SshTarget;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
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

import javafx.scene.control.ScrollPane;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
 */
public final class ConnectionView extends StackPane {

    private enum Tone {
        NEUTRAL,
        PENDING,
        OK,
        ERROR
    }

    private static final Color DOT_IDLE = Color.web("#6e6e7d");
    private static final Color DOT_BUSY = Color.web("#ff3fd8");
    private static final Color DOT_LIVE = Color.web("#35e0a1");
    private static final Color DOT_ALERT = Color.web("#ff2d55");
    private static final double STEP_OFF = 0.18;
    private static final DateTimeFormatter USED = DateTimeFormatter.ofPattern("dd MMM yyyy  HH:mm", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());

    private final ParticleField field = new ParticleField();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-ssh");
        thread.setDaemon(true);
        return thread;
    });

    private final Circle stateDot = new Circle(3.5, DOT_IDLE);
    private final Label portLabel = label("PORT —", "series");
    private final Region targetStep = step("step-target");
    private final Region credentialsStep = step("step-credentials");
    private final Region sessionStep = step("step-session");

    private final ToggleGroup modeGroup = new ToggleGroup();
    private final ToggleButton passwordMode = modeToggle("PASSWORD");
    private final ToggleButton keyMode = modeToggle("PRIVATE KEY");
    private final TextField targetField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final TextField keyFileField = new TextField();
    private final Button browseButton = new Button("BROWSE");
    private final PasswordField passphraseField = new PasswordField();
    private final VBox passwordGroup;
    private final VBox keyGroup;
    private final VBox form;
    private final Button connectButton = new Button("CONNECT");
    private final Label statusLabel = label("", "status");

    private final Label hostKeyHost = label("", "host-key-host");
    private final Label hostKeyAlgorithm = label("", "field-label");
    private final TextField hostKeyFingerprint = new TextField();
    private final VBox hostKeyCard;

    private final SessionStore store;
    private final VBox sessionList = new VBox(10);
    private final ToggleButton saveToggle = modeToggle("SAVE THIS SESSION");
    private final ToggleButton rememberToggle = modeToggle("REMEMBER PASSWORD");
    private final Label rememberNote = label("Encrypted by Windows for your account only (DPAPI). Anyone using your open Windows "
            + "session could connect with it.", "remember-note");
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

    public ConnectionView() {
        this(SessionStore.standard());
    }

    public ConnectionView(SessionStore store) {
        this.store = store;
        getStyleClass().add("connection-view");

        targetField.setPromptText("user@host:port");
        passwordField.setPromptText("password");
        keyFileField.setPromptText("C:\\Users\\you\\.ssh\\key.ppk");
        passphraseField.setPromptText("empty if none");
        hostKeyFingerprint.setEditable(false);
        hostKeyFingerprint.getStyleClass().add("fingerprint");

        passwordMode.setToggleGroup(modeGroup);
        keyMode.setToggleGroup(modeGroup);
        passwordMode.setSelected(true);
        HBox modes = new HBox(28, passwordMode, keyMode);

        browseButton.getStyleClass().add("ghost-button");
        browseButton.setOnAction(event -> browseKeyFile());
        HBox keyFileRow = new HBox(14, keyFileField, browseButton);
        keyFileRow.setAlignment(Pos.BOTTOM_LEFT);
        HBox.setHgrow(keyFileField, Priority.ALWAYS);

        passwordGroup = fieldGroup("PASSWORD", passwordField);
        keyGroup = new VBox(22, fieldGroup("KEY FILE  ·  PPK OR OPENSSH", keyFileRow),
                fieldGroup("PASSPHRASE", passphraseField));

        connectButton.getStyleClass().add("primary-button");
        connectButton.setDefaultButton(true);
        connectButton.setOnAction(event -> onConnectPressed());
        statusLabel.setWrapText(true);
        statusLabel.setMinHeight(Region.USE_PREF_SIZE);

        saveToggle.setSelected(true);
        rememberNote.setWrapText(true);
        rememberNote.setMinHeight(Region.USE_PREF_SIZE);
        saveToggle.selectedProperty().addListener((observable, previous, value) -> updateRemember());
        rememberToggle.selectedProperty().addListener((observable, previous, value) -> updateRemember());
        VBox saving = new VBox(8, new HBox(24, saveToggle, rememberToggle), rememberNote);

        form = new VBox(20, modes, fieldGroup("TARGET", targetField), passwordGroup, keyGroup, saving,
                new VBox(16, connectButton, statusLabel));
        form.setMaxWidth(340);

        hostKeyCard = buildHostKeyCard();
        StackPane formArea = new StackPane(form, hostKeyCard);
        StackPane.setAlignment(form, Pos.TOP_LEFT);
        StackPane.setAlignment(hostKeyCard, Pos.TOP_LEFT);

        // The particle scene is a full-bleed background. The former BorderPane center confined it to a narrow
        // rectangle between both columns when the window was maximized.
        BorderPane layout = new BorderPane();
        layout.setTop(buildHeader());
        layout.setLeft(buildColumn(formArea));
        layout.setRight(buildSessions());
        layout.setPickOnBounds(false);
        field.setMouseTransparent(true);

        VBox colorBar = new VBox(targetStep, credentialsStep, sessionStep);
        colorBar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        colorBar.setMouseTransparent(true);
        StackPane.setAlignment(colorBar, Pos.BOTTOM_LEFT);

        Label note = label("READ ONLY  —  SSH", "vertical-note");
        note.setRotate(-90);
        Group noteGroup = new Group(note);
        noteGroup.setMouseTransparent(true);
        StackPane.setAlignment(noteGroup, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(noteGroup, new Insets(0, 26, 48, 0));

        getChildren().addAll(field, layout, colorBar, noteGroup);

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
        showStatus("READY", Tone.NEUTRAL);
    }

    /**
     * Closes the session and stops background work. Call when the window closes.
     */
    public void shutdown() {
        closed = true;
        if (connection != null) {
            connection.close();
            connection = null;
        }
        if (hostKeyDecision != null) {
            hostKeyDecision.complete(false);
        }
        field.stop();
        worker.shutdownNow();
    }

    /**
     * Shows a given state with sample data and no server, to render previews.
     */
    void showPreview(ParticleField.State state, boolean useKey) {
        targetField.setText("deploy@203.0.113.10");
        (useKey ? keyMode : passwordMode).setSelected(true);
        if (useKey) {
            keyFileField.setText("C:\\Users\\you\\keys\\vps.ppk");
        } else {
            passwordField.setText("sample");
        }

        switch (state) {
            case CONNECTING -> showConnecting(SshTarget.parse(targetField.getText()));
            case AWAITING_HOST_KEY -> showHostKey(new SshConnection.HostKey("203.0.113.10", "ssh-ed25519",
                    "SHA256:q3Xw1nK8v2Lr0Tz6yHc9PbE4mJ7sA5dFgU1iOoZlRkY"), new CompletableFuture<>());
            case CONNECTED -> showConnected(SshTarget.parse(targetField.getText()), 184);
            case FAILED -> showFailure(new SshConnectionException(SshConnectionException.Reason.AUTH_FAILED,
                    "Methods accepted by the server: publickey"));
            default -> {
            }
        }
        field.stop();
        field.snapToState();
        applyCss();
        layout();
        field.renderFrame(8);
    }

    private HBox buildHeader() {
        Label wordmark = com.kubo.ui.RootButton.wordmark();
        HBox brand = new HBox(wordmark);
        brand.setAlignment(Pos.BOTTOM_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Region rule = new Region();
        rule.getStyleClass().add("series-rule");

        HBox header = new HBox(14, brand, spacer, rule, portLabel, stateDot);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(28, 48, 0, 36));
        return header;
    }

    private ScrollPane buildColumn(StackPane formArea) {
        Label kicker = label("SSH.SESSION", "series");
        Region dash = new Region();
        dash.getStyleClass().add("dash");
        Label title = label("CONNECT\nTO YOUR\nSERVER", "title");
        Label lede = label("Kubo reads your server over SSH without installing anything on it. Passwords and "
                + "passphrases are saved only if you ask, encrypted for your Windows account.", "lede");
        lede.setWrapText(true);
        lede.setMaxWidth(300);
        // Never squeeze the text when the form below grows.
        title.setMinHeight(Region.USE_PREF_SIZE);
        lede.setMinHeight(Region.USE_PREF_SIZE);

        VBox column = new VBox(kicker, dash, title, lede, formArea);
        VBox.setMargin(dash, new Insets(22, 0, 0, 0));
        VBox.setMargin(title, new Insets(20, 0, 16, 0));
        VBox.setMargin(formArea, new Insets(36, 0, 0, 0));
        column.setPadding(new Insets(40, 0, 32, 80));
        column.setMinWidth(470);
        column.setPrefWidth(470);
        ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefWidth(470);
        scroll.setMinWidth(390);
        scroll.getStyleClass().add("connection-scroll");
        return scroll;
    }

    private VBox buildHostKeyCard() {
        Label kicker = label("UNKNOWN SERVER", "series");
        Label explanation = label("Kubo has never seen this server. Compare the fingerprint with the one shown by "
                + "PuTTY or your hosting provider before trusting it.", "lede");
        explanation.setWrapText(true);

        Button trust = new Button("TRUST");
        trust.getStyleClass().add("primary-button");
        trust.setOnAction(event -> resolveHostKey(true));
        Button reject = new Button("REJECT");
        reject.getStyleClass().add("ghost-button");
        reject.setOnAction(event -> resolveHostKey(false));
        HBox actions = new HBox(24, trust, reject);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(16, kicker, hostKeyHost, new VBox(6, hostKeyAlgorithm, hostKeyFingerprint),
                explanation, actions);
        VBox.setMargin(actions, new Insets(8, 0, 0, 0));
        card.getStyleClass().add("host-key-card");
        card.setMaxWidth(360);
        card.setVisible(false);
        return card;
    }

    private void onConnectPressed() {
        if (connection != null) {
            disconnect();
            return;
        }

        SshTarget target;
        try {
            target = SshTarget.parse(targetField.getText());
        } catch (IllegalArgumentException e) {
            showStatus("INVALID TARGET — " + e.getMessage(), Tone.ERROR);
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
                showStatus("CHOOSE A PRIVATE KEY FILE", Tone.ERROR);
                return;
            }
            try {
                keyPath = Path.of(keyFile);
            } catch (InvalidPathException e) {
                showStatus("INVALID KEY FILE PATH — " + keyFile, Tone.ERROR);
                return;
            }
        } else if (typed.isEmpty() && stored == null) {
            showStatus("ENTER THE PASSWORD", Tone.ERROR);
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
                Platform.runLater(() -> showStatusIfCurrent(thisAttempt, "THE SAVED SECRET COULD NOT BE DECRYPTED — "
                        + "TYPE IT AGAIN (" + e.getMessage() + ")"));
                return;
            }
            SshCredentials credentials = auth == Auth.KEY
                    ? new SshCredentials.KeyFile(finalKeyPath, secret)
                    : new SshCredentials.Password(secret);
            SshConnection opened;
            try {
                opened = SshConnection.open(target, credentials, this::askHostKey);
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
                    saveProblem = "SESSION NOT SAVED: " + e.getMessage();
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
                opened.hostKeyWarning().ifPresent(warning -> warnings.add(warning.toUpperCase(Locale.ROOT)
                        + ". YOU WILL BE ASKED TO CONFIRM IT AGAIN NEXT TIME"));
                if (problem != null) {
                    warnings.add(problem.toUpperCase(Locale.ROOT));
                }
                if (!warnings.isEmpty()) {
                    showStatus("CONNECTED — " + target + "  ·  " + String.join("  ·  ", warnings), Tone.PENDING);
                }
                // Let the lit beam be seen before moving on; longer when there is something to read.
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
        connectButton.setDisable(false);
        field.setState(ParticleField.State.FAILED);
        field.setHandshake("FAILED");
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

    private VBox buildSessions() {
        Label kicker = label("SAVED.SESSIONS", "series");
        Label lede = label("Saved after each successful connection. Key files are remembered by their path; "
                + "passwords and passphrases only if you tick “remember”.", "lede");
        lede.setWrapText(true);
        lede.setMinHeight(Region.USE_PREF_SIZE);
        ScrollPane scroll = new ScrollPane(sessionList);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox column = new VBox(12, kicker, lede, scroll);
        VBox.setMargin(scroll, new Insets(14, 0, 0, 0));
        column.setPadding(new Insets(40, 48, 32, 0));
        column.setPrefWidth(360);
        column.setMinWidth(300);
        return column;
    }

    private void refreshSessions() {
        List<SavedSession> sessions = store.list();
        sessionList.getChildren().clear();
        if (sessions.isEmpty()) {
            Label none = label("NONE YET.", "field-label");
            sessionList.getChildren().add(none);
            return;
        }
        for (SavedSession session : sessions) {
            sessionList.getChildren().add(sessionCard(session));
        }
    }

    private VBox sessionCard(SavedSession session) {
        boolean key = session.auth() == Auth.KEY;
        Label target = label(session.target().toString(), "session-target");
        target.setMinWidth(0);
        HBox.setHgrow(target, Priority.ALWAYS);
        target.setMaxWidth(Double.MAX_VALUE);
        Button delete = new Button("✕");
        delete.getStyleClass().add("session-delete");
        delete.setFocusTraversable(false);
        delete.setOnAction(event -> {
            event.consume();
            runStoreChange(() -> store.delete(session.id()));
            if (selectedSession != null && selectedSession.id().equals(session.id())) {
                selectedSession = null;
            }
        });
        HBox top = new HBox(8, target, delete);
        top.setAlignment(Pos.CENTER_LEFT);

        String keyName = key ? keyFileName(session.keyFile()) : "";
        Label method = label(key ? "PRIVATE KEY  ·  " + keyName : "PASSWORD", "field-label");
        VBox card = new VBox(6, top, method);
        if (key && !keyFileExists(session.keyFile())) {
            card.getChildren().add(label("KEY FILE NOT FOUND: " + session.keyFile(), "session-warning"));
        }
        if (session.remembersSecret()) {
            Button forget = new Button("FORGET");
            forget.getStyleClass().add("session-link");
            forget.setFocusTraversable(false);
            forget.setOnAction(event -> {
                event.consume();
                runStoreChange(() -> store.forgetSecret(session.id()));
                if (selectedSession != null && selectedSession.id().equals(session.id())) {
                    selectedSession = null;
                    rememberToggle.setSelected(false);
                    updatePrompts();
                }
            });
            Label badge = label(key ? "PASSPHRASE REMEMBERED" : "PASSWORD REMEMBERED", "badge");
            badge.getStyleClass().add("badge-accent");
            HBox remembered = new HBox(10, badge, forget);
            remembered.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().add(remembered);
        }
        Button connect = new Button("CONNECT  →");
        connect.getStyleClass().add("session-connect");
        connect.setFocusTraversable(false);
        connect.setOnAction(event -> {
            event.consume();
            fill(session);
            boolean needsTyping = !key && !session.remembersSecret();
            if (needsTyping) {
                passwordField.requestFocus();
                showStatus("ENTER THE PASSWORD FOR " + session.target(), Tone.PENDING);
            } else if (connection == null && !busy) {
                onConnectPressed();
            }
        });
        Label used = label("LAST USED " + USED.format(Instant.ofEpochMilli(session.lastUsedMillis())), "field-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottom = new HBox(8, used, spacer, connect);
        bottom.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(bottom);

        card.getStyleClass().addAll("session-card", key ? "session-card-key" : "session-card-password");
        if (selectedSession != null && selectedSession.id().equals(session.id())) {
            card.getStyleClass().add("session-card-selected");
        }
        card.setOnMouseClicked(event -> {
            if (!busy && connection == null) {
                fill(session);
            }
        });
        return card;
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
        showStatus("SESSION " + session.target() + " READY", Tone.NEUTRAL);
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
        rememberToggle.setText(keyMode.isSelected() ? "REMEMBER PASSPHRASE" : "REMEMBER PASSWORD");
        rememberToggle.setDisable(!saveToggle.isSelected() || !canRemember || busy || connection != null);
        if (!saveToggle.isSelected()) {
            rememberToggle.setSelected(false);
        }
        boolean showNote = rememberToggle.isSelected() || !canRemember;
        rememberNote.setText(canRemember ? rememberNote.getText() : "REMEMBERING SECRETS NEEDS WINDOWS PROTECTION.");
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
            showStatus("SESSIONS FILE NOT WRITTEN — " + e.getMessage(), Tone.ERROR);
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
        hostKeyAlgorithm.setText(hostKey.algorithm().toUpperCase() + "  ·  KEY FINGERPRINT");
        hostKeyFingerprint.setText(hostKey.fingerprint());
        form.setVisible(false);
        hostKeyCard.setVisible(true);
        field.setState(ParticleField.State.AWAITING_HOST_KEY);
        field.setHandshake("HOST KEY PENDING");
        stateDot.setFill(DOT_ALERT);
        updateSteps();
    }

    private void resolveHostKey(boolean trusted) {
        if (hostKeyDecision != null) {
            hostKeyDecision.complete(trusted);
            hostKeyDecision = null;
        }
        hideHostKey();
        if (trusted) {
            field.setState(ParticleField.State.CONNECTING);
            field.setHandshake("NEGOTIATING");
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
        connectButton.setDisable(true);
        field.setState(ParticleField.State.CONNECTING);
        field.setHandshake("NEGOTIATING");
        stateDot.setFill(DOT_BUSY);
        showStatus("CONNECTING TO " + target, Tone.PENDING);
        updateSteps();
    }

    private void showConnected(SshTarget target, long handshakeMs) {
        busy = false;
        hideHostKey();
        setFormDisabled(true);
        connectButton.setDisable(false);
        connectButton.setText("DISCONNECT");
        field.setState(ParticleField.State.CONNECTED);
        field.setHandshake(handshakeMs + " MS");
        stateDot.setFill(DOT_LIVE);
        showStatus("CONNECTED — " + target, Tone.OK);
        sessionStep.setOpacity(1);
        credentialsStep.setOpacity(1);
    }

    private void showFailure(SshConnectionException failure) {
        busy = false;
        if (hostKeyDecision != null) {
            hostKeyDecision.complete(false);
            hostKeyDecision = null;
        }
        hideHostKey();
        setFormDisabled(false);
        connectButton.setDisable(false);
        field.setState(ParticleField.State.FAILED);
        field.setHandshake("FAILED");
        stateDot.setFill(DOT_ALERT);

        String prefix = switch (failure.reason()) {
            case KEY_UNREADABLE -> "KEY UNREADABLE";
            case UNREACHABLE -> "SERVER UNREACHABLE";
            case HOST_KEY_REJECTED -> "SERVER NOT TRUSTED";
            case HOST_KEY_CHANGED -> "HOST KEY CHANGED";
            case AUTH_FAILED -> "AUTHENTICATION REFUSED";
        };
        showStatus(prefix + " — " + failure.getMessage(), Tone.ERROR);
        updateSteps();
    }

    private void disconnect() {
        SshConnection closing = connection;
        connection = null;
        worker.execute(closing::close);
        setFormDisabled(false);
        connectButton.setText("CONNECT");
        field.setState(ParticleField.State.IDLE);
        field.setHandshake("IDLE");
        stateDot.setFill(DOT_IDLE);
        showStatus("DISCONNECTED", Tone.NEUTRAL);
        updateSteps();
    }

    private void browseKeyFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a private key");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Private keys", "*.ppk", "*.pem", "*.key", "id_*"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File sshDirectory = Path.of(System.getProperty("user.home"), ".ssh").toFile();
        if (sshDirectory.isDirectory()) {
            chooser.setInitialDirectory(sshDirectory);
        }
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) {
            keyFileField.setText(file.getAbsolutePath());
        }
    }

    private void updateMode() {
        boolean useKey = keyMode.isSelected();
        passwordGroup.setVisible(!useKey);
        passwordGroup.setManaged(!useKey);
        keyGroup.setVisible(useKey);
        keyGroup.setManaged(useKey);
        updateRemember();
        formEdited();
    }

    private void updateSteps() {
        SshTarget target = parseOrNull(targetField.getText());
        portLabel.setText(target == null ? "PORT —" : "PORT " + target.port());
        field.setRemote(target == null ? "NO TARGET YET" : target.toString());

        boolean hasCredentials = keyMode.isSelected()
                ? !keyFileField.getText().isBlank()
                : !passwordField.getText().isEmpty();
        boolean active = busy || connection != null;
        targetStep.setOpacity(target != null ? 1 : STEP_OFF);
        credentialsStep.setOpacity(hasCredentials || active ? 1 : STEP_OFF);
        sessionStep.setOpacity(connection != null ? 1 : hostKeyCard.isVisible() ? 0.55 : STEP_OFF);
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

    private void showStatus(String message, Tone tone) {
        statusLabel.setText("—  " + message);
        statusLabel.getStyleClass().removeAll("status-pending", "status-ok", "status-error");
        switch (tone) {
            case PENDING -> statusLabel.getStyleClass().add("status-pending");
            case OK -> statusLabel.getStyleClass().add("status-ok");
            case ERROR -> statusLabel.getStyleClass().add("status-error");
            default -> {
            }
        }
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

    private static VBox fieldGroup(String caption, Region input) {
        return new VBox(6, label(caption, "field-label"), input);
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static Region step(String styleClass) {
        Region region = new Region();
        region.getStyleClass().addAll("step", styleClass);
        region.setOpacity(STEP_OFF);
        return region;
    }

    private static ToggleButton modeToggle(String text) {
        Circle marker = new Circle(3);
        marker.getStyleClass().add("mode-marker");
        ToggleButton toggle = new ToggleButton(text, marker);
        toggle.getStyleClass().add("mode-toggle");
        return toggle;
    }
}
