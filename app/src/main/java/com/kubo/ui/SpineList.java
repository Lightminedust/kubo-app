package com.kubo.ui;

import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * A list hanging off a spine: one line down the left, a branch to every row, a ring for a row that opens onto
 * more and a dot for one that does not.
 *
 * <p>The look of LOCAL.SCOPE, which is now drawn by this and nothing else: anything with a list of things to
 * walk through has it, folders on this computer, paths on the server, whatever comes next. It knows nothing about
 * files. It is given rows, draws them, and says which one was clicked, opened, dragged or asked for by the
 * keyboard.
 *
 * <p>Drawn on a canvas because a list of five thousand rows as nodes is five thousand nodes, and this draws only
 * the ones on screen. It draws only while something moves: rows growing in, a scroll settling, the light on the
 * spine while a folder is read. Left open and still, it costs nothing.
 *
 * <p>The keyboard: up and down move, Home and End jump, a letter goes to the next row starting with it, Enter
 * opens or takes a row, Backspace goes back up, and Ctrl+A picks everything when several rows can be picked.
 */
public final class SpineList extends Pane {

    /**
     * One row.
     *
     * @param detail what goes on the right, muted: a size, a state, a word of advice. Empty for nothing.
     * @param opens  true for a row that leads somewhere, drawn as a ring with a chevron, false for a dot
     * @param color  the row's own colour, or null for the list's accent
     * @param radius the dot's radius, so a heavier thing can be drawn bigger; 0 for the usual size
     * @param weight where this row stands among its neighbours between 0 and 1, drawn as a short trail of light
     *               beside its detail; negative for no trail
     */
    public record Row(String name, String detail, boolean opens, Color color, double radius, double weight) {

        public Row {
            name = name == null ? "" : name;
            detail = detail == null ? "" : detail;
        }

        public Row(String name, String detail, boolean opens, Color color) {
            this(name, detail, opens, color, 0, -1);
        }
    }

    private static final double ROW = 28;
    private static final double SPINE = 18;
    private static final double REVEAL = 0.35;
    private static final double FLASH = 1.8;
    private static final Color BACKGROUND = Color.web("#06070b");
    private static final Color TEXT = Color.web("#e6e6ec");
    private static final Color SOFT = Color.web("#b8b8c4");
    private static final Color MUTED = Color.web("#6e6e7d");
    private static final Color FAINT = Color.web("#24242d");
    private static final Color PROBLEM = Color.web("#ff5a6e");
    private static final Font NAME_FONT = Font.font("Segoe UI", 12);
    private static final Font BOLD_FONT = Font.font("Segoe UI", FontWeight.SEMI_BOLD, 12);
    private static final Font SMALL_FONT = Font.font("Consolas", 10);

    private final Canvas canvas = new Canvas();
    private final Color accent;
    private final LiveTimer timer;
    private boolean ticking;

    private List<Row> rows = List.of();
    private final Set<Integer> selection = new LinkedHashSet<>();
    private boolean several;
    private int anchor = -1;
    private int hovered = -1;
    private double scroll;
    private double targetScroll;
    private double revealStart = -1;
    private boolean loading;
    private String loadingText = "READING…";
    private String message = "";
    private boolean problem;

    private int dropRow = -2;
    private String dropText = "";
    private Color dropColor = Color.WHITE;
    private String flashName;
    private Color flashColor = Color.WHITE;
    private double flashStart = -1;

    private IntConsumer onPick = index -> { };
    private IntConsumer onOpen = index -> { };
    private IntConsumer onEnter = index -> { };
    private Runnable onBack = () -> { };

    /**
     * @param accent the colour of the spine and of every row that does not name its own
     */
    public SpineList(Color accent) {
        this.accent = accent;
        getChildren().add(canvas);
        getStyleClass().add("spine-list");
        setMinHeight(ROW * 3);
        canvas.setFocusTraversable(true);
        canvas.setOnMouseMoved(this::moved);
        canvas.setOnMouseExited(event -> {
            hovered = -1;
            paint();
        });
        canvas.setOnMouseClicked(this::clicked);
        canvas.setOnScroll(this::scrolled);
        canvas.setOnKeyPressed(this::pressed);
        canvas.setOnKeyTyped(this::typed);
        timer = new LiveTimer(this, LiveTimer.AMBIENT, seconds -> frame());
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        canvas.relocate(0, 0);
        // Drawn now as well as on the next frame, so a list that has just been given a size is never shown blank.
        paint();
    }

    // What it shows

    /** Replaces the rows, and lets them grow in off the spine. A row being flashed shows at once instead. */
    public void setRows(List<Row> rows) {
        this.rows = rows == null ? List.of() : List.copyOf(rows);
        selection.clear();
        anchor = -1;
        hovered = -1;
        scroll = 0;
        targetScroll = 0;
        loading = false;
        boolean flashing = flashName != null && this.rows.stream().anyMatch(row -> row.name().equals(flashName));
        revealStart = flashName != null ? -1 : now();
        flashStart = flashing ? now() : -1;
        if (!flashing) {
            flashName = null;
        }
        wake();
    }

    /**
     * Draws the rows where they end up, without growing them in. For a still picture, and for a list that should
     * not animate because nothing about it is new.
     */
    public void settle() {
        revealStart = -1;
        flashStart = -1;
        scroll = targetScroll;
        paint();
    }

    /** While true, light runs down the spine and the rows are not drawn. */
    public void setLoading(boolean loading) {
        this.loading = loading;
        wake();
    }

    /** What is written beside the spine while it is loading. */
    public void setLoadingText(String text) {
        loadingText = text == null ? "" : text;
    }

    /**
     * What to say when there are no rows: that the folder is empty, or why it could not be read.
     *
     * @param problem true to say it in red, because something went wrong rather than there being nothing
     */
    public void setMessage(String message, boolean problem) {
        this.message = message == null ? "" : message;
        this.problem = problem;
        paint();
    }

    /** Lets several rows be picked at once, with Ctrl, Shift and Ctrl+A. One at a time otherwise. */
    public void setSeveral(boolean several) {
        this.several = several;
    }

    /**
     * Shows something being dragged over the list.
     *
     * @param row  the row it would land in, -1 for the list as a whole, -2 for nothing
     * @param text what dropping it would do, written along the bottom
     */
    public void setDrop(int row, String text, Color color) {
        dropRow = row;
        dropText = text == null ? "" : text;
        dropColor = color == null ? accent : color;
        paint();
    }

    /**
     * Lights up the row called {@code name} the next time the rows are replaced, for a thing that has just
     * arrived: it is found in the list without being looked for.
     */
    public void flash(String name, Color color) {
        flashName = name;
        flashColor = color == null ? accent : color;
    }

    // What it says

    /** Called with the row clicked, or -1 when a click in the empty space cleared the pick. */
    public void setOnPick(IntConsumer onPick) {
        this.onPick = onPick == null ? index -> { } : onPick;
    }

    /** Called with a row that opens, on a double click, a click on its chevron, or Enter. */
    public void setOnOpen(IntConsumer onOpen) {
        this.onOpen = onOpen == null ? index -> { } : onOpen;
    }

    /** Called with a row that does not open when Enter is pressed on it: the keyboard's way to take it. */
    public void setOnEnter(IntConsumer onEnter) {
        this.onEnter = onEnter == null ? index -> { } : onEnter;
    }

    /** Called on Backspace: go back up. */
    public void setOnBack(Runnable onBack) {
        this.onBack = onBack == null ? () -> { } : onBack;
    }

    /** The row picked, or the first of several, or -1. */
    public int selected() {
        return selection.isEmpty() ? -1 : selection.iterator().next();
    }

    /** Every row picked, in the order they were picked. */
    public List<Integer> selection() {
        return List.copyOf(selection);
    }

    public void select(int index) {
        selection.clear();
        if (index >= 0 && index < rows.size()) {
            selection.add(index);
            anchor = index;
        }
        paint();
    }

    public void setSelection(Collection<Integer> indices) {
        selection.clear();
        for (int index : indices) {
            if (index >= 0 && index < rows.size()) {
                selection.add(index);
            }
        }
        paint();
    }

    /** The row under this height, measured from the top of the list, or -1. */
    public int rowAt(double y) {
        int index = (int) Math.floor((y + scroll - 6) / ROW);
        return index >= 0 && index < rows.size() ? index : -1;
    }

    /** Takes the keyboard, so the arrows work without a click first. */
    public void focus() {
        canvas.requestFocus();
    }

    /** Stops drawing for good, for a card that is closing. */
    public void stop() {
        ticking = false;
        timer.stop();
    }

    // Input

    private void moved(MouseEvent event) {
        int before = hovered;
        hovered = loading ? -1 : rowAt(event.getY());
        canvas.setCursor(hovered >= 0 ? Cursor.HAND : Cursor.DEFAULT);
        if (before != hovered) {
            paint();
        }
    }

    private void clicked(MouseEvent event) {
        canvas.requestFocus();
        if (loading || event.getButton() != MouseButton.PRIMARY || !event.isStillSincePress()) {
            return;
        }
        int index = rowAt(event.getY());
        if (index < 0) {
            selection.clear();
            paint();
            onPick.accept(-1);
            return;
        }
        boolean onChevron = event.getX() > canvas.getWidth() - 34;
        if (rows.get(index).opens() && (event.getClickCount() >= 2 || onChevron)) {
            onOpen.accept(index);
            return;
        }
        if (several && event.isShiftDown() && anchor >= 0) {
            selection.clear();
            for (int i = Math.min(anchor, index); i <= Math.max(anchor, index); i++) {
                selection.add(i);
            }
        } else if (several && event.isShortcutDown()) {
            if (!selection.remove(index)) {
                selection.add(index);
            }
            anchor = index;
        } else {
            selection.clear();
            selection.add(index);
            anchor = index;
        }
        paint();
        onPick.accept(index);
    }

    private void scrolled(ScrollEvent event) {
        targetScroll = clamp(targetScroll - event.getDeltaY() * 1.4, 0, mostScroll());
        wake();
        event.consume();
    }

    private void pressed(KeyEvent event) {
        if (loading) {
            return;
        }
        KeyCode code = event.getCode();
        int current = anchor >= 0 ? anchor : selected();
        switch (code) {
            case DOWN -> move(current < 0 ? 0 : current + 1, event.isShiftDown());
            case UP -> move(current < 0 ? rows.size() - 1 : current - 1, event.isShiftDown());
            case HOME -> move(0, event.isShiftDown());
            case END -> move(rows.size() - 1, event.isShiftDown());
            case PAGE_DOWN -> move(current + (int) (canvas.getHeight() / ROW) - 1, event.isShiftDown());
            case PAGE_UP -> move(current - (int) (canvas.getHeight() / ROW) + 1, event.isShiftDown());
            case ENTER -> {
                if (current >= 0 && current < rows.size()) {
                    if (rows.get(current).opens()) {
                        onOpen.accept(current);
                    } else {
                        onEnter.accept(current);
                    }
                }
            }
            case BACK_SPACE -> onBack.run();
            case A -> {
                if (several && event.isShortcutDown()) {
                    selection.clear();
                    for (int i = 0; i < rows.size(); i++) {
                        selection.add(i);
                    }
                    paint();
                    onPick.accept(selected());
                } else {
                    return;
                }
            }
            default -> {
                return;
            }
        }
        event.consume();
    }

    /** A letter: the next row whose name starts with it, after the one picked, wrapping round. */
    private void typed(KeyEvent event) {
        String character = event.getCharacter();
        if (loading || character.isBlank() || event.isShortcutDown() || rows.isEmpty()
                || !Character.isLetterOrDigit(character.charAt(0))) {
            return;
        }
        String wanted = character.toLowerCase(Locale.ROOT);
        int from = selected();
        for (int step = 1; step <= rows.size(); step++) {
            int index = Math.floorMod(from + step, rows.size());
            if (rows.get(index).name().toLowerCase(Locale.ROOT).startsWith(wanted)) {
                move(index, false);
                event.consume();
                return;
            }
        }
    }

    private void move(int index, boolean extend) {
        if (rows.isEmpty()) {
            return;
        }
        int target = Math.max(0, Math.min(rows.size() - 1, index));
        if (several && extend && anchor >= 0) {
            int start = selection.isEmpty() ? anchor : selection.iterator().next();
            selection.clear();
            for (int i = Math.min(start, target); i <= Math.max(start, target); i++) {
                selection.add(i);
            }
            anchor = target;
        } else {
            selection.clear();
            selection.add(target);
            anchor = target;
        }
        reveal(target);
        paint();
        onPick.accept(target);
    }

    /** Scrolls just enough for this row to be on screen. */
    private void reveal(int index) {
        double top = 6 + index * ROW;
        double height = canvas.getHeight();
        if (top < targetScroll) {
            targetScroll = top - 6;
        } else if (top + ROW > targetScroll + height) {
            targetScroll = top + ROW - height + 6;
        }
        targetScroll = clamp(targetScroll, 0, mostScroll());
        wake();
    }

    private double mostScroll() {
        return Math.max(0, rows.size() * ROW + 12 - canvas.getHeight());
    }

    // The clock

    /**
     * The time, in seconds, on a clock of this list's own. Not the frame timer's: that one stops while nothing
     * moves, and a reveal timed against a clock that stood still for a minute would be over before it began.
     */
    private static double now() {
        return System.nanoTime() / 1e9;
    }

    /** Starts drawing frames, if it was not already. */
    private void wake() {
        paint();
        if (!ticking && busy()) {
            ticking = true;
            timer.start();
        }
    }

    /** True while anything on the list is still moving. */
    private boolean busy() {
        double t = now();
        boolean revealing = revealStart >= 0 && t - revealStart < REVEAL + Math.min(rows.size(), 40) * 0.015;
        boolean flashing = flashStart >= 0 && t - flashStart < FLASH;
        boolean scrolling = Math.abs(targetScroll - scroll) > 0.3;
        return loading || revealing || flashing || scrolling;
    }

    private void frame() {
        paint();
        if (!busy()) {
            // Nothing left to move. The last frame drawn is exactly the still picture, so stopping costs nothing
            // to look at and everything to run.
            if (Math.abs(targetScroll - scroll) <= 0.3) {
                scroll = targetScroll;
                paint();
            }
            ticking = false;
            timer.stop();
        }
    }

    // Drawing

    private void paint() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width < 1 || height < 1) {
            return;
        }
        double t = now();
        scroll += (targetScroll - scroll) * 0.25;
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setGlobalAlpha(1);
        gc.setFill(BACKGROUND);
        gc.fillRect(0, 0, width, height);
        gc.setTextBaseline(VPos.CENTER);

        gc.setStroke(accent);
        gc.setGlobalAlpha(0.35);
        gc.setLineWidth(1);
        gc.strokeLine(SPINE + 0.5, 0, SPINE + 0.5, height);

        if (loading) {
            // Light running down the spine: the list is being read, and it is this list that is.
            for (int i = 0; i < 14; i++) {
                double phase = (t * 0.8 + i / 14.0) % 1;
                gc.setFill(accent);
                gc.setGlobalAlpha(Math.sin(Math.PI * phase) * 0.9);
                gc.fillOval(SPINE - 2, phase * height - 2, 4, 4);
            }
            text(gc, loadingText, SPINE + 20, 24, MUTED, SMALL_FONT, TextAlignment.LEFT, 1);
            return;
        }
        if (rows.isEmpty()) {
            text(gc, message.isEmpty() ? "NOTHING HERE" : message, SPINE + 20, 24, problem ? PROBLEM : MUTED,
                    SMALL_FONT, TextAlignment.LEFT, 1);
            drawDrop(gc, width, height);
            return;
        }

        int first = Math.max(0, (int) Math.floor((scroll - 6) / ROW));
        int last = Math.min(rows.size() - 1, (int) Math.ceil((scroll + height) / ROW));
        for (int i = first; i <= last; i++) {
            drawRow(gc, rows.get(i), i, width, t);
        }

        gc.setGlobalAlpha(1);
        if (scroll > 1) {
            gc.setFill(new javafx.scene.paint.LinearGradient(0, 0, 0, 24, false,
                    javafx.scene.paint.CycleMethod.NO_CYCLE, new javafx.scene.paint.Stop(0, BACKGROUND),
                    new javafx.scene.paint.Stop(1, Color.TRANSPARENT)));
            gc.fillRect(0, 0, width, 24);
        }
        gc.setStroke(FAINT);
        gc.strokeLine(0, height - 0.5, width, height - 0.5);
        drawDrop(gc, width, height);
    }

    private void drawRow(GraphicsContext gc, Row row, int i, double width, double t) {
        double reveal = revealStart < 0 ? 1 : clamp((t - revealStart - i * 0.015) / REVEAL, 0, 1);
        if (reveal <= 0) {
            return;
        }
        double y = 6 + i * ROW - scroll;
        double center = y + ROW / 2;
        double offset = (1 - reveal) * 24;
        boolean picked = selection.contains(i);
        Color color = row.color() == null ? accent : row.color();

        if (row.name().equals(flashName) && flashStart >= 0 && t - flashStart < FLASH) {
            // Just arrived here.
            double age = (t - flashStart) / FLASH;
            gc.setGlobalAlpha((1 - age) * 0.35);
            gc.setFill(flashColor);
            gc.fillRect(SPINE + 6, y + 2, (width - SPINE - 10) * Math.min(1, age * 3), ROW - 4);
        }
        if (i == dropRow) {
            gc.setGlobalAlpha(0.9);
            gc.setStroke(dropColor);
            gc.strokeRect(SPINE + 6.5, y + 2.5, width - SPINE - 11, ROW - 5);
        }
        if (picked || i == hovered) {
            gc.setGlobalAlpha(picked ? 0.12 : 0.05);
            gc.setFill(accent);
            gc.fillRect(SPINE + 6, y + 2, width - SPINE - 10, ROW - 4);
        }
        if (picked) {
            gc.setGlobalAlpha(1);
            gc.setFill(accent);
            gc.fillRect(width - 4, y + 3, 2, ROW - 6);
        }

        // The branch from the spine, then the mark: a ring for a row that leads somewhere, a dot for one that
        // does not.
        gc.setGlobalAlpha(0.35 * reveal);
        gc.setStroke(color);
        gc.strokeLine(SPINE, center, SPINE + 14 + offset, center);
        double radius = row.radius() > 0 ? row.radius() : row.opens() ? 4.5 : 2.4;
        double markX = SPINE + 20 + offset;
        gc.setGlobalAlpha(reveal);
        if (row.opens()) {
            gc.setStroke(color);
            gc.setLineWidth(1.4);
            gc.strokeOval(markX - radius, center - radius, radius * 2, radius * 2);
            gc.setLineWidth(1);
        } else {
            gc.setFill(color);
            gc.fillOval(markX - radius, center - radius, radius * 2, radius * 2);
        }

        double nameX = SPINE + 34 + offset;
        double room = row.weight() >= 0 ? 150
                : (row.detail().isEmpty() ? 0 : Math.min(160, row.detail().length() * 6.2 + 16)) + 34;
        String name = row.name();
        int maxCharacters = (int) Math.max(6, (width - nameX - room) / 6.4);
        if (name.length() > maxCharacters) {
            name = name.substring(0, maxCharacters - 1) + "…";
        }
        text(gc, name, nameX, center, picked ? Color.WHITE : row.opens() ? TEXT : SOFT,
                picked ? BOLD_FONT : NAME_FONT, TextAlignment.LEFT, reveal);

        double right = width - 16;
        if (row.opens()) {
            text(gc, "›", right, center, MUTED, SMALL_FONT, TextAlignment.RIGHT, reveal);
            right -= 18;
        }
        if (!row.detail().isEmpty()) {
            text(gc, row.detail(), right, center, MUTED, SMALL_FONT, TextAlignment.RIGHT, reveal);
        }
        if (row.weight() >= 0) {
            // The row's weight among its neighbours, as a short trail of particles.
            int dots = (int) Math.round(1 + 9 * clamp(row.weight(), 0, 1));
            double dotsRight = width - 80;
            for (int d = 0; d < dots; d++) {
                gc.setFill(color);
                gc.setGlobalAlpha(reveal * (0.25 + 0.75 * d / Math.max(1, dots - 1)));
                gc.fillOval(dotsRight - d * 5 - 1.2, center - 1.2, 2.4, 2.4);
            }
        }
        if (picked) {
            gc.setGlobalAlpha(1);
            gc.setStroke(Color.WHITE);
            gc.setLineWidth(1.2);
            gc.strokeOval(markX - radius - 4, center - radius - 4, (radius + 4) * 2, (radius + 4) * 2);
            gc.setLineWidth(1);
        }
    }

    private void drawDrop(GraphicsContext gc, double width, double height) {
        if (dropRow < -1 || dropRow >= rows.size()) {
            return;
        }
        gc.setGlobalAlpha(1);
        if (dropRow == -1) {
            gc.setStroke(dropColor);
            gc.setLineWidth(1.5);
            gc.strokeRect(1, 1, width - 2, height - 2);
            gc.setLineWidth(1);
        }
        gc.setFill(BACKGROUND);
        gc.fillRect(0, height - 26, width, 26);
        text(gc, dropText, 14, height - 13, dropColor, BOLD_FONT, TextAlignment.LEFT, 1);
    }

    private static void text(GraphicsContext gc, String text, double x, double y, Color color, Font font,
                             TextAlignment align, double alpha) {
        gc.setGlobalAlpha(alpha);
        gc.setFill(color);
        gc.setFont(font);
        gc.setTextAlign(align);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText(text, x, y);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

}
