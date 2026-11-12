package com.kubo.ui;

import com.kubo.KuboApp;
import com.kubo.platform.Host;
import com.kubo.update.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** One asynchronous check per launch. Download and installation both require a user action. */
public final class UpdateController implements AutoCloseable {
    private final Stage owner;
    private final BuildInfo build;
    private final ReleaseClient client;
    private final Path cache;
    private final UpdateSettings settings;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "kubo-updates"); thread.setDaemon(true); return thread;
    });
    private Stage dialog;
    private Label title, detail, error;
    private Button action, notes, later;
    private ProgressBar progress;
    private CheckBox automatic;
    private Future<?> operation;
    private volatile long generation;
    private volatile boolean closed;
    private boolean busy, handingOff, handedOff;
    private Release release;
    private Release.Asset asset;
    private UpdateInstaller.Prepared prepared;

    public UpdateController(Stage owner) {
        this(owner, BuildInfo.current(), new ReleaseClient(), Path.of(System.getProperty("user.home"), ".kubo", "updates"));
    }

    UpdateController(Stage owner, BuildInfo build, ReleaseClient client, Path cache) {
        this.owner = owner;
        this.build = build;
        this.client = client;
        this.cache = cache;
        this.settings = new UpdateSettings(cache.resolve("settings.properties"));
        owner.addEventFilter(javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST, event -> {
            if (handingOff && !handedOff) event.consume();
        });
    }

    public void start() {
        operation = worker.submit(() -> {
            Path result = cache.resolve("last-result.txt");
            try {
                if (Files.isRegularFile(result)) {
                    String outcome = Files.readString(result).replace("\uFEFF", "").strip();
                    Files.delete(result);
                    if (outcome.startsWith("failed:")) {
                        Platform.runLater(() -> { if (!closed) message("Update did not finish", outcome.substring(7).strip()); });
                        return;
                    }
                }
            } catch (IOException ignored) { }
            if (settings.automatic()) Platform.runLater(() -> { if (!closed) check(false); });
        });
    }

    public void checkManually() { check(true); }

    private void check(boolean manual) {
        if (closed) return;
        if (busy || prepared != null) { show(); return; }
        long ticket = ++generation;
        busy = true;
        if (manual) {
            message("Checking for updates", "Kubo " + build.version() + " · " + build.description());
            progress.setProgress(-1);
            visible(progress, true);
        }
        operation = worker.submit(() -> {
            try {
                var latest = client.latest();
                ui(ticket, () -> {
                    busy = false;
                    if (latest.isPresent() && latest.get().version().compareTo(build.version()) > 0) {
                        release = latest.get();
                        asset = release.assetFor(build).orElse(null);
                        offer();
                    } else if (manual) message(latest.isPresent() ? "Kubo is up to date" : "No stable release available",
                            "Installed version: " + build.version());
                });
            } catch (IOException | RuntimeException failure) {
                ui(ticket, () -> { busy = false; if (manual) message("Could not check for updates", failure.getMessage()); });
            }
        });
    }

    private void offer() {
        message("Kubo " + release.version() + " is available", "Installed: " + build.version() + "\n" + build.description());
        visible(notes, true);
        notes.setOnAction(event -> openRelease());
        visible(action, true);
        if (asset == null || build.launcher() == null) {
            detail.setText(detail.getText() + "\nNo matching installable package is available for this build.");
            action.setText("OPEN RELEASE");
            action.setOnAction(event -> openRelease());
        } else {
            detail.setText(detail.getText() + "\n" + String.format(java.util.Locale.ROOT, "Download: %.1f MB", asset.size() / 1048576.0)
                    + " · Your saved servers and logs will be kept.");
            action.setText("DOWNLOAD UPDATE");
            action.setOnAction(event -> download());
        }
    }

    private void download() {
        long ticket = ++generation;
        busy = true;
        action.setDisable(true);
        later.setText("CANCEL");
        error.setText("");
        progress.setProgress(0);
        visible(progress, true);
        title.setText("Downloading Kubo " + release.version());
        operation = worker.submit(() -> {
            UpdateInstaller.Prepared pending = null;
            try {
                Files.createDirectories(cache);
                Path job = Files.createTempDirectory(cache, "update-");
                Path file = client.download(asset, job, value -> ui(ticket, () -> progress.setProgress(value)));
                ui(ticket, () -> title.setText("Preparing the update"));
                pending = UpdateInstaller.prepare(build, asset, file);
                UpdateInstaller.Prepared ready = pending;
                if (closed || ticket != generation) { ready.discard(); return; }
                Platform.runLater(() -> {
                    if (closed || ticket != generation) { discard(ready); return; }
                    busy = false;
                    prepared = ready;
                    title.setText("Kubo " + release.version() + " is ready");
                    detail.setText(build.description() + "\nSHA-256 verified.\nKubo will close, install the update and reopen."
                            + "\nAny current SSH connection will be closed.");
                    visible(progress, false);
                    action.setDisable(false);
                    action.setText(build.kind().portable() ? "RESTART AND UPDATE" : "CLOSE AND INSTALL");
                    action.setOnAction(event -> install());
                    later.setText("LATER");
                    dialog.sizeToScene();
                });
            } catch (IOException | RuntimeException failure) {
                if (pending != null) discard(pending);
                ui(ticket, () -> { busy = false; offer(); error.setText(failure.getMessage()); });
            }
        });
    }

    private void install() {
        if (prepared == null || handingOff) return;
        if (Window.getWindows().stream().anyMatch(window -> window instanceof Stage && window.isShowing()
                && window != owner && window != dialog)) {
            error.setText("Close the other Kubo windows before installing, so any edited files can be saved.");
            return;
        }
        handingOff = true;
        action.setDisable(true);
        later.setDisable(true);
        automatic.setDisable(true);
        long ticket = ++generation;
        UpdateInstaller.Prepared ready = prepared;
        operation = worker.submit(() -> {
            try {
                ready.start();
                ui(ticket, () -> { handedOff = true; prepared = null; Platform.exit(); });
            } catch (IOException failure) {
                ui(ticket, () -> {
                    handingOff = false;
                    action.setDisable(false);
                    later.setDisable(false);
                    automatic.setDisable(false);
                    error.setText(failure.getMessage());
                });
            }
        });
    }

    private void message(String heading, String body) {
        show();
        title.setText(heading);
        detail.setText(body == null ? "Please try again later." : body);
        error.setText("");
        action.setDisable(false);
        visible(action, false);
        visible(notes, false);
        visible(progress, false);
        later.setText("LATER");
    }

    private void show() {
        if (dialog == null) {
            title = label("", "card-title");
            detail = label("", "card-note");
            error = label("", "card-problem");
            for (Label text : new Label[]{title, detail, error}) { text.setWrapText(true); text.setMinHeight(VBox.USE_PREF_SIZE); }
            progress = new ProgressBar(); progress.setMaxWidth(Double.MAX_VALUE);
            action = new Button(); action.getStyleClass().add("primary-button");
            notes = new Button("RELEASE NOTES ↗"); notes.getStyleClass().add("ghost-button");
            later = new Button("LATER"); later.getStyleClass().add("ghost-button");
            later.setOnAction(event -> dismiss());
            automatic = new CheckBox("Check for updates at startup");
            automatic.setSelected(settings.automatic());
            automatic.getStyleClass().add("update-preference");
            automatic.setOnAction(event -> {
                try { settings.automatic(automatic.isSelected()); }
                catch (IOException failure) { error.setText("Could not save the update preference."); }
            });
            HBox actions = new HBox(18, action, later); actions.setAlignment(Pos.CENTER_LEFT);
            VBox body = new VBox(16, label("KUBO / UPDATE", "series"), title, detail, progress, error, notes, actions, automatic);
            body.setPadding(new Insets(26));
            body.setPrefWidth(510);
            body.getStyleClass().add("update-panel");
            dialog = new Stage(); dialog.initOwner(owner); dialog.setTitle("Kubo — Updates");
            KuboWindow frame = KuboWindow.installBare(dialog, body);
            Scene scene = new Scene(frame); scene.getStylesheets().add(KuboApp.STYLESHEET);
            dialog.setScene(scene); dialog.setResizable(false);
            dialog.setOnCloseRequest(event -> { event.consume(); if (!handingOff) dismiss(); });
        }
        if (!dialog.isShowing()) {
            dialog.show();
            dialog.setX(owner.getX() + (owner.getWidth() - dialog.getWidth()) / 2);
            dialog.setY(owner.getY() + (owner.getHeight() - dialog.getHeight()) / 2);
        }
        dialog.toFront();
        // Text and progress change after show(); size to the completed state on the next pulse.
        Platform.runLater(() -> { if (!closed && dialog.isShowing()) dialog.sizeToScene(); });
    }

    private void openRelease() {
        try { Host.openLink(release.page().toString()); }
        catch (IOException failure) { error.setText("Could not open the release page."); }
    }

    private void dismiss() {
        generation++;
        if (operation != null) operation.cancel(true);
        busy = false;
        if (prepared != null) { discard(prepared); prepared = null; }
        dialog.hide();
    }

    private static Label label(String text, String style) { Label label = new Label(text); label.getStyleClass().add(style); return label; }
    private static void visible(javafx.scene.Node node, boolean value) { node.setVisible(value); node.setManaged(value); }
    private void ui(long ticket, Runnable action) { Platform.runLater(() -> { if (!closed && generation == ticket) action.run(); }); }
    private void discard(UpdateInstaller.Prepared value) {
        // This directory was created by prepare(), outside the live application and user data.
        Thread cleanup = new Thread(() -> { try { value.discard(); } catch (IOException ignored) { } }, "kubo-update-cleanup");
        cleanup.setDaemon(true); cleanup.start();
    }

    @Override public void close() {
        closed = true;
        generation++;
        if (operation != null) operation.cancel(true);
        if (!handedOff && prepared != null) discard(prepared);
        worker.shutdownNow();
    }
}
