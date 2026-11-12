package com.kubo.ui.network;

import com.kubo.model.network.Endpoint;
import com.kubo.model.network.Firewall;
import com.kubo.model.network.NetworkMap;
import com.kubo.model.network.Risk;
import com.kubo.model.network.ServiceOwner;
import com.kubo.ui.LiveTimer;
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
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The way in, as a map you can move around: the internet a globe of hosts on the left, the firewall a shield
 * the traffic has to pass, then the kinds of reach a port can have, the ports themselves, and the server, a
 * cabinet every port lands on at its own height.
 *
 * <p>A branch the firewall lets through leaves the shield and carries light to its ports; a branch it stops
 * is crossed out at the shield and goes on only as a dashed line; a branch that only exists inside the
 * machine never touches the internet at all.
 *
 * <p>Drag to move, the wheel zooms where the pointer is, a double click puts the whole map back in the frame.
 * The picture draws itself once when the first answer lands; after that it changes only when the answer does.
 */
final class NetworkTree extends Pane {

    /** A right-click on a port, in screen coordinates. */
    record MenuRequest(Endpoint endpoint, double screenX, double screenY) {
    }

    /** A right-click anywhere that is not a port, in screen coordinates. */
    record SpaceMenu(double screenX, double screenY) {
    }

    /** The kinds of reach, which are the first branching of the tree. */
    private enum Band {
        EXPOSED("exposed"),
        OPEN("open"),
        SHIELDED("shielded"),
        PRIVATE("private"),
        LOCAL("local");

        final String title;

        Band(String title) {
            this.title = title;
        }
    }

    private static final Color TEXT = Color.web("#eaeaf2");
    private static final Color DIM = Color.web("#a9a9bb");
    private static final Color MUTED = Color.web("#6b6b80");
    private static final Color DARK = Color.web("#08080d");

    static final Color ALERT = Color.web("#ff2d55");
    static final Color AMBER = Color.web("#ffb347");
    static final Color CYAN = Color.web("#35e8ff");
    static final Color VIOLET = Color.web("#9a6bff");
    static final Color GREEN = Color.web("#35e0a1");
    private static final Color MAGENTA = Color.web("#ff3fd8");
    private static final Color PINK = Color.web("#ffc2f4");

    // The world, left to right, in map units. One unit is one pixel at 100 %.
    private static final double GLOBE_R = 118;
    private static final double SHIELD_X = 430;
    private static final double SHIELD_W = 92;
    private static final double SHIELD_H = 112;
    private static final double BAND_X = 800;
    private static final double PORT_X = 1010;
    private static final double RACK_X = 1560;
    private static final double RACK_W = 96;

    private static final double PORT_ROW = 56;
    private static final double BAND_MIN = 72;
    private static final double BAND_GAP = 60;

    private static final double MIN_ZOOM = 0.2;
    private static final double MAX_ZOOM = 2.2;
    private static final double FRICTION = 4.2;

    /** How long the map takes to draw itself when the first answer lands, in seconds. */
    private static final double REVEAL_SECONDS = 1.3;

    /** The globe's hosts: evenly spread on a sphere, the way a Fibonacci lattice spreads them. */
    private static final double[][] SPHERE = sphere(420);
    /** Which hosts are linked by an arc above the surface. */
    private static final int[][] ARCS = {{24, 188}, {61, 297}, {113, 246}, {151, 389}, {212, 344},
            {268, 92}, {329, 41}, {175, 401}};

    /** One branch of the tree: a kind of reach, its ports, and where it was drawn last. */
    private static final class Branch {
        final Band band;
        final List<Endpoint> ports = new ArrayList<>();
        boolean open = true;
        double openness = 1;
        double glow;
        double y;
        double height;

        Branch(Band band) {
            this.band = band;
        }
    }

    /** Where a port was drawn last, in screen coordinates, for the pointer. */
    private record Placed(Endpoint endpoint, double x, double y, double right, double half) {
    }

    private final Canvas canvas = new Canvas();
    private final LiveTimer timer;
    private final Map<Band, Branch> branches = new EnumMap<>(Band.class);
    private List<Branch> shown = List.of();
    private final List<Placed> placed = new ArrayList<>();

    private NetworkMap map = NetworkMap.empty();
    private boolean read;
    private String hostname = "server";
    private Endpoint selected;
    private Endpoint hovered;
    private Band hoveredBand;

    private double camX = (RACK_X - GLOBE_R) / 2;
    private double camY;
    private double zoom = 0.4;
    private double targetX = camX;
    private double targetY;
    private double targetZoom = 0.4;
    private double velocityX;
    private double velocityY;
    private boolean dragging;
    private boolean movedByHand;
    private double pressX;
    private double pressY;
    private double lastX;
    private double lastY;
    private long lastNanos;

    /** 0 to 1 while the map draws itself after the first answer. */
    private double reveal = 1;
    /** 0 while nothing is chosen, 1 once a port has the stage to itself. */
    private double focus;
    private double clock;
    private double lastTime = -1;
    private boolean live = true;

    // The type follows the zoom within bounds, so it never outgrows a row nor shrinks out of reach.
    private double textScale = 1;
    private Font titleFont;
    private Font bandFont;
    private Font portFont;
    private Font nameFont;
    private Font smallFont;

    private Consumer<Endpoint> onSelect = endpoint -> {
    };
    private Consumer<MenuRequest> onMenu = request -> {
    };
    private Consumer<SpaceMenu> onSpaceMenu = request -> {
    };

    NetworkTree() {
        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinSize(0, 0);
        for (Band band : Band.values()) {
            branches.put(band, new Branch(band));
        }
        setOnMouseMoved(this::handleMove);
        setOnMouseExited(event -> {
            hovered = null;
            hoveredBand = null;
        });
        setOnMousePressed(this::handlePress);
        setOnMouseDragged(this::handleDrag);
        setOnMouseReleased(this::handleRelease);
        setOnMouseClicked(this::handleClick);
        setOnScroll(this::handleScroll);
        scaleType();
        timer = new LiveTimer(this, () -> moving() ? LiveTimer.EVERY_PULSE : LiveTimer.AMBIENT, this::render);
        timer.start();
    }

    // Wiring

    void setOnSelect(Consumer<Endpoint> onSelect) {
        this.onSelect = onSelect;
    }

    void setOnMenu(Consumer<MenuRequest> onMenu) {
        this.onMenu = onMenu;
    }

    void setOnSpaceMenu(Consumer<SpaceMenu> onSpaceMenu) {
        this.onSpaceMenu = onSpaceMenu;
    }

    void setHostname(String hostname) {
        this.hostname = hostname == null || hostname.isBlank() ? "server" : hostname;
    }

    void setMap(NetworkMap map) {
        boolean first = !read;
        this.map = map == null ? NetworkMap.empty() : map;
        this.read = true;
        rebuild();
        if (selected != null) {
            selected = allPorts().stream().filter(port -> same(port, selected)).findFirst().orElse(null);
        }
        if (first) {
            fit();
            snap();
            if (live && !shown.isEmpty()) {
                reveal = 0;
            }
        }
        if (!live) {
            paint();
        }
    }

    void select(Endpoint endpoint) {
        selected = endpoint;
        if (endpoint != null) {
            branches.get(bandOf(endpoint)).open = true;
        }
    }

    void stop() {
        timer.stop();
    }

    /** Stops the clock at one moment and draws it, for a still picture. */
    void freeze(double time) {
        timer.stop();
        live = false;
        clock = time;
        reveal = 1;
        focus = selected == null ? 0 : 1;
        for (Branch branch : shown) {
            branch.openness = branch.open ? 1 : 0;
        }
        snap();
        paint();
    }

    void zoomIn() {
        movedByHand = true;
        targetZoom = clamp(targetZoom * 1.25, MIN_ZOOM, MAX_ZOOM);
    }

    void zoomOut() {
        movedByHand = true;
        targetZoom = clamp(targetZoom / 1.25, MIN_ZOOM, MAX_ZOOM);
    }

    /** Puts the whole map in the frame, and lets it follow the frame's size again. */
    void fit() {
        movedByHand = false;
        double w = getWidth() < 1 ? 700 : getWidth();
        double h = getHeight() < 1 ? 620 : getHeight();
        placeBranches();
        double half = Math.max(totalHeight() / 2, GLOBE_R + 40);
        double left = -GLOBE_R - 40;
        double right = RACK_X + RACK_W + 40;
        double top = -half - 90;
        double bottom = half + 70;
        targetZoom = clamp(Math.min(w / (right - left), h / (bottom - top)), MIN_ZOOM, 1.1);
        targetX = (left + right) / 2;
        targetY = (top + bottom) / 2;
        velocityX = 0;
        velocityY = 0;
        if (!live) {
            snap();
            paint();
        }
    }

    private void snap() {
        camX = targetX;
        camY = targetY;
        zoom = targetZoom;
        scaleType();
    }

    /** True while the view is dragged, gliding or easing, or the map is drawing itself. */
    private boolean moving() {
        return dragging || reveal < 1
                || Math.abs(velocityX) > 2 || Math.abs(velocityY) > 2
                || Math.abs(targetX - camX) > 0.5 || Math.abs(targetY - camY) > 0.5
                || Math.abs(targetZoom - zoom) > 0.001;
    }

    // The tree

    private void rebuild() {
        branches.values().forEach(branch -> branch.ports.clear());
        for (Endpoint endpoint : map.endpoints()) {
            // The same port, protocol and process on IPv4 and IPv6 is one way in, drawn once.
            List<Endpoint> ports = branches.get(bandOf(endpoint)).ports;
            boolean twin = ports.stream().anyMatch(other -> other.port() == endpoint.port()
                    && other.socket().protocol() == endpoint.socket().protocol()
                    && other.owner().label().equals(endpoint.owner().label()));
            if (!twin) {
                ports.add(endpoint);
            }
        }
        List<Branch> kept = new ArrayList<>();
        for (Band band : Band.values()) {
            Branch branch = branches.get(band);
            if (!branch.ports.isEmpty()) {
                branch.ports.sort(java.util.Comparator.comparingInt(Endpoint::port));
                kept.add(branch);
            }
        }
        shown = List.copyOf(kept);
        placeBranches();
    }

    private List<Endpoint> allPorts() {
        List<Endpoint> ports = new ArrayList<>();
        shown.forEach(branch -> ports.addAll(branch.ports));
        return ports;
    }

    /** Stacks the branches, each as tall as the ports it shows, centred on the line from the internet. */
    private void placeBranches() {
        double y = 0;
        for (Branch branch : shown) {
            branch.height = Math.max(BAND_MIN, branch.ports.size() * PORT_ROW * branch.openness + 16);
            branch.y = y + branch.height / 2;
            y += branch.height + BAND_GAP;
        }
        double total = Math.max(0, y - BAND_GAP);
        for (Branch branch : shown) {
            branch.y -= total / 2;
        }
    }

    private double totalHeight() {
        double total = 0;
        for (Branch branch : shown) {
            total += branch.height + BAND_GAP;
        }
        return Math.max(0, total - BAND_GAP);
    }

    private static double portY(Branch branch, int index) {
        double spread = branch.ports.size() * PORT_ROW * branch.openness;
        return branch.y - spread / 2 + PORT_ROW * branch.openness * (index + 0.5);
    }

    // Geometry

    private double sx(double worldX) {
        return getWidth() / 2 + (worldX - camX) * zoom;
    }

    private double sy(double worldY) {
        return getHeight() / 2 + (worldY - camY) * zoom;
    }

    private double worldX(double screenX) {
        return (screenX - getWidth() / 2) / zoom + camX;
    }

    private double worldY(double screenY) {
        return (screenY - getHeight() / 2) / zoom + camY;
    }

    @Override
    protected void layoutChildren() {
        boolean resized = Math.abs(canvas.getWidth() - getWidth()) > 0.5
                || Math.abs(canvas.getHeight() - getHeight()) > 0.5;
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        // Until somebody has moved the map, it follows the frame: the log opening under it, the window resized.
        if (resized && !movedByHand && read) {
            fit();
            if (!live) {
                snap();
            }
        }
        if (resized && !live) {
            paint();
        }
    }

    // Input

    private void handleMove(MouseEvent event) {
        hovered = portAt(event.getX(), event.getY());
        hoveredBand = hovered != null ? null : bandAt(event.getX(), event.getY());
        setCursor(hovered != null || hoveredBand != null ? Cursor.HAND : Cursor.DEFAULT);
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
        movedByHand = true;
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
        boolean secondary = event.getButton() == MouseButton.SECONDARY;
        Endpoint port = portAt(event.getX(), event.getY());
        if (port != null) {
            selected = same(port, selected) && !secondary ? null : port;
            onSelect.accept(selected);
            if (secondary) {
                onMenu.accept(new MenuRequest(port, event.getScreenX(), event.getScreenY()));
            }
            return;
        }
        if (secondary) {
            onSpaceMenu.accept(new SpaceMenu(event.getScreenX(), event.getScreenY()));
            return;
        }
        Band band = bandAt(event.getX(), event.getY());
        if (band != null) {
            Branch branch = branches.get(band);
            branch.open = !branch.open;
            return;
        }
        if (event.getClickCount() == 2) {
            fit();
            return;
        }
        if (selected != null) {
            selected = null;
            onSelect.accept(null);
        }
    }

    /** The wheel zooms where the pointer is, the way every map does. */
    private void handleScroll(ScrollEvent event) {
        if (event.getDeltaY() == 0) {
            return;
        }
        movedByHand = true;
        double before = targetZoom;
        targetZoom = clamp(targetZoom * (event.getDeltaY() > 0 ? 1.12 : 1 / 1.12), MIN_ZOOM, MAX_ZOOM);
        double wx = worldX(event.getX());
        double wy = worldY(event.getY());
        double factor = 1 - before / targetZoom;
        targetX += (wx - targetX) * factor;
        targetY += (wy - targetY) * factor;
        event.consume();
    }

    private Endpoint portAt(double x, double y) {
        for (Placed port : placed) {
            if (x > port.x() - 6 && x < port.right() && Math.abs(y - port.y()) < port.half()) {
                return port.endpoint();
            }
        }
        return null;
    }

    private Band bandAt(double x, double y) {
        double bx = sx(BAND_X);
        for (Branch branch : shown) {
            double by = sy(branch.y);
            if (x > bx - 120 * textScale && x < bx + 16 * textScale && Math.abs(y - by) < 22 * textScale) {
                return branch.band;
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
        if (reveal < 1) {
            reveal = Math.min(1, reveal + dt / REVEAL_SECONDS);
        }
        focus += ((selected == null ? 0 : 1) - focus) * Math.min(1, dt * 6);
        for (Branch branch : shown) {
            branch.openness += ((branch.open ? 1 : 0) - branch.openness) * Math.min(1, dt * 7);
            double wanted = branch.band == hoveredBand ? 1 : 0;
            branch.glow += (wanted - branch.glow) * Math.min(1, dt * 8);
        }
        placeBranches();
        scaleType();
        paint();
    }

    private void scaleType() {
        textScale = clamp(zoom * 1.6, 0.82, 1.3);
        titleFont = Font.font("Consolas", FontWeight.BOLD, 13 * textScale);
        bandFont = Font.font("Consolas", FontWeight.BOLD, 13 * textScale);
        portFont = Font.font("Consolas", FontWeight.BOLD, 12 * textScale);
        nameFont = Font.font("Consolas", 11.5 * textScale);
        smallFont = Font.font("Consolas", 10.5 * textScale);
    }

    private void paint() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w < 1 || h < 1) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setGlobalAlpha(1);
        gc.setLineDashes((double[]) null);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        gc.clearRect(0, 0, w, h);
        placed.clear();

        if (shown.isEmpty()) {
            if (read) {
                gc.setFont(nameFont);
                gc.setFill(MUTED);
                gc.setTextAlign(TextAlignment.CENTER);
                gc.setTextBaseline(VPos.CENTER);
                gc.fillText("nothing is listening", w / 2, h / 2);
            }
            return;
        }

        // The lines first, under everything they join.
        drawTrunk(gc);
        for (int i = 0; i < shown.size(); i++) {
            drawBranchLines(gc, shown.get(i), i);
        }
        drawRack(gc);
        drawShield(gc);
        drawGlobe(gc);
        for (int i = 0; i < shown.size(); i++) {
            drawBranchNodes(gc, shown.get(i), i);
        }
    }

    /** The share of the reveal this element has had, eased, with its own start and length. */
    private double phase(double start, double length) {
        double t = clamp((reveal - start) / length, 0, 1);
        return t * t * (3 - 2 * t);
    }

    // The internet

    private void drawGlobe(GraphicsContext gc) {
        double appear = phase(0, 0.22);
        if (appear < 0.01) {
            return;
        }
        double cx = sx(0);
        double cy = sy(0);
        double r = GLOBE_R * zoom;
        double spin = live ? clock * 0.14 : 0.9;
        double tilt = 0.38;

        // The air round it.
        gc.setGlobalAlpha(appear);
        gc.setFill(new RadialGradient(0, 0, cx, cy, r * 1.4, false, CycleMethod.NO_CYCLE,
                new Stop(0.66, MAGENTA.deriveColor(0, 1, 1, 0)),
                new Stop(0.72, MAGENTA.deriveColor(0, 1, 1, 0.16)),
                new Stop(1, MAGENTA.deriveColor(0, 1, 1, 0))));
        gc.fillOval(cx - r * 1.4, cy - r * 1.4, r * 2.8, r * 2.8);
        // The body, lit from the upper left.
        gc.setFill(DARK);
        gc.fillOval(cx - r, cy - r, r * 2, r * 2);
        gc.setFill(new RadialGradient(0, 0, cx - r * 0.35, cy - r * 0.4, r * 1.35, false, CycleMethod.NO_CYCLE,
                new Stop(0, MAGENTA.deriveColor(0, 1, 1, 0.16)),
                new Stop(1, MAGENTA.deriveColor(0, 1, 1, 0))));
        gc.fillOval(cx - r, cy - r, r * 2, r * 2);

        double cosS = Math.cos(spin);
        double sinS = Math.sin(spin);
        double cosT = Math.cos(tilt);
        double sinT = Math.sin(tilt);
        double dot = clamp(r / 110, 0.55, 1.5);
        // The hosts: the far side faint and small, the near side bright, so it reads as a ball.
        for (int pass = 0; pass < 2; pass++) {
            for (double[] p : SPHERE) {
                double x = p[0] * cosS + p[2] * sinS;
                double z = -p[0] * sinS + p[2] * cosS;
                double y = p[1] * cosT - z * sinT;
                double depth = p[1] * sinT + z * cosT;
                if ((pass == 0) != (depth < 0)) {
                    continue;
                }
                double near = (depth + 1) / 2;
                gc.setGlobalAlpha(appear * (0.06 + 0.86 * Math.pow(near, 1.7)));
                gc.setFill(MAGENTA.interpolate(PINK, near * near * 0.6));
                double size = (0.7 + 1.9 * near) * dot;
                gc.fillOval(cx + x * r - size / 2, cy + y * r - size / 2, size, size);
            }
        }

        // Arcs between hosts, lifted off the surface, with a light running along each.
        gc.setLineWidth(Math.max(0.8, 1.2 * dot));
        for (int k = 0; k < ARCS.length; k++) {
            double[] a = SPHERE[ARCS[k][0]];
            double[] b = SPHERE[ARCS[k][1]];
            double dotAB = clamp(a[0] * b[0] + a[1] * b[1] + a[2] * b[2], -0.999, 0.999);
            double omega = Math.acos(dotAB);
            int steps = 26;
            double[] xs = new double[steps + 1];
            double[] ys = new double[steps + 1];
            double depthSum = 0;
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                double fa = Math.sin((1 - t) * omega) / Math.sin(omega);
                double fb = Math.sin(t * omega) / Math.sin(omega);
                double lift = 1 + 0.24 * Math.sin(Math.PI * t);
                double px = (fa * a[0] + fb * b[0]) * lift;
                double py = (fa * a[1] + fb * b[1]) * lift;
                double pz = (fa * a[2] + fb * b[2]) * lift;
                double x = px * cosS + pz * sinS;
                double z = -px * sinS + pz * cosS;
                double y = py * cosT - z * sinT;
                depthSum += py * sinT + z * cosT;
                xs[i] = cx + x * r;
                ys[i] = cy + y * r;
            }
            double depth = depthSum / (steps + 1);
            if (depth < -0.05) {
                continue;
            }
            double strength = appear * Math.min(1, depth * 2.5 + 0.2);
            gc.setStroke(MAGENTA);
            gc.setGlobalAlpha(strength * 0.55);
            gc.strokePolyline(xs, ys, steps + 1);
            double t = fract((live ? clock : 1.3) * 0.32 + k * 0.17);
            int at = (int) Math.round(t * steps);
            gc.setFill(PINK);
            gc.setGlobalAlpha(strength * Math.sin(Math.PI * t));
            double size = 3.2 * dot;
            gc.fillOval(xs[at] - size / 2, ys[at] - size / 2, size, size);
        }
        gc.setGlobalAlpha(appear * 0.5);
        gc.setStroke(MAGENTA);
        gc.setLineWidth(1);
        gc.strokeOval(cx - r, cy - r, r * 2, r * 2);

        long reachable = shown.stream().filter(branch -> branch.band == Band.EXPOSED || branch.band == Band.OPEN)
                .mapToLong(branch -> branch.ports.size()).sum();
        gc.setGlobalAlpha(appear);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFont(titleFont);
        gc.setFill(MAGENTA);
        gc.fillText("internet", cx, cy + r * 1.18 + 14 * textScale);
        gc.setFont(smallFont);
        gc.setFill(MUTED);
        gc.fillText(reachable + (reachable == 1 ? " way in" : " ways in"), cx, cy + r * 1.18 + 30 * textScale);
        gc.setGlobalAlpha(1);
    }

    /** The single line from the internet to the firewall: everything comes in this way. */
    private void drawTrunk(GraphicsContext gc) {
        double drawn = phase(0.1, 0.22);
        double x1 = sx(GLOBE_R);
        double x2 = sx(SHIELD_X - SHIELD_W / 2);
        double y = sy(0);
        if (drawn < 0.01) {
            return;
        }
        double end = x1 + (x2 - x1) * drawn;
        Color wall = colourOfFirewall(map.firewall());
        gc.setStroke(new LinearGradient(x1, 0, x2, 0, false, CycleMethod.NO_CYCLE,
                new Stop(0, MAGENTA), new Stop(1, wall)));
        gc.setGlobalAlpha(0.7);
        gc.setLineWidth(Math.max(1.4, 2.4 * zoom));
        gc.strokeLine(x1, y, end, y);
        flow(gc, x1, y, x2, y, MAGENTA, 0.9, 4, 0, drawn);
        gc.setGlobalAlpha(1);
    }

    // The firewall

    private void drawShield(GraphicsContext gc) {
        double appear = phase(0.18, 0.22);
        if (appear < 0.01) {
            return;
        }
        Firewall firewall = map.firewall();
        boolean enforcing = firewall.isEnforcing();
        boolean known = firewall.isKnown();
        Color colour = enforcing ? GREEN : known ? ALERT : AMBER;
        double cx = sx(SHIELD_X);
        double cy = sy(0);
        double w = SHIELD_W * zoom * (0.85 + 0.15 * appear);
        double h = SHIELD_H * zoom * (0.85 + 0.15 * appear);
        double top = cy - h / 2;

        gc.setGlobalAlpha(appear);
        gc.setFill(new RadialGradient(0, 0, cx, cy, Math.max(w, h) * 0.95, false, CycleMethod.NO_CYCLE,
                new Stop(0, colour.deriveColor(0, 1, 1, enforcing ? 0.16 : 0.08)),
                new Stop(1, colour.deriveColor(0, 1, 1, 0))));
        gc.fillOval(cx - w * 0.95, cy - h * 0.95, w * 1.9, h * 1.9);

        shieldPath(gc, cx, top, w, h);
        gc.setFill(DARK);
        gc.fill();
        gc.setFill(colour.deriveColor(0, 1, 1, 0.10));
        gc.fill();
        if (enforcing) {
            // A light passing down the face, which is what makes it read as a surface rather than an outline.
            gc.save();
            shieldPath(gc, cx, top, w, h);
            gc.clip();
            double travel = live ? fract(clock * 0.2) * 1.6 - 0.3 : 0.4;
            double band = h * 0.35;
            double y = top + travel * h;
            gc.setFill(new LinearGradient(0, y - band / 2, 0, y + band / 2, false, CycleMethod.NO_CYCLE,
                    new Stop(0, colour.deriveColor(0, 1, 1, 0)),
                    new Stop(0.5, colour.deriveColor(0, 1, 1, 0.22)),
                    new Stop(1, colour.deriveColor(0, 1, 1, 0))));
            gc.fillRect(cx - w, y - band / 2, w * 2, band);
            gc.restore();
        }
        gc.setStroke(colour);
        gc.setLineWidth(Math.max(1.2, 2.2 * zoom));
        if (!enforcing) {
            gc.setLineDashes(known ? new double[]{8 * zoom, 6 * zoom} : new double[]{2, 5 * zoom});
        }
        shieldPath(gc, cx, top, w, h);
        gc.stroke();
        gc.setLineDashes((double[]) null);
        gc.setGlobalAlpha(appear * 0.35);
        gc.setLineWidth(1);
        shieldPath(gc, cx, top + h * 0.1, w * 0.76, h * 0.8);
        gc.stroke();

        // What state it is in, in the middle of it.
        gc.setGlobalAlpha(appear);
        gc.setStroke(colour);
        gc.setLineWidth(Math.max(1.6, 3.2 * zoom));
        if (enforcing) {
            gc.strokePolyline(new double[]{cx - w * 0.17, cx - w * 0.04, cx + w * 0.2},
                    new double[]{cy + h * 0.0, cy + h * 0.12, cy - h * 0.13}, 3);
        } else if (known) {
            gc.strokeLine(cx - w * 0.16, cy - h * 0.14, cx + w * 0.16, cy + h * 0.14);
            gc.strokeLine(cx - w * 0.16, cy + h * 0.14, cx + w * 0.16, cy - h * 0.14);
        } else {
            gc.setFont(Font.font("Consolas", FontWeight.BOLD, 30 * zoom));
            gc.setFill(colour);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setTextBaseline(VPos.CENTER);
            gc.fillText("?", cx, cy + h * 0.02);
        }

        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFont(titleFont);
        gc.setFill(colour);
        gc.fillText(firewall.title().toLowerCase(Locale.ROOT), cx, top + h + 16 * textScale);
        gc.setFont(smallFont);
        gc.setFill(enforcing ? MUTED : colour);
        gc.fillText(switch (firewall.state()) {
            case ACTIVE -> "on · " + map.openPorts().size() + " allowed";
            case INACTIVE -> "off, lets everything in";
            case ABSENT -> "none";
            case UNREADABLE, UNRECOGNISED -> "rules need root";
            case UNCHECKED -> "not checked";
        }, cx, top + h + 32 * textScale);
        gc.setGlobalAlpha(1);
    }

    /** The outline of a shield: a flat top with rounded shoulders, coming to a point. */
    private static void shieldPath(GraphicsContext gc, double cx, double top, double w, double h) {
        gc.beginPath();
        gc.moveTo(cx, top);
        gc.quadraticCurveTo(cx + w * 0.26, top + h * 0.09, cx + w / 2, top + h * 0.12);
        gc.lineTo(cx + w / 2, top + h * 0.48);
        gc.bezierCurveTo(cx + w / 2, top + h * 0.78, cx + w * 0.22, top + h * 0.92, cx, top + h);
        gc.bezierCurveTo(cx - w * 0.22, top + h * 0.92, cx - w / 2, top + h * 0.78, cx - w / 2, top + h * 0.48);
        gc.lineTo(cx - w / 2, top + h * 0.12);
        gc.quadraticCurveTo(cx - w * 0.26, top + h * 0.09, cx, top);
        gc.closePath();
    }

    // The server

    private void drawRack(GraphicsContext gc) {
        double appear = phase(0.55, 0.3);
        if (appear < 0.01) {
            return;
        }
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (Branch branch : shown) {
            low = Math.min(low, branch.y - branch.height / 2);
            high = Math.max(high, branch.y + branch.height / 2);
        }
        low = Math.min(low - 30, -110);
        high = Math.max(high + 30, 110);
        double x = sx(RACK_X);
        double top = sy(low);
        double bottom = sy(high);
        double w = RACK_W * zoom;
        double height = bottom - top;
        double radius = 10 * zoom;

        gc.setGlobalAlpha(appear);
        gc.setFill(DARK);
        gc.fillRoundRect(x, top, w, height, radius, radius);
        gc.setFill(new LinearGradient(x, 0, x + w, 0, false, CycleMethod.NO_CYCLE,
                new Stop(0, CYAN.deriveColor(0, 1, 1, 0.10)), new Stop(1, CYAN.deriveColor(0, 1, 1, 0.03))));
        gc.fillRoundRect(x, top, w, height, radius, radius);
        gc.setStroke(CYAN);
        gc.setGlobalAlpha(appear * 0.8);
        gc.setLineWidth(Math.max(1, 1.6 * zoom));
        gc.strokeRoundRect(x, top, w, height, radius, radius);
        // Its units, one ruled line each, and a few lights.
        double unit = 22 * zoom;
        int index = 0;
        for (double y = top + unit; y < bottom - unit * 0.6; y += unit, index++) {
            gc.setGlobalAlpha(appear * 0.14);
            gc.setLineWidth(1);
            gc.strokeLine(x + 8 * zoom, y, x + w - 8 * zoom, y);
            if (index % 3 == 1) {
                double blink = live ? 0.45 + 0.55 * (0.5 + 0.5 * Math.sin(clock * 1.3 + index * 1.7)) : 0.8;
                gc.setGlobalAlpha(appear * blink);
                gc.setFill(GREEN);
                double led = Math.max(2, 4 * zoom);
                gc.fillOval(x + w - 16 * zoom, y - unit / 2 - led / 2, led, led);
            }
        }
        gc.setGlobalAlpha(appear);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFont(titleFont);
        gc.setFill(CYAN);
        gc.fillText(clip(hostname, titleFont, Math.max(w * 2.4, 120)), x + w / 2, top - 30 * textScale);
        gc.setFont(smallFont);
        gc.setFill(MUTED);
        gc.fillText(allPorts().size() + " listening", x + w / 2, top - 14 * textScale);
        gc.setGlobalAlpha(1);
    }

    // The branches

    /** Every line of one branch: from the firewall to it, to its ports, and from them to the server. */
    private void drawBranchLines(GraphicsContext gc, Branch branch, int order) {
        Color colour = colourOf(branch.band);
        double bx = sx(BAND_X);
        double by = sy(branch.y);
        double dim = dimming(branch);
        double from = sx(SHIELD_X + SHIELD_W / 2);
        double cy = sy(0);
        double delay = order * 0.05;
        double reach = phase(0.28 + delay, 0.26);
        double node = 11 * zoom;

        if (branch.band == Band.EXPOSED || branch.band == Band.OPEN) {
            curve(gc, from, cy, bx - node, by, colour, 0.8 * dim, reach, 1.6);
            flow(gc, from, cy, bx - node, by, colour, 0.9 * dim, 2, order, reach);
        } else if (branch.band == Band.SHIELDED && reach > 0.01) {
            // Turned back at the shield: crossed out where it leaves, and only a dashed line after.
            double cross = from + 18 * zoom;
            gc.setLineDashes(4 * zoom + 2, 6 * zoom + 2);
            curve(gc, cross + 10 * zoom, cy, bx - node, by, colour, 0.35 * dim, reach, 1.1);
            gc.setLineDashes((double[]) null);
            cross(gc, cross, cy + (by - cy) * 0.08, colour, dim * reach);
        }

        if (branch.openness < 0.05) {
            return;
        }
        double px = sx(PORT_X);
        double rack = sx(RACK_X);
        for (int i = 0; i < branch.ports.size(); i++) {
            Endpoint endpoint = branch.ports.get(i);
            double y = sy(portY(branch, i));
            double related = selected == null ? 1 : isRelated(endpoint) ? 1 : 1 - 0.75 * focus;
            double drawn = phase(0.4 + delay + i * 0.012, 0.25);
            Color shade = colourOf(endpoint);
            curve(gc, bx + node, by, px, y, shade, 0.7 * branch.openness * related, drawn, 1.2);
            flow(gc, bx + node, by, px, y, shade, 0.6 * branch.openness * related, 1, order * 7 + i, drawn);
            double start = labelEnd(endpoint, px);
            double onward = phase(0.58 + delay + i * 0.012, 0.28);
            curve(gc, start, y, rack, y, CYAN, 0.32 * branch.openness * related, onward, 1);
            flow(gc, start, y, rack, y, CYAN, 0.35 * branch.openness * related, 1, i * 3 + 1, onward);
            if (onward > 0.98) {
                gc.setGlobalAlpha(0.8 * branch.openness * related);
                gc.setFill(CYAN);
                double socket = Math.max(2.5, 5 * zoom);
                gc.fillOval(rack - socket / 2, y - socket / 2, socket, socket);
                gc.setGlobalAlpha(1);
            }
        }
    }

    /** Where the words after a port end, which is where its line to the server starts. */
    private double labelEnd(Endpoint endpoint, double px) {
        String number = String.valueOf(endpoint.port());
        double jack = Math.max(40 * textScale, width(number, portFont) + 16 * textScale);
        double end = px + jack;
        if (PORT_ROW * zoom >= 17) {
            end += 10 * textScale + width(clip(name(endpoint), nameFont, nameRoom(px, jack)), nameFont);
        }
        if (PORT_ROW * zoom >= 26) {
            String detail = detail(endpoint);
            end += 10 * textScale + width(detail, smallFont);
        }
        return end + 10 * textScale;
    }

    private double nameRoom(double px, double jack) {
        return Math.max(40, sx(RACK_X) - 40 * zoom - (px + jack + 10 * textScale) - 30 * textScale);
    }

    /** The branch's node and its words, then its ports. */
    private void drawBranchNodes(GraphicsContext gc, Branch branch, int order) {
        Color colour = colourOf(branch.band);
        double bx = sx(BAND_X);
        double by = sy(branch.y);
        double dim = dimming(branch);
        double delay = order * 0.05;
        double appear = phase(0.36 + delay, 0.18) * dim;
        double node = Math.max(6, 11 * zoom);
        if (appear > 0.01) {
            gc.setGlobalAlpha(appear);
            gc.setFill(colour.deriveColor(0, 1, 1, 0.12 + 0.25 * branch.glow));
            gc.fillOval(bx - node * 1.8, by - node * 1.8, node * 3.6, node * 3.6);
            gc.setFill(DARK);
            gc.fillOval(bx - node, by - node, node * 2, node * 2);
            gc.setStroke(colour);
            gc.setLineWidth(Math.max(1.2, 1.8 * zoom));
            gc.strokeOval(bx - node, by - node, node * 2, node * 2);
            gc.setFill(colour);
            gc.fillOval(bx - node * 0.38, by - node * 0.38, node * 0.76, node * 0.76);
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.setTextBaseline(VPos.CENTER);
            gc.setFont(bandFont);
            gc.fillText(branch.band.title, bx - node - 10 * textScale, by - 7 * textScale);
            gc.setFont(smallFont);
            gc.setFill(MUTED);
            gc.fillText(branch.ports.size() + (branch.ports.size() == 1 ? " port" : " ports")
                    + (branch.open ? "" : " · folded"), bx - node - 10 * textScale, by + 8 * textScale);
            gc.setGlobalAlpha(1);
        }
        if (branch.openness < 0.05) {
            return;
        }
        for (int i = 0; i < branch.ports.size(); i++) {
            drawPort(gc, branch, order, i);
        }
    }

    /** One port: its number in a socket, the process behind it, and how it is run. */
    private void drawPort(GraphicsContext gc, Branch branch, int order, int index) {
        Endpoint endpoint = branch.ports.get(index);
        Color colour = colourOf(endpoint);
        double x = sx(PORT_X);
        double y = sy(portY(branch, index));
        boolean isSelected = same(endpoint, selected);
        boolean isHovered = same(endpoint, hovered);
        double related = selected == null ? 1 : isRelated(endpoint) ? 1 : 1 - 0.75 * focus;
        double appear = phase(0.48 + order * 0.05 + index * 0.012, 0.2) * branch.openness * related;
        if (appear < 0.01) {
            return;
        }
        String number = String.valueOf(endpoint.port());
        double jack = Math.max(40 * textScale, width(number, portFont) + 16 * textScale);
        double high = 18 * textScale;
        gc.setGlobalAlpha(appear);
        if (isSelected || isHovered) {
            gc.setFill(colour.deriveColor(0, 1, 1, isSelected ? 0.18 : 0.10));
            gc.fillRoundRect(x - 6, y - high, jack + 12, high * 2, 10, 10);
        }
        gc.setFill(DARK);
        gc.fillRoundRect(x, y - high / 2, jack, high, 6, 6);
        gc.setFill(colour.deriveColor(0, 1, 1, isSelected ? 0.3 : 0.1));
        gc.fillRoundRect(x, y - high / 2, jack, high, 6, 6);
        gc.setStroke(colour);
        gc.setLineWidth(isSelected ? 1.8 : 1.1);
        gc.strokeRoundRect(x + 0.5, y - high / 2 + 0.5, jack - 1, high - 1, 6, 6);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFont(portFont);
        gc.setFill(isSelected ? TEXT : colour);
        gc.fillText(number, x + jack / 2, y + 0.5);

        double right = x + jack;
        if (PORT_ROW * zoom >= 17) {
            boolean named = endpoint.owner().kind() != ServiceOwner.Kind.UNKNOWN;
            String name = clip(name(endpoint), nameFont, nameRoom(x, jack));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setFont(nameFont);
            gc.setFill(isSelected ? TEXT : named ? DIM : MUTED);
            gc.fillText(name, right + 10 * textScale, y);
            right += 10 * textScale + width(name, nameFont);
            if (PORT_ROW * zoom >= 26) {
                String detail = detail(endpoint);
                gc.setFont(smallFont);
                gc.setFill(MUTED);
                gc.fillText(detail, right + 10 * textScale, y + 0.5);
                right += 10 * textScale + width(detail, smallFont);
            }
        }
        gc.setGlobalAlpha(1);
        placed.add(new Placed(endpoint, x, y, right, Math.max(9, PORT_ROW * zoom / 2)));
    }

    private static String name(Endpoint endpoint) {
        return endpoint.owner().kind() == ServiceOwner.Kind.UNKNOWN ? "unknown" : endpoint.owner().label();
    }

    private static String detail(Endpoint endpoint) {
        String kind = switch (endpoint.owner().kind()) {
            case PM2 -> "pm2 · ";
            case DOCKER -> "docker · ";
            case SYSTEM -> "";
            case UNKNOWN -> "needs root · ";
        };
        return kind + endpoint.socket().protocol().name().toLowerCase(Locale.ROOT);
    }

    /**
     * How much of its brightness something keeps while one port has the stage.
     */
    private double dimming(Branch branch) {
        if (focus < 0.02 || selected == null) {
            return 1;
        }
        boolean related = branch.ports.stream().anyMatch(this::isRelated);
        return related ? 1 : 1 - 0.72 * focus;
    }

    /**
     * A port is related to the chosen one when it is the same port, or the same process holds both.
     */
    private boolean isRelated(Endpoint endpoint) {
        if (selected == null) {
            return false;
        }
        if (same(endpoint, selected)) {
            return true;
        }
        ServiceOwner a = endpoint.owner();
        ServiceOwner b = selected.owner();
        if (a.kind() == ServiceOwner.Kind.UNKNOWN || b.kind() == ServiceOwner.Kind.UNKNOWN) {
            return false;
        }
        if (!a.handle().isEmpty() && a.handle().equals(b.handle())) {
            return true;
        }
        return endpoint.socket().hasPid() && endpoint.socket().pid() == selected.socket().pid();
    }

    // Lines

    /** A curve between two points, flat at both ends, drawn up to {@code drawn} of its length. */
    private void curve(GraphicsContext gc, double x1, double y1, double x2, double y2, Color colour,
                       double strength, double drawn, double width) {
        if (strength <= 0.01 || drawn <= 0.001 || x2 <= x1) {
            return;
        }
        gc.setStroke(colour);
        gc.setLineWidth(Math.max(0.8, width * Math.max(0.7, zoom)));
        gc.setGlobalAlpha(Math.min(1, strength * 0.6));
        if (drawn >= 1) {
            double middle = (x1 + x2) / 2;
            gc.beginPath();
            gc.moveTo(x1, y1);
            gc.bezierCurveTo(middle, y1, middle, y2, x2, y2);
            gc.stroke();
        } else {
            int steps = 28;
            double[] xs = new double[steps + 1];
            double[] ys = new double[steps + 1];
            for (int i = 0; i <= steps; i++) {
                double[] point = bezier(x1, y1, x2, y2, drawn * i / steps);
                xs[i] = point[0];
                ys[i] = point[1];
            }
            gc.strokePolyline(xs, ys, steps + 1);
            gc.setFill(colour);
            gc.setGlobalAlpha(Math.min(1, strength * 1.4));
            gc.fillOval(xs[steps] - 2, ys[steps] - 2, 4, 4);
        }
        gc.setGlobalAlpha(1);
    }

    /** A few lights running along a line, in the direction a request travels. */
    private void flow(GraphicsContext gc, double x1, double y1, double x2, double y2, Color colour,
                      double strength, int count, int seed, double drawn) {
        if (!live || strength <= 0.01 || drawn < 1 || x2 <= x1) {
            return;
        }
        double speed = 220 / Math.max(80, x2 - x1);
        for (int i = 0; i < count; i++) {
            double t = fract(clock * speed * 0.35 + (double) i / count + (seed % 11) * 0.083);
            double[] point = bezier(x1, y1, x2, y2, t);
            double fade = Math.sin(Math.PI * t);
            double size = Math.max(2, (2.2 + 1.4 * fade) * Math.max(0.6, zoom));
            gc.setFill(colour);
            gc.setGlobalAlpha(Math.min(1, strength * fade * 0.35));
            gc.fillOval(point[0] - size, point[1] - size, size * 2, size * 2);
            gc.setGlobalAlpha(Math.min(1, strength * fade));
            gc.fillOval(point[0] - size / 2, point[1] - size / 2, size, size);
        }
        gc.setGlobalAlpha(1);
    }

    /** The mark where a line is turned back. */
    private void cross(GraphicsContext gc, double x, double y, Color colour, double alpha) {
        double arm = Math.max(4, 7 * zoom);
        gc.setGlobalAlpha(alpha * 0.2);
        gc.setFill(colour);
        gc.fillOval(x - arm * 2, y - arm * 2, arm * 4, arm * 4);
        gc.setStroke(colour);
        gc.setLineWidth(Math.max(1.6, 2.4 * zoom));
        gc.setGlobalAlpha(alpha);
        gc.strokeLine(x - arm, y - arm, x + arm, y + arm);
        gc.strokeLine(x - arm, y + arm, x + arm, y - arm);
        gc.setGlobalAlpha(1);
    }

    // Helpers

    static Color colourOf(Endpoint endpoint) {
        return colourOf(bandOf(endpoint));
    }

    private static Color colourOf(Band band) {
        return switch (band) {
            case EXPOSED -> ALERT;
            case OPEN -> AMBER;
            case SHIELDED -> CYAN;
            case PRIVATE -> VIOLET;
            case LOCAL -> GREEN;
        };
    }

    static Color colourOfFirewall(Firewall firewall) {
        return switch (firewall.state()) {
            case ACTIVE -> GREEN;
            case INACTIVE, ABSENT -> ALERT;
            case UNREADABLE, UNRECOGNISED, UNCHECKED -> AMBER;
        };
    }

    private static Band bandOf(Endpoint endpoint) {
        if (endpoint.isOpenToTheWorld()) {
            return Risk.isSensitive(endpoint.port()) ? Band.EXPOSED : Band.OPEN;
        }
        return switch (endpoint.reach()) {
            case SHIELDED -> Band.SHIELDED;
            case PRIVATE -> Band.PRIVATE;
            case ISOLATED, OPEN -> Band.LOCAL;
        };
    }

    /** Points spread evenly over a unit sphere. */
    private static double[][] sphere(int count) {
        double[][] points = new double[count][];
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < count; i++) {
            double y = 1 - (i / (double) (count - 1)) * 2;
            double ring = Math.sqrt(1 - y * y);
            double theta = golden * i;
            points[i] = new double[]{Math.cos(theta) * ring, y, Math.sin(theta) * ring};
        }
        return points;
    }

    /** A point along the curve {@link #curve} draws. */
    private static double[] bezier(double x1, double y1, double x2, double y2, double t) {
        double c = (x1 + x2) / 2;
        double u = 1 - t;
        double x = u * u * u * x1 + 3 * u * u * t * c + 3 * u * t * t * c + t * t * t * x2;
        double y = u * u * u * y1 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y2;
        return new double[]{x, y};
    }

    private static double fract(double value) {
        return value - Math.floor(value);
    }

    private static boolean same(Endpoint a, Endpoint b) {
        return a != null && b != null && a.port() == b.port()
                && a.socket().bindAddress().equals(b.socket().bindAddress())
                && a.socket().protocol() == b.socket().protocol();
    }

    private static double width(String text, Font font) {
        Text measure = new Text(text);
        measure.setFont(font);
        return measure.getLayoutBounds().getWidth();
    }

    private static String clip(String text, Font font, double room) {
        if (room <= 14) {
            return "";
        }
        if (width(text, font) <= room) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && width(cut + "…", font) > room) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    static String web(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", Math.round(color.getRed() * 255),
                Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
    }
}
