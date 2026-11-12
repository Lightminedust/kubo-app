package com.kubo.ui.services;

import com.kubo.model.services.BootState;
import com.kubo.model.services.Dependencies;
import com.kubo.model.services.ServiceUnit;
import com.kubo.model.services.ServicesReport;
import com.kubo.model.services.Standing;
import com.kubo.ui.LiveTimer;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Every service of the machine, as a tree that grows to the right.
 *
 * <p>On the left the five standings, each a block with how many services stand there, in its colour. A wire
 * runs from the open one to its services. A click on a service grows a column beside it: what holds it up,
 * and what it holds up. A click in that column grows the next one. Nothing is taken away on the way, so the
 * way back is always on the screen.
 *
 * <p>Words are kept to names. What a service is, what it does at boot and what can be done to it are on the
 * tile beside the board.
 */
final class ServicesBoard extends Pane {

    static final Color GREEN = Color.web("#35e0a1");
    static final Color AMBER = Color.web("#ffb347");
    static final Color RED = Color.web("#ff2d55");
    static final Color VIOLET = Color.web("#b58cff");
    static final Color CYAN = Color.web("#35e8ff");
    static final Color GREY = Color.web("#6b6b80");
    private static final Color TEXT = Color.web("#eaeaf2");
    private static final Color DIM = Color.web("#a9a9bb");
    private static final Color MUTED = Color.web("#6b6b80");
    private static final Color FAINT = Color.web("#24242f");
    private static final Color WELL = Color.web("#0b0b12");
    private static final Color KEY = Color.web("#ff2d55");

    private static final Font BIG = Font.font("Consolas", FontWeight.BOLD, 22);
    private static final Font BOOT = Font.font("Consolas", FontWeight.BOLD, 19);
    private static final Font NAME = Font.font("Consolas", 12.5);
    private static final Font NAME_STRONG = Font.font("Consolas", FontWeight.BOLD, 12.5);
    private static final Font SMALL = Font.font("Consolas", 10.5);
    private static final Font KEY_FONT = Font.font("Consolas", 11.5);

    private static final double LEFT = 22;
    private static final double STRIP = 70;
    private static final double TREE_TOP = 34;
    private static final double CARD_W = 184;
    private static final double CARD_H = 58;
    private static final double CARD_STEP = 68;
    private static final double ROW_W = 236;
    private static final double ROW_H = 26;
    private static final double ROW_STEP = 31;
    private static final double SECTION = 26;
    private static final double GAP = 64;
    private static final String HOLDS_IT_UP = "holds it up";
    private static final String IT_HOLDS_UP = "it holds up";

    /** The order of the standings: what needs looking at first. */
    static final Standing[] ORDER = {Standing.FAILED, Standing.WILL_NOT_RETURN, Standing.NOT_RUNNING,
        Standing.RUNNING, Standing.IDLE};

    private final Canvas canvas = new Canvas();
    private final LiveTimer timer;
    private ServicesReport report = ServicesReport.EMPTY;
    /** Null until the second reading lands: "not read yet" is not "depends on nothing". */
    private Dependencies dependencies;
    private Standing open = Standing.RUNNING;
    /** What was clicked in each column, which is what the column after it grew from. */
    private final List<String> trail = new ArrayList<>();
    /** When each column appeared, so it can slide in. */
    private final Map<Integer, Double> grownAt = new HashMap<>();
    private final List<Card> cards = new ArrayList<>();
    private final List<Column> columns = new ArrayList<>();
    private String filter = "";
    private String chosen;
    private String hovered;
    private Standing hoveredCard;
    private String emptyTitle = "";
    private String emptyDetail = "";
    private double camX;
    private double camY;
    private double targetX;
    private double targetY;
    private double width;
    private double height;
    private double pressX = Double.NaN;
    private double pressY;
    private double pressCamX;
    private double pressCamY;
    private double shownAt = -1;
    private double frozen = -1;
    private double lastFrame = -1;

    private Consumer<ServiceUnit> onSelect = unit -> { };
    private Consumer<ServiceUnit> onOpen = unit -> { };
    private BiConsumer<ServiceUnit, double[]> onMenu = (unit, at) -> { };

    private record Card(Standing standing, int count, double y) {
    }

    /**
     * One service in a column. A name this reading does not hold, such as a target or a unit that has gone, is
     * still drawn, as a name and nothing more.
     */
    private record Row(ServiceUnit unit, boolean known, int column, double x, double y) {
        boolean holds(double wx, double wy) {
            return wx >= x && wx <= x + ROW_W && wy >= y && wy <= y + ROW_H;
        }
    }

    /** A column: its title, its sections, its rows. */
    private static final class Column {
        final String title;
        final Color tint;
        final List<Row> rows = new ArrayList<>();
        /** For each section, the index of its first row and, once placed, where its title goes. */
        final List<double[]> sections = new ArrayList<>();
        final List<String> sectionWords = new ArrayList<>();
        /** How many of the rows hold up the service the column grew from; the rest are held up by it. */
        int holding;
        double x;
        double top;
        double bottom;
        String note;

        Column(String title, Color tint) {
            this.title = title;
            this.tint = tint;
        }
    }

    ServicesBoard() {
        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinSize(0, 0);
        setFocusTraversable(true);
        timer = new LiveTimer(this, LiveTimer.AMBIENT, seconds -> paint());
        setOnMouseMoved(this::moved);
        setOnMouseExited(event -> {
            hovered = null;
            hoveredCard = null;
            setCursor(Cursor.DEFAULT);
        });
        setOnMousePressed(event -> {
            requestFocus();
            pressX = event.getX();
            pressY = event.getY();
            pressCamX = targetX;
            pressCamY = targetY;
        });
        setOnMouseDragged(event -> {
            if (Double.isNaN(pressX)) {
                return;
            }
            targetX = pressCamX - (event.getX() - pressX);
            targetY = pressCamY - (event.getY() - pressY);
            clamp();
            camX = targetX;
            camY = targetY;
            setCursor(Cursor.CLOSED_HAND);
        });
        setOnMouseReleased(event -> pressX = Double.NaN);
        setOnMouseClicked(this::clicked);
        setOnScroll(this::scrolled);
        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DOWN || event.getCode() == KeyCode.UP) {
                step(event.getCode() == KeyCode.DOWN ? 1 : -1);
                event.consume();
            } else if (event.getCode() == KeyCode.ENTER && unit(chosen) != null) {
                onOpen.accept(unit(chosen));
                event.consume();
            }
        });
        timer.start();
    }

    void setOnSelect(Consumer<ServiceUnit> onSelect) {
        this.onSelect = onSelect;
    }

    /** A double click, or Enter: the journal of that service. */
    void setOnOpen(Consumer<ServiceUnit> onOpen) {
        this.onOpen = onOpen;
    }

    /** Called with the service and where on the screen the right click was. */
    void setOnMenu(BiConsumer<ServiceUnit, double[]> onMenu) {
        this.onMenu = onMenu;
    }

    /**
     * A new reading. The tree stays where it was; when the picked service changed standing, because it was
     * just stopped or started, the tree follows it there.
     */
    void show(ServicesReport read) {
        boolean first = report.units().isEmpty() && !read.units().isEmpty();
        report = read;
        if (first || shownAt < 0) {
            shownAt = clock();
        }
        ServiceUnit picked = unit(chosen);
        if (picked == null) {
            String pick = firstWorthPicking();
            if (pick != null) {
                choose(pick);
                return;
            }
        } else if (first || (!trail.isEmpty() && trail.get(0).equals(chosen) && picked.standing() != open)) {
            choose(chosen);
            return;
        }
        if (!trail.isEmpty() && unit(trail.get(0)) == null) {
            trail.clear();
        }
        place();
        paint();
    }

    void setDependencies(Dependencies value) {
        dependencies = value;
        place();
        paint();
    }

    /** Only the services whose name or description holds this. Empty shows them all. */
    void setFilter(String text) {
        String next = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (next.equals(filter)) {
            return;
        }
        filter = next;
        if (!filter.isEmpty() && matching(open).isEmpty()) {
            for (Standing standing : ORDER) {
                if (!matching(standing).isEmpty()) {
                    open = standing;
                    break;
                }
            }
        }
        trail.clear();
        targetX = 0;
        targetY = 0;
        grownAt.put(0, clock());
        place();
        paint();
    }

    /** What to say when there is nothing to draw. */
    void setEmpty(String title, String detail) {
        emptyTitle = title == null ? "" : title;
        emptyDetail = detail == null ? "" : detail;
        paint();
    }

    /** Picks a service: its standing opens, and its links grow beside it. */
    void choose(String name) {
        ServiceUnit unit = unit(name);
        if (unit == null) {
            return;
        }
        chosen = name;
        if (!matches(unit)) {
            filter = "";
        }
        open = unit.standing();
        trail.clear();
        trail.add(name);
        grownAt.put(1, clock());
        place();
        reveal(0, name);
        paint();
    }

    /** Opens one standing, as a click on its block does. */
    void openStanding(Standing standing) {
        open = standing;
        trail.clear();
        targetX = 0;
        targetY = 0;
        grownAt.put(0, clock());
        place();
        paint();
    }

    String chosen() {
        return chosen;
    }

    ServiceUnit chosenUnit() {
        return unit(chosen);
    }

    ServiceUnit unit(String name) {
        if (name == null) {
            return null;
        }
        for (ServiceUnit unit : report.units()) {
            if (unit.name().equals(name)) {
                return unit;
            }
        }
        return null;
    }

    void stop() {
        timer.stop();
    }

    /** Only for the previews: one more step along the tree, as a click on that name in the last column. */
    void followForPreview(String name) {
        Column last = columns.get(columns.size() - 1);
        for (Row row : last.rows) {
            if (row.unit.name().equals(name)) {
                select(row);
                return;
            }
        }
    }

    /** Only for the previews: the picture held this long after it appeared. */
    void freezeForPreview(double seconds) {
        shownAt = 0;
        grownAt.replaceAll((index, at) -> 0.0);
        frozen = seconds;
        camX = targetX;
        camY = targetY;
        paint();
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        width = getWidth();
        height = getHeight();
        clamp();
        paint();
    }

    @Override
    protected double computePrefWidth(double h) {
        return 760;
    }

    @Override
    protected double computePrefHeight(double w) {
        return 600;
    }

    // Choosing

    /** The first service that needs looking at, or the first running one. */
    private String firstWorthPicking() {
        for (Standing standing : ORDER) {
            List<ServiceUnit> units = report.of(standing);
            if (!units.isEmpty()) {
                return units.get(0).name();
            }
        }
        return null;
    }

    private boolean matches(ServiceUnit unit) {
        return filter.isEmpty() || unit.shortName().toLowerCase(Locale.ROOT).contains(filter)
                || unit.description().toLowerCase(Locale.ROOT).contains(filter);
    }

    private List<ServiceUnit> matching(Standing standing) {
        List<ServiceUnit> found = new ArrayList<>();
        for (ServiceUnit unit : report.of(standing)) {
            if (matches(unit)) {
                found.add(unit);
            }
        }
        return found;
    }

    /** The arrow keys walk the open standing's list. */
    private void step(int direction) {
        List<ServiceUnit> units = matching(open);
        if (units.isEmpty()) {
            return;
        }
        int at = -1;
        for (int i = 0; i < units.size(); i++) {
            if (!trail.isEmpty() && units.get(i).name().equals(trail.get(0))) {
                at = i;
            }
        }
        int next = Math.max(0, Math.min(units.size() - 1, at < 0 ? 0 : at + direction));
        ServiceUnit unit = units.get(next);
        chosen = unit.name();
        trail.clear();
        trail.add(unit.name());
        grownAt.put(1, clock());
        place();
        reveal(0, unit.name());
        onSelect.accept(unit);
        paint();
    }

    /** Moves the view just enough for this row, and the column grown from it, to be in sight. */
    private void reveal(int column, String name) {
        if (column >= columns.size() || width <= 0) {
            return;
        }
        for (Row row : columns.get(column).rows) {
            if (row.unit.name().equals(name)) {
                double viewH = treeHeight();
                if (row.y - 40 < targetY) {
                    targetY = row.y - 40;
                } else if (row.y + ROW_H + 40 > targetY + viewH) {
                    targetY = row.y + ROW_H + 40 - viewH;
                }
            }
        }
        Column last = columns.get(columns.size() - 1);
        double right = last.x + ROW_W + 30;
        if (right > targetX + width) {
            targetX = right - width;
        }
        double leftEdge = columns.get(Math.max(0, columns.size() - 2)).x - 30;
        if (leftEdge < targetX) {
            targetX = Math.max(0, leftEdge);
        }
        clamp();
    }

    // The pointer

    private double treeHeight() {
        return Math.max(0, height - STRIP);
    }

    private double contentBottom() {
        double bottom = TREE_TOP + ORDER.length * CARD_STEP;
        for (Column column : columns) {
            bottom = Math.max(bottom, column.bottom + 30);
        }
        return bottom;
    }

    private void clamp() {
        double right = LEFT + CARD_W;
        for (Column column : columns) {
            right = Math.max(right, column.x + ROW_W + 30);
        }
        targetX = Math.max(0, Math.min(targetX, Math.max(0, right - width)));
        targetY = Math.max(0, Math.min(targetY, Math.max(0, contentBottom() - treeHeight())));
    }

    private Card cardAt(double x, double y) {
        if (y < STRIP) {
            return null;
        }
        double wx = x + camX;
        double wy = y - STRIP + camY;
        for (Card card : cards) {
            if (wx >= LEFT && wx <= LEFT + CARD_W && wy >= card.y && wy <= card.y + CARD_H) {
                return card;
            }
        }
        return null;
    }

    private Row rowAt(double x, double y) {
        if (y < STRIP) {
            return null;
        }
        double wx = x + camX;
        double wy = y - STRIP + camY;
        for (Column column : columns) {
            for (Row row : column.rows) {
                if (row.holds(wx, wy)) {
                    return row;
                }
            }
        }
        return null;
    }

    private void moved(MouseEvent event) {
        Row row = rowAt(event.getX(), event.getY());
        Card card = row == null ? cardAt(event.getX(), event.getY()) : null;
        hovered = row == null ? null : row.unit.name();
        hoveredCard = card == null ? null : card.standing;
        setCursor(row != null || card != null ? Cursor.HAND : Cursor.DEFAULT);
    }

    private void scrolled(ScrollEvent event) {
        double horizontal = event.isShiftDown() ? event.getDeltaY() : event.getDeltaX();
        double vertical = event.isShiftDown() ? 0 : event.getDeltaY();
        targetX -= horizontal;
        targetY -= vertical;
        clamp();
        event.consume();
    }

    private void clicked(MouseEvent event) {
        if (!event.isStillSincePress()) {
            return;
        }
        Card card = cardAt(event.getX(), event.getY());
        if (card != null && event.getButton() == MouseButton.PRIMARY) {
            openStanding(card.standing);
            return;
        }
        Row row = rowAt(event.getX(), event.getY());
        if (row == null) {
            return;
        }
        if (event.getButton() == MouseButton.SECONDARY) {
            if (row.known) {
                select(row);
                onMenu.accept(row.unit, new double[]{event.getScreenX(), event.getScreenY()});
            }
            return;
        }
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        select(row);
        if (event.getClickCount() >= 2 && row.known) {
            onOpen.accept(row.unit);
        }
    }

    /** Carries the tree one step further from this row: everything after its column goes, a new one grows. */
    private void select(Row row) {
        while (trail.size() > row.column) {
            trail.remove(trail.size() - 1);
        }
        trail.add(row.unit.name());
        grownAt.put(row.column + 1, clock());
        if (row.known) {
            chosen = row.unit.name();
        }
        place();
        reveal(row.column, row.unit.name());
        if (row.known) {
            onSelect.accept(row.unit);
        }
        paint();
    }

    // Placing

    private void place() {
        cards.clear();
        columns.clear();
        double y = TREE_TOP;
        for (Standing standing : ORDER) {
            cards.add(new Card(standing, filter.isEmpty() ? report.count(standing) : matching(standing).size(), y));
            y += CARD_STEP;
        }

        Column first = new Column(word(open), colour(open));
        for (ServiceUnit unit : matching(open)) {
            first.rows.add(new Row(unit, true, 0, 0, 0));
        }
        if (first.rows.isEmpty()) {
            first.note = filter.isEmpty() ? "none" : "none matches";
        }
        columns.add(first);

        for (int i = 0; i < trail.size(); i++) {
            String from = trail.get(i);
            ServiceUnit fromUnit = unit(from);
            Column column = new Column(shortName(from), fromUnit == null ? GREY : colour(fromUnit.standing()));
            if (dependencies == null) {
                column.note = "reading the links";
            } else {
                List<String> needs = sorted(dependencies.needsOf(from));
                List<String> neededBy = sorted(dependencies.neededByOf(from));
                if (needs.isEmpty() && neededBy.isEmpty()) {
                    column.note = "no link listed";
                }
                section(column, HOLDS_IT_UP, needs, i + 1);
                column.holding = column.rows.size();
                section(column, IT_HOLDS_UP, neededBy, i + 1);
            }
            columns.add(column);
        }

        double x = LEFT + CARD_W + GAP;
        double startAt = TREE_TOP;
        for (int index = 0; index < columns.size(); index++) {
            Column column = columns.get(index);
            column.x = x;
            column.top = startAt;
            List<Row> placed = new ArrayList<>();
            double rowY = startAt;
            int section = 0;
            for (int count = 0; count < column.rows.size(); count++) {
                while (section < column.sections.size() && column.sections.get(section)[0] == count) {
                    column.sections.get(section)[1] = rowY;
                    rowY += SECTION;
                    section++;
                }
                Row entry = column.rows.get(count);
                placed.add(new Row(entry.unit, entry.known, index, x, rowY));
                rowY += ROW_STEP;
            }
            if (column.note != null) {
                rowY += ROW_STEP;
            }
            column.rows.clear();
            column.rows.addAll(placed);
            column.bottom = rowY;
            x += ROW_W + GAP;
            // The next column starts level with the row that grew it, so the eye keeps the thread.
            startAt = TREE_TOP;
            if (index < trail.size()) {
                for (Row row : placed) {
                    if (row.unit.name().equals(trail.get(index))) {
                        startAt = Math.max(TREE_TOP, row.y - SECTION);
                    }
                }
            }
        }
        clamp();
    }

    private void section(Column column, String words, List<String> names, int index) {
        if (names.isEmpty()) {
            return;
        }
        column.sections.add(new double[]{column.rows.size(), 0});
        column.sectionWords.add(words);
        for (String name : names) {
            ServiceUnit known = unit(name);
            column.rows.add(new Row(known == null ? ServiceUnit.of(name, "", "", "", "") : known, known != null,
                    index, 0, 0));
        }
    }

    private static List<String> sorted(List<String> names) {
        List<String> copy = new ArrayList<>(names);
        copy.sort(String.CASE_INSENSITIVE_ORDER);
        return copy;
    }

    // Drawing

    private double clock() {
        return frozen >= 0 ? frozen : System.nanoTime() / 1e9;
    }

    private void paint() {
        width = canvas.getWidth();
        height = canvas.getHeight();
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setGlobalAlpha(1);
        gc.clearRect(0, 0, width, height);
        if (width < 300 || height < 120) {
            return;
        }
        double t = clock();
        double step = lastFrame < 0 || frozen >= 0 ? 1 : Math.min(1, (t - lastFrame) * 7);
        lastFrame = t;
        camX += (targetX - camX) * step;
        camY += (targetY - camY) * step;
        if (Math.abs(targetX - camX) < 0.3) {
            camX = targetX;
        }
        if (Math.abs(targetY - camY) < 0.3) {
            camY = targetY;
        }
        gc.setTextBaseline(VPos.CENTER);
        gc.setLineCap(StrokeLineCap.ROUND);
        drawBoot(gc);
        gc.setGlobalAlpha(1);
        gc.setStroke(FAINT);
        gc.setLineWidth(1);
        gc.strokeLine(LEFT, STRIP - 0.5, width - LEFT, STRIP - 0.5);
        if (report.units().isEmpty()) {
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setFont(NAME);
            gc.setFill(DIM);
            double middle = STRIP + treeHeight() / 2;
            gc.fillText(emptyTitle, width / 2, middle - 10);
            gc.setFont(SMALL);
            gc.setFill(MUTED);
            gc.fillText(emptyDetail, width / 2, middle + 10);
            return;
        }
        gc.save();
        gc.beginPath();
        gc.rect(0, STRIP, width, height - STRIP);
        gc.closePath();
        gc.clip();
        gc.translate(-camX, STRIP - camY);
        double since = t - shownAt;
        drawWires(gc, t, since);
        for (int i = 0; i < cards.size(); i++) {
            drawCard(gc, cards.get(i), t, ease((since - i * 0.05) / 0.35));
        }
        for (int index = 0; index < columns.size(); index++) {
            drawColumn(gc, columns.get(index), index, t, columnIn(index, t));
        }
        gc.restore();
        drawTrack(gc);
    }

    private double columnIn(int index, double t) {
        double grown = Math.max(grownAt.getOrDefault(index, shownAt), shownAt);
        return ease((t - grown - (index == 0 ? 0.15 : 0)) / 0.3);
    }

    /** How long the last boot took, and the services that took most of it. */
    private void drawBoot(GraphicsContext gc) {
        double mid = 34;
        gc.setGlobalAlpha(1);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(KEY_FONT);
        gc.setFill(KEY);
        gc.fillText("boot", LEFT, mid);
        double left = LEFT + 62;
        Duration boot = report.boot();
        if (boot == null) {
            gc.setFont(SMALL);
            gc.setFill(MUTED);
            gc.fillText(report.units().isEmpty() ? "" : "this machine does not say what its last boot took",
                    left, mid);
            return;
        }
        String total = shortTime(boot);
        gc.setFont(BOOT);
        gc.setFill(TEXT);
        gc.fillText(total, left, mid - 6);
        gc.setFont(SMALL);
        gc.setFill(MUTED);
        gc.fillText("last boot", left, mid + 13);
        double x = left + Math.max(width(total, BOOT), width("last boot", SMALL)) + 34;
        double item = 150;
        int fits = (int) Math.floor((width - LEFT - x) / item);
        List<ServiceUnit> slowest = report.slowest(Math.max(0, Math.min(4, fits)));
        if (slowest.isEmpty()) {
            return;
        }
        double most = Math.max(1, slowest.get(0).startup().toMillis());
        for (ServiceUnit unit : slowest) {
            boolean lit = hovered == null || hovered.equals(unit.name());
            gc.setGlobalAlpha(lit ? 1 : 0.45);
            gc.setFont(SMALL);
            gc.setFill(unit.name().equals(chosen) ? TEXT : DIM);
            gc.fillText(clip(unit.shortName(), SMALL, item - 16), x, mid - 7);
            double bar = 84;
            gc.setFill(WELL);
            gc.fillRoundRect(x, mid + 6, bar, 4, 3, 3);
            gc.setFill(colour(unit.standing()));
            gc.fillRoundRect(x, mid + 6, Math.max(2, bar * unit.startup().toMillis() / most), 4, 3, 3);
            gc.setFill(TEXT);
            gc.fillText(shortTime(unit.startup()), x + bar + 7, mid + 8);
            x += item;
        }
        gc.setGlobalAlpha(1);
    }

    /** A standing: how many stand there, big, in its colour, and its name under it. */
    private void drawCard(GraphicsContext gc, Card card, double t, double in) {
        if (in <= 0) {
            return;
        }
        Color colour = colour(card.standing);
        boolean isOpen = card.standing == open;
        boolean pointed = card.standing == hoveredCard;
        boolean alarm = card.standing.wrong() && card.count > 0;
        double x = LEFT - (1 - in) * 10;
        double y = card.y;
        if (alarm) {
            // Something here needs looking at: the block breathes, in a soft ring around its own edge.
            double beat = 0.5 + 0.5 * Math.sin(t * 2.2 + card.y);
            gc.setStroke(colour);
            for (int ring = 3; ring >= 1; ring--) {
                gc.setGlobalAlpha(in * (0.05 + 0.07 * beat) / ring);
                gc.setLineWidth(ring * 2.5);
                gc.strokeRoundRect(x - ring, y - ring, CARD_W + ring * 2, CARD_H + ring * 2, 8 + ring, 8 + ring);
            }
        }
        gc.setGlobalAlpha(in);
        gc.setFill(isOpen ? colour.deriveColor(0, 1, 0.2, 1) : pointed ? Color.web("#11111a") : WELL);
        gc.fillRoundRect(x, y, CARD_W, CARD_H, 6, 6);
        gc.setGlobalAlpha(in * (isOpen ? 0.9 : card.count > 0 ? 0.35 : 0.6));
        gc.setStroke(isOpen || card.count > 0 ? colour : FAINT);
        gc.setLineWidth(1);
        gc.strokeRoundRect(x + 0.5, y + 0.5, CARD_W - 1, CARD_H - 1, 6, 6);
        gc.setGlobalAlpha(in * (card.count > 0 ? 1 : 0.3));
        gc.setFill(colour);
        gc.fillRoundRect(x, y + 8, 3, CARD_H - 16, 2, 2);

        gc.setGlobalAlpha(in);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(BIG);
        gc.setFill(card.count > 0 ? (card.standing == Standing.IDLE ? DIM : colour) : MUTED);
        String count = String.valueOf(card.count);
        gc.fillText(count, x + 16, y + 22);
        gc.setFont(SMALL);
        gc.setFill(isOpen ? TEXT : card.count > 0 ? DIM : MUTED);
        gc.fillText(word(card.standing), x + 16, y + 43);
        // How much of the machine stands here, as a line beside the count.
        int total = Math.max(1, report.units().size());
        double from = x + 16 + width(count, BIG) + 12;
        double room = x + CARD_W - 16 - from;
        gc.setGlobalAlpha(in);
        gc.setFill(FAINT);
        gc.fillRect(from, y + 21, room, 2);
        if (card.count > 0) {
            gc.setGlobalAlpha(in * 0.85);
            gc.setFill(colour);
            gc.fillRect(from, y + 21, Math.max(2, room * card.count / total), 2);
        }
    }

    /** The wires: from the open standing to its services, and from each clicked row to the column it grew. */
    private void drawWires(GraphicsContext gc, double t, double since) {
        if (columns.isEmpty()) {
            return;
        }
        Column first = columns.get(0);
        double firstIn = columnIn(0, t);
        for (Card card : cards) {
            if (card.standing != open || first.rows.isEmpty()) {
                continue;
            }
            double x1 = LEFT + CARD_W;
            double y1 = card.y + CARD_H / 2;
            wire(gc, x1, y1, first.x, first.rows.get(0).y + ROW_H / 2, colour(open), t, 0.55 * firstIn, true);
        }
        for (int index = 0; index < trail.size() && index + 1 < columns.size(); index++) {
            Column from = columns.get(index);
            Column to = columns.get(index + 1);
            double in = columnIn(index + 1, t);
            Row source = null;
            for (Row row : from.rows) {
                if (row.unit.name().equals(trail.get(index))) {
                    source = row;
                }
            }
            if (source == null || in <= 0) {
                continue;
            }
            double x1 = source.x + ROW_W;
            double y1 = source.y + ROW_H / 2;
            for (int i = 0; i < to.rows.size(); i++) {
                Row row = to.rows.get(i);
                Color tint = row.known ? colour(row.unit.standing()) : GREY;
                // What holds it up flows into it; what it holds up flows out of it.
                wire(gc, x1, y1, row.x, row.y + ROW_H / 2, tint, t, (row.known ? 0.4 : 0.2) * in,
                        i >= to.holding);
            }
        }
    }

    /** A soft curve with light travelling along it, the way the apps board draws a request going in. */
    private void wire(GraphicsContext gc, double x1, double y1, double x2, double y2, Color colour, double t,
                      double alpha, boolean outward) {
        if (alpha <= 0.01) {
            return;
        }
        double bend = Math.max(24, (x2 - x1) * 0.5);
        gc.setGlobalAlpha(alpha);
        gc.setStroke(colour);
        gc.setLineWidth(1.1);
        gc.beginPath();
        gc.moveTo(x1, y1);
        gc.bezierCurveTo(x1 + bend, y1, x2 - bend, y2, x2, y2);
        gc.stroke();
        for (int i = 0; i < 2; i++) {
            double p = (t * 0.45 + i * 0.5 + (y2 * 0.013)) % 1;
            double s = outward ? p : 1 - p;
            double u = 1 - s;
            double px = u * u * u * x1 + 3 * u * u * s * (x1 + bend) + 3 * u * s * s * (x2 - bend) + s * s * s * x2;
            double py = u * u * u * y1 + 3 * u * u * s * y1 + 3 * u * s * s * y2 + s * s * s * y2;
            gc.setGlobalAlpha(Math.min(1, alpha + 0.3) * Math.sin(Math.PI * s));
            gc.setFill(colour);
            gc.fillOval(px - 1.8, py - 1.8, 3.6, 3.6);
        }
        gc.setGlobalAlpha(1);
    }

    private void drawColumn(GraphicsContext gc, Column column, int index, double t, double in) {
        if (in <= 0) {
            return;
        }
        double slide = (1 - in) * 14;
        double x = column.x - slide;
        // The title: the standing's word for the first column, the service it grew from for the others.
        double titleY = column.top - 12;
        gc.setGlobalAlpha(in);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(KEY_FONT);
        gc.setFill(column.tint);
        String title = clip(column.title, KEY_FONT, ROW_W - 60);
        gc.fillText(title, x, titleY);
        double hx = x + width(title, KEY_FONT) + 8;
        if (index == 0) {
            gc.setFont(SMALL);
            gc.setFill(MUTED);
            String count = String.valueOf(column.rows.size());
            gc.fillText(count, hx, titleY + 0.5);
            hx += width(count, SMALL) + 10;
        }
        gc.setStroke(FAINT);
        gc.setLineWidth(1);
        for (double h = hx; h < Math.min(hx + 40, x + ROW_W); h += 5) {
            gc.strokeLine(h, titleY + 3, h + 3, titleY - 3);
        }

        double viewTop = camY - 40;
        double viewBottom = camY + treeHeight() + 40;
        for (int s = 0; s < column.sections.size(); s++) {
            double sy = column.sections.get(s)[1] + SECTION / 2 + 2;
            gc.setGlobalAlpha(in);
            gc.setFont(SMALL);
            gc.setFill(MUTED);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText(column.sectionWords.get(s), x, sy);
        }
        for (Row row : column.rows) {
            if (row.y + ROW_H < viewTop || row.y > viewBottom) {
                continue;
            }
            drawRow(gc, row, x, t, in);
        }
        if (column.note != null) {
            gc.setGlobalAlpha(in);
            gc.setFont(SMALL);
            gc.setFill(MUTED);
            gc.setTextAlign(TextAlignment.LEFT);
            double ny = column.rows.isEmpty() ? column.top + ROW_STEP / 2 + 4 : column.bottom - ROW_STEP / 2;
            gc.fillText(column.note, x + 4, ny);
        }
    }

    private void drawRow(GraphicsContext gc, Row row, double x, double t, double in) {
        ServiceUnit unit = row.unit;
        double y = row.y;
        double mid = y + ROW_H / 2;
        boolean onTrail = trail.size() > row.column && trail.get(row.column).equals(unit.name());
        boolean picked = unit.name().equals(chosen) && onTrail;
        boolean pointed = unit.name().equals(hovered);
        Color colour = row.known ? colour(unit.standing()) : GREY;

        gc.setGlobalAlpha(in);
        gc.setFill(picked ? colour.deriveColor(0, 1, 0.2, 1) : pointed ? Color.web("#13131c") : WELL);
        gc.fillRoundRect(x, y, ROW_W, ROW_H, 4, 4);
        if (row.known) {
            gc.setGlobalAlpha(in * (onTrail ? 0.95 : 1));
            gc.setStroke(onTrail ? colour : FAINT);
        } else {
            gc.setGlobalAlpha(in);
            gc.setStroke(FAINT);
            gc.setLineDashes(3, 3);
        }
        gc.setLineWidth(1);
        gc.strokeRoundRect(x + 0.5, y + 0.5, ROW_W - 1, ROW_H - 1, 4, 4);
        gc.setLineDashes((double[]) null);
        gc.setGlobalAlpha(in * (unit.standing() == Standing.IDLE || !row.known ? 0.45 : 1));
        gc.setFill(colour);
        gc.fillRoundRect(x, y + 4, 2.5, ROW_H - 8, 2, 2);

        // Running: a dot that breathes. Failed: red. Anything else: a quiet dot.
        double dotX = x + 13;
        Color state = row.known ? nowColour(unit) : GREY;
        if (row.known && unit.running()) {
            double beat = 0.5 + 0.5 * Math.sin(t * 2.4 + unit.name().length());
            gc.setGlobalAlpha(in * 0.35 * beat);
            gc.setFill(new RadialGradient(0, 0, dotX, mid, 7, false, CycleMethod.NO_CYCLE,
                    new Stop(0, state), new Stop(1, Color.TRANSPARENT)));
            gc.fillOval(dotX - 7, mid - 7, 14, 14);
        }
        gc.setGlobalAlpha(in);
        if (row.known) {
            gc.setFill(state);
            gc.fillOval(dotX - 2.5, mid - 2.5, 5, 5);
        } else {
            gc.setStroke(GREY);
            gc.strokeOval(dotX - 2.5, mid - 2.5, 5, 5);
        }

        String time = "";
        if (row.known && unit.startup() != null && unit.startup().toMillis() >= 1000) {
            time = shortTime(unit.startup());
        }
        Font font = picked ? NAME_STRONG : NAME;
        double room = ROW_W - 30 - (time.isEmpty() ? 8 : width(time, SMALL) + 14);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(font);
        gc.setFill(picked ? Color.WHITE : !row.known ? MUTED : unit.standing() == Standing.IDLE ? DIM : TEXT);
        gc.fillText(clip(row.known ? unit.shortName() : unit.name(), font, room), x + 23, mid);
        if (!time.isEmpty()) {
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.setFont(SMALL);
            gc.setFill(unit.startup().toMillis() >= 5000 ? AMBER : MUTED);
            gc.fillText(time, x + ROW_W - 8, mid);
        }
    }

    /** A thin bar on the right edge saying how far down the tree the view is, when it is taller than the board. */
    private void drawTrack(GraphicsContext gc) {
        double bottom = contentBottom();
        double view = treeHeight();
        if (bottom <= view) {
            return;
        }
        double x = width - 6;
        double size = Math.max(28, view * view / bottom);
        double top = STRIP + (view - size) * (camY / Math.max(1, bottom - view));
        gc.setGlobalAlpha(1);
        gc.setStroke(FAINT);
        gc.setLineWidth(2);
        gc.strokeLine(x, STRIP + 4, x, height - 4);
        gc.setStroke(GREY);
        gc.setLineWidth(3);
        gc.strokeLine(x, top + 3, x, top + size - 3);
    }

    // Words and colours, shared with the screen around the board

    static Color colour(Standing standing) {
        return switch (standing) {
            case FAILED -> RED;
            case WILL_NOT_RETURN -> AMBER;
            case NOT_RUNNING -> VIOLET;
            case RUNNING -> GREEN;
            case IDLE -> GREY;
        };
    }

    /** The standing's name as the screen writes it. */
    static String word(Standing standing) {
        return switch (standing) {
            case FAILED -> "failed";
            case WILL_NOT_RETURN -> "won't survive a reboot";
            case NOT_RUNNING -> "enabled, not running";
            case RUNNING -> "running";
            case IDLE -> "idle";
        };
    }

    /** systemd's own word for what it does now: the sub-state, which says more than the active state. */
    static String nowWord(ServiceUnit unit) {
        String word = unit.subState().isEmpty() ? unit.activeState() : unit.subState();
        return word.isEmpty() ? "unknown" : word;
    }

    static Color nowColour(ServiceUnit unit) {
        if (unit.failed()) {
            return RED;
        }
        if (unit.running()) {
            return GREEN;
        }
        String state = unit.activeState();
        if (state.equals("activating") || state.equals("deactivating") || state.equals("reloading")
                || unit.subState().equals("auto-restart")) {
            return AMBER;
        }
        return GREY;
    }

    static String bootWord(BootState boot) {
        return boot == BootState.UNKNOWN ? "unknown" : boot.name().toLowerCase(Locale.ROOT);
    }

    static String shortTime(Duration duration) {
        long millis = duration.toMillis();
        if (millis >= 60_000) {
            return millis / 60_000 + "m " + Math.round(millis % 60_000 / 1000.0) + "s";
        }
        if (millis >= 1000) {
            return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
        }
        return millis + "ms";
    }

    private static String shortName(String name) {
        return name.endsWith(".service") ? name.substring(0, name.length() - ".service".length()) : name;
    }

    private static double ease(double value) {
        double v = Math.max(0, Math.min(1, value));
        return 1 - Math.pow(1 - v, 3);
    }

    private static double width(String text, Font font) {
        Text measure = new Text(text);
        measure.setFont(font);
        return measure.getLayoutBounds().getWidth();
    }

    private static String clip(String text, Font font, double room) {
        if (width(text, font) <= room) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && width(cut + "…", font) > room) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }
}
