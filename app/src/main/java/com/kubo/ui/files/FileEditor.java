package com.kubo.ui.files;

import com.kubo.command.CommandEntry;
import com.kubo.files.FileBrowser;
import com.kubo.files.FileCommands;
import com.kubo.model.files.FileText;
import com.kubo.model.files.RemoteEntry;
import com.kubo.ui.scope.Format;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Edits one text file of the server. The file is read whole, edited locally and written back in one go;
 * a save is refused when someone changed the file on the server meanwhile, unless the user overwrites it.
 */
public final class FileEditor extends StackPane {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final Font TEXT_FONT = Font.font("Consolas", 13);
    private static final Font GUTTER_FONT = Font.font("Consolas", 11);
    private static final Font SMALL_FONT = Font.font("Segoe UI", 9);
    // Must match ".editor-area .content" in the stylesheet.
    private static final double PADDING_TOP = 10;
    private static final Color GROUND = Color.web("#050508");
    private static final Color RULE = Color.web("#1c1c24");
    private static final Color MUTED = Color.web("#4a4a57");
    private static final Color TEXT = Color.web("#f4f4f7");
    private static final Color CHANGED = Color.web("#ff3fd8");
    private static final Color LINE = Color.web("#8d8d9b");

    private final FileBrowser browser;
    private final String path;
    private final String name;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-editor");
        thread.setDaemon(true);
        return thread;
    });

    private final TextArea area = new TextArea();
    private final Canvas gutter = new Canvas();
    private final Canvas minimap = new Canvas();
    private final Canvas sparks = new Canvas(220, 150);
    private final Label sizeBadge = badge("—");
    private final Label endingBadge = badge("LF");
    private final Label encodingBadge = badge("UTF-8");
    private final Label readOnlyBadge = badge("");
    private final Label statusLabel = label("READING…", "editor-status");
    private final Label positionLabel = label("LN 1, COL 1", "editor-status");
    private final Label linesLabel = label("", "editor-status");
    private final TextField findField = new TextField();
    private final Button saveButton = new Button("SAVE");
    private final Button reloadButton = new Button("RELOAD");
    private final HBox conflictBar;
    private final VBox messageCard = new VBox(8);
    private final VBox loadingCard;
    private final StackPane dialogLayer = new StackPane();
    private final PauseTransition analysis = new PauseTransition(Duration.millis(140));
    private final AnimationTimer animation;
    private final double lineHeight;

    private FileText loaded;
    private String savedText = "";
    private String version = "";
    private boolean saving;
    private boolean loading;
    private boolean closeAfterSave;
    private double clock;
    private double savedFlash = -1;

    private int[] lineStarts = {0};
    private int[] lineIndent = {0};
    private int[] lineLength = {0};
    private int changedFrom = -1;
    private int changedTo = -1;

    private Consumer<String> onSaved = saved -> {
    };
    private Consumer<String> onTitle = title -> {
    };
    private Runnable onClose = () -> {
    };

    /**
     * @param browser null to build the editor without a server (previews)
     */
    public FileEditor(FileBrowser browser, String path) {
        this.browser = browser;
        this.path = path;
        this.name = RemoteEntry.folder(path).name();
        this.lineHeight = measureLineHeight();
        getStyleClass().add("editor-view");

        area.getStyleClass().add("editor-area");
        area.setWrapText(false);
        area.setEditable(false);
        HBox.setHgrow(area, Priority.ALWAYS);

        Pane gutterPane = canvasPane(gutter, 62, this::drawGutter);
        Pane minimapPane = canvasPane(minimap, 104, this::drawMinimap);
        HBox body = new HBox(gutterPane, area, minimapPane);

        conflictBar = buildConflictBar();
        VBox top = new VBox(buildHeader(), buildInfoRow(), conflictBar);

        HBox bar = new HBox(22, positionLabel, linesLabel);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        bar.getChildren().addAll(spacer, label("CTRL+S SAVE  ·  CTRL+F FIND  ·  F3 NEXT  ·  TAB INSERTS A TAB",
                "editor-status"));
        bar.getStyleClass().add("editor-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        BorderPane layout = new BorderPane(body);
        layout.setTop(top);
        layout.setBottom(bar);

        loadingCard = new VBox(4, sparks, label("READING " + path, "mono-muted"));
        loadingCard.setAlignment(Pos.CENTER);
        loadingCard.setMouseTransparent(true);
        messageCard.getStyleClass().add("chooser-card");
        messageCard.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        messageCard.setVisible(false);
        dialogLayer.setVisible(false);
        dialogLayer.getStyleClass().add("picker-scrim");
        getChildren().addAll(layout, loadingCard, messageCard, dialogLayer);

        area.textProperty().addListener((observable, previous, text) -> {
            indexLines(text);
            analysis.playFromStart();
            refreshState();
            drawGutter();
        });
        analysis.setOnFinished(event -> analyzeChanges());
        area.caretPositionProperty().addListener((observable, previous, caret) -> {
            updatePosition();
            drawGutter();
        });
        area.scrollTopProperty().addListener((observable, previous, scroll) -> {
            drawGutter();
            drawMinimap();
        });
        area.heightProperty().addListener((observable, previous, height) -> drawMinimap());
        minimap.setOnMousePressed(this::scrollFromMinimap);
        minimap.setOnMouseDragged(this::scrollFromMinimap);

        addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);

        animation = new AnimationTimer() {
            private long start = -1;

            @Override
            public void handle(long now) {
                if (start < 0) {
                    start = now;
                }
                clock = (now - start) / 1e9;
                drawSparks();
                drawMinimap();
                if (!loading && !saving && (savedFlash < 0 || clock - savedFlash > 1.2)) {
                    stop();
                    start = -1;
                    drawMinimap();
                }
            }
        };
        setLoading(true);
        refreshState();
    }

    public void setOnSaved(Consumer<String> onSaved) {
        this.onSaved = onSaved;
    }

    /**
     * Receives the window title, now and whenever it changes.
     */
    public void setOnTitle(Consumer<String> onTitle) {
        this.onTitle = onTitle;
        refreshState();
    }

    /**
     * Called when the editor asks its window to close, after the user decided about unsaved changes.
     */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    public void load() {
        if (browser == null) {
            return;
        }
        setLoading(true);
        worker.execute(() -> {
            FileText text = browser.read(path);
            Platform.runLater(() -> show(text));
        });
    }

    public boolean isDirty() {
        return loaded != null && !loaded.failed() && !area.getText().equals(savedText);
    }

    /**
     * Asks what to do with unsaved changes before closing.
     */
    public void confirmClose() {
        if (!isDirty()) {
            onClose.run();
            return;
        }
        Button save = primary("SAVE AND CLOSE", () -> {
            hideDialog();
            closeAfterSave = true;
            save(false);
        });
        Button discard = danger("DISCARD", () -> {
            hideDialog();
            onClose.run();
        });
        showDialog("UNSAVED CHANGES", name + " HAS CHANGES THAT ARE NOT ON THE SERVER YET.", save, discard);
    }

    public void shutdown() {
        animation.stop();
        analysis.stop();
        worker.shutdownNow();
    }

    /**
     * Shows a file read from the server; also used by previews.
     */
    void show(FileText text) {
        setLoading(false);
        loaded = text;
        conflictBar.setVisible(false);
        conflictBar.setManaged(false);
        if (text.failed()) {
            area.clear();
            savedText = "";
            area.setDisable(true);
            showMessage("CANNOT OPEN THIS FILE", text.error());
            setStatus(text.error().lines().findFirst().orElse("").toUpperCase(Locale.ROOT), "editor-status-error");
            refreshState();
            return;
        }
        boolean binary = text.readOnly() && text.text().isEmpty() && text.readOnlyReason().equals("Binary file");
        area.setDisable(binary);
        savedText = text.text();
        version = text.version();
        area.setText(text.text());
        area.positionCaret(0);
        area.setScrollTop(0);
        area.setEditable(!text.readOnly());
        if (binary) {
            showMessage("BINARY FILE", "KUBO ONLY EDITS TEXT. THIS FILE IS SHOWN IN THE TREE BUT NOT OPENED HERE.");
        } else {
            messageCard.setVisible(false);
        }
        endingBadge.setText(text.crlf() ? "CRLF" : "LF");
        readOnlyBadge.setText(text.readOnly() ? "READ ONLY  ·  " + text.readOnlyReason().toUpperCase(Locale.ROOT) : "");
        readOnlyBadge.setVisible(text.readOnly());
        readOnlyBadge.setManaged(text.readOnly());
        analyzeChanges();
        setStatus("OPENED " + CLOCK.format(Instant.now()), "editor-status");
        refreshState();
        Platform.runLater(area::requestFocus);
    }

    /**
     * Replaces the text as if typed, for previews.
     */
    void type(String text) {
        area.setText(text);
        analyzeChanges();
    }

    // Saving

    private void save(boolean overwrite) {
        if (loaded == null || loaded.failed() || loaded.readOnly() || saving) {
            return;
        }
        if (browser == null) {
            return;
        }
        String text = area.getText();
        saving = true;
        setStatus("SAVING…", "editor-status");
        refreshState();
        startAnimation();
        worker.execute(() -> {
            CommandEntry result = browser.write(path, text, loaded.crlf(),
                    overwrite ? Optional.empty() : Optional.of(version));
            Platform.runLater(() -> saved(text, result));
        });
    }

    private void saved(String text, CommandEntry result) {
        saving = false;
        if (result.success()) {
            savedText = text;
            version = FileCommands.parseVersion(result).orElse(version);
            conflictBar.setVisible(false);
            conflictBar.setManaged(false);
            setStatus("SAVED " + CLOCK.format(Instant.now()), "editor-status-ok");
            savedFlash = clock;
            startAnimation();
            analyzeChanges();
            onSaved.accept(path);
            if (closeAfterSave) {
                onClose.run();
            }
        } else if (result.exitCode() == FileCommands.CHANGED_EXIT) {
            closeAfterSave = false;
            conflictBar.setVisible(true);
            conflictBar.setManaged(true);
            setStatus("NOT SAVED: CHANGED ON THE SERVER", "editor-status-error");
        } else {
            closeAfterSave = false;
            setStatus("NOT SAVED: " + FileCommands.problem(result).lines().findFirst().orElse("")
                    .toUpperCase(Locale.ROOT), "editor-status-error");
        }
        refreshState();
    }

    private void reload() {
        if (isDirty()) {
            Button reload = danger("RELOAD", () -> {
                hideDialog();
                load();
            });
            showDialog("RELOAD FROM THE SERVER?", "YOUR CHANGES TO " + name + " WILL BE LOST.", reload);
        } else {
            load();
        }
    }

    // State

    private void refreshState() {
        boolean dirty = isDirty();
        boolean editable = loaded != null && !loaded.failed() && !loaded.readOnly();
        saveButton.setDisable(!editable || !dirty || saving);
        reloadButton.setDisable(loading || saving);
        boolean showsDirty = statusLabel.getStyleClass().contains("editor-status-dirty");
        boolean showsError = statusLabel.getStyleClass().contains("editor-status-error");
        if (dirty && !saving && !showsDirty && !showsError) {
            setStatus("● MODIFIED  ·  CTRL+S TO SAVE", "editor-status-dirty");
        } else if (!dirty && showsDirty) {
            setStatus("NO UNSAVED CHANGE", "editor-status");
        }
        onTitle.accept((dirty ? "● " : "") + name);
    }

    private void setStatus(String text, String styleClass) {
        statusLabel.getStyleClass().setAll("label", "editor-status", styleClass);
        statusLabel.setText(text);
    }

    private void setLoading(boolean value) {
        loading = value;
        loadingCard.setVisible(value);
        if (value) {
            setStatus("READING…", "editor-status");
            messageCard.setVisible(false);
            startAnimation();
        }
    }

    private void startAnimation() {
        animation.start();
    }

    private void showMessage(String title, String text) {
        Label note = label(text, "lede");
        note.setWrapText(true);
        note.setMaxWidth(420);
        messageCard.getChildren().setAll(label(name, "series"), label(title, "host-key-host"), note);
        messageCard.setVisible(true);
    }

    private void showDialog(String title, String text, Button... actions) {
        Label note = label(text, "card-note");
        note.setWrapText(true);
        note.setMaxWidth(360);
        Button cancel = new Button("CANCEL");
        cancel.getStyleClass().add("ghost-button");
        cancel.setOnAction(event -> {
            closeAfterSave = false;
            hideDialog();
        });
        HBox buttons = new HBox(16, actions);
        buttons.getChildren().add(cancel);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(12, label(title, "card-title"), note, buttons);
        card.getStyleClass().add("float-card");
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        dialogLayer.getChildren().setAll(card);
        dialogLayer.setVisible(true);
        Platform.runLater(() -> actions[0].requestFocus());
    }

    private void hideDialog() {
        dialogLayer.setVisible(false);
        dialogLayer.getChildren().clear();
        area.requestFocus();
    }

    // Keys and find

    private void handleKey(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            if (dialogLayer.isVisible()) {
                closeAfterSave = false;
                hideDialog();
            } else if (findField.isFocused()) {
                area.requestFocus();
            }
            event.consume();
        } else if (event.isShortcutDown() && event.getCode() == KeyCode.S) {
            save(false);
            event.consume();
        } else if (event.isShortcutDown() && event.getCode() == KeyCode.F) {
            String selected = area.getSelectedText();
            if (!selected.isEmpty() && selected.indexOf('\n') < 0) {
                findField.setText(selected);
            }
            findField.requestFocus();
            findField.selectAll();
            event.consume();
        } else if (event.getCode() == KeyCode.F3) {
            find(event.isShiftDown());
            event.consume();
        }
    }

    private void find(boolean backwards) {
        String query = findField.getText().toLowerCase(Locale.ROOT);
        findField.getStyleClass().remove("find-missing");
        if (query.isEmpty()) {
            return;
        }
        String text = area.getText().toLowerCase(Locale.ROOT);
        int index = backwards
                ? text.lastIndexOf(query, area.getSelection().getStart() - 1)
                : text.indexOf(query, area.getSelection().getEnd());
        if (index < 0) {
            index = backwards ? text.lastIndexOf(query) : text.indexOf(query);
        }
        if (index < 0) {
            findField.getStyleClass().add("find-missing");
            return;
        }
        area.selectRange(index, index + query.length());
        double y = PADDING_TOP + lineOf(index) * lineHeight;
        if (y < area.getScrollTop() + lineHeight || y > area.getScrollTop() + area.getHeight() - lineHeight * 3) {
            area.setScrollTop(Math.max(0, y - area.getHeight() / 3));
        }
    }

    // Lines

    private void indexLines(String text) {
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        int[] starts = new int[count];
        int line = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts[line++] = i + 1;
            }
        }
        lineStarts = starts;
        linesLabel.setText(count + (count == 1 ? " LINE" : " LINES"));
        updatePosition();
    }

    /**
     * The changed region (from the first to the last differing character) and each line's shape for the minimap.
     */
    private void analyzeChanges() {
        String text = area.getText();
        int lines = lineStarts.length;
        int[] indent = new int[lines];
        int[] length = new int[lines];
        for (int i = 0; i < lines; i++) {
            int start = lineStarts[i];
            int end = i + 1 < lines ? lineStarts[i + 1] - 1 : text.length();
            int spaces = 0;
            while (start + spaces < end && spaces < 60 && (text.charAt(start + spaces) == ' ' || text.charAt(start + spaces) == '\t')) {
                spaces++;
            }
            indent[i] = spaces;
            length[i] = end - start - spaces;
        }
        lineIndent = indent;
        lineLength = length;

        if (text.equals(savedText)) {
            changedFrom = -1;
            changedTo = -1;
        } else {
            int prefix = 0;
            int max = Math.min(text.length(), savedText.length());
            while (prefix < max && text.charAt(prefix) == savedText.charAt(prefix)) {
                prefix++;
            }
            int suffix = 0;
            while (suffix < max - prefix
                    && text.charAt(text.length() - 1 - suffix) == savedText.charAt(savedText.length() - 1 - suffix)) {
                suffix++;
            }
            changedFrom = lineOf(prefix);
            changedTo = Math.max(changedFrom, lineOf(Math.max(prefix, text.length() - suffix)));
        }
        sizeBadge.setText(Format.bytes(FileCommands.encode(text, loaded != null && loaded.crlf()).length));
        drawGutter();
        drawMinimap();
    }

    private int lineOf(int position) {
        int index = Arrays.binarySearch(lineStarts, position);
        return index >= 0 ? index : Math.max(0, -index - 2);
    }

    private void updatePosition() {
        int caret = Math.min(area.getCaretPosition(), area.getLength());
        int line = lineOf(caret);
        positionLabel.setText("LN " + (line + 1) + ", COL " + (caret - lineStarts[line] + 1));
    }

    // Drawing

    private void drawGutter() {
        double width = gutter.getWidth();
        double height = gutter.getHeight();
        if (width < 1 || height < 1) {
            return;
        }
        GraphicsContext gc = gutter.getGraphicsContext2D();
        gc.setGlobalAlpha(1);
        gc.setFill(GROUND);
        gc.fillRect(0, 0, width, height);
        gc.setStroke(RULE);
        gc.strokeLine(width - 0.5, 0, width - 0.5, height);
        if (loaded == null || loaded.failed()) {
            return;
        }
        double top = area.getScrollTop();
        int caretLine = lineOf(Math.min(area.getCaretPosition(), area.getLength()));
        int first = Math.max(0, (int) Math.floor((top - PADDING_TOP) / lineHeight));
        gc.setFont(GUTTER_FONT);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setTextBaseline(VPos.CENTER);
        for (int i = first; i < lineStarts.length; i++) {
            double y = PADDING_TOP + i * lineHeight - top;
            if (y > height) {
                break;
            }
            if (i >= changedFrom && i <= changedTo) {
                gc.setFill(CHANGED);
                gc.fillRect(width - 4, y + 1, 2, lineHeight - 2);
            }
            gc.setFill(i == caretLine ? TEXT : MUTED);
            gc.fillText(String.valueOf(i + 1), width - 14, y + lineHeight / 2);
        }
    }

    /**
     * Every line as a particle bar: its indent, its length, magenta where it changed; the frame shows what the
     * editor shows, and a light runs down while saving.
     */
    private void drawMinimap() {
        double width = minimap.getWidth();
        double height = minimap.getHeight();
        if (width < 1 || height < 1) {
            return;
        }
        GraphicsContext gc = minimap.getGraphicsContext2D();
        gc.setGlobalAlpha(1);
        gc.setFill(Color.web("#06060a"));
        gc.fillRect(0, 0, width, height);
        gc.setStroke(RULE);
        gc.strokeLine(0.5, 0, 0.5, height);
        if (loaded == null || loaded.failed()) {
            return;
        }
        int lines = Math.min(lineIndent.length, lineStarts.length);
        double pitch = Math.min(3, (height - 24) / Math.max(1, lines));
        for (int i = 0; i < lines; i++) {
            double y = 12 + i * pitch;
            if (y > height - 12) {
                break;
            }
            if (lineLength[i] <= 0) {
                continue;
            }
            boolean changed = i >= changedFrom && i <= changedTo;
            double x = 12 + lineIndent[i] * 1.6;
            double length = Math.min(lineLength[i], 80) * 1.05;
            gc.setFill(changed ? CHANGED : LINE);
            gc.setGlobalAlpha(changed ? 0.95 : 0.4 + 0.3 * hash(i));
            gc.fillRect(x, y, Math.max(1, Math.min(length, width - x - 8)), Math.max(0.8, pitch - (pitch >= 2 ? 1 : 0.2)));
        }

        double first = Math.max(0, (area.getScrollTop() - PADDING_TOP) / lineHeight);
        double shown = Math.max(1, area.getHeight() / lineHeight);
        double frameTop = 12 + first * pitch;
        double frameHeight = Math.max(6, Math.min(shown, lines) * pitch);
        gc.setGlobalAlpha(0.07);
        gc.setFill(BranchStyle.FOLDER);
        gc.fillRect(4, frameTop - 2, width - 8, frameHeight + 4);
        gc.setGlobalAlpha(0.85);
        gc.setStroke(BranchStyle.FOLDER);
        gc.setLineWidth(1);
        gc.strokeRect(4.5, frameTop - 1.5, width - 9, frameHeight + 3);

        double contentHeight = Math.min(height - 24, lines * pitch);
        if (saving) {
            double y = 12 + (clock * 0.8 % 1) * contentHeight;
            glow(gc, width / 2, y, BranchStyle.CODE);
        } else if (savedFlash >= 0 && clock - savedFlash < 1.2) {
            double t = (clock - savedFlash) / 1.2;
            gc.setGlobalAlpha((1 - t) * 0.35);
            gc.setFill(BranchStyle.CODE);
            gc.fillRect(2, 12, width - 4, contentHeight * Math.min(1, t * 2.5));
        }
        gc.setGlobalAlpha(1);
    }

    private void drawSparks() {
        double width = sparks.getWidth();
        double height = sparks.getHeight();
        GraphicsContext gc = sparks.getGraphicsContext2D();
        gc.clearRect(0, 0, width, height);
        if (!loading) {
            return;
        }
        double cx = width / 2;
        double cy = height / 2 - 8;
        for (int ring = 0; ring < 2; ring++) {
            double phase = (clock * 0.9 + ring * 0.5) % 1;
            double radius = 10 + phase * 40;
            gc.setStroke(BranchStyle.FOLDER);
            gc.setGlobalAlpha((1 - phase) * 0.55);
            gc.strokeOval(cx - radius, cy - radius, radius * 2, radius * 2);
        }
        for (int i = 0; i < 18; i++) {
            double angle = clock * 2.6 + i * Math.PI * 2 / 18;
            double radius = 22 + 5 * Math.sin(clock * 4 + i);
            gc.setFill(i % 3 == 0 ? BranchStyle.CODE : i % 3 == 1 ? BranchStyle.FOLDER : CHANGED);
            gc.setGlobalAlpha(0.3 + 0.7 * i / 18.0);
            gc.fillOval(cx + Math.cos(angle) * radius - 1.6, cy + Math.sin(angle) * radius - 1.6, 3.2, 3.2);
        }
        // Lines of text flowing in toward the centre.
        for (int j = 0; j < 7; j++) {
            double t = (clock * 0.7 + j / 7.0) % 1;
            double y = cy - 30 + j * 10;
            gc.setGlobalAlpha((1 - t) * 0.5);
            gc.setFill(LINE);
            gc.fillRect(width - 20 - t * (width / 2 - 40), y, 14 + 10 * hash(j), 1.5);
        }
        gc.setGlobalAlpha(0.6 + 0.4 * Math.sin(clock * 4));
        gc.setFill(Color.web("#6e6e7d"));
        gc.setFont(SMALL_FONT);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText("READING", cx, cy + 58);
        gc.setGlobalAlpha(1);
    }

    private static void glow(GraphicsContext gc, double x, double y, Color color) {
        gc.setFill(color);
        gc.setGlobalAlpha(0.25);
        gc.fillOval(x - 9, y - 9, 18, 18);
        gc.setGlobalAlpha(0.95);
        gc.fillOval(x - 2, y - 2, 4, 4);
        gc.setGlobalAlpha(0.5);
        gc.fillRect(8, y - 0.5, gc.getCanvas().getWidth() - 16, 1);
    }

    private void scrollFromMinimap(MouseEvent event) {
        int lines = lineStarts.length;
        double pitch = Math.min(3, (minimap.getHeight() - 24) / Math.max(1, lines));
        double line = (event.getY() - 12) / pitch;
        area.setScrollTop(Math.max(0, PADDING_TOP + line * lineHeight - area.getHeight() / 2));
    }

    // Building blocks

    private HBox buildHeader() {
        Label wordmark = com.kubo.ui.RootButton.wordmark();
        HBox brand = new HBox(wordmark);
        brand.setAlignment(Pos.BOTTOM_LEFT);

        Label kicker = label("EDIT.SCOPE", "series");
        Label title = label(name, "files-title");
        Label pathLabel = label(path, "mono-muted");
        VBox names = new VBox(1, title, pathLabel);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        findField.getStyleClass().add("find-field");
        findField.setPromptText("find  (ctrl+f)");
        findField.setOnAction(event -> find(false));
        findField.textProperty().addListener((observable, previous, text) -> findField.getStyleClass().remove("find-missing"));

        reloadButton.getStyleClass().add("ghost-button");
        reloadButton.setOnAction(event -> reload());
        saveButton.getStyleClass().addAll("primary-button", "compact-button");
        saveButton.setOnAction(event -> save(false));

        HBox header = new HBox(20, brand, kicker, names, spacer, findField, reloadButton, saveButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(20, 40, 8, 36));
        return header;
    }

    private HBox buildInfoRow() {
        readOnlyBadge.getStyleClass().add("badge-alert");
        readOnlyBadge.setVisible(false);
        readOnlyBadge.setManaged(false);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, sizeBadge, encodingBadge, endingBadge, readOnlyBadge, spacer, statusLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0, 40, 12, 36));
        return row;
    }

    private HBox buildConflictBar() {
        Label text = label("THE FILE CHANGED ON THE SERVER SINCE YOU OPENED IT. SAVING NOW WOULD ERASE THOSE CHANGES.",
                "card-problem");
        text.setWrapText(true);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button overwrite = danger("OVERWRITE WITH MINE", () -> save(true));
        Button reload = new Button("RELOAD THEIRS");
        reload.getStyleClass().add("ghost-button");
        reload.setOnAction(event -> reload());
        HBox bar = new HBox(18, text, reload, overwrite);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("conflict-bar");
        bar.setVisible(false);
        bar.setManaged(false);
        return bar;
    }

    private static Pane canvasPane(Canvas canvas, double width, Runnable redraw) {
        Pane pane = new Pane(canvas) {
            @Override
            protected void layoutChildren() {
                canvas.setWidth(getWidth());
                canvas.setHeight(getHeight());
                redraw.run();
            }
        };
        pane.setPrefWidth(width);
        pane.setMinWidth(width);
        return pane;
    }

    private static double measureLineHeight() {
        Text one = new Text("Ag");
        one.setFont(TEXT_FONT);
        Text two = new Text("Ag\nAg");
        two.setFont(TEXT_FONT);
        return two.getLayoutBounds().getHeight() - one.getLayoutBounds().getHeight();
    }

    private static double hash(int value) {
        int mixed = value * 0x9E3779B1;
        mixed ^= mixed >>> 15;
        return (mixed & 0xffff) / 65535.0;
    }

    private static Button primary(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().addAll("primary-button", "compact-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Button danger(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("danger-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Label badge(String text) {
        return label(text, "badge");
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    /**
     * For previews: the nodes whose layout must settle before a snapshot.
     */
    Node area() {
        return area;
    }
}
