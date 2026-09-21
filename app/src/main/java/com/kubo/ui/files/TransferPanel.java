package com.kubo.ui.files;

import com.kubo.command.CommandEntry;
import com.kubo.files.Download;
import com.kubo.files.FileBrowser;
import com.kubo.files.FileCommands;
import com.kubo.files.LocalNames;
import com.kubo.files.LocalScan;
import com.kubo.files.UploadCommands;
import com.kubo.files.UploadCommands.Conflict;
import com.kubo.model.files.RemoteEntry;
import com.kubo.ui.LiveTimer;
import com.kubo.ui.files.Transfer.State;
import com.kubo.ui.scope.Format;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Uploads and downloads, one after the other. Each is a {@link Transfer} shown by a {@link TransferRow};
 * this panel queues them, runs them on its own thread, and asks what to do when a name is already taken.
 */
final class TransferPanel extends VBox {

    private static final double WIDTH = 400;

    private final FilesView view;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-transfer");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Transfer, TransferRow> transfers = new LinkedHashMap<>();
    private final VBox rows = new VBox(2);
    private final ScrollPane scroll = new ScrollPane(rows);
    private final Label summary = label("", "metric-label");
    private final Button collapse = new Button("▾");
    private final LiveTimer timer;
    private double clock;
    private boolean collapsed;

    TransferPanel(FilesView view) {
        this.view = view;
        getStyleClass().addAll("float-card", "upload-card");
        setPrefWidth(WIDTH);
        setMaxWidth(WIDTH);
        setMaxHeight(Region.USE_PREF_SIZE);

        Label title = label("TRANSFERS", "local-kicker");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button clear = new Button("CLEAR");
        clear.getStyleClass().add("ghost-button");
        clear.setFocusTraversable(false);
        clear.setOnAction(event -> clearFinished());
        collapse.getStyleClass().add("ghost-button");
        collapse.setFocusTraversable(false);
        collapse.setOnAction(event -> setCollapsed(!collapsed));
        title.setMinWidth(Region.USE_PREF_SIZE);
        clear.setMinWidth(Region.USE_PREF_SIZE);
        collapse.setMinWidth(Region.USE_PREF_SIZE);
        summary.setMinWidth(0);
        HBox header = new HBox(12, title, summary, spacer, clear, collapse);
        header.setAlignment(Pos.CENTER_LEFT);

        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMaxHeight(360);
        rows.setPadding(new Insets(4, 0, 0, 0));
        getChildren().addAll(header, scroll);
        setSpacing(8);
        setVisible(false);
        setManaged(false);

        timer = new LiveTimer(this, LiveTimer.AMBIENT, time -> {
            clock = time;
            refresh();
        });
    }

    /**
     * Queues local files and folders for one folder of the server.
     */
    void upload(List<Path> paths, RemoteEntry folder) {
        for (Path path : paths) {
            Path fileName = path.getFileName();
            Transfer transfer = add(new Transfer(false, path, folder, fileName == null ? path.toString() : fileName.toString()));
            var problem = FileCommands.nameProblem(transfer.name);
            if (problem.isPresent()) {
                finish(transfer, State.FAILED, "THIS NAME CANNOT BE USED ON THE SERVER: " + problem.get());
            } else {
                submit(transfer);
            }
        }
        reveal();
    }

    /**
     * Queues a file or folder of the server for a local folder.
     */
    void download(RemoteEntry entry, Path folder) {
        Transfer transfer = add(new Transfer(true, folder, entry, LocalNames.sanitize(entry.name())));
        transfer.total = entry.isDirectory() ? -1 : entry.sizeBytes();
        if (Files.exists(folder.resolve(transfer.name), LinkOption.NOFOLLOW_LINKS)) {
            conflict(transfer, false);
        } else {
            submit(transfer);
        }
        reveal();
    }

    boolean active() {
        return transfers.keySet().stream().anyMatch(Transfer::running);
    }

    void shutdown() {
        transfers.keySet().forEach(transfer -> transfer.cancelled = true);
        timer.stop();
        worker.shutdownNow();
    }

    /**
     * Shows a transfer in a given state without running it; for previews.
     */
    void addSample(boolean download, String name, Path local, RemoteEntry remote, State state, long sent, long total,
                   double speed, String message) {
        Transfer transfer = add(new Transfer(download, local, remote, name));
        transfer.state = state;
        transfer.sent = sent;
        transfer.total = total;
        transfer.setSpeed(speed);
        transfer.message = message;
        if (state == State.CONFLICT) {
            conflict(transfer, false);
        }
        if (state == State.DONE && download) {
            transfer.saved = local.resolve(name);
            showSaved(transfer);
        }
        setVisible(true);
        setManaged(true);
        clock = 1.3;
        refresh();
    }

    private Transfer add(Transfer transfer) {
        TransferRow row = new TransferRow(transfer, WIDTH, () -> closeOrCancel(transfer));
        transfers.put(transfer, row);
        rows.getChildren().add(row);
        return transfer;
    }

    private void reveal() {
        setVisible(true);
        setManaged(true);
        setCollapsed(false);
        timer.start();
        Platform.runLater(() -> scroll.setVvalue(1));
    }

    private void submit(Transfer transfer) {
        transfer.state = State.WAITING;
        transfer.cancelled = false;
        transfers.get(transfer).hideActions();
        FileBrowser browser = view.browser();
        if (browser == null) {
            return;
        }
        worker.execute(() -> {
            if (transfer.cancelled) {
                return;
            }
            if (transfer.download) {
                runDownload(browser, transfer);
            } else {
                runUpload(browser, transfer);
            }
        });
    }

    private void runUpload(FileBrowser browser, Transfer transfer) {
        try {
            if (transfer.scan == null) {
                transfer.state = State.READING;
                transfer.scan = LocalScan.of(transfer.local);
                transfer.total = FileBrowser.uploadBytes(transfer.scan);
            }
        } catch (IOException e) {
            Platform.runLater(() -> finish(transfer, State.FAILED, "CANNOT READ IT HERE: " + e.getMessage()));
            return;
        }
        if (transfer.cancelled) {
            return;
        }
        transfer.start(System.nanoTime());
        CommandEntry result = browser.upload(transfer.scan, transfer.remote.path(), transfer.name, transfer.conflict,
                sent -> transfer.sent = sent, () -> transfer.cancelled);
        Platform.runLater(() -> uploaded(transfer, result));
    }

    private void runDownload(FileBrowser browser, Transfer transfer) {
        if (transfer.remote.isDirectory() && transfer.total < 0) {
            transfer.state = State.READING;
            OptionalLong size = browser.measure(transfer.remote.path());
            transfer.total = size.orElse(-1);
            transfer.estimated = true;
        }
        if (transfer.cancelled) {
            return;
        }
        transfer.start(System.nanoTime());
        Download result = browser.download(transfer.remote, transfer.local, transfer.name,
                transfer.conflict == Conflict.REPLACE, received -> transfer.sent = received, () -> transfer.cancelled);
        Platform.runLater(() -> downloaded(transfer, result));
    }

    private void uploaded(Transfer transfer, CommandEntry result) {
        if (result.success()) {
            transfer.destination = UploadCommands.parseDestination(result)
                    .orElse(RemoteEntry.join(transfer.remote.path(), transfer.name));
            String skipped = transfer.scan.skipped() > 0
                    ? "  ·  " + transfer.scan.skipped() + " LINKS OR UNREADABLE ITEMS LEFT OUT" : "";
            finish(transfer, State.DONE, "SENT TO " + transfer.destination + skipped);
            view.changed(transfer.destination, transfer.remote.path());
        } else if (transfer.cancelled || result.error().equals("Cancelled")) {
            finish(transfer, State.CANCELLED, "CANCELLED  ·  NOTHING WAS KEPT ON THE SERVER");
        } else if (result.exitCode() == FileCommands.EXISTS_EXIT) {
            boolean folderInTheWay = transfer.conflict == Conflict.REPLACE;
            transfer.message = folderInTheWay
                    ? "A FOLDER HAS THIS NAME THERE AND IS NEVER REPLACED"
                    : "SOMETHING NAMED " + transfer.name + " IS ALREADY IN " + transfer.remote.path();
            conflict(transfer, folderInTheWay);
        } else {
            finish(transfer, State.FAILED, FileCommands.problem(result).toUpperCase(Locale.ROOT));
        }
        refresh();
    }

    private void downloaded(Transfer transfer, Download result) {
        if (result.success()) {
            transfer.saved = result.saved();
            List<String> notes = new ArrayList<>();
            if (result.left() > 0) {
                notes.add(result.left() + " ITEMS COULD NOT BE READ OR CREATED");
            }
            if (result.renamed() > 0) {
                notes.add(result.renamed() + " NAMES CHANGED FOR WINDOWS");
            }
            finish(transfer, State.DONE, "SAVED TO " + result.saved()
                    + (notes.isEmpty() ? "" : "  ·  " + String.join("  ·  ", notes)));
            showSaved(transfer);
            view.downloaded(result.saved());
        } else if (result.cancelled() || transfer.cancelled) {
            finish(transfer, State.CANCELLED, "CANCELLED  ·  NOTHING WAS KEPT ON THIS COMPUTER");
        } else if (!transfer.remote.isDirectory() && Files.exists(transfer.local.resolve(transfer.name))
                && transfer.conflict != Conflict.REPLACE) {
            conflict(transfer, false);
        } else {
            finish(transfer, State.FAILED, result.problem().lines().findFirst().orElse("").toUpperCase(Locale.ROOT));
        }
        refresh();
    }

    /**
     * The destination already has the name: replace (files only), keep both or skip.
     */
    private void conflict(Transfer transfer, boolean folderInTheWay) {
        transfer.state = State.CONFLICT;
        if (transfer.message.isEmpty() || transfer.download) {
            transfer.message = "SOMETHING NAMED " + transfer.name + " IS ALREADY IN "
                    + (transfer.download ? transfer.local : transfer.remote.path());
        }
        boolean folder = transfer.download ? transfer.remote.isDirectory()
                : transfer.scan != null && transfer.scan.isDirectory();
        boolean localFolderInTheWay = transfer.download && Files.isDirectory(transfer.local.resolve(transfer.name));
        List<Button> buttons = new ArrayList<>();
        if (!folderInTheWay && !folder && !localFolderInTheWay) {
            buttons.add(button("REPLACE", "danger-button", () -> retry(transfer, Conflict.REPLACE)));
        }
        buttons.add(button("KEEP BOTH", "ghost-button", () -> retry(transfer, Conflict.KEEP_BOTH)));
        buttons.add(button("SKIP", "ghost-button", () -> finish(transfer, State.CANCELLED, "SKIPPED")));
        transfers.get(transfer).showActions(buttons);
    }

    private void retry(Transfer transfer, Conflict conflict) {
        transfer.conflict = conflict;
        transfer.message = "";
        if (transfer.download && conflict == Conflict.KEEP_BOTH) {
            // Locally, the free name is known right away.
            transfer.name = LocalNames.freeName(transfer.local, transfer.name, transfer.remote.isDirectory());
        }
        submit(transfer);
        timer.start();
    }

    private void showSaved(Transfer transfer) {
        transfers.get(transfer).showActions(List.of(button("SHOW IN FOLDER", "ghost-button", () -> reveal(transfer.saved))));
    }

    private static void reveal(Path saved) {
        try {
            com.kubo.platform.Host.reveal(saved);
        } catch (IOException ignored) {
            // No file manager answered: the path is already shown on the card.
        }
    }

    private void finish(Transfer transfer, State state, String message) {
        transfer.state = state;
        transfer.message = message;
        transfer.finishedAt = clock;
        transfers.get(transfer).hideActions();
        refresh();
    }

    private void closeOrCancel(Transfer transfer) {
        if (transfer.running()) {
            transfer.cancelled = true;
            if (transfer.state != State.SENDING) {
                finish(transfer, State.CANCELLED, "CANCELLED BEFORE STARTING");
            }
            return;
        }
        rows.getChildren().remove(transfers.remove(transfer));
        hideIfEmpty();
    }

    private void clearFinished() {
        transfers.entrySet().removeIf(entry -> {
            State state = entry.getKey().state;
            boolean finished = state == State.DONE || state == State.CANCELLED;
            if (finished) {
                rows.getChildren().remove(entry.getValue());
            }
            return finished;
        });
        hideIfEmpty();
    }

    private void hideIfEmpty() {
        if (transfers.isEmpty()) {
            setVisible(false);
            setManaged(false);
            timer.stop();
        }
    }

    private void setCollapsed(boolean value) {
        collapsed = value;
        scroll.setVisible(!value);
        scroll.setManaged(!value);
        collapse.setText(value ? "▴" : "▾");
    }

    private void refresh() {
        Set<String> incoming = new HashSet<>();
        Set<String> outgoing = new HashSet<>();
        long now = System.nanoTime();
        double up = 0;
        double down = 0;
        int waiting = 0;
        for (Map.Entry<Transfer, TransferRow> entry : transfers.entrySet()) {
            Transfer transfer = entry.getKey();
            if (transfer.state == State.SENDING) {
                (transfer.download ? outgoing : incoming).add(transfer.remote.path());
                transfer.measureSpeed(now);
                if (transfer.download) {
                    down += transfer.speed();
                } else {
                    up += transfer.speed();
                }
            } else if (transfer.state == State.WAITING || transfer.state == State.READING) {
                waiting++;
            }
            entry.getValue().update(clock);
        }
        view.canvas().setIncoming(incoming);
        view.canvas().setOutgoing(outgoing);
        List<String> parts = new ArrayList<>();
        if (up > 0) {
            parts.add("⇪ " + Format.bytes((long) up) + "/S");
        }
        if (down > 0) {
            parts.add("⇩ " + Format.bytes((long) down) + "/S");
        }
        if (waiting > 0) {
            parts.add(waiting + " WAITING");
        }
        summary.setText(String.join("  ·  ", parts));
        if (!active() && transfers.keySet().stream().noneMatch(t -> t.finishedAt >= 0 && clock - t.finishedAt < 1.2)) {
            timer.stop();
        }
    }

    private static Button button(String text, String styleClass, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add(styleClass);
        if (styleClass.equals("danger-button")) {
            button.getStyleClass().add("compact-danger");
        }
        button.setFocusTraversable(false);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
