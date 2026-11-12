package com.kubo.ui.apps;

import com.kubo.model.apps.App;
import com.kubo.model.apps.AppProcess;
import com.kubo.model.apps.AppsReport;
import com.kubo.model.apps.Manager;
import com.kubo.ui.LiveTimer;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * What the process managers keep running, drawn.
 *
 * <p>On top the machine: one cell per core, filled by how busy that core really is, a line under them saying
 * how much of the cores the applications take, each in its colour, then the memory the same way. Under it one
 * lane per application, read left to right the way a request travels: its name and state, the port it listens
 * on, a wire to its processes, one cell per process lit by the CPU it uses, then how its CPU and memory moved
 * over the last minutes, how long it has been up, and how often it restarted.
 *
 * <p>The lane that is picked opens, and lists its processes one by one.
 */
final class AppsBoard extends Pane {

    static final Color GREEN = Color.web("#35e0a1");
    static final Color AMBER = Color.web("#ffb347");
    static final Color RED = Color.web("#ff2d55");
    static final Color CYAN = Color.web("#35e8ff");
    static final Color GREY = Color.web("#6b6b80");
    private static final Color TEXT = Color.web("#eaeaf2");
    private static final Color DIM = Color.web("#a9a9bb");
    private static final Color MUTED = Color.web("#6b6b80");
    private static final Color FAINT = Color.web("#24242f");
    private static final Color WELL = Color.web("#0b0b12");
    private static final Color KEY = Color.web("#ff2d55");
    private static final Color LOAD = Color.web("#c9c9dd");
    private static final Color LANE_HOVER = Color.rgb(255, 255, 255, 0.022);
    private static final Color LANE_CHOSEN = Color.rgb(255, 255, 255, 0.045);
    static final Color[] PALETTE = {
            Color.web("#35e8ff"), Color.web("#b58cff"), Color.web("#ff3fd8"), Color.web("#ffb347"),
            Color.web("#35e0a1"), Color.web("#5b8cff"), Color.web("#ff8a5b"), Color.web("#e8c35a")};

    private static final Font NAME = Font.font("Consolas", FontWeight.BOLD, 13.5);
    private static final Font BIG = Font.font("Consolas", FontWeight.BOLD, 19);
    private static final Font TEXT_FONT = Font.font("Consolas", 12);
    private static final Font SMALL = Font.font("Consolas", 10.5);
    private static final Font KEY_FONT = Font.font("Consolas", 11.5);

    private static final double LEFT = 22;
    private static final double TOP_BLOCK = 122;
    private static final double HEAD = 26;
    private static final double GROUP = 30;
    private static final double LANE = 64;
    private static final double ROW = 22;
    private static final int MAX_ROWS = 12;
    private static final int SAMPLES = 90;

    private final Canvas canvas = new Canvas();
    private final LiveTimer timer;
    private AppsReport report = AppsReport.EMPTY;
    private final Map<String, Deque<double[]>> history = new HashMap<>();
    private final Map<String, Double> shownCores = new HashMap<>();
    private final Map<String, Double> openness = new HashMap<>();
    private final Map<String, Color> colours = new HashMap<>();
    private final List<Slot> slots = new ArrayList<>();
    private double[] shownLoads = new double[0];
    private String chosen;
    private String hovered;
    private String emptyTitle = "";
    private String emptyDetail = "";
    private double shownAt = -1;
    private double frozen = -1;
    private double lastFrame = -1;
    private double needed = -1;
    private Instant now = Instant.now();

    private Consumer<App> onSelect = app -> { };
    private Consumer<App> onOpen = app -> { };
    private BiConsumer<App, double[]> onMenu = (app, at) -> { };

    /** Where one lane is drawn, and the title of its manager when it is the first of it. */
    private record Slot(App app, double top, double height, boolean heads) {
    }

    AppsBoard() {
        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinWidth(0);
        timer = new LiveTimer(this, LiveTimer.AMBIENT, seconds -> paint());
        setOnMouseMoved(this::moved);
        setOnMouseExited(event -> {
            hovered = null;
            setCursor(Cursor.DEFAULT);
        });
        setOnMouseClicked(this::clicked);
        timer.start();
    }

    void setOnSelect(Consumer<App> onSelect) {
        this.onSelect = onSelect;
    }

    void setOnOpen(Consumer<App> onOpen) {
        this.onOpen = onOpen;
    }

    /** Called with the application and where on the screen the right click was. */
    void setOnMenu(BiConsumer<App, double[]> onMenu) {
        this.onMenu = onMenu;
    }

    /** A new reading: the lanes change in place, and each one's history gets one more point. */
    void show(AppsReport read) {
        boolean first = report.apps().isEmpty() && !read.apps().isEmpty();
        report = read;
        now = Instant.now();
        for (App app : read.apps()) {
            Deque<double[]> points = history.computeIfAbsent(app.key(), key -> new ArrayDeque<>());
            points.addLast(new double[]{app.cores() * 100, app.memory()});
            while (points.size() > SAMPLES) {
                points.removeFirst();
            }
            colours.computeIfAbsent(app.key(), key -> PALETTE[colours.size() % PALETTE.length]);
            shownCores.putIfAbsent(app.key(), 0.0);
        }
        if (first || shownAt < 0) {
            shownAt = clock();
        }
        boolean known = chosen != null && read.apps().stream().anyMatch(app -> app.key().equals(chosen));
        if (!known && !read.apps().isEmpty()) {
            chosen = read.apps().get(0).key();
        }
        resize();
        paint();
    }

    /** What to say when there is no lane to draw. */
    void setEmpty(String title, String detail) {
        emptyTitle = title == null ? "" : title;
        emptyDetail = detail == null ? "" : detail;
        paint();
    }

    void choose(String key) {
        chosen = key;
        resize();
        paint();
    }

    String chosen() {
        return chosen;
    }

    /** The colour this application has everywhere on the board. */
    Color colourOf(App app) {
        return colours.getOrDefault(app.key(), CYAN);
    }

    void stop() {
        timer.stop();
    }

    /** Only for the previews: a history already gathered, and the picture held this long after it appeared. */
    void historyForPreview(String key, double[] cpu, double[] memory) {
        Deque<double[]> points = new ArrayDeque<>();
        for (int i = 0; i < cpu.length; i++) {
            points.addLast(new double[]{cpu[i], memory[i]});
        }
        history.put(key, points);
    }

    void freezeForPreview(double seconds) {
        shownAt = 0;
        frozen = seconds;
        for (App app : report.apps()) {
            shownCores.put(app.key(), app.cores());
            openness.put(app.key(), app.key().equals(chosen) ? 1.0 : 0.0);
        }
        shownLoads = report.coreLoads().stream().mapToDouble(Double::doubleValue).toArray();
        paint();
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        paint();
    }

    @Override
    protected double computeMinHeight(double width) {
        return heightNeeded();
    }

    @Override
    protected double computePrefHeight(double width) {
        return heightNeeded();
    }

    /** The height every lane needs once the picked one is fully open. */
    private double heightNeeded() {
        double y = TOP_BLOCK + HEAD;
        Manager last = null;
        for (App app : report.apps()) {
            if (app.manager() != last) {
                y += GROUP;
                last = app.manager();
            }
            y += LANE + (app.key().equals(chosen) ? detailHeight(app) : 0);
        }
        return Math.max(TOP_BLOCK + 200, y + 18);
    }

    private void resize() {
        double height = heightNeeded();
        if (height != needed) {
            needed = height;
            requestLayout();
        }
    }

    private static double detailHeight(App app) {
        int rows = Math.min(app.processes().size(), MAX_ROWS) + (app.processes().size() > MAX_ROWS ? 1 : 0);
        return rows * ROW + 16;
    }

    // The pointer

    private App laneAt(double y) {
        for (Slot slot : slots) {
            if (y >= slot.top && y < slot.top + slot.height) {
                return slot.app;
            }
        }
        return null;
    }

    private void moved(MouseEvent event) {
        App app = laneAt(event.getY());
        hovered = app == null ? null : app.key();
        setCursor(app == null ? Cursor.DEFAULT : Cursor.HAND);
    }

    private void clicked(MouseEvent event) {
        App app = laneAt(event.getY());
        if (app == null) {
            return;
        }
        if (event.getButton() == MouseButton.SECONDARY) {
            choose(app.key());
            onSelect.accept(app);
            onMenu.accept(app, new double[]{event.getScreenX(), event.getScreenY()});
            return;
        }
        if (event.getButton() != MouseButton.PRIMARY || !event.isStillSincePress()) {
            return;
        }
        choose(app.key());
        onSelect.accept(app);
        if (event.getClickCount() >= 2) {
            onOpen.accept(app);
        }
    }

    // Drawing

    private double clock() {
        return frozen >= 0 ? frozen : System.nanoTime() / 1e9;
    }

    private void paint() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setGlobalAlpha(1);
        gc.clearRect(0, 0, w, h);
        if (w < 300 || h < 100) {
            return;
        }
        double t = clock();
        double step = lastFrame < 0 || frozen >= 0 ? 1 : Math.min(1, (t - lastFrame) * 5);
        lastFrame = t;
        for (App app : report.apps()) {
            double shown = shownCores.getOrDefault(app.key(), 0.0);
            shownCores.put(app.key(), shown + (app.cores() - shown) * step);
            double open = openness.getOrDefault(app.key(), 0.0);
            double target = app.key().equals(chosen) ? 1 : 0;
            double next = open + (target - open) * Math.min(1, step * 1.6);
            openness.put(app.key(), Math.abs(target - next) < 0.004 ? target : next);
        }
        List<Double> loads = report.coreLoads();
        if (shownLoads.length != loads.size()) {
            shownLoads = new double[loads.size()];
        }
        for (int i = 0; i < shownLoads.length; i++) {
            shownLoads[i] += (loads.get(i) - shownLoads[i]) * step;
        }
        place();
        gc.setTextBaseline(VPos.CENTER);
        gc.setLineCap(StrokeLineCap.ROUND);
        drawCores(gc, w, t);
        drawMemory(gc, w);
        drawLanes(gc, w, h, t);
    }

    /** Where each lane goes this frame, the picked one as open as it has got. */
    private void place() {
        slots.clear();
        double y = TOP_BLOCK + HEAD;
        Manager last = null;
        for (App app : report.apps()) {
            boolean heads = app.manager() != last;
            if (heads) {
                y += GROUP;
                last = app.manager();
            }
            double height = LANE + openness.getOrDefault(app.key(), 0.0) * detailHeight(app);
            slots.add(new Slot(app, y, height, heads));
            y += height;
        }
    }

    private double cellsLeft() {
        return LEFT + 84;
    }

    /**
     * One cell per core, filled from the bottom by how busy that core was since the last reading. Under them,
     * on the same width, the share of all the cores the applications take, each in its colour.
     */
    private void drawCores(GraphicsContext gc, double w, double t) {
        double mid = 36;
        key(gc, "cores", mid);
        int cores = Math.max(1, report.cores());
        double left = cellsLeft();
        double room = Math.max(120, w - left - 250);
        double gap = cores > 24 ? 3 : 6;
        double size = Math.max(5, Math.min(30, (room - gap * (cores - 1)) / cores));
        double height = 34;
        double top = mid - height / 2;
        double span = cores * size + (cores - 1) * gap;
        boolean measured = shownLoads.length > 0;
        double busy = 0;
        for (int cell = 0; cell < cores; cell++) {
            double x = left + cell * (size + gap);
            gc.setGlobalAlpha(1);
            gc.setFill(WELL);
            gc.fillRoundRect(x, top, size, height, 4, 4);
            // The legs of a chip, above and under.
            gc.setStroke(FAINT);
            gc.setLineWidth(1);
            if (size >= 12) {
                for (double leg : new double[]{0.3, 0.7}) {
                    double lx = Math.round(x + size * leg) + 0.5;
                    gc.strokeLine(lx, top - 4, lx, top - 1);
                    gc.strokeLine(lx, top + height + 1, lx, top + height + 4);
                }
            }
            if (measured && cell < shownLoads.length) {
                double load = Math.max(0, Math.min(1, shownLoads[cell]));
                busy += load;
                double filled = Math.max(1.5, (height - 4) * load);
                Color colour = load > 0.9 ? RED : load > 0.7 ? AMBER : LOAD;
                gc.setGlobalAlpha(0.2 + 0.65 * Math.min(1, load * 1.6 + 0.15));
                gc.setFill(new LinearGradient(0, top + height - 2 - filled, 0, top + height - 2, false,
                        CycleMethod.NO_CYCLE, new Stop(0, colour), new Stop(1, colour.deriveColor(0, 1, 0.55, 1))));
                gc.fillRoundRect(x + 2, top + height - 2 - filled, size - 4, filled, 2, 2);
            } else if (!measured && report.cores() > 0) {
                // Not measured yet: a light going along the cells says a figure is on its way.
                double wave = 0.5 + 0.5 * Math.sin(t * 3 - cell * 0.7);
                gc.setGlobalAlpha(0.06 + 0.1 * wave);
                gc.setFill(LOAD);
                gc.fillRoundRect(x + 2, top + 2, size - 4, height - 4, 2, 2);
            }
            gc.setGlobalAlpha(1);
            gc.setStroke(FAINT);
            gc.strokeRoundRect(x + 0.5, top + 0.5, size - 1, height - 1, 4, 4);
        }

        // Who takes them: the applications' share of every core together.
        double barY = top + height + 9;
        gc.setGlobalAlpha(1);
        gc.setFill(WELL);
        gc.fillRect(left, barY, span, 3);
        double used = 0;
        double x = left;
        for (App app : report.apps()) {
            double share = shownCores.getOrDefault(app.key(), 0.0);
            used += share;
            if (share <= 0.002) {
                continue;
            }
            double part = Math.max(2, span * share / cores);
            boolean lit = hovered == null || hovered.equals(app.key());
            gc.setGlobalAlpha(lit ? 0.95 : 0.3);
            gc.setFill(colourOf(app));
            gc.fillRect(x, barY, Math.min(part, left + span - x), 3);
            x += part;
        }

        double after = left + span + 16;
        gc.setGlobalAlpha(1);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(BIG);
        gc.setFill(TEXT);
        String count = report.cores() > 0 ? String.valueOf(report.cores()) : "?";
        gc.fillText(count, after, mid - 6);
        gc.setFont(SMALL);
        gc.setFill(DIM);
        gc.fillText(report.cores() == 1 ? "core" : "cores", after + width(count, BIG) + 7, mid - 4);
        String machine = measured ? "machine " + Math.round(busy / cores * 100) + "%" : "measuring";
        gc.setFill(MUTED);
        gc.fillText(machine + "  ·  apps " + String.format(Locale.ROOT, "%.1f", used)
                + (report.load() >= 0 ? "  ·  load " + String.format(Locale.ROOT, "%.2f", report.load()) : ""),
                after, mid + 13);
    }

    /** The machine's memory: each application's share in its colour, the rest of what is used in grey. */
    private void drawMemory(GraphicsContext gc, double w) {
        double y = 92;
        key(gc, "memory", y);
        double left = cellsLeft();
        double bar = Math.max(120, Math.min(620, w - left - 250));
        gc.setGlobalAlpha(1);
        gc.setFill(WELL);
        gc.fillRoundRect(left, y - 6, bar, 12, 4, 4);
        long total = report.memoryTotal();
        long apps = 0;
        if (total > 0) {
            double x = left + 2;
            double inner = bar - 4;
            for (App app : report.apps()) {
                double part = inner * app.memory() / (double) total;
                boolean lit = hovered == null || hovered.equals(app.key());
                gc.setGlobalAlpha(lit ? 0.9 : 0.3);
                gc.setFill(colourOf(app));
                gc.fillRect(x, y - 4, Math.max(0, part), 8);
                x += part;
                apps += app.memory();
            }
            double rest = inner * Math.max(0, report.memoryUsed() - apps) / (double) total;
            gc.setGlobalAlpha(0.3);
            gc.setFill(GREY);
            gc.fillRect(x, y - 4, Math.max(0, Math.min(rest, left + 2 + inner - x)), 8);
        }
        gc.setGlobalAlpha(1);
        gc.setStroke(FAINT);
        gc.setLineWidth(1);
        gc.strokeRoundRect(left + 0.5, y - 5.5, bar - 1, 11, 4, 4);
        gc.setTextAlign(TextAlignment.LEFT);
        double after = left + bar + 16;
        if (total <= 0) {
            gc.setFont(SMALL);
            gc.setFill(MUTED);
            gc.fillText("unknown", after, y);
            return;
        }
        gc.setFont(TEXT_FONT);
        gc.setFill(TEXT);
        String used = bytes(report.memoryUsed());
        gc.fillText(used, after, y);
        gc.setFont(SMALL);
        gc.setFill(MUTED);
        gc.fillText("of " + bytes(total) + "  ·  apps " + bytes(apps), after + width(used, TEXT_FONT) + 7, y + 0.5);
    }

    private void key(GraphicsContext gc, String word, double y) {
        gc.setGlobalAlpha(1);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(KEY_FONT);
        gc.setFill(KEY);
        gc.fillText(word, LEFT, y);
    }

    private void drawLanes(GraphicsContext gc, double w, double h, double t) {
        gc.setGlobalAlpha(1);
        gc.setStroke(FAINT);
        gc.setLineWidth(1);
        gc.strokeLine(LEFT, TOP_BLOCK - 0.5, w - LEFT, TOP_BLOCK - 0.5);
        if (slots.isEmpty()) {
            double middle = TOP_BLOCK + (h - TOP_BLOCK) / 2;
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setFont(TEXT_FONT);
            gc.setFill(DIM);
            gc.fillText(emptyTitle, w / 2, middle - 10);
            gc.setFont(SMALL);
            gc.setFill(MUTED);
            gc.fillText(emptyDetail, w / 2, middle + 10);
            return;
        }
        Columns columns = columns(w);
        double headY = TOP_BLOCK + 17;
        gc.setFont(SMALL);
        gc.setFill(MUTED);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("app", columns.name, headY);
        gc.fillText("listens", columns.port, headY);
        gc.fillText("processes", columns.workers, headY);
        gc.fillText("cpu", columns.cpu, headY);
        if (columns.memory > 0) {
            gc.fillText("memory", columns.memory, headY);
        } else if (columns.memoryText > 0) {
            gc.fillText("memory", columns.memoryText, headY);
        }
        gc.fillText("up", columns.up, headY);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText("restarts", w - LEFT, headY);

        double since = t - shownAt;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            double in = ease((since - i * 0.06) / 0.35);
            if (in <= 0) {
                continue;
            }
            if (slot.heads) {
                drawGroup(gc, slot, w, in);
            }
            drawLane(gc, slot, w, columns, t, in);
        }
    }

    /** The manager's name over its lanes, with how many it runs, and a hatched rule to the edge. */
    private void drawGroup(GraphicsContext gc, Slot slot, double w, double in) {
        double y = slot.top - GROUP / 2 + 3;
        long count = report.apps().stream().filter(app -> app.manager() == slot.app.manager()).count();
        gc.setGlobalAlpha(in);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(KEY_FONT);
        gc.setFill(KEY);
        String word = slot.app.manager().word();
        gc.fillText(word, LEFT, y);
        double x = LEFT + width(word, KEY_FONT) + 8;
        gc.setFont(SMALL);
        gc.setFill(MUTED);
        String tally = String.valueOf(count);
        gc.fillText(tally, x, y + 0.5);
        x += width(tally, SMALL) + 12;
        gc.setStroke(FAINT);
        gc.setLineWidth(1);
        for (double hx = x; hx < Math.min(x + 60, w - LEFT); hx += 5) {
            gc.strokeLine(hx, y + 3, hx + 3, y - 3);
        }
        gc.strokeLine(Math.min(x + 66, w - LEFT), y + 0.5, w - LEFT, y + 0.5);
    }

    /**
     * Where the columns start. The name and the port keep their room; the processes, the two curves and the
     * figures share what is left, and the memory curve goes first when there is not enough of it.
     */
    private record Columns(double name, double nameRoom, double port, double workers, double workersRoom,
                           double cpu, double cpuRoom, double memory, double memoryRoom, double memoryText,
                           double up) {
    }

    private Columns columns(double w) {
        double name = LEFT + 16;
        double nameRoom = Math.max(140, Math.min(230, w * 0.19));
        double port = name + nameRoom;
        double workers = port + 124;
        double up = w - LEFT - 114;
        double span = up - workers;
        // What the processes, a curve with its figure, and a second curve with its figure need at the least.
        double figures = 18 + 46 + 18 + 62;
        double both = 60 + figures + 70 + 60;
        if (span >= both) {
            double workersRoom = Math.min(230, 60 + (span - both) * 0.3);
            double each = (span - workersRoom - figures) / 2;
            double cpu = workers + workersRoom + 18;
            double memory = cpu + each + 46 + 18;
            return new Columns(name, nameRoom, port, workers, workersRoom, cpu, each, memory, each,
                    memory + each + 10, up);
        }
        double workersRoom = 60;
        double cpu = workers + workersRoom + 18;
        double cpuRoom = span - 60 - figures;
        if (cpuRoom < 30) {
            return new Columns(name, nameRoom, port, workers, workersRoom, cpu, 0, -1, 0,
                    cpuRoom >= 0 ? cpu + 54 : -1, up);
        }
        return new Columns(name, nameRoom, port, workers, workersRoom, cpu, cpuRoom, -1, 0,
                cpu + cpuRoom + 10 + 54, up);
    }

    private void drawLane(GraphicsContext gc, Slot slot, double w, Columns columns, double t, double in) {
        App app = slot.app;
        double top = slot.top;
        double mid = top + LANE / 2;
        boolean picked = app.key().equals(chosen);
        boolean pointed = app.key().equals(hovered);
        double slide = (1 - in) * 10;
        Color colour = colourOf(app);
        boolean running = app.online() > 0;
        if (picked || pointed) {
            gc.setGlobalAlpha(in);
            gc.setFill(picked ? LANE_CHOSEN : LANE_HOVER);
            gc.fillRect(LEFT - 10, top + 2, w - LEFT * 2 + 20, slot.height - 4);
            if (picked) {
                gc.setFill(colour);
                gc.fillRect(LEFT - 10, top + 2, 2, slot.height - 4);
            }
        }
        gc.setGlobalAlpha(in);
        gc.setStroke(FAINT);
        gc.setLineWidth(1);
        gc.strokeLine(LEFT, top + slot.height - 0.5, w - LEFT, top + slot.height - 0.5);

        // Its colour, its name, and under it how it holds.
        double nameX = columns.name + slide;
        gc.setGlobalAlpha(in * (running ? 1 : 0.55));
        gc.setFill(colour);
        gc.fillRect(nameX - 14, mid - 14, 7, 7);
        gc.setGlobalAlpha(in);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(NAME);
        gc.setFill(picked ? Color.WHITE : TEXT);
        gc.fillText(clip(app.name(), NAME, columns.nameRoom - 14), nameX, mid - 10);

        Color state = stateColour(app.state());
        double dotX = nameX + 3;
        if (app.state() == App.State.UP) {
            double beat = 0.5 + 0.5 * Math.sin(t * 2.4 + app.name().length());
            gc.setGlobalAlpha(in * 0.3 * beat);
            gc.setFill(new RadialGradient(0, 0, dotX, mid + 10, 8, false, CycleMethod.NO_CYCLE,
                    new Stop(0, state), new Stop(1, Color.TRANSPARENT)));
            gc.fillOval(dotX - 8, mid + 2, 16, 16);
        }
        gc.setGlobalAlpha(in);
        gc.setFill(state);
        gc.fillOval(dotX - 3, mid + 7, 6, 6);
        gc.setFont(SMALL);
        double x = nameX + 12;
        double edge = columns.port - 14;
        String words = stateWords(app);
        gc.fillText(clip(words, SMALL, edge - x), x, mid + 10.5);
        x += width(words, SMALL) + 7;
        // What is wrong goes before how it runs: when there is room for one of the two, it is the warning.
        if (!app.saved() && x + width("not saved", SMALL) + 10 <= edge) {
            chip(gc, "not saved", x, mid + 10.5, AMBER, in);
            x += width("not saved", SMALL) + 17;
        }
        String how = how(app);
        if (x + width(how, SMALL) <= edge) {
            gc.setGlobalAlpha(in);
            gc.setFont(SMALL);
            gc.setFill(MUTED);
            gc.fillText(how, x, mid + 10.5);
        }

        double wireFrom = drawPort(gc, app, columns.port + slide, mid, in);
        drawWire(gc, app, wireFrom, columns.workers + slide - 9, mid, colour, t, in);
        drawWorkers(gc, app, columns.workers + slide, mid, columns.workersRoom, t, in);

        Deque<double[]> points = history.getOrDefault(app.key(), new ArrayDeque<>());
        // A stopped one keeps its history, greyed: what it was doing when it stopped is worth seeing.
        Color line = running ? colour : GREY;
        spark(gc, points, 0, columns.cpu, mid, columns.cpuRoom, line, in, Math.max(100, maxOf(points, 0)));
        gc.setGlobalAlpha(in);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(TEXT_FONT);
        gc.setFill(running ? TEXT : MUTED);
        gc.fillText(running ? Math.round(app.cores() * 100) + "%" : "—",
                columns.cpu + (columns.cpuRoom >= 30 ? columns.cpuRoom + 10 : 0), mid);
        if (columns.memory > 0) {
            spark(gc, points, 1, columns.memory, mid, columns.memoryRoom, line, in, Math.max(1, maxOf(points, 1)));
        }
        if (columns.memoryText > 0) {
            gc.setGlobalAlpha(in);
            gc.setFont(TEXT_FONT);
            gc.setFill(running ? TEXT : MUTED);
            gc.fillText(running ? bytes(app.memory()) : "—", columns.memoryText, mid);
        }
        gc.setGlobalAlpha(in);
        gc.setFont(TEXT_FONT);
        gc.setFill(running ? DIM : MUTED);
        gc.fillText(uptime(app), columns.up, mid);
        gc.setTextAlign(TextAlignment.RIGHT);
        int restarts = app.restarts();
        gc.setFill(restarts > 20 ? RED : restarts > 3 ? AMBER : DIM);
        gc.fillText("↻ " + restarts, w - LEFT, mid);

        double open = openness.getOrDefault(app.key(), 0.0);
        if (open > 0.02) {
            drawProcesses(gc, app, top + LANE, slot.height - LANE, w, columns, in * open);
        }
    }

    /**
     * The port it listens on, as a socket: cyan when only this machine reaches it, red when anyone does.
     *
     * @return where the wire to the processes starts
     */
    private double drawPort(GraphicsContext gc, App app, double left, double mid, double in) {
        gc.setTextAlign(TextAlignment.LEFT);
        if (app.ports().isEmpty()) {
            gc.setGlobalAlpha(in * 0.8);
            gc.setStroke(MUTED);
            gc.setLineWidth(1);
            gc.setLineDashes(2, 2);
            gc.strokeOval(left + 0.5, mid - 5.5, 11, 11);
            gc.setLineDashes((double[]) null);
            gc.setFont(SMALL);
            gc.setFill(MUTED);
            gc.fillText(app.online() > 0 ? "no port" : "—", left + 19, mid);
            return left + 19 + width(app.online() > 0 ? "no port" : "—", SMALL) + 8;
        }
        App.Listening port = app.ports().get(0);
        Color colour = port.everywhere() ? RED : CYAN;
        gc.setGlobalAlpha(in * 0.22);
        gc.setFill(new RadialGradient(0, 0, left + 6, mid - 6, 11, false, CycleMethod.NO_CYCLE,
                new Stop(0, colour), new Stop(1, Color.TRANSPARENT)));
        gc.fillOval(left - 5, mid - 17, 22, 22);
        gc.setGlobalAlpha(in);
        gc.setStroke(colour);
        gc.setLineWidth(1.3);
        gc.strokeOval(left + 0.5, mid - 11.5, 11, 11);
        gc.setFill(colour);
        gc.fillOval(left + 4, mid - 8, 4, 4);
        gc.setFont(TEXT_FONT);
        String number = ":" + port.port();
        gc.fillText(number, left + 19, mid - 6);
        gc.setFont(SMALL);
        String more = app.ports().size() > 1 ? "  +" + (app.ports().size() - 1) : "";
        gc.setFill(port.everywhere() ? RED : MUTED);
        gc.fillText((port.everywhere() ? "open to all" : port.address()) + more, left + 19, mid + 10.5);
        return left + 19 + width(number, TEXT_FONT) + 8;
    }

    /**
     * The wire from the port to the processes. Lit and travelled while it runs, the faster the harder it
     * works; dashed when stopped; red and cut when it crashed.
     */
    private void drawWire(GraphicsContext gc, App app, double from, double to, double mid, Color colour,
                          double t, double in) {
        double y = Math.round(mid - 6) + 0.5;
        if (to - from < 10) {
            return;
        }
        gc.setLineWidth(1);
        if (app.online() == 0) {
            boolean failed = app.state() == App.State.FAILED;
            gc.setGlobalAlpha(in * (failed ? 0.8 : 0.5));
            gc.setStroke(failed ? RED : GREY);
            gc.setLineDashes(3, 4);
            gc.strokeLine(from, y, to - (failed ? 8 : 0), y);
            gc.setLineDashes((double[]) null);
            if (failed) {
                gc.strokeLine(to - 6, y - 3, to, y + 3);
                gc.strokeLine(to - 6, y + 3, to, y - 3);
            }
            return;
        }
        gc.setGlobalAlpha(in * 0.75);
        gc.setStroke(new LinearGradient(from, 0, to, 0, false, CycleMethod.NO_CYCLE,
                new Stop(0, app.exposed() ? RED : CYAN), new Stop(1, colour)));
        gc.strokeLine(from, y, to, y);
        gc.setFill(colour);
        gc.fillPolygon(new double[]{to + 4, to - 2, to - 2}, new double[]{y, y - 3.5, y + 3.5}, 3);
        if (app.ports().isEmpty()) {
            return;
        }
        double speed = 0.35 + Math.min(1.6, app.cores() * 1.2);
        for (int i = 0; i < 2; i++) {
            double along = (t * speed + i * 0.5 + app.name().length() * 0.13) % 1.0;
            double x = from + (to - from) * along;
            gc.setGlobalAlpha(in * Math.sin(along * Math.PI));
            gc.setFill(Color.WHITE);
            gc.fillOval(x - 1.6, y - 1.6, 3.2, 3.2);
        }
    }

    /**
     * One cell per process. The cell fills from the bottom with the CPU it uses, and a running one beats, a
     * little faster the harder it works. A crashed one is red with a cross; a stopped one is an empty frame.
     */
    private void drawWorkers(GraphicsContext gc, App app, double left, double mid, double room, double t,
                             double in) {
        List<AppProcess> processes = app.processes();
        double cellWidth = 16;
        double gap = 5;
        int fits = Math.max(1, (int) Math.floor((room + gap) / (cellWidth + gap)));
        int shown = Math.min(processes.size(), processes.size() > fits ? fits - 1 : fits);
        double height = 30;
        double top = mid - height / 2;
        Color colour = colourOf(app);
        for (int i = 0; i < shown; i++) {
            AppProcess process = processes.get(i);
            double x = left + i * (cellWidth + gap);
            gc.setGlobalAlpha(in);
            gc.setFill(WELL);
            gc.fillRoundRect(x, top, cellWidth, height, 4, 4);
            if (process.online()) {
                double load = Math.max(0.07, Math.min(1, process.cpu() / 100));
                double beat = 0.5 + 0.5 * Math.sin(t * (2 + process.cpu() / 20) + i * 1.3);
                gc.setGlobalAlpha(in * (0.1 + 0.18 * beat));
                gc.setFill(new RadialGradient(0, 0, x + cellWidth / 2, mid, 18, false, CycleMethod.NO_CYCLE,
                        new Stop(0, colour), new Stop(1, Color.TRANSPARENT)));
                gc.fillOval(x + cellWidth / 2 - 18, mid - 18, 36, 36);
                gc.setGlobalAlpha(in * 0.9);
                gc.setFill(colour);
                double filled = (height - 4) * load;
                gc.fillRoundRect(x + 2, top + height - 2 - filled, cellWidth - 4, filled, 2, 2);
                gc.setStroke(colour);
                gc.setGlobalAlpha(in * 0.75);
            } else if (process.failed()) {
                double blink = 0.55 + 0.45 * Math.sin(t * 1.6 + i);
                gc.setGlobalAlpha(in * blink);
                gc.setStroke(RED);
                gc.setLineWidth(1.3);
                gc.strokeLine(x + 4, mid - 4, x + cellWidth - 4, mid + 4);
                gc.strokeLine(x + 4, mid + 4, x + cellWidth - 4, mid - 4);
            } else {
                gc.setStroke(process.moving() ? CYAN : GREY);
                gc.setGlobalAlpha(in * 0.8);
                gc.setLineDashes(2, 2);
            }
            gc.setLineWidth(1);
            gc.strokeRoundRect(x + 0.5, top + 0.5, cellWidth - 1, height - 1, 4, 4);
            gc.setLineDashes((double[]) null);
        }
        if (shown < processes.size()) {
            gc.setGlobalAlpha(in);
            gc.setFont(SMALL);
            gc.setFill(DIM);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText("+" + (processes.size() - shown), left + shown * (cellWidth + gap) + 2, mid);
        }
    }

    /**
     * The picked application's processes, one a line, hung from its lane the way files hang from a folder:
     * which one, its pid, how it holds, and what it alone uses.
     */
    private void drawProcesses(GraphicsContext gc, App app, double top, double room, double w, Columns columns,
                               double alpha) {
        gc.save();
        gc.beginPath();
        gc.rect(0, top, w, Math.max(0, room));
        gc.clip();
        List<AppProcess> processes = app.processes();
        int shown = Math.min(processes.size(), MAX_ROWS);
        Color colour = colourOf(app);
        double trunk = columns.name + 3.5;
        double first = top + 4 + ROW / 2;
        gc.setGlobalAlpha(alpha);
        gc.setStroke(FAINT.brighter());
        gc.setLineWidth(1);
        gc.strokeLine(trunk, top - 4, trunk, first + (shown - 1) * ROW);
        for (int i = 0; i < shown; i++) {
            AppProcess process = processes.get(i);
            double y = first + i * ROW;
            gc.setGlobalAlpha(alpha);
            gc.setStroke(FAINT.brighter());
            gc.strokeLine(trunk, Math.round(y) + 0.5, trunk + 9, Math.round(y) + 0.5);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setFont(SMALL);
            gc.setFill(DIM);
            String which = "#" + (app.cluster() ? process.instance() : Math.max(0, process.id()));
            gc.fillText(which, trunk + 14, y);
            gc.setFill(MUTED);
            gc.fillText(process.pid() > 0 ? "pid " + process.pid() : "no pid", trunk + 46, y);
            Color state = process.online() ? GREEN : process.failed() ? RED : process.moving() ? CYAN : GREY;
            gc.setFill(state);
            gc.fillText(process.status().toLowerCase(Locale.ROOT), columns.port + 19, y);

            double bar = Math.min(columns.workersRoom, 96);
            gc.setFill(WELL);
            gc.fillRect(columns.workers, y - 2, bar, 4);
            if (process.online()) {
                gc.setFill(colour);
                gc.setGlobalAlpha(alpha * 0.9);
                gc.fillRect(columns.workers, y - 2, Math.max(1.5, bar * Math.min(1, process.cpu() / 100)), 4);
            }
            gc.setGlobalAlpha(alpha);
            gc.setFill(process.online() ? TEXT : MUTED);
            gc.fillText(process.online() ? Math.round(process.cpu()) + "%" : "—",
                    columns.cpu + (columns.cpuRoom >= 30 ? columns.cpuRoom + 10 : 0), y);
            if (columns.memoryText > 0) {
                gc.fillText(process.online() ? bytes(process.memory()) : "—", columns.memoryText, y);
            }
            gc.setFill(DIM);
            Duration up = process.uptime(now);
            gc.fillText(process.online() && !up.isZero() ? duration(up) : "—", columns.up, y);
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.setFill(process.restarts() > 20 ? RED : process.restarts() > 3 ? AMBER : MUTED);
            gc.fillText("↻ " + process.restarts(), w - LEFT, y);
        }
        if (processes.size() > shown) {
            gc.setGlobalAlpha(alpha);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setFont(SMALL);
            gc.setFill(MUTED);
            gc.fillText("and " + (processes.size() - shown) + " more", trunk + 14, first + shown * ROW);
        }
        gc.restore();
    }

    /** A word in a thin frame of its own colour. */
    private static void chip(GraphicsContext gc, String word, double x, double y, Color colour, double in) {
        double width = width(word, SMALL) + 10;
        gc.setGlobalAlpha(in * 0.12);
        gc.setFill(colour);
        gc.fillRoundRect(x, y - 7.5, width, 15, 4, 4);
        gc.setGlobalAlpha(in * 0.7);
        gc.setStroke(colour);
        gc.setLineWidth(1);
        gc.strokeRoundRect(x + 0.5, y - 7, width - 1, 14, 4, 4);
        gc.setGlobalAlpha(in);
        gc.setFill(colour);
        gc.setFont(SMALL);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(word, x + 5, y);
    }

    /** A line of the last readings, the area under it shaded, and a dot on the latest. */
    private static void spark(GraphicsContext gc, Deque<double[]> points, int which, double left, double mid,
                              double width, Color colour, double in, double ceiling) {
        if (width < 30) {
            return;
        }
        double top = mid - 13;
        double height = 26;
        gc.setGlobalAlpha(in);
        gc.setStroke(FAINT);
        gc.setLineWidth(1);
        gc.strokeLine(left, top + height + 0.5, left + width, top + height + 0.5);
        if (points.isEmpty()) {
            return;
        }
        double[] xs = new double[points.size()];
        double[] ys = new double[points.size()];
        int i = 0;
        double stepX = width / (SAMPLES - 1);
        double start = left + width - (points.size() - 1) * stepX;
        for (double[] point : points) {
            xs[i] = start + i * stepX;
            ys[i] = top + height - Math.min(1, point[which] / ceiling) * (height - 2);
            i++;
        }
        gc.setGlobalAlpha(in);
        gc.setFill(new LinearGradient(0, top, 0, top + height, false, CycleMethod.NO_CYCLE,
                new Stop(0, colour.deriveColor(0, 1, 1, 0.26)), new Stop(1, colour.deriveColor(0, 1, 1, 0.02))));
        gc.beginPath();
        gc.moveTo(xs[0], top + height);
        for (int k = 0; k < xs.length; k++) {
            gc.lineTo(xs[k], ys[k]);
        }
        gc.lineTo(xs[xs.length - 1], top + height);
        gc.closePath();
        gc.fill();
        gc.setGlobalAlpha(in * 0.95);
        gc.setStroke(colour);
        gc.setLineWidth(1.2);
        gc.strokePolyline(xs, ys, xs.length);
        gc.setFill(colour);
        gc.fillOval(xs[xs.length - 1] - 2.2, ys[ys.length - 1] - 2.2, 4.4, 4.4);
    }

    private static double maxOf(Deque<double[]> points, int which) {
        double most = 0;
        for (double[] point : points) {
            most = Math.max(most, point[which]);
        }
        return most;
    }

    /** How the application holds, in the fewest words. */
    static String stateWords(App app) {
        int up = app.online();
        int all = app.processes().size();
        AppProcess first = app.first();
        return switch (app.state()) {
            case UP -> all == 1 ? "online" : all + " online";
            case PARTLY -> up + " of " + all + " online";
            case FAILED -> first == null ? "down" : first.status().toLowerCase(Locale.ROOT);
            case MOVING -> first == null ? "moving" : first.status().toLowerCase(Locale.ROOT);
            case STOPPED -> "stopped";
        };
    }

    static String how(App app) {
        return switch (app.manager()) {
            case SUPERVISOR -> "program";
            case PM2 -> app.cluster() ? "cluster" : "fork";
        };
    }

    private String uptime(App app) {
        AppProcess first = app.first();
        if (first == null || app.online() == 0) {
            return "—";
        }
        Duration up = app.processes().stream().map(process -> process.uptime(now))
                .filter(duration -> !duration.isZero()).min(Duration::compareTo).orElse(Duration.ZERO);
        return duration(up);
    }

    static String duration(Duration up) {
        long minutes = up.toMinutes();
        if (minutes < 1) {
            return up.toSeconds() + "s";
        }
        if (minutes < 60) {
            return minutes + "m";
        }
        if (minutes < 60 * 24) {
            return up.toHours() + "h " + (minutes % 60) + "m";
        }
        return up.toDays() + "d " + (up.toHours() % 24) + "h";
    }

    static Color stateColour(App.State state) {
        return switch (state) {
            case UP -> GREEN;
            case PARTLY -> AMBER;
            case FAILED -> RED;
            case MOVING -> CYAN;
            case STOPPED -> GREY;
        };
    }

    static String bytes(long value) {
        if (value >= 1L << 30) {
            return String.format(Locale.ROOT, "%.1f GB", value / (double) (1L << 30));
        }
        if (value >= 1L << 20) {
            return Math.round(value / (double) (1L << 20)) + " MB";
        }
        if (value >= 1L << 10) {
            return Math.round(value / (double) (1L << 10)) + " KB";
        }
        return value + " B";
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
