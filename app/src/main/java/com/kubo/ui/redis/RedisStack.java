package com.kubo.ui.redis;

import com.kubo.model.redis.RedisKey;
import com.kubo.model.redis.RedisType;
import javafx.animation.AnimationTimer;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The keyspace as a stack of lit isometric plates, one per Redis data type, each carrying its keys as points of
 * light joined by a thin web, with filaments falling between the plates. It reads at a glance on a keyspace of a
 * dozen keys or of many thousands: a plate states its count, and its surface fills with light as keys arrive.
 *
 * <p>The camera pans with inertia and zooms around the pointer. Picking is by plate (choose a type) or by point
 * (choose a key); the dense list beside the stack stays the precise way to reach one key among hundreds.
 */
final class RedisStack extends Pane {

    /** A right-click on a key, in screen coordinates, so the scope can raise its menu. */
    record MenuRequest(RedisKey key, double screenX, double screenY) {
    }

    private static final Color BACKGROUND = Color.web("#0b0b0f");
    private static final Color GRID = Color.web("#1a1a22");
    private static final Color TEXT = Color.web("#e8e8ee");
    private static final Color MUTED = Color.web("#75758a");
    private static final Color RULE = Color.web("#26262f");
    private static final Color EXPIRE = Color.web("#ffb347");

    private static final RedisType[] ORDER = {RedisType.STRING, RedisType.HASH, RedisType.LIST, RedisType.SET,
            RedisType.ZSET, RedisType.STREAM, RedisType.OTHER};

    // World geometry of one plate: an isometric square seen edge-on.
    private static final double HALF_WIDTH = 188;
    private static final double DEPTH = 44;
    private static final double GAP = 132;
    /** A slab has real depth: thin enough to stack, thick enough to read as a solid. */
    private static final double THICKNESS = 26;
    private static final double LABEL_X = HALF_WIDTH + 34;

    private static final double MIN_ZOOM = 0.25;
    private static final double MAX_ZOOM = 2.6;
    private static final double FRICTION = 4.2;
    private static final int MAX_DOTS = 420;
    /** Linking every dot to its neighbours is quadratic, so the lattice is built from this many at most. */
    private static final int MAX_LINKED = 300;

    private static final Font PLATE_FONT = Font.font("Consolas", FontWeight.BOLD, 14);
    private static final Font COUNT_FONT = Font.font("Consolas", 12);
    private static final Font TINY = Font.font("Consolas", 10);

    /** One plate: a type, the keys it holds, and the dots actually drawn. */
    private static final class Plate {
        final RedisType type;
        final List<RedisKey> keys = new ArrayList<>();
        List<double[]> dots = List.of();
        /** Pairs of dot indices: the lattice over the surface. */
        List<int[]> links = List.of();
        double glow;

        Plate(RedisType type) {
            this.type = type;
        }
    }

    private final Canvas canvas = new Canvas();
    private final AnimationTimer timer;

    private final Map<RedisType, Plate> plates = new LinkedHashMap<>();
    private List<Plate> visible = List.of();
    private Map<String, Long> memory = Map.of();
    private long heaviest;

    private RedisType selectedType = RedisType.STRING;
    private String selectedKey;
    private RedisType hoveredType;
    private String hoveredKey;
    private boolean loading;
    private boolean more;

    private double camX;
    private double camY;
    private double zoom = 0.9;
    private double targetX;
    private double targetY;
    private double targetZoom = 0.9;
    private double velocityX;
    private double velocityY;
    private boolean dragging;
    private double pressX;
    private double pressY;
    private double lastX;
    private double lastY;
    private long lastNanos;
    private boolean placed;

    private double clock;
    private double lastTime = -1;
    private boolean live = true;

    private Consumer<RedisKey> onSelect = key -> {
    };
    private Consumer<RedisType> onTypeSelect = type -> {
    };
    private Consumer<MenuRequest> onMenu = request -> {
    };
    private Runnable onNeedMore = () -> {
    };

    RedisStack() {
        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinSize(0, 0);
        for (RedisType type : ORDER) {
            Plate plate = new Plate(type);
            plates.put(type, plate);
        }
        setOnMouseMoved(this::handleMove);
        setOnMouseExited(event -> {
            hoveredType = null;
            hoveredKey = null;
        });
        setOnMousePressed(this::handlePress);
        setOnMouseDragged(this::handleDrag);
        setOnMouseReleased(this::handleRelease);
        setOnMouseClicked(this::handleClick);
        setOnScroll(this::handleScroll);

        timer = new AnimationTimer() {
            private long start = -1;

            @Override
            public void handle(long now) {
                if (start < 0) {
                    start = now;
                }
                render((now - start) / 1e9);
            }
        };
        timer.start();
    }

    // Wiring

    void setOnSelect(Consumer<RedisKey> onSelect) {
        this.onSelect = onSelect;
    }

    void setOnTypeSelect(Consumer<RedisType> onTypeSelect) {
        this.onTypeSelect = onTypeSelect;
    }

    void setOnMenu(Consumer<MenuRequest> onMenu) {
        this.onMenu = onMenu;
    }

    void setOnNeedMore(Runnable onNeedMore) {
        this.onNeedMore = onNeedMore;
    }

    /**
     * Files every key onto the plate of its type and lays out the points of light.
     */
    void setKeys(List<RedisKey> keys, boolean more) {
        this.more = more;
        plates.values().forEach(plate -> plate.keys.clear());
        for (RedisKey key : keys) {
            plates.get(bucket(key.type())).keys.add(key);
        }
        List<Plate> shown = new ArrayList<>();
        for (RedisType type : ORDER) {
            Plate plate = plates.get(type);
            if (!plate.keys.isEmpty()) {
                layoutDots(plate);
                shown.add(plate);
            }
        }
        visible = List.copyOf(shown);
        if (selectedKey != null && keys.stream().noneMatch(key -> key.name().equals(selectedKey))) {
            selectedKey = null;
        }
        if (!visible.isEmpty() && visible.stream().noneMatch(plate -> plate.type == selectedType)) {
            selectedType = visible.get(0).type;
        }
        if (!placed && !visible.isEmpty()) {
            placed = true;
            fit();
        }
    }

    void setLoading(boolean loading) {
        this.loading = loading;
    }

    /**
     * Sizes each point of light by how much memory its key uses.
     */
    void setMemoryUsage(Map<String, Long> memory, boolean unusedSort) {
        this.memory = Map.copyOf(memory);
        heaviest = this.memory.values().stream().mapToLong(Long::longValue).max().orElse(0);
        plates.values().forEach(this::layoutDots);
    }

    void select(String keyName) {
        selectedKey = keyName;
    }

    void selectType(RedisType type) {
        selectedType = bucket(type);
    }

    RedisType selectedType() {
        return selectedType;
    }

    /**
     * The keys on one plate, in the order they are drawn.
     */
    List<RedisKey> keysOf(RedisType type) {
        return List.copyOf(plates.get(bucket(type)).keys);
    }

    void stop() {
        timer.stop();
    }

    void freeze(double time) {
        timer.stop();
        live = false;
        clock = time;
        camX = targetX;
        camY = targetY;
        zoom = targetZoom;
        paint();
    }

    // Camera

    void zoomIn() {
        zoomTo(targetZoom * 1.25);
    }

    void zoomOut() {
        zoomTo(targetZoom / 1.25);
    }

    /**
     * Frames the whole stack in the view.
     */
    void fit() {
        int count = Math.max(1, visible.size());
        double height = count * GAP + DEPTH * 2 + 40;
        double width = (HALF_WIDTH + 120) * 2;
        double available = Math.max(200, getHeight() < 1 ? 620 : getHeight());
        double availableWidth = Math.max(200, getWidth() < 1 ? 900 : getWidth());
        targetZoom = clamp(Math.min(available / height, availableWidth / width), MIN_ZOOM, 1.35);
        // Leave room on the right for the annotations, which do not shrink with the zoom.
        targetX = 78 / Math.max(0.35, targetZoom);
        targetY = (count - 1) * GAP / 2;
        velocityX = 0;
        velocityY = 0;
    }

    private void zoomTo(double value) {
        targetZoom = clamp(value, MIN_ZOOM, MAX_ZOOM);
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
    }

    // World mapping

    private double plateY(int index) {
        return index * GAP;
    }

    private double screenX(double worldX) {
        return getWidth() / 2 + (worldX - camX) * zoom;
    }

    private double screenY(double worldY) {
        return getHeight() / 2 + (worldY - camY) * zoom;
    }

    private double worldX(double screenX) {
        return (screenX - getWidth() / 2) / zoom + camX;
    }

    private double worldY(double screenY) {
        return (screenY - getHeight() / 2) / zoom + camY;
    }

    /**
     * Spreads a plate's keys over its surface, deterministically so they do not jump between refreshes.
     */
    private void layoutDots(Plate plate) {
        int count = Math.min(MAX_DOTS, plate.keys.size());
        List<double[]> dots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            RedisKey key = plate.keys.get(i);
            double a = hash(key.name()) * Math.PI * 2;
            double r = Math.sqrt(hash(key.name() + "#r"));
            // A point inside the diamond: interpolate along both isometric axes.
            double u = Math.cos(a) * r;
            double v = Math.sin(a) * r;
            double x = (u + v) * HALF_WIDTH * 0.46;
            double y = (v - u) * DEPTH * 0.46;
            double size = 1.9;
            Long bytes = memory.get(key.name());
            if (bytes != null && heaviest > 0) {
                size = 1.7 + 4.3 * Math.sqrt(bytes / (double) heaviest);
            }
            dots.add(new double[]{x, y, size, i});
        }
        plate.dots = List.copyOf(dots);
        plate.links = weave(dots);
    }

    /**
     * Joins each key to the two nearest others, which is what turns a scatter of points into a lattice. The
     * vertical axis is stretched first, because the surface is drawn squashed and true neighbours would otherwise
     * all be found sideways.
     */
    private static List<int[]> weave(List<double[]> dots) {
        int limit = Math.min(dots.size(), MAX_LINKED);
        List<int[]> links = new ArrayList<>();
        double stretch = HALF_WIDTH / DEPTH;
        for (int i = 0; i < limit; i++) {
            int first = -1;
            int second = -1;
            double firstDistance = Double.MAX_VALUE;
            double secondDistance = Double.MAX_VALUE;
            for (int j = 0; j < limit; j++) {
                if (j == i) {
                    continue;
                }
                double dx = dots.get(i)[0] - dots.get(j)[0];
                double dy = (dots.get(i)[1] - dots.get(j)[1]) * stretch;
                double distance = dx * dx + dy * dy;
                if (distance < firstDistance) {
                    secondDistance = firstDistance;
                    second = first;
                    firstDistance = distance;
                    first = j;
                } else if (distance < secondDistance) {
                    secondDistance = distance;
                    second = j;
                }
            }
            if (first > i) {
                links.add(new int[]{i, first});
            }
            if (second > i) {
                links.add(new int[]{i, second});
            }
        }
        return List.copyOf(links);
    }

    private static RedisType bucket(RedisType type) {
        return type == RedisType.NONE || type == null ? RedisType.OTHER : type;
    }

    // Input

    private void handleMove(MouseEvent event) {
        hoveredType = null;
        hoveredKey = null;
        RedisKey key = keyAt(event.getX(), event.getY());
        if (key != null) {
            hoveredKey = key.name();
            hoveredType = bucket(key.type());
        } else {
            hoveredType = plateAt(event.getX(), event.getY());
        }
        setCursor(hoveredType != null ? Cursor.HAND : Cursor.DEFAULT);
    }

    private void handlePress(MouseEvent event) {
        pressX = event.getX();
        pressY = event.getY();
        lastX = event.getX();
        lastY = event.getY();
        lastNanos = System.nanoTime();
        velocityX = 0;
        velocityY = 0;
        dragging = false;
    }

    private void handleDrag(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        dragging = true;
        targetX -= (event.getX() - lastX) / zoom;
        targetY -= (event.getY() - lastY) / zoom;
        camX = targetX;
        camY = targetY;
        long now = System.nanoTime();
        double seconds = (now - lastNanos) / 1e9;
        if (seconds > 0.004) {
            velocityX = clamp(-(event.getX() - lastX) / seconds / zoom, -5000, 5000);
            velocityY = clamp(-(event.getY() - lastY) / seconds / zoom, -5000, 5000);
            lastNanos = now;
        }
        lastX = event.getX();
        lastY = event.getY();
        setCursor(Cursor.CLOSED_HAND);
    }

    private void handleRelease(MouseEvent event) {
        if (dragging && (System.nanoTime() - lastNanos) / 1e9 > 0.08) {
            velocityX = 0;
            velocityY = 0;
        }
        dragging = false;
        handleMove(event);
    }

    private void handleClick(MouseEvent event) {
        if (Math.hypot(event.getX() - pressX, event.getY() - pressY) > 4) {
            return;
        }
        RedisKey key = keyAt(event.getX(), event.getY());
        if (event.getButton() == MouseButton.SECONDARY) {
            if (key != null) {
                selectedKey = key.name();
                onSelect.accept(key);
                onMenu.accept(new MenuRequest(key, event.getScreenX(), event.getScreenY()));
            }
            return;
        }
        if (key != null) {
            selectedKey = key.name();
            selectedType = bucket(key.type());
            onTypeSelect.accept(selectedType);
            onSelect.accept(key);
            return;
        }
        RedisType plate = plateAt(event.getX(), event.getY());
        if (plate != null) {
            selectedType = plate;
            onTypeSelect.accept(plate);
        }
    }

    private void handleScroll(ScrollEvent event) {
        if (event.isControlDown()) {
            double before = targetZoom;
            zoomTo(targetZoom * (event.getDeltaY() > 0 ? 1.1 : 1 / 1.1));
            // Keep the point under the pointer where it is.
            double wx = worldX(event.getX());
            double wy = worldY(event.getY());
            double factor = 1 - before / targetZoom;
            targetX += (wx - targetX) * factor;
            targetY += (wy - targetY) * factor;
        } else {
            velocityY = clamp(velocityY - event.getDeltaY() * 7 / zoom, -5000, 5000);
            velocityX = clamp(velocityX - event.getDeltaX() * 7 / zoom, -5000, 5000);
            // Looking around is the moment to fetch the next page.
            if (more && !loading) {
                onNeedMore.run();
            }
        }
        event.consume();
    }

    private RedisType plateAt(double screenX, double screenY) {
        for (int i = 0; i < visible.size(); i++) {
            Plate plate = visible.get(i);
            double cx = screenX(0);
            double cy = screenY(plateY(i));
            double dx = Math.abs(screenX - cx) / (HALF_WIDTH * zoom);
            double dy = Math.abs(screenY - cy) / (DEPTH * zoom);
            if (dx + dy <= 1.05) {
                return plate.type;
            }
            // The label to the right belongs to the plate too.
            if (screenX > cx + LABEL_X * zoom && screenX < cx + (LABEL_X + 210) * zoom
                    && Math.abs(screenY - cy) < 22 * zoom) {
                return plate.type;
            }
        }
        return null;
    }

    private RedisKey keyAt(double screenX, double screenY) {
        for (int i = 0; i < visible.size(); i++) {
            Plate plate = visible.get(i);
            double baseY = plateY(i);
            for (int d = plate.dots.size() - 1; d >= 0; d--) {
                double[] dot = plate.dots.get(d);
                double x = screenX(dot[0]);
                double y = screenY(baseY + dot[1]);
                double radius = Math.max(4, dot[2] * zoom + 2.5);
                if (Math.hypot(screenX - x, screenY - y) <= radius) {
                    return plate.keys.get((int) dot[3]);
                }
            }
        }
        return null;
    }

    // Drawing

    private void render(double time) {
        double dt = lastTime < 0 ? 0 : Math.min(0.1, time - lastTime);
        lastTime = time;
        clock = time;
        if (!dragging && (Math.abs(velocityX) > 2 || Math.abs(velocityY) > 2)) {
            targetX += velocityX * dt;
            targetY += velocityY * dt;
            double decay = Math.exp(-dt * FRICTION);
            velocityX *= decay;
            velocityY *= decay;
        }
        camX += (targetX - camX) * Math.min(1, dt * 9);
        camY += (targetY - camY) * Math.min(1, dt * 9);
        zoom += (targetZoom - zoom) * Math.min(1, dt * 9);
        for (Plate plate : visible) {
            double wanted = plate.type == selectedType ? 1 : plate.type == hoveredType ? 0.6 : 0;
            plate.glow += (wanted - plate.glow) * Math.min(1, dt * 8);
        }
        paint();
    }

    private void paint() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width < 1 || height < 1) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setGlobalAlpha(1);
        gc.setFill(BACKGROUND);
        gc.fillRect(0, 0, width, height);
        drawGrid(gc, width, height);

        if (visible.isEmpty()) {
            drawEmpty(gc, width, height);
            return;
        }

        // Filaments hanging from the lowest plate, and webs between the plates.
        for (int i = 0; i < visible.size() - 1; i++) {
            drawFilaments(gc, visible.get(i), i, visible.get(i + 1), i + 1);
        }
        drawSkirt(gc, visible.get(visible.size() - 1), visible.size() - 1);

        for (int i = 0; i < visible.size(); i++) {
            drawPlate(gc, visible.get(i), i);
        }
        drawFooter(gc, width, height);
    }

    private void drawGrid(GraphicsContext gc, double width, double height) {
        double step = 26;
        gc.setFill(GRID);
        gc.setGlobalAlpha(0.5);
        double offsetX = -(camX * zoom) % step;
        double offsetY = -(camY * zoom) % step;
        for (double x = offsetX; x < width; x += step) {
            for (double y = offsetY; y < height; y += step) {
                gc.fillRect(x, y, 1, 1);
            }
        }
        gc.setGlobalAlpha(1);
    }

    private void drawEmpty(GraphicsContext gc, double width, double height) {
        gc.setFill(MUTED);
        gc.setFont(PLATE_FONT);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText(loading ? "SCANNING THE KEYSPACE…" : "NO KEYS MATCH THIS PATTERN", width / 2, height / 2);
        if (loading) {
            double r = 26;
            gc.setStroke(RedisPalette.STRING);
            gc.setLineWidth(1.4);
            gc.strokeArc(width / 2 - r, height / 2 - 52 - r, r * 2, r * 2,
                    -(clock * 240) % 360, 90, javafx.scene.shape.ArcType.OPEN);
        }
    }

    /**
     * The plate itself: a lit isometric square, its keys as points of light, and its name on a leader line.
     */
    private void drawPlate(GraphicsContext gc, Plate plate, int index) {
        double cx = screenX(0);
        double cy = screenY(plateY(index));
        double w = HALF_WIDTH * zoom;
        double d = DEPTH * zoom;
        if (cy < -d * 3 - 60 || cy > canvas.getHeight() + d * 3 + 60) {
            return;
        }
        Color color = RedisPalette.color(plate.type);
        boolean chosen = plate.type == selectedType;
        double lift = plate.glow * 6 * zoom + (live ? Math.sin(clock * 1.3 + index) * 1.5 * zoom : 0);
        cy -= lift;

        double[] xs = {cx, cx + w, cx, cx - w};
        double[] ys = {cy - d, cy, cy + d, cy};

        // The two side faces. Light falls from the right, so the right face is lit and the left one is in
        // shadow: that difference alone is what makes a slab read as a solid rather than a sheet.
        double t = THICKNESS * zoom;
        gc.setGlobalAlpha(1);
        gc.setFill(color.deriveColor(0, 0.9, 0.30, 0.92));
        gc.fillPolygon(new double[]{cx - w, cx, cx, cx - w}, new double[]{cy, cy + d, cy + d + t, cy + t}, 4);
        gc.setFill(color.deriveColor(0, 1, 0.62, 0.92));
        gc.fillPolygon(new double[]{cx, cx + w, cx + w, cx}, new double[]{cy + d, cy, cy + t, cy + d + t}, 4);

        // The bottom rim of the slab, lit, which is where the light gathers on a real edge.
        gc.setStroke(color.deriveColor(0, 1, 1, 0.55 + 0.35 * plate.glow));
        gc.setLineWidth(1.4);
        gc.strokeLine(cx - w, cy + t, cx, cy + d + t);
        gc.strokeLine(cx, cy + d + t, cx + w, cy + t);
        // The three corners you can see down the thickness.
        gc.setStroke(color.deriveColor(0, 1, 1, 0.35));
        gc.setLineWidth(1);
        gc.strokeLine(cx - w, cy, cx - w, cy + t);
        gc.strokeLine(cx + w, cy, cx + w, cy + t);
        gc.strokeLine(cx, cy + d, cx, cy + d + t);

        // Surface: dark glass, lit from the back edge toward the viewer.
        gc.setFill(Color.web("#0c0c13").deriveColor(0, 1, 1, 0.96));
        gc.fillPolygon(xs, ys, 4);
        gc.setFill(new javafx.scene.paint.LinearGradient(cx, cy - d, cx, cy + d, false,
                javafx.scene.paint.CycleMethod.NO_CYCLE,
                new javafx.scene.paint.Stop(0, color.deriveColor(0, 1, 1, 0.16 + 0.12 * plate.glow)),
                new javafx.scene.paint.Stop(1, color.deriveColor(0, 1, 1, 0.02))));
        gc.fillPolygon(xs, ys, 4);

        // Glowing rim, brighter when the plate is the chosen one.
        for (int pass = 4; pass >= 1; pass--) {
            gc.setStroke(color.deriveColor(0, 1, 1, (chosen ? 0.15 : 0.08) * pass + 0.10 * plate.glow));
            gc.setLineWidth(pass * 2.4);
            gc.strokePolygon(xs, ys, 4);
        }
        gc.setStroke(color.deriveColor(0, 1, 1, 0.85 + 0.15 * plate.glow));
        gc.setLineWidth(1.3);
        gc.strokePolygon(xs, ys, 4);

        // A band of light crossing the surface: it is what makes a plate of five hundred keys feel alive, and it
        // runs faster while the keyspace is being scanned.
        double sweep = live ? ((clock * (loading ? 0.55 : 0.22) + index * 0.21) % 1.7) - 0.35 : 0.5;

        // The lattice over the surface, then the keys themselves.
        drawWeb(gc, plate, cx, cy, color, sweep);
        double baseY = plateY(index);
        for (double[] dot : plate.dots) {
            double x = screenX(dot[0]);
            double y = screenY(baseY + dot[1]) - lift;
            RedisKey key = plate.keys.get((int) dot[3]);
            boolean isSelected = key.name().equals(selectedKey);
            boolean isHovered = key.name().equals(hoveredKey);
            double size = Math.max(1.4, dot[2] * zoom);
            if (isSelected || isHovered) {
                size = Math.max(size, 3.4 * zoom);
            }
            // How close the sweep is to this key, plus a breath of its own so no two pulse together.
            double across = (dot[0] / HALF_WIDTH + 1) / 2;
            double lit = live ? Math.exp(-Math.pow((across - sweep) * 3.4, 2)) : 0;
            double breath = live ? 0.5 + 0.5 * Math.sin(clock * 1.7 + hash(key.name()) * 6.3) : 1;
            double halo = size * (2.1 + 2.6 * lit);
            gc.setFill(color.deriveColor(0, 1, 1, 0.18 + 0.36 * lit));
            gc.fillOval(x - halo, y - halo, halo * 2, halo * 2);
            gc.setFill(key.expires() ? EXPIRE : color);
            gc.setGlobalAlpha(isSelected ? 1 : Math.min(1, 0.55 + 0.25 * breath + 0.45 * lit));
            double grown = size * (1 + 0.45 * lit);
            gc.fillOval(x - grown, y - grown, grown * 2, grown * 2);
            gc.setGlobalAlpha(1);
            if (isSelected) {
                gc.setStroke(TEXT);
                gc.setLineWidth(1.1);
                double r = size + 5 * zoom;
                gc.strokeOval(x - r, y - r, r * 2, r * 2);
                gc.setFill(TEXT);
                gc.setFont(TINY);
                gc.setTextAlign(TextAlignment.CENTER);
                gc.setTextBaseline(VPos.BOTTOM);
                gc.fillText(clip(key.name(), 26), x, y - r - 5);
            } else if (isHovered) {
                gc.setFill(MUTED);
                gc.setFont(TINY);
                gc.setTextAlign(TextAlignment.CENTER);
                gc.setTextBaseline(VPos.BOTTOM);
                gc.fillText(clip(key.name(), 26), x, y - size - 8);
            }
        }

        drawPlateLabel(gc, plate, cx + w, cy, color, chosen);
    }

    /**
     * A sparse web across the plate, in the spirit of a lit mesh rather than a full graph.
     */
    private void drawWeb(GraphicsContext gc, Plate plate, double cx, double cy, Color color, double sweep) {
        if (plate.links.isEmpty() || zoom < 0.3) {
            return;
        }
        gc.setStroke(color);
        gc.setLineWidth(0.7);
        for (int[] link : plate.links) {
            double[] a = plate.dots.get(link[0]);
            double[] b = plate.dots.get(link[1]);
            double across = ((a[0] + b[0]) / 2 / HALF_WIDTH + 1) / 2;
            double lit = live ? Math.exp(-Math.pow((across - sweep) * 3.4, 2)) : 0;
            gc.setGlobalAlpha(0.17 + 0.38 * lit);
            gc.strokeLine(cx + a[0] * zoom, cy + a[1] * zoom, cx + b[0] * zoom, cy + b[1] * zoom);
        }
        gc.setGlobalAlpha(1);
    }

    /**
     * The name, count and state of a plate, on a leader line to its right.
     */
    private void drawPlateLabel(GraphicsContext gc, Plate plate, double edgeX, double cy, Color color, boolean chosen) {
        // The annotation keeps its own size whatever the zoom, the way a callout on a drawing stays legible.
        double x = edgeX + 26;
        gc.setStroke(color.deriveColor(0, 1, 1, chosen ? 0.85 : 0.35));
        gc.setLineWidth(1);
        gc.strokeLine(edgeX + 5, cy, x - 8, cy);
        if (chosen) {
            gc.setFill(color);
            gc.fillOval(x - 13, cy - 2.5, 5, 5);
        }

        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFont(PLATE_FONT);
        gc.setFill(chosen ? TEXT : Color.web("#a9a9b8"));
        gc.fillText(plateName(plate), x, cy - 9);

        gc.setFont(COUNT_FONT);
        gc.setFill(color);
        gc.fillText(plate.keys.size() + (plate.keys.size() == 1 ? " KEY" : " KEYS"), x, cy + 9);

        if (plate.keys.size() > plate.dots.size()) {
            gc.setFont(TINY);
            gc.setFill(MUTED);
            gc.fillText(plate.dots.size() + " SHOWN", x, cy + 24);
        }

        long expiring = plate.keys.stream().filter(RedisKey::expires).count();
        if (expiring > 0) {
            gc.setFill(EXPIRE);
            gc.setFont(TINY);
            gc.fillText(expiring + " TTL", x + 82, cy + 9);
        }
    }

    /**
     * Light filaments falling from one plate to the next, as in a stack of lit layers.
     */
    private void drawFilaments(GraphicsContext gc, Plate upper, int upperIndex, Plate lower, int lowerIndex) {
        if (zoom < 0.3) {
            return;
        }
        double cx = screenX(0);
        double topY = screenY(plateY(upperIndex));
        double bottomY = screenY(plateY(lowerIndex));
        Color color = RedisPalette.color(upper.type);
        gc.setStroke(color);
        gc.setLineWidth(0.9);
        int strands = 9;
        for (int i = 0; i < strands; i++) {
            double fraction = (i + 0.5) / strands;
            double x1 = cx + (fraction * 2 - 1) * HALF_WIDTH * 0.82 * zoom;
            double sag = Math.sin(fraction * Math.PI) * 16 * zoom;
            double x2 = cx + (fraction * 2 - 1) * HALF_WIDTH * 0.6 * zoom;
            gc.setGlobalAlpha(0.10 + 0.05 * Math.sin(clock + i));
            gc.beginPath();
            gc.moveTo(x1, topY + DEPTH * 0.35 * zoom);
            gc.bezierCurveTo(x1, topY + sag + 30 * zoom, x2, bottomY - sag - 30 * zoom, x2, bottomY - DEPTH * 0.35 * zoom);
            gc.stroke();
        }
        gc.setGlobalAlpha(1);
    }

    /**
     * The hanging web below the lowest plate, which gives the stack its footing.
     */
    private void drawSkirt(GraphicsContext gc, Plate plate, int index) {
        if (zoom < 0.3) {
            return;
        }
        double cx = screenX(0);
        double cy = screenY(plateY(index));
        Color color = RedisPalette.color(plate.type);
        gc.setStroke(color);
        gc.setLineWidth(0.8);
        int strands = 13;
        double drop = 92 * zoom;
        for (int i = 0; i < strands; i++) {
            double fraction = (i + 0.5) / strands;
            double x = cx + (fraction * 2 - 1) * HALF_WIDTH * 0.92 * zoom;
            double depth = drop * Math.sin(fraction * Math.PI);
            gc.setGlobalAlpha(0.09);
            gc.beginPath();
            gc.moveTo(x, cy + DEPTH * 0.4 * zoom);
            gc.bezierCurveTo(x, cy + depth * 0.7, cx, cy + depth, cx, cy + depth);
            gc.stroke();
        }
        gc.setGlobalAlpha(1);
    }

    private void drawFooter(GraphicsContext gc, double width, double height) {
        gc.setFont(TINY);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BOTTOM);
        gc.setFill(MUTED);
        String hint = "DRAG TO PAN  ·  CTRL+WHEEL TO ZOOM  ·  CLICK A PLATE TO FILTER  ·  RIGHT-CLICK A KEY";
        gc.fillText(hint, 22, height - 14);

        if (loading || more) {
            gc.setFill(loading ? RedisPalette.STRING : MUTED);
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.fillText(loading ? "SCANNING…" : "MORE KEYS AVAILABLE", width - 22, height - 14);
        }
        gc.setStroke(RULE);
        gc.setLineWidth(1);
        gc.strokeLine(22, height - 28, width - 22, height - 28);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFill(MUTED);
        gc.fillText(Math.round(zoom * 100) + "%", width - 22, height - 34);
    }

    // Helpers

    /**
     * A plate of unknown values is named after the module type it holds, when its keys agree on one, rather than
     * being left as the unhelpful "other".
     */
    private static String plateName(Plate plate) {
        if (plate.type != RedisType.OTHER) {
            return plate.type.name();
        }
        String shared = plate.keys.isEmpty() ? "" : plate.keys.get(0).typeName();
        boolean agreed = plate.keys.stream().allMatch(key -> key.typeName().equals(shared));
        return agreed && !shared.isBlank() ? shared.toUpperCase(Locale.ROOT) : "OTHER";
    }

    private static String clip(String text, int max) {
        String clean = text.replace('\n', ' ');
        return clean.length() > max ? clean.substring(0, max - 1) + "…" : clean;
    }

    private static double hash(String text) {
        int h = 0;
        for (int i = 0; i < text.length(); i++) {
            h = h * 31 + text.charAt(i);
        }
        return ((h ^ (h >>> 15)) & 0xffff) / 65535.0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
