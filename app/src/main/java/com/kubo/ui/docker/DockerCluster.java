package com.kubo.ui.docker;

import com.kubo.model.docker.ContainerState;
import com.kubo.model.docker.ContainerStats;
import com.kubo.model.docker.DockerContainer;
import com.kubo.model.docker.Health;
import com.kubo.model.docker.PortMapping;
import com.kubo.model.server.ListeningPort.Exposure;
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
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The Docker cluster as a living map: a central daemon node on the left, one node per container fanning out to the
 * right, grouped into their Compose stacks. Running containers glow and stream particles toward the daemon; stopped
 * ones dim to red. Each node carries its name, image, state, health and — when running — CPU and memory gauges.
 *
 * <p>The column of stacks scrolls with inertia. Nodes being read or acted on spin a local mark, since Docker calls
 * take time.
 */
final class DockerCluster extends Pane {

    private static final Color BACKGROUND = Color.web("#050508");
    private static final Color DAEMON = Color.web("#35e8ff");
    private static final Color TEXT = Color.web("#e6e6ec");
    private static final Color MUTED = Color.web("#6e6e7d");
    private static final Color FAINT = Color.web("#1c1c24");
    private static final Color RUNNING = Color.web("#35e0a1");
    private static final Color AMBER = Color.web("#ffb347");
    private static final Color PAUSED = Color.web("#35e8ff");
    private static final Color STOPPED = Color.web("#ff5a6e");
    private static final Color IDLE = Color.web("#8a8fb0");
    private static final Color PUBLIC = Color.web("#ff2d55");
    private static final Color INTERNAL = Color.web("#8a4dff");
    private static final Color STACK = Color.web("#5b6fd6");

    private static final double ROW = 80;
    private static final double HEADER = 34;
    private static final double TOP = 120;
    private static final double NODE_X = 0.58;
    private static final double FRICTION = 4.5;
    private static final Font NAME_FONT = Font.font("Segoe UI", FontWeight.SEMI_BOLD, 13);
    private static final Font STACK_FONT = Font.font("Segoe UI", FontWeight.SEMI_BOLD, 10.5);
    private static final Font SMALL = Font.font("Consolas", 10);
    private static final Font TINY = Font.font("Segoe UI", 9);

    /**
     * A drawn row: either a stack header or a container.
     */
    private record Row(String stack, DockerContainer container, double y) {
        boolean isHeader() {
            return container == null;
        }
    }

    private final Canvas canvas = new Canvas();
    private final AnimationTimer timer;
    private List<DockerContainer> containers = List.of();
    private List<Row> rows = List.of();
    private String version = "";
    private String selectedId;
    private String hoveredId;
    private String hoveredStack;

    private double scroll;
    private double velocity;
    private boolean dragging;
    private double pressY;
    private double pressScroll;
    private long lastDragNanos;
    private double lastDragY;

    private double clock;
    private double lastTime = -1;
    private boolean live = true;
    private boolean loading;
    private double flashStart = -100;
    private Set<String> busy = Set.of();

    private Consumer<DockerContainer> onSelect = container -> {
    };
    private Consumer<DockerContainer> onOpen = container -> {
    };
    private Consumer<String> onRestartStack = stack -> {
    };

    DockerCluster() {
        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinSize(0, 0);
        setOnMouseMoved(this::handleMove);
        setOnMouseExited(event -> {
            hoveredId = null;
            hoveredStack = null;
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

    void setOnSelect(Consumer<DockerContainer> onSelect) {
        this.onSelect = onSelect;
    }

    /**
     * Called with a double-clicked container, to open its logs.
     */
    void setOnOpen(Consumer<DockerContainer> onOpen) {
        this.onOpen = onOpen;
    }

    /**
     * Called with a stack name when its restart glyph is clicked.
     */
    void setOnRestartStack(Consumer<String> onRestartStack) {
        this.onRestartStack = onRestartStack;
    }

    void setVersion(String version) {
        this.version = version;
    }

    void setContainers(List<DockerContainer> containers) {
        this.containers = List.copyOf(containers);
        if (selectedId != null && containers.stream().noneMatch(c -> c.id().equals(selectedId))) {
            selectedId = null;
        }
    }

    void select(String id) {
        selectedId = id;
    }

    /**
     * The whole list is being read again.
     */
    void setLoading(boolean loading) {
        this.loading = loading;
    }

    /**
     * Containers with a read or an action in flight: they spin a local mark.
     */
    void setBusy(Set<String> ids) {
        this.busy = Set.copyOf(ids);
    }

    /**
     * A one-off scan sweep across the map, to show a manual refresh is happening.
     */
    void flash() {
        flashStart = clock;
    }

    void stop() {
        timer.stop();
    }

    /**
     * Freezes the animation on a given time; for previews.
     */
    void freeze(double time) {
        timer.stop();
        live = false;
        clock = time;
        layoutRows();
        paint();
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
    }

    // Layout

    /**
     * Groups the containers into stacks and lays out header and container rows top to bottom.
     */
    private void layoutRows() {
        Map<String, List<DockerContainer>> stacks = new LinkedHashMap<>();
        for (DockerContainer container : containers) {
            String key = container.inStack() ? container.project() : "";
            stacks.computeIfAbsent(key, k -> new ArrayList<>()).add(container);
        }
        List<Row> laid = new ArrayList<>();
        double y = TOP - scroll;
        for (Map.Entry<String, List<DockerContainer>> stack : stacks.entrySet()) {
            boolean grouped = !stack.getKey().isEmpty();
            if (grouped) {
                laid.add(new Row(stack.getKey(), null, y));
                y += HEADER;
            }
            for (DockerContainer container : stack.getValue()) {
                laid.add(new Row(grouped ? stack.getKey() : "", container, y));
                y += ROW;
            }
            if (grouped) {
                y += 10;
            }
        }
        rows = laid;
    }

    private double contentHeight() {
        double height = 0;
        Map<String, Long> stacks = new LinkedHashMap<>();
        for (DockerContainer c : containers) {
            stacks.merge(c.inStack() ? c.project() : "", 1L, Long::sum);
        }
        for (Map.Entry<String, Long> stack : stacks.entrySet()) {
            if (!stack.getKey().isEmpty()) {
                height += HEADER + 10;
            }
            height += stack.getValue() * ROW;
        }
        return TOP + height + 40;
    }

    private double nodeX(double width) {
        return width * NODE_X;
    }

    private double centerX(double width) {
        return width * 0.19;
    }

    private double centerY(double height) {
        return height * 0.42;
    }

    // Input

    private void handleMove(MouseEvent event) {
        hoveredId = null;
        hoveredStack = null;
        Row row = rowAt(event.getX(), event.getY());
        if (row != null) {
            if (row.isHeader()) {
                hoveredStack = row.stack();
            } else {
                hoveredId = row.container().id();
            }
        }
        setCursor(hoveredId != null || hoveredStack != null ? Cursor.HAND : Cursor.DEFAULT);
    }

    private void handlePress(MouseEvent event) {
        pressY = event.getY();
        pressScroll = scroll;
        lastDragY = event.getY();
        lastDragNanos = System.nanoTime();
        velocity = 0;
        dragging = false;
    }

    private void handleDrag(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        dragging = true;
        setScroll(pressScroll - (event.getY() - pressY));
        long now = System.nanoTime();
        double seconds = (now - lastDragNanos) / 1e9;
        if (seconds > 0.004) {
            velocity = clamp(velocity * 0.4 - (event.getY() - lastDragY) / seconds * 0.6, -6000, 6000);
            lastDragY = event.getY();
            lastDragNanos = now;
        }
        setCursor(Cursor.CLOSED_HAND);
    }

    private void handleRelease(MouseEvent event) {
        if (dragging && (System.nanoTime() - lastDragNanos) / 1e9 > 0.08) {
            velocity = 0;
        }
        boolean wasDrag = dragging;
        dragging = false;
        handleMove(event);
        if (wasDrag) {
            // Swallow the click that would follow a pan.
            event.consume();
        }
    }

    private void handleClick(MouseEvent event) {
        if (Math.abs(event.getY() - pressY) > 4) {
            return;
        }
        Row row = rowAt(event.getX(), event.getY());
        if (row == null) {
            return;
        }
        if (row.isHeader()) {
            // The restart glyph sits at the right end of the header.
            if (event.getX() > getWidth() - 60) {
                onRestartStack.accept(row.stack());
            }
            return;
        }
        selectedId = row.container().id();
        onSelect.accept(row.container());
        if (event.getClickCount() >= 2) {
            onOpen.accept(row.container());
        }
    }

    private void handleScroll(ScrollEvent event) {
        velocity = clamp(velocity - event.getDeltaY() * 6, -6000, 6000);
        event.consume();
    }

    private Row rowAt(double x, double y) {
        double width = getWidth();
        double cx = nodeX(width);
        for (Row row : rows) {
            if (row.isHeader()) {
                if (y >= row.y() && y <= row.y() + HEADER && x < width - 20) {
                    return row;
                }
            } else if (Math.abs(y - row.y() - ROW / 2) < ROW / 2 - 2
                    && (Math.hypot(x - cx, y - row.y() - ROW / 2) <= 34 || x > cx - 40)) {
                return row;
            }
        }
        return null;
    }

    private void setScroll(double value) {
        double max = Math.max(0, contentHeight() - getHeight());
        scroll = clamp(value, 0, max);
    }

    // Drawing

    private void render(double time) {
        double dt = lastTime < 0 ? 0 : Math.min(0.1, time - lastTime);
        lastTime = time;
        clock = time;
        if (!dragging && Math.abs(velocity) > 2) {
            setScroll(scroll + velocity * dt);
            velocity *= Math.exp(-dt * FRICTION);
        }
        layoutRows();
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
        gc.setFill(new RadialGradient(0, 0, centerX(width), centerY(height), height * 0.9, false, CycleMethod.NO_CYCLE,
                new Stop(0, DAEMON.deriveColor(0, 1, 1, 0.10)), new Stop(1, Color.TRANSPARENT)));
        gc.fillRect(0, 0, width, height);

        double cx = centerX(width);
        double cy = centerY(height);
        double nx = nodeX(width);

        if (containers.isEmpty()) {
            drawDaemon(gc, cx, cy);
            gc.setFill(MUTED);
            gc.setFont(NAME_FONT);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setTextBaseline(VPos.CENTER);
            gc.fillText(loading ? "READING CONTAINERS…" : "NO CONTAINERS ON THIS SERVER", width / 2, cy + 90);
            return;
        }

        // Stack bands behind everything.
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.isHeader()) {
                drawStackBand(gc, i, width, height);
            }
        }
        for (Row row : rows) {
            if (!row.isHeader()) {
                drawFiber(gc, cx, cy, nx, row.y() + ROW / 2, row.container());
            }
        }
        drawDaemon(gc, cx, cy);
        for (Row row : rows) {
            if (row.isHeader()) {
                drawStackHeader(gc, row, width);
            } else {
                drawContainer(gc, row, nx, width);
            }
        }
        // Fade at the top edge under the header area.
        gc.setFill(new javafx.scene.paint.LinearGradient(0, TOP - 30, 0, TOP + 10, false, CycleMethod.NO_CYCLE,
                new Stop(0, BACKGROUND), new Stop(1, Color.TRANSPARENT)));
        gc.fillRect(nx - 50, TOP - 30, width, 40);

        drawScan(gc, width, height);
    }

    /**
     * The refresh sweep: a bright line running down the map with a soft glow, fading as it goes.
     */
    private void drawScan(GraphicsContext gc, double width, double height) {
        double t = (clock - flashStart) / 0.9;
        if (t < 0 || t > 1) {
            return;
        }
        double y = t * height;
        double fade = 1 - t;
        gc.setGlobalAlpha(fade * 0.5);
        gc.setFill(new javafx.scene.paint.LinearGradient(0, y - 60, 0, y, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.TRANSPARENT), new Stop(1, DAEMON.deriveColor(0, 1, 1, 0.35))));
        gc.fillRect(0, y - 60, width, 60);
        gc.setStroke(DAEMON);
        gc.setLineWidth(1.5);
        gc.setGlobalAlpha(fade * 0.9);
        gc.strokeLine(0, y, width, y);
        gc.setGlobalAlpha(1);
    }

    private void drawDaemon(GraphicsContext gc, double cx, double cy) {
        double pulse = 1 + 0.05 * Math.sin(clock * 2);
        for (int r = 3; r >= 1; r--) {
            gc.setFill(DAEMON.deriveColor(0, 1, 1, 0.12 * r));
            double radius = 26 * pulse * r / 2.0;
            gc.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
        }
        gc.setFill(BACKGROUND);
        gc.fillOval(cx - 24, cy - 24, 48, 48);
        gc.setStroke(DAEMON);
        gc.setLineWidth(1.4);
        gc.setGlobalAlpha(0.9);
        gc.strokeOval(cx - 24, cy - 24, 48, 48);
        double spin = loading ? clock * 3 : clock * 0.4;
        for (int i = 0; i < 24; i++) {
            double angle = spin + i * Math.PI / 12;
            double inner = 28;
            double outer = 28 + (i % 2 == 0 ? 5 : 2) + (loading ? 2 * Math.sin(clock * 5 + i) : 0);
            gc.strokeLine(cx + Math.cos(angle) * inner, cy + Math.sin(angle) * inner,
                    cx + Math.cos(angle) * outer, cy + Math.sin(angle) * outer);
        }
        gc.setGlobalAlpha(1);
        gc.setFill(DAEMON);
        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText("DOCKER", cx, cy - 2);
        gc.setFill(MUTED);
        gc.setFont(TINY);
        gc.fillText(version.isEmpty() ? "DAEMON" : "v" + version, cx, cy + 11);

        long running = containers.stream().filter(c -> c.state().isRunning()).count();
        gc.setFill(TEXT);
        gc.setFont(SMALL);
        gc.fillText(running + " / " + containers.size() + " UP", cx, cy + 44);
    }

    private void drawStackBand(GraphicsContext gc, int headerIndex, double width, double height) {
        double top = rows.get(headerIndex).y();
        double bottom = top + HEADER;
        for (int j = headerIndex + 1; j < rows.size(); j++) {
            if (rows.get(j).isHeader()) {
                break;
            }
            bottom = rows.get(j).y() + ROW;
        }
        if (bottom < TOP || top > height) {
            return;
        }
        double left = nodeX(width) - 52;
        gc.setFill(STACK.deriveColor(0, 1, 1, rows.get(headerIndex).stack().equals(hoveredStack) ? 0.10 : 0.05));
        gc.fillRect(left, Math.max(TOP, top), width - left - 12, Math.min(height, bottom) - Math.max(TOP, top));
        gc.setStroke(STACK.deriveColor(0, 1, 1, 0.4));
        gc.setLineWidth(1);
        gc.strokeLine(left + 0.5, Math.max(TOP, top), left + 0.5, Math.min(height, bottom));
    }

    private void drawStackHeader(GraphicsContext gc, Row row, double width) {
        double y = row.y() + HEADER / 2;
        if (y < TOP - 10 || y > canvas.getHeight() + 10) {
            return;
        }
        double left = nodeX(width) - 46;
        gc.setFont(STACK_FONT);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFill(STACK);
        gc.fillText("◆ STACK", left, y);
        gc.setFill(TEXT);
        gc.fillText(row.stack().toUpperCase(Locale.ROOT), left + 56, y);
        // Restart glyph at the right.
        boolean hover = row.stack().equals(hoveredStack);
        gc.setFill(hover ? STACK : MUTED);
        gc.setFont(TINY);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText("⟳ RESTART STACK", width - 24, y);
    }

    private void drawFiber(GraphicsContext gc, double cx, double cy, double nx, double ny, DockerContainer container) {
        if (ny < TOP - 40 || ny > canvas.getHeight() + 40) {
            return;
        }
        Color color = stateColor(container.state());
        boolean running = container.state().isRunning();
        boolean selected = container.id().equals(selectedId) || container.id().equals(hoveredId);
        double dx = nx - cx;
        gc.setStroke(color);
        gc.setGlobalAlpha(selected ? 0.9 : running ? 0.4 : 0.16);
        gc.setLineWidth(selected ? 2 : 1);
        gc.beginPath();
        gc.moveTo(cx, cy);
        gc.bezierCurveTo(cx + dx * 0.5, cy, nx - dx * 0.5, ny, nx, ny);
        gc.stroke();

        if (running && live) {
            for (int p = 0; p < 4; p++) {
                double t = 1 - ((clock * 0.5 + p * 0.25 + hash(container.id())) % 1);
                double[] point = bezier(cx, cy, nx, ny, dx, t);
                double size = 1.5 + 2 * (1 - t);
                gc.setFill(color);
                gc.setGlobalAlpha(Math.sin(Math.PI * t) * 0.9);
                gc.fillOval(point[0] - size / 2, point[1] - size / 2, size, size);
            }
        }
        gc.setGlobalAlpha(1);
    }

    private void drawContainer(GraphicsContext gc, Row row, double x, double width) {
        double y = row.y() + ROW / 2;
        if (y < TOP - 40 || y > canvas.getHeight() + 40) {
            return;
        }
        DockerContainer container = row.container();
        Color color = stateColor(container.state());
        boolean running = container.state().isRunning();
        boolean selected = container.id().equals(selectedId);
        boolean hover = container.id().equals(hoveredId);

        if (selected || hover) {
            gc.setGlobalAlpha(selected ? 0.10 : 0.05);
            gc.setFill(color);
            gc.fillRect(x - 40, row.y() + 4, width - x + 20, ROW - 8);
        }
        gc.setGlobalAlpha(1);

        double radius = 12 + Math.min(10, container.stats() != null
                ? Math.log10(1 + container.stats().memUsedBytes() / 1_000_000.0) * 3 : 3);
        if (running) {
            double halo = radius + 6 + Math.sin(clock * 3 + y) * 2;
            gc.setFill(color.deriveColor(0, 1, 1, 0.18));
            gc.fillOval(x - halo, y - halo, halo * 2, halo * 2);
        }
        if (running) {
            gc.setFill(color);
            gc.fillOval(x - radius, y - radius, radius * 2, radius * 2);
        } else {
            gc.setStroke(color);
            gc.setLineWidth(1.4);
            gc.strokeOval(x - radius, y - radius, radius * 2, radius * 2);
            if (container.state() != ContainerState.PAUSED) {
                double c = radius * 0.4;
                gc.strokeLine(x - c, y - c, x + c, y + c);
                gc.strokeLine(x - c, y + c, x + c, y - c);
            }
        }
        if (selected) {
            gc.setStroke(TEXT);
            gc.setLineWidth(1);
            gc.strokeOval(x - radius - 4, y - radius - 4, (radius + 4) * 2, (radius + 4) * 2);
        }
        // Health ring.
        if (container.health().isKnown()) {
            gc.setStroke(healthColor(container.health()));
            gc.setLineWidth(2);
            double hr = radius + 7;
            double sweep = container.health() == Health.STARTING ? (clock * 200) % 360 : 300;
            gc.strokeArc(x - hr, y - hr, hr * 2, hr * 2, 90, sweep, ArcType.OPEN);
        }
        // Local spinner while this container is being read or acted on.
        if (busy.contains(container.id())) {
            gc.setStroke(DAEMON);
            gc.setLineWidth(1.6);
            double sr = radius + 11;
            gc.strokeArc(x - sr, y - sr, sr * 2, sr * 2, -(clock * 400) % 360, 90, ArcType.OPEN);
        }

        double textX = x + radius + 14;
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFont(NAME_FONT);
        gc.setFill(selected || hover ? TEXT : Color.web("#c9c9d4"));
        gc.fillText(clip(container.name(), 26), textX, y - 16);

        gc.setFont(SMALL);
        gc.setFill(MUTED);
        gc.fillText(clip(container.image(), 30), textX, y);

        gc.setFont(TINY);
        gc.setFill(color);
        String state = container.state().name() + (container.health().isKnown() ? "  ·  " + container.health().name() : "");
        gc.fillText(state, textX, y + 15);

        double badgeX = textX + 96;
        for (PortMapping port : container.publishedPorts()) {
            Color pc = port.exposure() == Exposure.PUBLIC ? PUBLIC : INTERNAL;
            String text = String.valueOf(port.hostPort());
            gc.setFill(pc);
            gc.setGlobalAlpha(0.9);
            gc.fillOval(badgeX, y + 10, 5, 5);
            gc.setGlobalAlpha(1);
            gc.fillText(text, badgeX + 9, y + 15);
            badgeX += 9 + text.length() * 6 + 12;
            if (badgeX > width - 200) {
                break;
            }
        }

        ContainerStats stats = container.stats();
        if (stats != null && running) {
            double gaugeX = width - 190;
            drawGauge(gc, gaugeX, y, "CPU", stats.cpuPercent(), 100, formatPercent(stats.cpuPercent()), RUNNING);
            drawGauge(gc, gaugeX + 96, y, "MEM", stats.memPercent(), 100,
                    com.kubo.ui.scope.Format.bytes(stats.memUsedBytes()), PAUSED);
        }

        gc.setStroke(FAINT);
        gc.setGlobalAlpha(0.6);
        gc.setLineWidth(1);
        gc.strokeLine(x - 40, row.y() + ROW - 4, width - 20, row.y() + ROW - 4);
        gc.setGlobalAlpha(1);
    }

    private void drawGauge(GraphicsContext gc, double x, double y, String label, double value, double max,
                           String reading, Color color) {
        double width = 82;
        gc.setFont(TINY);
        gc.setFill(MUTED);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BOTTOM);
        gc.fillText(label, x, y - 6);
        gc.setFill(TEXT);
        gc.setFont(SMALL);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText(reading, x + width, y - 6);
        gc.setFill(FAINT);
        gc.fillRect(x, y, width, 3);
        double fraction = Math.min(1, value / max);
        gc.setFill(fraction > 0.85 ? PUBLIC : color);
        gc.fillRect(x, y, width * fraction, 3);
    }

    private static Color stateColor(ContainerState state) {
        return switch (state) {
            case RUNNING -> RUNNING;
            case RESTARTING, REMOVING -> AMBER;
            case PAUSED -> PAUSED;
            case CREATED -> IDLE;
            case EXITED, DEAD, OTHER -> STOPPED;
        };
    }

    private static Color healthColor(Health health) {
        return switch (health) {
            case HEALTHY -> RUNNING;
            case UNHEALTHY -> STOPPED;
            case STARTING -> AMBER;
            case NONE -> MUTED;
        };
    }

    private static String formatPercent(double value) {
        return String.format(Locale.ROOT, value >= 10 ? "%.0f%%" : "%.1f%%", value);
    }

    private static String clip(String text, int max) {
        String clean = text.replace('\n', ' ');
        return clean.length() > max ? clean.substring(0, max - 1) + "…" : clean;
    }

    private static double[] bezier(double x1, double y1, double x2, double y2, double dx, double t) {
        double c1x = x1 + dx * 0.5;
        double c2x = x2 - dx * 0.5;
        double u = 1 - t;
        double x = u * u * u * x1 + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * x2;
        double y = u * u * u * y1 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y2;
        return new double[]{x, y};
    }

    private static double hash(String text) {
        return (text.hashCode() & 0xffff) / 65535.0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
