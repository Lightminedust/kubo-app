package com.kubo.ui.files;

import com.kubo.ui.BranchCamera;

import com.kubo.model.files.DirectoryListing;
import com.kubo.model.files.RemoteEntry;
import com.kubo.ui.files.BranchTree.Column;
import com.kubo.ui.files.BranchTree.Delivery;
import com.kubo.ui.files.BranchTree.Hit;
import javafx.animation.AnimationTimer;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.Pane;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import static com.kubo.ui.BranchCamera.HEADER_HEIGHT;
import static com.kubo.ui.files.BranchTree.COLUMN_GAP;
import static com.kubo.ui.files.BranchTree.ROW;
import static com.kubo.ui.files.BranchTree.columnX;
import static com.kubo.ui.files.BranchTree.indexOf;

/**
 * Folders as a lineage read from left to right, laid out on a map the user pans and zooms.
 * Opening a folder adds a column of its entries, centred on it and joined to it by fibers; earlier columns stay,
 * so the path remains visible, and going back removes the last one. Every entry keeps a full row whatever the
 * folder size, so large folders grow taller instead of collapsing. Each column sorts on its own.
 *
 * <p>This class handles the surface, the public API and the user's gestures; the columns live in
 * {@link BranchTree}, the view in {@link BranchCamera} and the drawing in {@link BranchPainter}.
 */
public final class BranchCanvas extends Pane {

    /**
     * A right-click on an entry, or a click on the empty space of a folder's branch.
     *
     * @param entry  the entry, or null for a folder's space
     * @param folder the folder holding the entry, or the folder whose space was clicked; null for the server root
     * @param x      where to anchor a floating card, in this pane's coordinates
     */
    public record MenuRequest(RemoteEntry entry, RemoteEntry folder, double x, double y) {

        public boolean onSpace() {
            return entry == null;
        }

        /**
         * What the menu is about: the entry, or the folder for a space.
         */
        public RemoteEntry subject() {
            return entry != null ? entry : folder;
        }
    }

    /**
     * Clipboard format of an entry dragged out of the tree: its path on the server.
     */
    public static final DataFormat REMOTE_PATH = new DataFormat("application/x-kubo-remote-path");

    private record Focus(int column, double worldY, boolean snap) {
    }

    private final Canvas canvas = new Canvas();
    private final AnimationTimer timer;
    private final BranchTree tree = new BranchTree();
    private final BranchCamera camera = new BranchCamera();
    private final BranchPainter painter = new BranchPainter(tree, camera);

    private double lastTime = -1;
    private double reportedZoom = 1;
    private Focus pendingFocus;

    private RemoteEntry selected;
    private String selectedPath;
    private String pendingSelect;

    // Folder spaces: highlighted under the cursor and while their card is open.
    private boolean menus;
    private int hoveredSpace = -1;
    private boolean transferDrag;

    private Consumer<MenuRequest> onMenu = request -> {
    };
    private Consumer<RemoteEntry> onSelect = entry -> {
    };
    private Consumer<RemoteEntry> onOpen = entry -> {
    };
    private Consumer<RemoteEntry> onNavigate = entry -> {
    };
    private DoubleConsumer onZoomChanged = value -> {
    };

    public BranchCanvas() {
        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinSize(0, 0);

        setOnMouseMoved(this::handleMove);
        setOnMouseExited(event -> {
            painter.hovered = null;
            hoveredSpace = -1;
            painter.mouseX = -1;
        });
        setOnMousePressed(event -> camera.press(event.getX(), event.getY(), System.nanoTime()));
        setOnMouseDragged(this::handleDrag);
        setOnMouseReleased(event -> {
            camera.release(System.nanoTime());
            handleMove(event);
        });
        setOnMouseClicked(this::handleClick);
        setOnScroll(this::handleScroll);
        setOnZoom(this::handlePinch);
        setOnDragDetected(this::handleDragDetected);
        setOnDragDone(event -> painter.carried = null);

        timer = new AnimationTimer() {
            private long start = -1;

            @Override
            public void handle(long now) {
                if (start < 0) {
                    start = now;
                }
                renderFrame((now - start) / 1e9, true);
            }
        };
        timer.start();
    }

    /**
     * Called with a single-clicked entry: show its details.
     */
    public void setOnSelect(Consumer<RemoteEntry> onSelect) {
        this.onSelect = Objects.requireNonNull(onSelect);
    }

    /**
     * Called with a folder whose content must be read; answer with {@link #setListing}.
     */
    public void setOnOpen(Consumer<RemoteEntry> onOpen) {
        this.onOpen = Objects.requireNonNull(onOpen);
    }

    /**
     * Called with the folder at the end of the path whenever it changes.
     */
    public void setOnNavigate(Consumer<RemoteEntry> onNavigate) {
        this.onNavigate = Objects.requireNonNull(onNavigate);
    }

    /**
     * Called with the zoom factor (1 = 100 %) as it changes.
     */
    public void setOnZoomChanged(DoubleConsumer onZoomChanged) {
        this.onZoomChanged = Objects.requireNonNull(onZoomChanged);
    }

    /**
     * Called with a right-clicked entry, or a clicked folder space. Setting it turns folder spaces on.
     */
    public void setOnMenu(Consumer<MenuRequest> onMenu) {
        this.onMenu = Objects.requireNonNull(onMenu);
        this.menus = true;
    }

    /**
     * Starts over from the server root and opens the path down to the user's home folder, so the system sits
     * on the left, the user's own tree on the right, and the home folder in the middle among its siblings.
     */
    public void setHome(String homePath) {
        showPath(homePath, homePath);
    }

    /**
     * Starts over from the server root and opens the path down to any folder, which ends up in the middle.
     * Columns inside {@code homePath} are marked as the user's.
     */
    public void showPath(String homePath, String path) {
        RemoteEntry root = tree.start(homePath, path);
        camera.resetZoomInstantly();
        select(root);
        open(0, root);
        pendingFocus = new Focus(1, 0, true);
    }

    /**
     * Reads an open folder again after a change, keeping the path open when its folders still exist.
     * Entries that appeared light up, entries that vanished dissolve.
     *
     * @param select an entry to select once the folder is read again, or null
     */
    public void refresh(String path, String select) {
        pendingSelect = select;
        tree.markRefreshing(path).forEach(onOpen);
    }

    /**
     * Opens a folder shown in the tree, as a double-click would.
     */
    public void openPath(String path) {
        for (int k = tree.size() - 1; k >= 0; k--) {
            if (indexOf(tree.column(k).visible, path) >= 0) {
                open(k, path);
                return;
            }
        }
    }

    public RemoteEntry selectedEntry() {
        return selected;
    }

    /**
     * The card anchored on an entry of the tree, e.g. for the Delete key; empty when the entry is not shown.
     */
    public Optional<MenuRequest> menuFor(String path) {
        tree.layout();
        for (int k = tree.size() - 1; k >= 0; k--) {
            Column column = tree.column(k);
            int index = indexOf(column.visible, path);
            if (index >= 0) {
                return Optional.of(new MenuRequest(column.visible.get(index), tree.folderOf(k),
                        camera.sx(columnX(k)), camera.sy(tree.nodeY(column, index))));
            }
        }
        return Optional.empty();
    }

    /**
     * The card of an open folder's space, anchored in the middle of its branch; for previews.
     */
    Optional<MenuRequest> spaceMenuFor(String path) {
        tree.layout();
        for (int k = tree.size() - 1; k >= 1; k--) {
            Column column = tree.column(k);
            if (column.directory.path().equals(path)) {
                double y = column.top + (Math.max(1, column.visible.size()) - 1) * ROW / 2;
                return Optional.of(new MenuRequest(null, column.directory,
                        camera.sx(columnX(k) - COLUMN_GAP * 0.35), camera.sy(y)));
            }
        }
        return Optional.empty();
    }

    /**
     * Marks what an open card is about, or nothing with null.
     */
    public void setMenuTarget(MenuRequest request) {
        painter.menuTarget = request;
    }

    /**
     * The folder something dropped at this point goes into: a folder's dot, the folder holding a file's dot,
     * or the folder whose space is there.
     */
    public Optional<RemoteEntry> folderAt(double x, double y) {
        Hit hit = hitTest(x, y);
        if (hit != null) {
            return hit.entry().isDirectory() ? Optional.of(hit.entry()) : Optional.ofNullable(tree.folderOf(hit.column()));
        }
        int space = spaceTest(x, y);
        return space >= 1 ? Optional.of(tree.column(space).directory) : Optional.empty();
    }

    /**
     * Highlights where a drag would drop, in red with the reason when it cannot; null clears it.
     */
    public void setDropTarget(RemoteEntry folder, String problem) {
        painter.dropTarget = folder;
        painter.dropProblem = problem;
    }

    /**
     * Folders receiving an upload right now: particles stream into them.
     */
    public void setIncoming(Set<String> folders) {
        painter.incoming = Set.copyOf(folders);
    }

    /**
     * Entries being downloaded right now: particles stream out of them.
     */
    public void setOutgoing(Set<String> paths) {
        painter.outgoing = Set.copyOf(paths);
    }

    /**
     * When on, dragging an entry (instead of empty space) carries it out of the tree, e.g. to download it.
     */
    public void setTransferDrag(boolean enabled) {
        this.transferDrag = enabled;
    }

    /**
     * Re-opens the path to the home folder.
     */
    public void goHome() {
        if (tree.homePath() != null) {
            setHome(tree.homePath());
        }
    }

    /**
     * Delivers the content of a folder requested through {@code onOpen}. Ignored if the user moved elsewhere meanwhile.
     */
    public void setListing(DirectoryListing listing) {
        Delivery delivery = tree.deliver(listing, selectedPath, pendingSelect);
        if (delivery == null) {
            return;
        }
        if (!delivery.refreshed()) {
            continueChain(delivery.column());
            return;
        }
        if (delivery.pathShortened()) {
            onNavigate.accept(currentDirectory());
        }
        if (delivery.toSelect() != null) {
            select(delivery.toSelect());
            if (delivery.revealIndex() >= 0) {
                pendingSelect = null;
                tree.layout();
                camera.reveal(columnX(delivery.column()), tree.nodeY(tree.column(delivery.column()), delivery.revealIndex()),
                        canvas.getWidth(), canvas.getHeight());
            }
        }
    }

    public void setFilter(boolean showFiles, boolean showHidden) {
        if (tree.setFilter(showFiles, showHidden)) {
            onNavigate.accept(currentDirectory());
        }
    }

    /**
     * Closes the last opened folder.
     *
     * @return false when already at the root
     */
    public boolean back() {
        Column removed = tree.removeLast();
        if (removed == null) {
            return false;
        }
        int last = tree.size() - 1;
        tree.layout();
        focus(last, tree.column(last).top + (tree.column(last).visible.size() - 1) * ROW / 2, false);
        select(removed.directory);
        onNavigate.accept(currentDirectory());
        return true;
    }

    /**
     * The folder at the end of the open path, or null before the tree starts.
     */
    public RemoteEntry currentDirectory() {
        return tree.currentDirectory();
    }

    /**
     * Marks the entry whose total size is being measured, or none with null.
     */
    public void setMeasuring(String path) {
        painter.measuringPath = path;
    }

    public void zoomIn() {
        camera.zoomAround(1.3, canvas.getWidth() / 2, (canvas.getHeight() + HEADER_HEIGHT) / 2, true);
    }

    public void zoomOut() {
        camera.zoomAround(1 / 1.3, canvas.getWidth() / 2, (canvas.getHeight() + HEADER_HEIGHT) / 2, true);
    }

    public void resetZoom() {
        camera.zoomAround(1 / camera.targetZoom, canvas.getWidth() / 2, (canvas.getHeight() + HEADER_HEIGHT) / 2, true);
    }

    /**
     * Zooms and pans so the whole open path fits the screen. Large folders only contribute the rows around
     * their opened entry, so one folder with hundreds of entries does not shrink everything to dots.
     */
    public void fit() {
        if (tree.isEmpty() || canvas.getWidth() < 1) {
            return;
        }
        camera.fit(tree.fitBounds(), canvas.getWidth(), canvas.getHeight());
    }

    public void stop() {
        timer.stop();
    }

    /**
     * Opens a visible folder by path, as a double-click would; for previews.
     */
    void open(int column, String path) {
        Column source = tree.column(column);
        int index = indexOf(source.visible, path);
        if (index >= 0) {
            select(source.visible.get(index));
            open(column, source.visible.get(index));
        }
    }

    void selectPath(int column, String path) {
        Column source = tree.column(column);
        int index = indexOf(source.visible, path);
        if (index >= 0) {
            select(source.visible.get(index));
        }
    }

    void sort(int column, BranchTree.SortKey key) {
        tree.sort(column, key);
    }

    /**
     * Advances time without drawing, then draws; for previews.
     */
    void simulate(double from, double to) {
        for (double time = from; time < to; time += 1.0 / 30) {
            renderFrame(time, false);
        }
        renderFrame(to, true);
    }

    void renderFrame(double time, boolean draw) {
        double dt = lastTime < 0 ? 0 : Math.min(0.1, time - lastTime);
        lastTime = time;
        tree.clock = time;
        if (canvas.getWidth() < 1 || tree.isEmpty()) {
            return;
        }

        tree.layout();
        if (pendingFocus != null) {
            focus(pendingFocus.column(), pendingFocus.worldY(), pendingFocus.snap());
            pendingFocus = null;
        }
        camera.step(dt);
        if (Math.abs(camera.zoom - reportedZoom) > 0.004) {
            reportedZoom = camera.zoom;
            onZoomChanged.accept(camera.zoom);
        }
        MenuRequest menuTarget = painter.menuTarget;
        int wantedSpace = menuTarget != null && menuTarget.onSpace() ? tree.columnOf(menuTarget.folder()) : hoveredSpace;
        if (wantedSpace >= 1) {
            if (wantedSpace != painter.shownSpace) {
                painter.spaceAlpha = painter.shownSpace >= 1 ? painter.spaceAlpha * 0.4 : painter.spaceAlpha;
                painter.shownSpace = wantedSpace;
            }
            painter.spaceAlpha += (1 - painter.spaceAlpha) * Math.min(1, dt * 9);
        } else {
            painter.spaceAlpha += (0 - painter.spaceAlpha) * Math.min(1, dt * 9);
        }
        if (draw) {
            painter.paint(canvas.getGraphicsContext2D(), canvas.getWidth(), canvas.getHeight());
        }
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
    }

    // Navigation

    private void open(int column, RemoteEntry entry) {
        if (!tree.open(column, entry)) {
            return;
        }
        tree.layout();
        Column source = tree.column(column);
        int index = indexOf(source.visible, entry.path());
        focus(column + 1, index < 0 ? 0 : tree.nodeY(source, index), false);
        onOpen.accept(entry);
        onNavigate.accept(entry);
    }

    /**
     * While opening the way to a folder, opens the next folder of the path once its parent is listed.
     */
    private void continueChain(int columnIndex) {
        RemoteEntry next = tree.nextInChain(columnIndex);
        if (next == null) {
            return;
        }
        select(next);
        open(columnIndex, next);
        if (tree.chainDone()) {
            // The folder reached ends up in the middle of the screen.
            tree.layout();
            Column column = tree.column(columnIndex);
            camera.center(columnX(columnIndex), tree.nodeY(column, indexOf(column.visible, next.path())),
                    canvas.getWidth(), canvas.getHeight());
        }
    }

    private void select(RemoteEntry entry) {
        selected = entry;
        selectedPath = entry.path();
        painter.selectedPath = selectedPath;
        onSelect.accept(entry);
    }

    private void focus(int column, double worldY, boolean snap) {
        if (canvas.getWidth() < 1) {
            pendingFocus = new Focus(column, worldY, snap);
            return;
        }
        camera.focus(columnX(column), worldY, canvas.getWidth(), canvas.getHeight(), snap);
    }

    // Input

    private Hit hitTest(double screenX, double screenY) {
        if (screenY < HEADER_HEIGHT) {
            return null;
        }
        return tree.hitAt(camera.worldX(screenX), camera.worldY(screenY), camera.zoom);
    }

    private int spaceTest(double screenX, double screenY) {
        if (screenY < HEADER_HEIGHT) {
            return -1;
        }
        return tree.spaceAt(camera.worldX(screenX), camera.worldY(screenY));
    }

    private void handleMove(MouseEvent event) {
        painter.mouseX = event.getX();
        painter.mouseY = event.getY();
        painter.hovered = hitTest(event.getX(), event.getY());
        boolean overChip = painter.chipAt(event.getX(), event.getY()) != null;
        hoveredSpace = menus && painter.hovered == null && !overChip ? spaceTest(event.getX(), event.getY()) : -1;
        setCursor(painter.hovered != null || overChip ? Cursor.HAND : Cursor.DEFAULT);
    }

    private void handleDrag(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY && event.getButton() != MouseButton.MIDDLE) {
            return;
        }
        camera.drag(event.getX(), event.getY(), System.nanoTime());
        setCursor(Cursor.CLOSED_HAND);
    }

    private void handleDragDetected(MouseEvent event) {
        if (!transferDrag || event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        // The first pixels already panned the map: look where the press was.
        double[] panned = camera.rewindToPress();
        Hit hit = hitTest(camera.pressX(), camera.pressY());
        if (hit == null || hit.column() == 0) {
            camera.restore(panned);
            return;
        }
        camera.settle();
        painter.carried = hit.entry().path();
        Dragboard board = startDragAndDrop(TransferMode.COPY);
        ClipboardContent content = new ClipboardContent();
        content.put(REMOTE_PATH, painter.carried);
        board.setContent(content);
        board.setDragView(LocalCard.tag("⇩  " + hit.entry().name(), "#6ee0c8"), -12, -12);
        event.consume();
    }

    private void handleClick(MouseEvent event) {
        if (event.getButton() == MouseButton.BACK) {
            back();
            return;
        }
        boolean primary = event.getButton() == MouseButton.PRIMARY;
        boolean secondary = event.getButton() == MouseButton.SECONDARY;
        if (!primary && !secondary || !event.isStillSincePress()) {
            return;
        }
        requestFocus();
        if (primary) {
            BranchPainter.SortChip chip = painter.chipAt(event.getX(), event.getY());
            if (chip != null) {
                sort(chip.column(), chip.key());
                return;
            }
        }
        Hit hit = hitTest(event.getX(), event.getY());
        if (hit == null) {
            int space = menus && event.getClickCount() == 1 ? spaceTest(event.getX(), event.getY()) : -1;
            if (space >= 1) {
                onMenu.accept(new MenuRequest(null, tree.column(space).directory, event.getX(), event.getY()));
            }
            return;
        }
        if (!hit.entry().path().equals(selectedPath)) {
            select(hit.entry());
        }
        if (secondary) {
            if (menus) {
                menuFor(hit.entry().path()).ifPresent(onMenu);
            }
            return;
        }
        if (event.getClickCount() >= 2) {
            open(hit.column(), hit.entry());
        }
    }

    private void handleScroll(ScrollEvent event) {
        if (event.isControlDown()) {
            camera.zoomAround(Math.exp(event.getDeltaY() * 0.0022), event.getX(), event.getY(), true);
        } else {
            double horizontal = event.isShiftDown() ? event.getDeltaY() : event.getDeltaX();
            double vertical = event.isShiftDown() ? 0 : event.getDeltaY();
            camera.wheel(horizontal, vertical);
        }
        event.consume();
    }

    private void handlePinch(ZoomEvent event) {
        camera.zoomAround(event.getZoomFactor(), event.getX(), event.getY(), false);
        event.consume();
    }
}
