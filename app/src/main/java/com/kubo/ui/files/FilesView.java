package com.kubo.ui.files;

import com.kubo.command.CommandEntry;
import com.kubo.files.FileBrowser;
import com.kubo.model.files.Account;
import com.kubo.model.files.DirectoryListing;
import com.kubo.model.files.RemoteEntry;
import com.kubo.ui.files.BranchCanvas.MenuRequest;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The file manager: the folder lineage in the middle, details of the clicked entry on the right,
 * a shell at the bottom that follows the open folder. Actions float over the tree where they were asked for.
 *
 * <p>This class wires the parts together and offers them shared services (running commands, reloading folders,
 * opening editors and transfers); the parts are {@link BranchCanvas}, {@link DetailsPanel}, {@link FloatingLayer},
 * {@link DropHandler}, {@link TransferPanel}, {@link LocalCard} and {@link CommandPrompt}.
 */
public final class FilesView extends StackPane {

    // Folders read less than this long ago are not read again, e.g. when returning home.
    private static final long CACHE_NANOS = 30_000_000_000L;

    private record CachedListing(DirectoryListing listing, long readAt) {
    }

    private final FileBrowser browser;
    private final ExecutorService worker = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "kubo-files");
        thread.setDaemon(true);
        return thread;
    });
    private final BranchCanvas canvas = new BranchCanvas();
    private final CommandPrompt prompt;
    private final DetailsPanel details;
    private final StackPane map;
    private final FloatingLayer floating;
    private final TransferPanel transfers;
    private final Label notice = new Label();
    private final PauseTransition noticeTimer = new PauseTransition(Duration.seconds(4));

    private final Label pathLabel = label("", "mono-muted");
    private final ToggleGroup viewGroup = new ToggleGroup();
    private final ToggleButton foldersOnly = toggle("FOLDERS");
    private final ToggleButton foldersAndFiles = toggle("FOLDERS + FILES");
    /** Holds the ROOT button once the session's root state is known. */
    private final HBox rootSlot = new HBox();
    private com.kubo.privilege.RootSession rootSession;
    private StackPane rootOverlay;
    private final ToggleButton hiddenToggle = toggle("HIDDEN");
    private final ToggleButton shellToggle = toggle("SHELL");

    private final Map<String, CachedListing> cache = new HashMap<>();
    private final Map<String, EditorWindow> editors = new HashMap<>();
    /** One window per file, so opening the same picture twice brings the first one back. */
    private final Map<String, com.kubo.ui.media.MediaWindow> viewers = new HashMap<>();
    private Account account;
    private LocalCard localCard;
    private Runnable onLogs = () -> {
    };
    private int unseen;
    private Path lastDownloadFolder = defaultDownloads();

    /**
     * @param browser null to build the view without a server (previews)
     */
    public FilesView(FileBrowser browser) {
        this.browser = browser;
        this.prompt = new CommandPrompt(browser, worker);
        this.details = new DetailsPanel(browser, worker, canvas);
        getStyleClass().add("files-view");

        notice.getStyleClass().add("files-notice");
        notice.setVisible(false);
        notice.setMouseTransparent(true);
        StackPane.setAlignment(notice, Pos.BOTTOM_LEFT);
        StackPane.setMargin(notice, new Insets(0, 0, 18, 36));
        noticeTimer.setOnFinished(event -> FloatingLayer.fade(notice, false));

        transfers = new TransferPanel(this);
        StackPane.setAlignment(transfers, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(transfers, new Insets(0, 24, 62, 0));
        map = new StackPane(canvas);
        floating = new FloatingLayer(this, map, canvas);
        map.getChildren().addAll(floating.overlay(), buildZoomBar(), notice, transfers);
        map.setMinSize(0, 0);
        // Floating cards may reach below the tree: they stay above the shell.
        map.setViewOrder(-1);
        new DropHandler(this, map, canvas);

        VBox center = new VBox(map, prompt);
        VBox.setVgrow(map, Priority.ALWAYS);
        prompt.setOnHide(() -> setShellVisible(false));
        prompt.setOnLogs(() -> onLogs.run());
        prompt.setOnActivity(() -> {
            if (!prompt.isVisible()) {
                unseen++;
                shellToggle.setText("SHELL · " + unseen);
            }
        });

        BorderPane layout = new BorderPane(center);
        layout.setTop(buildHeader());
        layout.setRight(details);

        getChildren().add(layout);

        canvas.setOnOpen(directory -> load(canvas, directory));
        canvas.setOnSelect(entry -> details.show(entry, account, elevated()));
        canvas.setOnNavigate(directory -> {
            pathLabel.setText(directory.path());
            prompt.setDirectory(directory.path());
            if (localCard != null) {
                localCard.setDestination(directory);
            }
        });
        canvas.setOnMenu(floating::showCard);

        viewGroup.selectedToggleProperty().addListener((observable, previous, selected) -> {
            if (selected == null) {
                previous.setSelected(true);
                return;
            }
            applyFilter();
        });
        hiddenToggle.selectedProperty().addListener((observable, previous, selected) -> applyFilter());
        addEventHandler(KeyEvent.KEY_PRESSED, this::handleKey);
        // Folders and files from the start; the top bar switches it at any time.
        choose(true);
    }

    public void shutdown() {
        floating.closePicker();
        transfers.shutdown();
        if (localCard != null) {
            localCard.shutdown();
        }
        canvas.stop();
        worker.shutdownNow();
        editors.values().forEach(EditorWindow::close);
        editors.clear();
        viewers.values().forEach(com.kubo.ui.media.MediaWindow::close);
        viewers.clear();
    }

    /**
     * Editors with changes not saved yet; they are brought to the front.
     */
    public boolean hasUnsavedEditors() {
        boolean unsaved = false;
        for (EditorWindow editor : editors.values()) {
            if (editor.isShowing() && editor.isDirty()) {
                editor.show();
                unsaved = true;
            }
        }
        return unsaved;
    }

    /**
     * Transfers still running; closing now would cut them.
     */
    public boolean hasActiveUploads() {
        return transfers.active();
    }

    public void setOnLogs(Runnable onLogs) {
        this.onLogs = onLogs;
    }

    /**
     * Shows folders, or folders and files, and starts from the home folder.
     */
    void choose(boolean showFiles) {
        (showFiles ? foldersAndFiles : foldersOnly).setSelected(true);
        applyFilter();
        if (browser == null) {
            return;
        }
        details.status("LOCATING HOME", "");
        worker.execute(() -> {
            var found = browser.account();
            List<String> missing = browser.missingGnuTools();
            Platform.runLater(() -> {
                if (found.isPresent()) {
                    account = found.get();
                    canvas.setHome(account.home());
                } else {
                    details.status("HOME NOT FOUND", "The server did not report a home folder for this user.");
                }
                if (!missing.isEmpty()) {
                    notice("THIS SERVER LACKS GNU TOOLS (" + String.join(", ", missing).toUpperCase(java.util.Locale.ROOT)
                            + "): SOME ACTIONS WILL FAIL. KUBO SUPPORTS LINUX SERVERS WITH GNU COREUTILS.", true);
                }
            });
        });
    }

    // Parts, for previews

    BranchCanvas canvas() {
        return canvas;
    }

    CommandPrompt prompt() {
        return prompt;
    }

    DestinationPicker picker() {
        return floating.picker();
    }

    ActionCard card() {
        return floating.card();
    }

    TransferPanel transfers() {
        return transfers;
    }

    LocalCard localCard() {
        return localCard;
    }

    void setAccount(Account account) {
        this.account = account;
    }

    // Services for cards, the picker, transfers and editors

    FileBrowser browser() {
        return browser;
    }

    Account account() {
        return account;
    }

    /**
     * Runs a command off the UI thread and hands its result back on it.
     */
    void run(Supplier<CommandEntry> action, Consumer<CommandEntry> done) {
        if (browser == null) {
            return;
        }
        worker.execute(() -> {
            CommandEntry result = action.get();
            Platform.runLater(() -> done.accept(result));
        });
    }

    /**
     * Folders changed on the server: forget what was read about them and read the open ones again.
     *
     * @param select an entry to select once read again, or null
     */
    void changed(String select, String... folders) {
        details.clearMeasurements();
        for (String folder : folders) {
            if (folder != null) {
                cache.remove(folder);
                canvas.refresh(folder, select);
            }
        }
    }

    /**
     * An entry is gone: its details are cleared if they were shown.
     */
    void forget(String path) {
        details.forget(path);
    }

    void showCard(MenuRequest request) {
        floating.showCard(request);
    }

    void closeCard() {
        floating.closeCard();
    }

    void openFolder(RemoteEntry folder) {
        canvas.openPath(folder.path());
    }

    /**
     * Shows a picture, plays a sound or a film.
     *
     * <p>Separate from {@link #edit}, because an editor opening an MP4 shows a page of bytes and the first
     * thing it would do is offer to save them back.
     */
    void view(RemoteEntry file, com.kubo.model.media.MediaKind kind) {
        if (browser == null) {
            return;
        }
        com.kubo.ui.media.MediaWindow window = viewers.get(file.path());
        if (window == null || !window.isShowing()) {
            window = new com.kubo.ui.media.MediaWindow(browser, file, kind);
            viewers.put(file.path(), window);
        }
        window.show();
    }

    void edit(RemoteEntry file) {
        if (browser == null) {
            return;
        }
        EditorWindow window = editors.get(file.path());
        if (window == null || !window.isShowing()) {
            window = new EditorWindow(browser, file.path(), saved ->
                    changed(saved, RemoteEntry.parentOf(saved).orElse(null)));
            editors.put(file.path(), window);
        }
        window.show();
    }

    /**
     * Opens the destination tree for a copy or a move.
     */
    void transfer(RemoteEntry entry, RemoteEntry folder, boolean move) {
        String home = account != null ? account.home() : folder.path();
        floating.openPicker(entry, folder, move, home, hiddenToggle.isSelected());
    }

    void closePicker() {
        floating.closePicker();
    }

    void load(BranchCanvas tree, RemoteEntry directory) {
        if (browser == null) {
            return;
        }
        CachedListing cached = cache.get(directory.path());
        if (cached != null && System.nanoTime() - cached.readAt() < CACHE_NANOS) {
            // Delivered on the next pulse, once the tree has finished opening the column.
            Platform.runLater(() -> tree.setListing(cached.listing()));
            return;
        }
        worker.execute(() -> {
            DirectoryListing listing = browser.list(directory.path());
            Platform.runLater(() -> {
                if (!listing.failed()) {
                    cache.put(listing.path(), new CachedListing(listing, System.nanoTime()));
                }
                tree.setListing(listing);
                if (tree != canvas) {
                    // A folder read for the picker is fresh for the main tree too.
                    canvas.setListing(listing);
                }
            });
        });
    }

    void notice(String text, boolean error) {
        notice.setText(text);
        notice.getStyleClass().setAll("label", "files-notice");
        if (error) {
            notice.getStyleClass().add("files-notice-error");
        }
        FloatingLayer.fade(notice, true);
        noticeTimer.playFromStart();
    }

    // Shell and this computer

    /**
     * Shows or hides the shell; hidden, the top bar counts what it missed.
     */
    void setShellVisible(boolean visible) {
        prompt.setVisible(visible);
        prompt.setManaged(visible);
        shellToggle.setSelected(visible);
        if (visible) {
            unseen = 0;
            shellToggle.setText("SHELL");
        }
    }

    /**
     * Opens or closes the view of this computer, for uploads and downloads.
     */
    void toggleLocal() {
        if (localCard != null) {
            map.getChildren().remove(localCard);
            localCard.shutdown();
            localCard = null;
            canvas.setTransferDrag(false);
            return;
        }
        localCard = new LocalCard(this);
        // With the card open, dragging an entry of the tree carries it there to download it.
        canvas.setTransferDrag(true);
        localCard.setDestination(canvas.currentDirectory());
        localCard.setPrefWidth(430);
        localCard.setMaxWidth(430);
        localCard.maxHeightProperty().bind(map.heightProperty().subtract(112));
        StackPane.setAlignment(localCard, Pos.BOTTOM_LEFT);
        StackPane.setMargin(localCard, new Insets(0, 0, 16, 16));
        map.getChildren().add(map.getChildren().indexOf(transfers), localCard);
        FloatingLayer.fade(localCard, true);
    }

    /**
     * Queues local files and folders for a folder of the server.
     */
    void upload(List<Path> paths, RemoteEntry folder) {
        if (!paths.isEmpty()) {
            transfers.upload(paths, folder);
        }
    }

    /**
     * Queues a file or folder of the server for a folder of this computer.
     */
    void download(RemoteEntry entry, Path folder) {
        lastDownloadFolder = folder;
        transfers.download(entry, folder);
    }

    /**
     * An entry dragged out of the tree and dropped on this computer's card.
     */
    void downloadPath(String remotePath, Path folder) {
        canvas.menuFor(remotePath).map(MenuRequest::entry).ifPresent(entry -> download(entry, folder));
    }

    /**
     * Where a download goes when nothing else is chosen: the folder open in this computer's card, otherwise the
     * last one used, otherwise Downloads.
     */
    Path downloadFolder() {
        if (localCard != null && localCard.currentFolder() != null) {
            return localCard.currentFolder();
        }
        return lastDownloadFolder;
    }

    /**
     * A download finished: said, and shown in this computer's card if it displays that folder.
     */
    void downloaded(Path saved) {
        notice("SAVED  " + saved, false);
        if (localCard != null) {
            localCard.refreshIfShowing(saved.getParent(), saved.getFileName().toString());
        }
    }

    // Keys

    private void handleKey(KeyEvent event) {
        boolean typing = getScene() != null && getScene().getFocusOwner() instanceof TextInputControl;
        ActionCard card = floating.card();
        DestinationPicker picker = floating.picker();
        if (event.getCode() == KeyCode.ESCAPE) {
            if (card != null) {
                floating.closeCard();
            } else if (picker != null && !picker.busy()) {
                floating.closePicker();
            } else {
                return;
            }
            event.consume();
            return;
        }
        if (picker != null) {
            if (!typing && (event.getCode() == KeyCode.BACK_SPACE || event.isAltDown() && event.getCode() == KeyCode.LEFT)) {
                picker.back();
                event.consume();
            }
            return;
        }
        if (event.isAltDown() && event.getCode() == KeyCode.LEFT) {
            canvas.back();
            event.consume();
            return;
        }
        if (typing || card != null) {
            return;
        }
        RemoteEntry selected = canvas.selectedEntry();
        switch (event.getCode()) {
            case BACK_SPACE -> canvas.back();
            case PLUS, ADD, EQUALS -> canvas.zoomIn();
            case MINUS, SUBTRACT -> canvas.zoomOut();
            case DIGIT0, NUMPAD0 -> canvas.resetZoom();
            case F -> canvas.fit();
            case DELETE, F2 -> {
                if (selected == null) {
                    return;
                }
                canvas.menuFor(selected.path()).ifPresent(request -> {
                    floating.showCard(request);
                    ActionCard opened = floating.card();
                    if (opened == null || request.folder() == null) {
                        return;
                    }
                    if (account == null || account.canRemove(selected, request.folder())) {
                        if (event.getCode() == KeyCode.DELETE) {
                            opened.showDelete();
                        } else {
                            opened.showRename();
                        }
                    }
                });
            }
            case ENTER -> {
                if (selected == null) {
                    return;
                }
                if (selected.isDirectory()) {
                    canvas.openPath(selected.path());
                } else if (selected.type() != RemoteEntry.Type.OTHER) {
                    edit(selected);
                }
            }
            default -> {
                return;
            }
        }
        event.consume();
    }

    // Building

    private void applyFilter() {
        canvas.setFilter(foldersAndFiles.isSelected(), hiddenToggle.isSelected());
    }

    private HBox buildZoomBar() {
        Button out = new Button("−");
        Button in = new Button("+");
        Button reset = new Button("100 %");
        Button fit = new Button("FIT");
        for (Button button : new Button[]{out, in, reset, fit}) {
            button.getStyleClass().add("zoom-button");
            button.setFocusTraversable(false);
        }
        out.setOnAction(event -> canvas.zoomOut());
        in.setOnAction(event -> canvas.zoomIn());
        reset.setOnAction(event -> canvas.resetZoom());
        fit.setOnAction(event -> canvas.fit());
        canvas.setOnZoomChanged(value -> reset.setText(Math.round(value * 100) + " %"));

        HBox bar = new HBox(4, out, reset, in, fit);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.getStyleClass().add("zoom-bar");
        bar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(bar, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(bar, new Insets(0, 24, 18, 0));
        return bar;
    }

    /**
     * Gives the file manager the session's root state: the ROOT button appears, and every file command runs as
     * root while root mode is on.
     */
    public void setRootSession(com.kubo.privilege.RootSession session) {
        this.rootSession = session;
        if (browser != null) {
            browser.setRootSession(session);
        }
        rootSlot.getChildren().setAll(new com.kubo.ui.RootButton(session,
                (command, done) -> worker.execute(() -> {
                    com.kubo.command.CommandEntry entry = browser == null ? null : browser.verify(command);
                    boolean ok = entry != null && entry.success();
                    Platform.runLater(() -> done.accept(ok));
                }),
                this::showRootCard, this::hideRootCard));
    }

    /**
     * True when file commands are currently running as root, so the cards stop withholding actions the connected
     * user could not do on their own but root can.
     */
    boolean elevated() {
        return browser != null && browser.elevated();
    }

    private void showRootCard(Region card) {
        hideRootCard();
        Region scrim = new Region();
        scrim.getStyleClass().add("picker-scrim");
        rootOverlay = new StackPane(scrim, card);
        rootOverlay.setMinSize(0, 0);
        getChildren().add(rootOverlay);
    }

    private void hideRootCard() {
        if (rootOverlay != null) {
            getChildren().remove(rootOverlay);
            rootOverlay = null;
        }
    }

    private HBox buildHeader() {
        Label wordmark = com.kubo.ui.RootButton.wordmark();
        HBox brand = new HBox(wordmark);
        brand.setAlignment(Pos.BOTTOM_LEFT);

        Label kicker = label("FILES.SCOPE", "series");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        foldersOnly.setToggleGroup(viewGroup);
        foldersAndFiles.setToggleGroup(viewGroup);
        foldersAndFiles.setSelected(true);
        Button backButton = new Button("← BACK");
        backButton.getStyleClass().add("ghost-button");
        backButton.setOnAction(event -> canvas.back());
        Button homeButton = new Button("⌂ HOME");
        homeButton.getStyleClass().add("ghost-button");
        homeButton.setOnAction(event -> canvas.goHome());

        Button importButton = new Button("⇅ THIS PC");
        importButton.getStyleClass().addAll("ghost-button", "local-ghost");
        importButton.setOnAction(event -> toggleLocal());
        Button logsButton = new Button("≡ LOGS");
        logsButton.getStyleClass().add("ghost-button");
        logsButton.setOnAction(event -> onLogs.run());
        shellToggle.setSelected(true);
        shellToggle.setOnAction(event -> setShellVisible(shellToggle.isSelected()));
        pathLabel.setMinWidth(0);
        for (Region control : new Region[]{foldersOnly, foldersAndFiles, hiddenToggle, homeButton, backButton,
                importButton, logsButton, shellToggle}) {
            control.setMinWidth(Region.USE_PREF_SIZE);
        }

        rootSlot.setAlignment(Pos.CENTER_LEFT);
        HBox header = new HBox(18, brand, kicker, pathLabel, spacer, foldersOnly, foldersAndFiles, hiddenToggle,
                homeButton, backButton, importButton, rootSlot, logsButton, shellToggle);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(22, 40, 6, 36));
        HBox.setMargin(hiddenToggle, new Insets(0, 12, 0, 12));
        HBox.setMargin(importButton, new Insets(0, 0, 0, 16));
        return header;
    }

    private static ToggleButton toggle(String text) {
        Circle marker = new Circle(3);
        marker.getStyleClass().add("mode-marker");
        ToggleButton toggle = new ToggleButton(text, marker);
        toggle.getStyleClass().add("mode-toggle");
        return toggle;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static Path defaultDownloads() {
        Path home = Path.of(System.getProperty("user.home"));
        Path downloads = home.resolve("Downloads");
        return Files.isDirectory(downloads) ? downloads : home;
    }
}
