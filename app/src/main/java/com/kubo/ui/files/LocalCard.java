package com.kubo.ui.files;

import com.kubo.model.files.RemoteEntry;
import com.kubo.ui.SpineList;
import com.kubo.ui.scope.Format;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This computer, to pick what to upload. Unlike the server's tree it reads top to bottom: a spine of the path
 * followed down to the open folder, then its entries as a list where each item's weight shows as particles.
 * Items are selected here, then dragged onto a folder of the tree or sent to the folder open in it.
 */
final class LocalCard extends VBox {

    record LocalEntry(Path path, String name, boolean directory, long size, Instant modified) {
    }

    private static final int MAX_ENTRIES = 5_000;
    private static final Color LOCAL = BranchStyle.LOCAL;

    private final FilesView view;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-local");
        thread.setDaemon(true);
        return thread;
    });
    /** The list itself: the same spine every list in Kubo hangs off. */
    private final SpineList list = new SpineList(LOCAL);
    private final FlowPane crumbs = new FlowPane(4, 2);
    private final Label folderLabel = label("", "files-title");
    private final Label selectionLabel = label("NOTHING SELECTED", "metric-label");
    private final Label destinationLabel = label("", "mono-muted");
    private final Button sendButton = new Button("SEND");
    private final ToggleButton hiddenToggle = new ToggleButton("HIDDEN");

    private Path current;
    private List<LocalEntry> entries = List.of();
    private boolean loading;
    private RemoteEntry destination;
    // -2: no drop over the list, -1: over the open folder, otherwise the folder row under the cursor.
    private int dropRow = -2;

    LocalCard(FilesView view) {
        this.view = view;
        getStyleClass().addAll("float-card", "local-card");
        setSpacing(10);

        Label kicker = label("LOCAL.SCOPE", "local-kicker");
        Label title = label("This computer", "card-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        hiddenToggle.getStyleClass().add("mode-toggle");
        hiddenToggle.setFocusTraversable(false);
        hiddenToggle.selectedProperty().addListener((observable, previous, value) -> open(current));
        Button close = new Button("✕");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> view.toggleLocal());
        HBox top = new HBox(10, new VBox(2, kicker, title), spacer, hiddenToggle, close);
        top.setAlignment(Pos.TOP_LEFT);

        FlowPane places = new FlowPane(12, 4);
        Path home = Path.of(System.getProperty("user.home"));
        addPlace(places, "HOME", home);
        addPlace(places, "DESKTOP", home.resolve("Desktop"));
        addPlace(places, "DOCUMENTS", home.resolve("Documents"));
        addPlace(places, "DOWNLOADS", home.resolve("Downloads"));
        for (File root : File.listRoots()) {
            addPlace(places, root.getPath().replace("\\", ""), root.toPath());
        }

        folderLabel.setMinWidth(0);
        crumbs.setPrefWrapLength(360);

        list.setMinHeight(120);
        VBox.setVgrow(list, Priority.ALWAYS);
        list.setSeveral(true);
        list.setLoadingText("READING THIS FOLDER…");
        list.setOnPick(index -> updateSelection());
        list.setOnOpen(index -> open(entries.get(index).path()));
        list.setOnBack(() -> {
            if (current != null && current.getParent() != null) {
                open(current.getParent());
            }
        });

        sendButton.getStyleClass().addAll("primary-button", "compact-button", "local-button");
        sendButton.setOnAction(event -> send());
        sendButton.setMinWidth(Region.USE_PREF_SIZE);
        destinationLabel.setWrapText(true);
        destinationLabel.setMinHeight(Region.USE_PREF_SIZE);
        VBox chosen = new VBox(2, selectionLabel, destinationLabel);
        chosen.setMinWidth(0);
        HBox.setHgrow(chosen, Priority.ALWAYS);
        HBox footer = new HBox(12, chosen, sendButton);
        footer.setAlignment(Pos.CENTER_LEFT);
        Label hint = label("⇪ DRAG THE SELECTION ONTO ANY FOLDER OF THE TREE  ·  ⇩ DRAG AN ENTRY OF THE TREE HERE, "
                + "OR ONTO A FOLDER ROW, TO DOWNLOAD IT  ·  CTRL OR SHIFT TO SELECT MORE  ·  DOUBLE-CLICK TO ENTER  ·  "
                + "ARROWS, ENTER AND BACKSPACE WORK TOO",
                "metric-label");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);

        getChildren().addAll(top, places, crumbs, folderLabel, list, footer, hint);

        // Carrying the selection out, onto a folder of the tree.
        list.setOnDragDetected(this::handleDragStart);
        // An entry carried out of the tree: saved into the folder row under the cursor, or the open folder.
        list.setOnDragOver(event -> {
            if (!event.getDragboard().hasContent(BranchCanvas.REMOTE_PATH) || current == null || loading) {
                return;
            }
            int index = list.rowAt(event.getY());
            showDrop(index >= 0 && entries.get(index).directory() ? index : -1);
            event.acceptTransferModes(TransferMode.COPY);
            event.consume();
        });
        list.setOnDragExited(event -> showDrop(-2));
        list.setOnDragDropped(event -> {
            Object remote = event.getDragboard().getContent(BranchCanvas.REMOTE_PATH);
            Path folder = dropRow >= 0 && dropRow < entries.size() ? entries.get(dropRow).path() : current;
            showDrop(-2);
            if (remote instanceof String path && folder != null) {
                view.downloadPath(path, folder);
                event.setDropCompleted(true);
            }
            event.consume();
        });

        setDestination(null);
        open(home);
    }

    void shutdown() {
        list.stop();
        reader.shutdownNow();
    }

    /**
     * The folder shown, where downloads go by default.
     */
    Path currentFolder() {
        return current;
    }

    /**
     * Reads the shown folder again if it is {@code folder}, lighting up the entry just saved there.
     */
    void refreshIfShowing(Path folder, String name) {
        if (folder != null && folder.equals(current)) {
            list.flash(name, BranchStyle.CODE);
            open(current);
        }
    }

    /**
     * Shows a drop hovering the list; for previews.
     */
    void setDropRow(int row) {
        showDrop(row);
    }

    /**
     * The folder the SEND button uploads into.
     */
    void setDestination(RemoteEntry folder) {
        destination = folder;
        destinationLabel.setText(folder == null ? "OPEN A FOLDER IN THE TREE TO SEND HERE" : "INTO " + folder.path());
        updateSelection();
    }

    /**
     * Shows a folder's content without reading the disk; for previews.
     */
    void showSample(Path folder, List<LocalEntry> sample, Set<String> selectedNames) {
        current = folder;
        entries = sample;
        loading = false;
        list.setRows(rows(sample));
        List<Integer> picked = new ArrayList<>();
        for (int i = 0; i < sample.size(); i++) {
            if (selectedNames.contains(sample.get(i).name())) {
                picked.add(i);
            }
        }
        list.setSelection(picked);
        list.settle();
        updateCrumbs();
        updateSelection();
    }

    void renderFrame() {
        list.settle();
    }

    // Reading

    private void open(Path folder) {
        if (folder == null) {
            return;
        }
        current = folder;
        loading = true;
        list.setLoading(true);
        updateCrumbs();
        boolean hidden = hiddenToggle.isSelected();
        reader.execute(() -> {
            List<LocalEntry> read = new ArrayList<>();
            String problem = "";
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
                for (Path path : stream) {
                    if (read.size() >= MAX_ENTRIES) {
                        break;
                    }
                    try {
                        if (!hidden && (Files.isHidden(path) || path.getFileName().toString().startsWith("."))) {
                            continue;
                        }
                        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                        if (attributes.isSymbolicLink() || attributes.isOther()) {
                            continue;
                        }
                        read.add(new LocalEntry(path, path.getFileName().toString(), attributes.isDirectory(),
                                attributes.isDirectory() ? 0 : attributes.size(),
                                attributes.lastModifiedTime().toInstant()));
                    } catch (IOException ignored) {
                        // Unreadable entries are simply not listed.
                    }
                }
            } catch (IOException e) {
                problem = "CANNOT OPEN THIS FOLDER: " + e.getMessage();
            }
            read.sort(Comparator.comparing((LocalEntry entry) -> !entry.directory())
                    .thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT)));
            String failure = problem;
            Platform.runLater(() -> {
                if (!folder.equals(current)) {
                    return;
                }
                entries = List.copyOf(read);
                loading = false;
                list.setRows(rows(entries));
                list.setMessage(failure.isEmpty() ? "EMPTY FOLDER" : failure, !failure.isEmpty());
                updateSelection();
            });
        });
    }

    /**
     * The entries as the list draws them: a folder is a ring, a file a dot sized by its bytes, with a trail
     * saying how heavy it is among its neighbours.
     */
    private static List<SpineList.Row> rows(List<LocalEntry> entries) {
        long largest = Math.max(1, entries.stream().mapToLong(LocalEntry::size).max().orElse(1));
        List<SpineList.Row> rows = new ArrayList<>(entries.size());
        for (LocalEntry entry : entries) {
            if (entry.directory()) {
                rows.add(new SpineList.Row(entry.name(), "", true, LOCAL));
            } else {
                double radius = 1.8 + Math.min(4.5, Math.log10(1 + entry.size()) * 0.7);
                double weight = Math.log1p(entry.size()) / Math.log1p(largest);
                rows.add(new SpineList.Row(entry.name(), Format.bytes(entry.size()), false,
                        BranchStyle.color(asRemote(entry)), radius, weight));
            }
        }
        return rows;
    }

    private void updateCrumbs() {
        crumbs.getChildren().clear();
        List<Path> chain = new ArrayList<>();
        for (Path path = current; path != null; path = path.getParent()) {
            chain.add(0, path);
        }
        for (int i = 0; i < chain.size(); i++) {
            Path path = chain.get(i);
            String name = path.getFileName() == null ? path.toString().replace("\\", "") : path.getFileName().toString();
            Button crumb = new Button(name);
            crumb.getStyleClass().add(i == chain.size() - 1 ? "local-crumb-current" : "local-crumb");
            crumb.setFocusTraversable(false);
            crumb.setOnAction(event -> open(path));
            crumbs.getChildren().add(crumb);
            if (i < chain.size() - 1) {
                crumbs.getChildren().add(label("›", "mono-muted"));
            }
        }
        folderLabel.setText(current.getFileName() == null ? current.toString() : current.getFileName().toString());
    }

    /** The entries picked in the list, as paths. */
    private List<Path> selectedPaths() {
        List<Path> paths = new ArrayList<>();
        for (int index : list.selection()) {
            if (index >= 0 && index < entries.size()) {
                paths.add(entries.get(index).path());
            }
        }
        return paths;
    }

    private void updateSelection() {
        List<LocalEntry> picked = new ArrayList<>();
        for (int index : list.selection()) {
            if (index >= 0 && index < entries.size()) {
                picked.add(entries.get(index));
            }
        }
        long bytes = picked.stream().filter(entry -> !entry.directory()).mapToLong(LocalEntry::size).sum();
        long folders = picked.stream().filter(LocalEntry::directory).count();
        selectionLabel.setText(picked.isEmpty() ? "NOTHING SELECTED"
                : picked.size() + " SELECTED  ·  " + Format.bytes(bytes)
                + (folders > 0 ? " + " + folders + (folders == 1 ? " FOLDER" : " FOLDERS") : ""));
        sendButton.setDisable(picked.isEmpty() || destination == null);
        sendButton.setText(destination == null ? "SEND" : "SEND TO " + ActionCard.displayName(destination));
    }

    private void send() {
        List<Path> picked = selectedPaths();
        if (destination == null || picked.isEmpty()) {
            return;
        }
        view.upload(picked, destination);
        list.setSelection(List.of());
        updateSelection();
    }

    private void addPlace(FlowPane places, String name, Path path) {
        if (!Files.isDirectory(path)) {
            return;
        }
        Button place = new Button(name);
        place.getStyleClass().add("local-place");
        place.setFocusTraversable(false);
        place.setOnAction(event -> open(path));
        places.getChildren().add(place);
    }

    /** What the bottom of the list says while something from the tree hovers over it. */
    private void showDrop(int row) {
        dropRow = row;
        if (row < -1 || row >= entries.size()) {
            list.setDrop(-2, "", BranchStyle.CODE);
            return;
        }
        String into = row >= 0 ? entries.get(row).name() : folderLabel.getText();
        list.setDrop(row, "⇩  DROP TO SAVE IN " + into.toUpperCase(Locale.ROOT), BranchStyle.CODE);
    }

    // Input

    private void handleDragStart(MouseEvent event) {
        int index = list.rowAt(event.getY());
        if (index < 0) {
            return;
        }
        if (!list.selection().contains(index)) {
            list.select(index);
            updateSelection();
        }
        List<Path> picked = selectedPaths();
        Dragboard board = list.startDragAndDrop(TransferMode.COPY);
        ClipboardContent content = new ClipboardContent();
        content.putFiles(picked.stream().map(Path::toFile).toList());
        board.setContent(content);
        int count = picked.size();
        board.setDragView(tag("⇪  " + count + (count == 1 ? " ITEM" : " ITEMS"), "#35e8ff"), -12, -12);
        event.consume();
    }

    /**
     * A small tag following the cursor during a drag.
     */
    static javafx.scene.image.Image tag(String text, String accent) {
        Label tag = new Label(text);
        tag.setStyle("-fx-background-color: #06070b; -fx-border-color: " + accent + "; -fx-border-width: 0 0 0 2; "
                + "-fx-text-fill: #e6e6ec; -fx-font-family: 'Segoe UI'; -fx-font-size: 11px; -fx-padding: 6 12 6 10;");
        new javafx.scene.Scene(new javafx.scene.Group(tag));
        javafx.scene.SnapshotParameters parameters = new javafx.scene.SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        return tag.snapshot(parameters, null);
    }

    private static RemoteEntry asRemote(LocalEntry entry) {
        return new RemoteEntry(entry.name(), "/" + entry.name(), RemoteEntry.Type.FILE, entry.size(), entry.modified(),
                "", "", "", "", RemoteEntry.UNKNOWN, true);
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    static Insets margin() {
        return new Insets(16, 0, 16, 16);
    }
}
