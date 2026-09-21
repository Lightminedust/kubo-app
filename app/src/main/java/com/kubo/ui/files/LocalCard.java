package com.kubo.ui.files;

import com.kubo.model.files.RemoteEntry;
import com.kubo.ui.LiveTimer;
import com.kubo.ui.scope.Format;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

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
import java.util.LinkedHashSet;
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

    private static final double ROW = 28;
    private static final int MAX_ENTRIES = 5_000;
    private static final Color LOCAL = BranchStyle.LOCAL;
    private static final Color TEXT = Color.web("#e6e6ec");
    private static final Color MUTED = Color.web("#6e6e7d");
    private static final Color FAINT = Color.web("#24242d");
    private static final Font NAME_FONT = Font.font("Segoe UI", 12);
    private static final Font BOLD_FONT = Font.font("Segoe UI", FontWeight.SEMI_BOLD, 12);
    private static final Font SMALL_FONT = Font.font("Consolas", 10);

    private final FilesView view;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-local");
        thread.setDaemon(true);
        return thread;
    });
    private final Canvas canvas = new Canvas();
    private final Pane canvasPane;
    private final FlowPane crumbs = new FlowPane(4, 2);
    private final Label folderLabel = label("", "files-title");
    private final Label selectionLabel = label("NOTHING SELECTED", "metric-label");
    private final Label destinationLabel = label("", "mono-muted");
    private final Button sendButton = new Button("SEND");
    private final ToggleButton hiddenToggle = new ToggleButton("HIDDEN");
    private final LiveTimer timer;

    private Path current;
    private List<LocalEntry> entries = List.of();
    private final Set<Path> selected = new LinkedHashSet<>();
    private int anchor = -1;
    private int hovered = -1;
    private double scroll;
    private double targetScroll;
    private double revealStart = -1;
    private double clock;
    private boolean loading;
    private String error = "";
    private RemoteEntry destination;
    // -2: no drop over the list, -1: over the open folder, otherwise the folder row under the cursor.
    private int dropRow = -2;
    private String bornName;
    private double bornAt = -1;

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

        canvasPane = new Pane(canvas) {
            @Override
            protected void layoutChildren() {
                canvas.setWidth(getWidth());
                canvas.setHeight(getHeight());
            }
        };
        canvasPane.setMinHeight(120);
        VBox.setVgrow(canvasPane, Priority.ALWAYS);

        sendButton.getStyleClass().addAll("primary-button", "compact-button", "local-button");
        sendButton.setOnAction(event -> send());
        sendButton.setMinWidth(Region.USE_PREF_SIZE);
        destinationLabel.setWrapText(true);
        destinationLabel.setMinHeight(Region.USE_PREF_SIZE);
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        VBox chosen = new VBox(2, selectionLabel, destinationLabel);
        chosen.setMinWidth(0);
        HBox.setHgrow(chosen, Priority.ALWAYS);
        HBox footer = new HBox(12, chosen, sendButton);
        footer.setAlignment(Pos.CENTER_LEFT);
        Label hint = label("⇪ DRAG THE SELECTION ONTO ANY FOLDER OF THE TREE  ·  ⇩ DRAG AN ENTRY OF THE TREE HERE, "
                + "OR ONTO A FOLDER ROW, TO DOWNLOAD IT  ·  CTRL OR SHIFT TO SELECT MORE  ·  DOUBLE-CLICK TO ENTER",
                "metric-label");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);

        getChildren().addAll(top, places, crumbs, folderLabel, canvasPane, footer, hint);

        canvas.setOnMouseMoved(this::handleMove);
        canvas.setOnMouseExited(event -> hovered = -1);
        canvas.setOnMouseClicked(this::handleClick);
        canvas.setOnScroll(this::handleScroll);
        canvas.setOnDragDetected(this::handleDragStart);
        // An entry carried out of the tree: saved into the folder row under the cursor, or the open folder.
        canvas.setOnDragOver(event -> {
            if (!event.getDragboard().hasContent(BranchCanvas.REMOTE_PATH) || current == null || loading) {
                return;
            }
            int index = rowAt(event.getY());
            dropRow = index >= 0 && entries.get(index).directory() ? index : -1;
            event.acceptTransferModes(TransferMode.COPY);
            event.consume();
        });
        canvas.setOnDragExited(event -> dropRow = -2);
        canvas.setOnDragDropped(event -> {
            Object remote = event.getDragboard().getContent(BranchCanvas.REMOTE_PATH);
            Path folder = dropRow >= 0 && dropRow < entries.size() ? entries.get(dropRow).path() : current;
            dropRow = -2;
            if (remote instanceof String path && folder != null) {
                view.downloadPath(path, folder);
                event.setDropCompleted(true);
            }
            event.consume();
        });
        canvas.setFocusTraversable(true);
        canvas.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.BACK_SPACE && current != null && current.getParent() != null) {
                open(current.getParent());
                event.consume();
            } else if (event.getCode() == KeyCode.A && event.isShortcutDown()) {
                entries.forEach(entry -> selected.add(entry.path()));
                updateSelection();
                event.consume();
            }
        });

        timer = new LiveTimer(this, LiveTimer.AMBIENT, time -> {
            clock = time;
            paint();
        });
        timer.start();
        setDestination(null);
        open(home);
    }

    void shutdown() {
        timer.stop();
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
            bornName = name;
            bornAt = -1;
            open(current);
        }
    }

    /**
     * Shows a drop hovering the list; for previews.
     */
    void setDropRow(int row) {
        dropRow = row;
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
        selected.clear();
        sample.stream().filter(entry -> selectedNames.contains(entry.name())).forEach(entry -> selected.add(entry.path()));
        loading = false;
        error = "";
        revealStart = -10;
        updateCrumbs();
        updateSelection();
        timer.stop();
        clock = 2;
    }

    void renderFrame() {
        paint();
    }

    // Reading

    private void open(Path folder) {
        if (folder == null) {
            return;
        }
        current = folder;
        loading = true;
        error = "";
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
                error = failure;
                loading = false;
                selected.clear();
                anchor = -1;
                scroll = 0;
                targetScroll = 0;
                revealStart = bornName != null ? -1 : clock;
                if (bornName != null) {
                    bornAt = clock;
                }
                updateSelection();
            });
        });
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

    private void updateSelection() {
        long bytes = entries.stream().filter(entry -> selected.contains(entry.path()) && !entry.directory())
                .mapToLong(LocalEntry::size).sum();
        long folders = entries.stream().filter(entry -> selected.contains(entry.path()) && entry.directory()).count();
        selectionLabel.setText(selected.isEmpty() ? "NOTHING SELECTED"
                : selected.size() + " SELECTED  ·  " + Format.bytes(bytes)
                + (folders > 0 ? " + " + folders + (folders == 1 ? " FOLDER" : " FOLDERS") : ""));
        sendButton.setDisable(selected.isEmpty() || destination == null);
        sendButton.setText(destination == null ? "SEND" : "SEND TO " + ActionCard.displayName(destination));
    }

    private void send() {
        if (destination == null || selected.isEmpty()) {
            return;
        }
        view.upload(new ArrayList<>(selected), destination);
        selected.clear();
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

    // Input

    private int rowAt(double y) {
        int index = (int) Math.floor((y + scroll - 6) / ROW);
        return index >= 0 && index < entries.size() ? index : -1;
    }

    private void handleMove(MouseEvent event) {
        hovered = rowAt(event.getY());
        canvas.setCursor(hovered >= 0 ? Cursor.HAND : Cursor.DEFAULT);
    }

    private void handleClick(MouseEvent event) {
        canvas.requestFocus();
        if (event.getButton() != MouseButton.PRIMARY || !event.isStillSincePress()) {
            return;
        }
        int index = rowAt(event.getY());
        if (index < 0) {
            selected.clear();
            updateSelection();
            return;
        }
        LocalEntry entry = entries.get(index);
        if (event.getClickCount() >= 2 && entry.directory()) {
            open(entry.path());
            return;
        }
        if (event.isShiftDown() && anchor >= 0) {
            selected.clear();
            for (int i = Math.min(anchor, index); i <= Math.max(anchor, index); i++) {
                selected.add(entries.get(i).path());
            }
        } else if (event.isShortcutDown()) {
            if (!selected.remove(entry.path())) {
                selected.add(entry.path());
            }
            anchor = index;
        } else {
            selected.clear();
            selected.add(entry.path());
            anchor = index;
        }
        updateSelection();
    }

    private void handleScroll(ScrollEvent event) {
        double max = Math.max(0, entries.size() * ROW + 12 - canvas.getHeight());
        targetScroll = Math.max(0, Math.min(max, targetScroll - event.getDeltaY() * 1.4));
        event.consume();
    }

    private void handleDragStart(MouseEvent event) {
        int index = rowAt(event.getY());
        if (index < 0) {
            return;
        }
        Path path = entries.get(index).path();
        if (!selected.contains(path)) {
            selected.clear();
            selected.add(path);
            updateSelection();
        }
        Dragboard board = canvas.startDragAndDrop(TransferMode.COPY);
        ClipboardContent content = new ClipboardContent();
        content.putFiles(selected.stream().map(Path::toFile).toList());
        board.setContent(content);
        int count = selected.size();
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

    // Drawing

    private void paint() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width < 1 || height < 1) {
            return;
        }
        scroll += (targetScroll - scroll) * 0.25;
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setGlobalAlpha(1);
        gc.setFill(Color.web("#06070b"));
        gc.fillRect(0, 0, width, height);

        double spineX = 18;
        gc.setStroke(LOCAL);
        gc.setGlobalAlpha(0.35);
        gc.setLineWidth(1);
        gc.strokeLine(spineX + 0.5, 0, spineX + 0.5, height);

        if (loading) {
            for (int i = 0; i < 14; i++) {
                double t = (clock * 0.8 + i / 14.0) % 1;
                gc.setFill(LOCAL);
                gc.setGlobalAlpha(Math.sin(Math.PI * t) * 0.9);
                gc.fillOval(spineX - 2, t * height - 2, 4, 4);
            }
            text(gc, "READING THIS FOLDER…", spineX + 20, 24, MUTED, SMALL_FONT, TextAlignment.LEFT, 1);
            return;
        }
        if (!error.isEmpty() || entries.isEmpty()) {
            text(gc, error.isEmpty() ? "EMPTY FOLDER" : error, spineX + 20, 24, error.isEmpty() ? MUTED
                    : Color.web("#ff5a6e"), SMALL_FONT, TextAlignment.LEFT, 1);
            drawDrop(gc, width, height);
            return;
        }

        long largest = Math.max(1, entries.stream().mapToLong(LocalEntry::size).max().orElse(1));
        int first = Math.max(0, (int) Math.floor((scroll - 6) / ROW));
        int last = Math.min(entries.size() - 1, (int) Math.ceil((scroll + height) / ROW));
        for (int i = first; i <= last; i++) {
            LocalEntry entry = entries.get(i);
            double reveal = revealStart < 0 ? 1 : clamp((clock - revealStart - i * 0.015) / 0.35, 0, 1);
            if (reveal <= 0) {
                continue;
            }
            double y = 6 + i * ROW - scroll;
            double center = y + ROW / 2;
            double offset = (1 - reveal) * 24;
            boolean isSelected = selected.contains(entry.path());
            Color color = entry.directory() ? LOCAL : BranchStyle.color(asRemote(entry));

            if (entry.name().equals(bornName) && bornAt >= 0 && clock - bornAt < 1.8) {
                // Just downloaded here.
                double t = (clock - bornAt) / 1.8;
                gc.setGlobalAlpha((1 - t) * 0.35);
                gc.setFill(BranchStyle.CODE);
                gc.fillRect(spineX + 6, y + 2, (width - spineX - 10) * Math.min(1, t * 3), ROW - 4);
            }
            if (i == dropRow) {
                gc.setGlobalAlpha(0.9);
                gc.setStroke(BranchStyle.CODE);
                gc.strokeRect(spineX + 6.5, y + 2.5, width - spineX - 11, ROW - 5);
            }
            if (isSelected || i == hovered) {
                gc.setGlobalAlpha(isSelected ? 0.12 : 0.05);
                gc.setFill(LOCAL);
                gc.fillRect(spineX + 6, y + 2, width - spineX - 10, ROW - 4);
            }
            if (isSelected) {
                gc.setGlobalAlpha(1);
                gc.setFill(LOCAL);
                gc.fillRect(width - 4, y + 3, 2, ROW - 6);
            }

            // A branch from the spine to the item.
            gc.setGlobalAlpha(0.35 * reveal);
            gc.setStroke(color);
            gc.strokeLine(spineX, center, spineX + 14 + offset, center);
            double radius = entry.directory() ? 4.5 : 1.8 + Math.min(4.5, Math.log10(1 + entry.size()) * 0.7);
            gc.setGlobalAlpha(reveal);
            if (entry.directory()) {
                gc.setStroke(color);
                gc.setLineWidth(1.4);
                gc.strokeOval(spineX + 20 + offset - radius, center - radius, radius * 2, radius * 2);
                gc.setLineWidth(1);
            } else {
                gc.setFill(color);
                gc.fillOval(spineX + 20 + offset - radius, center - radius, radius * 2, radius * 2);
            }

            double nameX = spineX + 34 + offset;
            String name = entry.name();
            int maxCharacters = (int) Math.max(6, (width - nameX - 150) / 6.4);
            if (name.length() > maxCharacters) {
                name = name.substring(0, maxCharacters - 1) + "…";
            }
            text(gc, name, nameX, center, isSelected ? Color.WHITE : entry.directory() ? TEXT : Color.web("#b8b8c4"),
                    isSelected ? BOLD_FONT : NAME_FONT, TextAlignment.LEFT, reveal);

            if (!entry.directory()) {
                text(gc, Format.bytes(entry.size()), width - 16, center, MUTED, SMALL_FONT, TextAlignment.RIGHT, reveal);
                // The item's weight among its neighbours, as a short trail of particles.
                int dots = (int) Math.round(1 + 9 * Math.log1p(entry.size()) / Math.log1p(largest));
                double dotsRight = width - 80;
                for (int d = 0; d < dots; d++) {
                    gc.setFill(color);
                    gc.setGlobalAlpha(reveal * (0.25 + 0.75 * d / Math.max(1, dots - 1)));
                    gc.fillOval(dotsRight - d * 5 - 1.2, center - 1.2, 2.4, 2.4);
                }
            } else {
                text(gc, "›", width - 16, center, MUTED, SMALL_FONT, TextAlignment.RIGHT, reveal);
            }
            if (isSelected) {
                gc.setGlobalAlpha(1);
                gc.setStroke(Color.WHITE);
                gc.setLineWidth(1.2);
                double checkX = spineX + 20 + offset;
                gc.strokeOval(checkX - radius - 4, center - radius - 4, (radius + 4) * 2, (radius + 4) * 2);
                gc.setLineWidth(1);
            }
        }
        // Fades at both ends of the list.
        gc.setGlobalAlpha(1);
        if (scroll > 1) {
            gc.setFill(new javafx.scene.paint.LinearGradient(0, 0, 0, 24, false, javafx.scene.paint.CycleMethod.NO_CYCLE,
                    new javafx.scene.paint.Stop(0, Color.web("#06070b")), new javafx.scene.paint.Stop(1, Color.TRANSPARENT)));
            gc.fillRect(0, 0, width, 24);
        }
        gc.setStroke(FAINT);
        gc.strokeLine(0, height - 0.5, width, height - 0.5);
        drawDrop(gc, width, height);
    }

    private void drawDrop(GraphicsContext gc, double width, double height) {
        if (dropRow >= -1 && dropRow < entries.size()) {
            String into = dropRow >= 0 ? entries.get(dropRow).name() : folderLabel.getText();
            gc.setGlobalAlpha(1);
            if (dropRow == -1) {
                gc.setStroke(BranchStyle.CODE);
                gc.setLineWidth(1.5);
                gc.strokeRect(1, 1, width - 2, height - 2);
                gc.setLineWidth(1);
            }
            gc.setFill(Color.web("#06070b"));
            gc.fillRect(0, height - 26, width, 26);
            text(gc, "⇩  DROP TO SAVE IN " + into.toUpperCase(Locale.ROOT), 14, height - 13, BranchStyle.CODE,
                    BOLD_FONT, TextAlignment.LEFT, 1);
        }
    }

    private static RemoteEntry asRemote(LocalEntry entry) {
        return new RemoteEntry(entry.name(), "/" + entry.name(), RemoteEntry.Type.FILE, entry.size(), entry.modified(),
                "", "", "", "", RemoteEntry.UNKNOWN, true);
    }

    private static void text(GraphicsContext gc, String text, double x, double y, Color color, Font font,
                             TextAlignment alignment, double alpha) {
        gc.setGlobalAlpha(alpha);
        gc.setFill(color);
        gc.setFont(font);
        gc.setTextAlign(alignment);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText(text, x, y);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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
