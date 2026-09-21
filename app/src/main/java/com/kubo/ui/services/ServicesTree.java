package com.kubo.ui.services;

import com.kubo.model.services.Dependencies;
import com.kubo.model.services.ServiceUnit;
import com.kubo.model.services.ServicesReport;
import com.kubo.model.services.Standing;
import com.kubo.ui.BranchCamera;
import com.kubo.ui.Fibers;
import com.kubo.ui.LiveTimer;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The services of a server as a tree that keeps going: the five standings, then the services of the one that
 * is open, then what the service you clicked is tied to, then what that one is tied to, and so on.
 *
 * <p>Every click adds a column to the right instead of replacing what is on the screen. Nothing is taken
 * away, nothing jumps, and the way you came is still there behind you — which is the one thing a picture of a
 * dependency graph has to do if anybody is to find their way in it.
 *
 * <p>A service already shown further left may well appear again further right. That is not a fault: it is
 * what lets the tree keep going without ever folding back on itself.
 *
 * <p>It moves the way the file tree moves, because it is the same camera and the same handling: the wheel
 * travels, control and the wheel zoom, a drag is a drag and a click that has not moved is a click.
 */
public final class ServicesTree extends Pane {

    // The palette Kubo uses everywhere else.
    static final Color BACKGROUND = Color.web("#050508");
    static final Color PANEL = Color.web("#0b0b12");
    static final Color EDGE = Color.web("#2c2c3a");
    static final Color TEXT = Color.web("#e6e6ec");
    static final Color DIM = Color.web("#8a8fb0");
    static final Color MUTED = Color.web("#5a5a6b");
    static final Color ALERT = Color.web("#ff2d55");
    static final Color AMBER = Color.web("#ffb347");
    static final Color VIOLET = Color.web("#9d4dff");
    static final Color CYAN = Color.web("#35e8ff");
    static final Color TEAL = Color.web("#35e0a1");

    private static final double LEFT_X = 40;
    private static final double TOP_Y = 46;
    private static final double CARD_WIDTH = 250;
    private static final double CARD_HEIGHT = 44;
    private static final double CARD_STEP = 54;
    private static final double ROW_WIDTH = 280;
    private static final double ROW_HEIGHT = 24;
    private static final double ROW_STEP = 28;
    private static final double GROUP_STEP = 30;
    private static final double COLUMN_GAP = 96;

    /** The order the standings are offered in: what is wrong first, then what is right, then what is asleep. */
    private static final Standing[] ORDER = {
            Standing.FAILED, Standing.WILL_NOT_RETURN, Standing.NOT_RUNNING, Standing.RUNNING, Standing.IDLE};

    /** A right click on a service, and where on the screen it happened. */
    record MenuRequest(ServiceUnit unit, double screenX, double screenY) {
    }

    /** One standing, as a card in the first column. */
    private record Card(Standing standing, int count, double y) {

        boolean holds(double worldX, double worldY) {
            return worldX >= LEFT_X && worldX <= LEFT_X + CARD_WIDTH && worldY >= y && worldY <= y + CARD_HEIGHT;
        }
    }

    /** One service in a column, with the heading it sits under when there is one. */
    private record Row(ServiceUnit unit, Standing standing, String heading, int column, double x, double y) {

        boolean holds(double worldX, double worldY) {
            return worldX >= x && worldX <= x + ROW_WIDTH && worldY >= y && worldY <= y + ROW_HEIGHT;
        }
    }

    /** One column of services: the ones of a standing, or the ones a service is tied to. */
    private static final class Column {

        private final String title;
        private final ServiceUnit from;
        private final List<Row> rows = new ArrayList<>();
        private double x;
        /** Where this column starts: level with the row that grew it, so the eye keeps the thread. */
        private double top = TOP_Y;
        private double bottom;

        Column(String title, ServiceUnit from) {
            this.title = title;
            this.from = from;
        }
    }

    private final Canvas canvas = new Canvas();
    private final Canvas still = new Canvas();
    private final BranchCamera camera = new BranchCamera();
    private final LiveTimer timer;
    private final List<Card> cards = new ArrayList<>();
    private final List<Column> columns = new ArrayList<>();
    /** What was clicked in each column, which is what the column after it was grown from. */
    private final List<ServiceUnit> trail = new ArrayList<>();
    private final Map<Integer, Font> fonts = new HashMap<>();

    private ServicesReport report = ServicesReport.EMPTY;
    private Dependencies dependencies = Dependencies.EMPTY;
    private Standing open = Standing.RUNNING;
    private ServiceUnit hovered;
    private double reachX;
    private double clock;
    private double lastTime = -1;
    private boolean framed;
    private WritableImage settled;
    private boolean stale = true;
    private Consumer<ServiceUnit> onSelect = unit -> { };
    private Consumer<MenuRequest> onMenu = request -> { };

    public ServicesTree() {
        canvas.setManaged(false);
        still.setManaged(false);
        getChildren().add(canvas);
        setMinSize(0, 0);

        // The same handling as the file tree, to the letter. Writing my own each time is what made this
        // screen drift away from the rest of Kubo.
        setOnMouseMoved(this::handleMove);
        setOnMouseExited(event -> {
            hovered = null;
            stale = true;
        });
        setOnMousePressed(event -> camera.press(event.getX(), event.getY(), System.nanoTime()));
        setOnMouseDragged(event -> {
            if (event.getButton() != MouseButton.PRIMARY && event.getButton() != MouseButton.MIDDLE) {
                return;
            }
            camera.drag(event.getX(), event.getY(), System.nanoTime());
            setCursor(Cursor.CLOSED_HAND);
        });
        setOnMouseReleased(event -> {
            camera.release(System.nanoTime());
            setCursor(Cursor.DEFAULT);
            handleMove(event);
        });
        setOnMouseClicked(this::handleClick);
        setOnScroll(this::handleScroll);

        timer = new LiveTimer(this, () -> camera.moving() ? LiveTimer.EVERY_PULSE : LiveTimer.AMBIENT,
                time -> renderFrame(time, true));
        timer.start();
    }

    public void setReport(ServicesReport value) {
        report = value == null ? ServicesReport.EMPTY : value;
        place();
        if (!framed) {
            fit();
            framed = true;
        }
    }

    /** Which service needs which. It arrives after the tree is up, so it is set on its own. */
    public void setDependencies(Dependencies value) {
        dependencies = value == null ? Dependencies.EMPTY : value;
        place();
    }

    /** Which standing is open, so a preview can ask for one other than the usual. */
    void setOpen(Standing standing) {
        open = standing == null ? Standing.RUNNING : standing;
        trail.clear();
        place();
    }

    /** Called with the service the user clicked, whose detail belongs beside the tree. */
    public void setOnSelect(Consumer<ServiceUnit> handler) {
        onSelect = handler == null ? unit -> { } : handler;
    }

    /** Called when a service is right clicked, so the screen above can offer what can be done with it. */
    public void setOnMenu(Consumer<MenuRequest> handler) {
        onMenu = handler == null ? request -> { } : handler;
    }

    public void stop() {
        timer.stop();
    }

    public void zoomIn() {
        camera.zoomAround(1.25, getWidth() / 2, getHeight() / 2, true);
    }

    public void zoomOut() {
        camera.zoomAround(1 / 1.25, getWidth() / 2, getHeight() / 2, true);
    }

    public void fit() {
        fit(false);
    }

    private void fit(boolean snap) {
        if (getWidth() < 1) {
            return;
        }
        // The width is what is framed, never the height: a column of a hundred services made to fit the
        // window is a column nobody can read. A long list is scrolled, the way a long folder is.
        double zoom = Fibers.clamp(getWidth() / (reachX + 60), 0.4, 1.05);
        camera.stopInertia();
        camera.targetZoom = zoom;
        camera.targetCamX = -20;
        camera.targetCamY = -20;
        if (snap) {
            camera.zoom = zoom;
            camera.camX = camera.targetCamX;
            camera.camY = camera.targetCamY;
        }
        stale = true;
    }

    /** Renders one frame at a given time, framed and still, for the previews. */
    public void freeze(double time) {
        framed = true;
        fit(true);
        renderFrame(time, true);
    }

    /** Grows the tree onto a service, as a click would, for the previews. */
    void freezeOn(String name, double time) {
        for (Column column : List.copyOf(columns)) {
            for (Row row : List.copyOf(column.rows)) {
                if (row.unit().name().equals(name)) {
                    grow(row);
                }
            }
        }
        framed = true;
        fit(true);
        renderFrame(time, true);
    }

    @Override
    protected void layoutChildren() {
        if (canvas.getWidth() != getWidth() || canvas.getHeight() != getHeight()) {
            canvas.setWidth(getWidth());
            canvas.setHeight(getHeight());
            still.setWidth(getWidth());
            still.setHeight(getHeight());
            settled = null;
            stale = true;
        }
        if (!framed) {
            fit(true);
            framed = true;
        }
        renderFrame(clock, true);
    }

    // Handling

    private void handleMove(MouseEvent event) {
        Row row = rowAt(event.getX(), event.getY());
        ServiceUnit was = hovered;
        hovered = row == null ? null : row.unit();
        if (was != hovered) {
            stale = true;
        }
    }

    private void handleClick(MouseEvent event) {
        if (!event.isStillSincePress()) {
            return;
        }
        if (event.getButton() == MouseButton.SECONDARY) {
            Row asked = rowAt(event.getX(), event.getY());
            if (asked != null) {
                onMenu.accept(new MenuRequest(asked.unit(), event.getScreenX(), event.getScreenY()));
            }
            return;
        }
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        Card card = cardAt(event.getX(), event.getY());
        if (card != null) {
            open = card.standing();
            trail.clear();
            place();
            return;
        }
        Row row = rowAt(event.getX(), event.getY());
        if (row != null) {
            grow(row);
        }
    }

    /**
     * Carries the tree one step further: everything after this column is dropped, and a new one is grown from
     * what was clicked.
     */
    private void grow(Row row) {
        while (trail.size() > row.column()) {
            trail.remove(trail.size() - 1);
        }
        trail.add(row.unit());
        place();
        follow();
        onSelect.accept(row.unit());
    }

    /** Eases the view along so the column that just appeared is in it. */
    private void follow() {
        if (columns.isEmpty() || getWidth() < 1) {
            return;
        }
        Column grown = columns.get(columns.size() - 1);
        double visible = getWidth() / camera.zoom;
        double right = grown.x + ROW_WIDTH + 60;
        double left = grown.x - 60;
        // Both edges, not only the right one: a column narrower than the window was left off the screen when
        // the view happened to be further right than it was.
        if (right > camera.camX + visible) {
            camera.targetCamX = right - visible;
        } else if (left < camera.camX) {
            camera.targetCamX = left;
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

    private Card cardAt(double screenX, double screenY) {
        double worldX = camera.worldX(screenX);
        double worldY = camera.worldY(screenY);
        for (Card card : cards) {
            if (card.holds(worldX, worldY)) {
                return card;
            }
        }
        return null;
    }

    private Row rowAt(double screenX, double screenY) {
        double worldX = camera.worldX(screenX);
        double worldY = camera.worldY(screenY);
        for (Column column : columns) {
            for (Row row : column.rows) {
                if (row.holds(worldX, worldY)) {
                    return row;
                }
            }
        }
        return null;
    }

    // Placing

    private void place() {
        cards.clear();
        columns.clear();
        double y = TOP_Y;
        for (Standing standing : ORDER) {
            cards.add(new Card(standing, report.count(standing), y));
            y += CARD_STEP;
        }

        Column first = new Column(open.label(), null);
        fill(first, report.of(open), null, 0);
        columns.add(first);

        // One column per step already taken, grown from what was clicked in the one before it.
        for (int i = 0; i < trail.size(); i++) {
            ServiceUnit from = trail.get(i);
            Column column = new Column(from.shortName(), from);
            int index = i + 1;
            fill(column, units(dependencies.needsOf(from.name())), "HOLDS IT UP", index);
            fill(column, units(dependencies.neededByOf(from.name())), "IT HOLDS UP", index);
            columns.add(column);
        }

        double x = LEFT_X + CARD_WIDTH + COLUMN_GAP;
        double startAt = TOP_Y;
        for (int index = 0; index < columns.size(); index++) {
            Column column = columns.get(index);
            column.x = x;
            column.top = startAt;
            List<Row> placed = new ArrayList<>();
            double row = startAt;
            String heading = null;
            for (Row entry : column.rows) {
                if (entry.heading() != null && !entry.heading().equals(heading)) {
                    heading = entry.heading();
                    row += GROUP_STEP;
                }
                placed.add(new Row(entry.unit(), entry.standing(), entry.heading(), entry.column(), x, row));
                row += ROW_STEP;
            }
            column.rows.clear();
            column.rows.addAll(placed);
            column.bottom = row;
            x += ROW_WIDTH + COLUMN_GAP;
            // The next column starts level with whichever row grew it. Starting every column at the top sent
            // the reader back to the beginning of the list at each step, and the thread was lost.
            startAt = TOP_Y;
            if (index < trail.size()) {
                for (Row placedRow : placed) {
                    if (placedRow.unit().name().equals(trail.get(index).name())) {
                        startAt = placedRow.y();
                    }
                }
            }
        }
        reachX = x - COLUMN_GAP;
        stale = true;
    }

    private void fill(Column column, List<ServiceUnit> units, String heading, int index) {
        for (ServiceUnit unit : units) {
            column.rows.add(new Row(unit, unit.standing(), heading, index, 0, 0));
        }
    }

    /**
     * The units of those names as this reading knows them.
     *
     * <p>systemd names things this listing may not hold — a unit of another type, or one it has forgotten.
     * Rather than dropping the link, the neighbour is shown as what it is: a name, and nothing known about it.
     */
    private List<ServiceUnit> units(List<String> names) {
        List<ServiceUnit> found = new ArrayList<>();
        names = new ArrayList<>(names);
        // The same order as everywhere else. systemd answers these in the order it happens to hold them,
        // which is stable enough but means nothing to a reader.
        names.sort(String.CASE_INSENSITIVE_ORDER);
        for (String name : names) {
            ServiceUnit known = null;
            for (ServiceUnit unit : report.units()) {
                if (unit.name().equals(name)) {
                    known = unit;
                    break;
                }
            }
            found.add(known == null ? ServiceUnit.of(name, "", "", "", "") : known);
        }
        return found;
    }

    // Drawing

    void renderFrame(double time, boolean draw) {
        double dt = lastTime < 0 ? 0 : Math.min(0.1, time - lastTime);
        lastTime = time;
        clock = time;
        camera.step(dt);
        if (!draw || canvas.getWidth() < 1) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        if (camera.moving()) {
            stale = true;
            drawAll(gc);
        } else {
            if (stale || settled == null) {
                drawAll(still.getGraphicsContext2D());
                settled = still.snapshot(null, settled);
                stale = false;
            }
            gc.setGlobalAlpha(1);
            gc.drawImage(settled, 0, 0);
        }
    }

    private void drawAll(GraphicsContext gc) {
        gc.setGlobalAlpha(1);
        gc.setFill(BACKGROUND);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        for (Card card : cards) {
            drawCard(gc, card);
        }
        for (int i = 0; i < columns.size(); i++) {
            drawColumn(gc, columns.get(i), i);
        }
        gc.setGlobalAlpha(1);
    }

    private double sx(double worldX) {
        return camera.sx(worldX);
    }

    private double sy(double worldY) {
        return camera.sy(worldY);
    }

    /** One standing: its colour, its count, and the branch out to its services when it is the one open. */
    private void drawCard(GraphicsContext gc, Card card) {
        double x = sx(LEFT_X);
        double y = sy(card.y());
        double w = CARD_WIDTH * camera.zoom;
        double h = CARD_HEIGHT * camera.zoom;
        boolean chosen = card.standing() == open;
        Color colour = colour(card.standing());

        gc.setGlobalAlpha(1);
        gc.setFill(chosen ? colour.deriveColor(0, 1, 1, 0.16) : PANEL);
        gc.fillRect(x, y, w, h);
        gc.setStroke(chosen ? colour : EDGE);
        gc.setLineWidth(1);
        gc.strokeRect(Math.floor(x) + 0.5, Math.floor(y) + 0.5, Math.round(w), Math.round(h));
        gc.setFill(colour);
        gc.fillRect(x, y, Math.max(2, 4 * camera.zoom), h);

        if (h > 16) {
            gc.setTextBaseline(VPos.CENTER);
            gc.setFont(mono(Math.min(11.5, h * 0.26), true));
            gc.setFill(card.standing().wrong() && card.count() > 0 ? colour : chosen ? TEXT : DIM);
            gc.fillText(card.standing().label(), x + 12 * camera.zoom, y + h * 0.36);
            gc.setFont(mono(Math.min(9, h * 0.2), false));
            gc.setFill(MUTED);
            gc.fillText(card.count() + (card.count() == 1 ? " SERVICE" : " SERVICES"),
                    x + 12 * camera.zoom, y + h * 0.7);
        }
        if (chosen && !columns.isEmpty() && !columns.get(0).rows.isEmpty()) {
            branch(gc, x + w, y + h / 2, sx(columns.get(0).x),
                    sy(columns.get(0).rows.get(0).y() + ROW_HEIGHT / 2), colour, false);
        }
    }

    private void drawColumn(GraphicsContext gc, Column column, int index) {
        double x = sx(column.x);
        if (x > canvas.getWidth() + 40 || x + ROW_WIDTH * camera.zoom < -40) {
            return;
        }
        gc.setGlobalAlpha(1);
        gc.setFont(mono(9.5, true));
        gc.setTextBaseline(VPos.BOTTOM);
        gc.setFill(MUTED);
        gc.fillText(column.title.toUpperCase(java.util.Locale.ROOT), x, sy(column.top - 14));

        String heading = null;
        ServiceUnit next = index < trail.size() ? trail.get(index) : null;
        for (Row row : column.rows) {
            if (row.heading() != null && !row.heading().equals(heading)) {
                heading = row.heading();
                gc.setFont(mono(8.5, false));
                gc.setFill(heading.equals("HOLDS IT UP") ? CYAN : AMBER);
                gc.setTextBaseline(VPos.BOTTOM);
                gc.fillText(heading, x, sy(row.y() - 8));
            }
            drawRow(gc, row, next != null && next.name().equals(row.unit().name()), index);
        }
        if (column.rows.isEmpty()) {
            double y = sy(column.top);
            double w = ROW_WIDTH * camera.zoom;
            double h = ROW_HEIGHT * camera.zoom;
            gc.setGlobalAlpha(0.9);
            gc.setLineDashes(4, 4);
            gc.setStroke(EDGE);
            gc.setLineWidth(1);
            gc.strokeRect(Math.floor(x) + 0.5, Math.floor(y) + 0.5, Math.round(w), Math.round(h));
            gc.setLineDashes((double[]) null);
            gc.setFont(mono(9, false));
            gc.setFill(MUTED);
            gc.setTextBaseline(VPos.CENTER);
            gc.fillText(column.from == null ? "  NOTHING IN THIS GROUP" : "  NOTHING DEPENDS ON IT, AND IT "
                    + "DEPENDS ON NOTHING", x, y + h / 2);
        }
    }

    private void drawRow(GraphicsContext gc, Row row, boolean followed, int index) {
        double x = sx(row.x());
        double y = sy(row.y());
        double w = ROW_WIDTH * camera.zoom;
        double h = ROW_HEIGHT * camera.zoom;
        if (y < -40 || y > canvas.getHeight() + 40) {
            return;
        }
        boolean over = row.unit().equals(hovered);
        boolean known = !row.unit().load().isEmpty();
        Color colour = known ? colour(row.standing()) : MUTED;

        gc.setGlobalAlpha(1);
        gc.setFill(followed ? colour.deriveColor(0, 1, 1, 0.26) : over ? colour.deriveColor(0, 1, 1, 0.16) : PANEL);
        gc.fillRect(x, y, w, h);
        gc.setStroke(followed ? TEXT : over ? colour : EDGE);
        gc.setLineWidth(1);
        gc.strokeRect(Math.floor(x) + 0.5, Math.floor(y) + 0.5, Math.round(w), Math.round(h));
        gc.setFill(colour);
        gc.fillRect(x, y, Math.max(2, 3 * camera.zoom), h);

        if (h >= 11) {
            gc.setTextBaseline(VPos.CENTER);
            gc.setFont(name(Math.min(11.5, h * 0.55), followed || over));
            gc.setFill(followed || over ? TEXT : known ? DIM : MUTED);
            gc.fillText(clip(label(row.unit()), w - 18 * camera.zoom), x + 10 * camera.zoom, y + h / 2);
        }
        // The branch on to the column this row grew.
        if (followed && index + 1 < columns.size()) {
            Column grown = columns.get(index + 1);
            double toY = grown.rows.isEmpty() ? sy(grown.top + ROW_HEIGHT / 2)
                    : sy(grown.rows.get(0).y() + ROW_HEIGHT / 2);
            branch(gc, x + w, y + h / 2, sx(grown.x), toY, colour, true);
        }
    }

    /** One curve from a row to the column it grew, in the manner of the file tree. */
    private void branch(GraphicsContext gc, double x1, double y1, double x2, double y2, Color colour,
                        boolean strong) {
        double reach = Math.max(20, (x2 - x1) * 0.5);
        gc.setStroke(colour);
        gc.setGlobalAlpha(strong ? 0.8 : 0.5);
        gc.setLineWidth(strong ? 1.4 : 1);
        gc.beginPath();
        gc.moveTo(x1, y1);
        gc.bezierCurveTo(x1 + reach, y1, x2 - reach, y2, x2, y2);
        gc.stroke();
        gc.setGlobalAlpha(1);
    }

    // Small helpers

    /** The name, and the one number a service carries: what it cost the last boot, when that was a real cost. */
    static String label(ServiceUnit unit) {
        Duration startup = unit.startup();
        return startup == null || startup.toMillis() < 1000
                ? unit.shortName() : unit.shortName() + "   " + shortTime(startup);
    }

    private static String clip(String text, double room) {
        int fits = (int) Math.max(3, room / 6.4);
        return text.length() <= fits ? text : text.substring(0, fits - 1) + "…";
    }

    private Font name(double size, boolean strong) {
        int key = (int) Math.round(size * 2) * 2 + (strong ? 1 : 0);
        return fonts.computeIfAbsent(key, k -> Font.font("Segoe UI",
                strong ? FontWeight.SEMI_BOLD : FontWeight.NORMAL, Math.round(size * 2) / 2.0));
    }

    private Font mono(double size, boolean strong) {
        int key = -((int) Math.round(size * 2) * 2 + (strong ? 1 : 0));
        return fonts.computeIfAbsent(key, k -> Font.font("Consolas",
                strong ? FontWeight.BOLD : FontWeight.NORMAL, Math.round(size * 2) / 2.0));
    }

    static Color colour(Standing standing) {
        return switch (standing) {
            case FAILED -> ALERT;
            case WILL_NOT_RETURN -> AMBER;
            case NOT_RUNNING -> VIOLET;
            case RUNNING -> TEAL;
            case IDLE -> MUTED;
        };
    }

    static String shortTime(Duration duration) {
        long millis = duration.toMillis();
        if (millis >= 60_000) {
            return millis / 60_000 + "m " + Math.round(millis % 60_000 / 1000.0) + "s";
        }
        if (millis >= 1000) {
            return String.format("%.1fs", millis / 1000.0);
        }
        return millis + "ms";
    }
}
