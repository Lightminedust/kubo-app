package com.kubo.ui.network;

import com.kubo.model.network.Endpoint;
import com.kubo.model.network.Firewall;
import com.kubo.model.network.NetworkMap;
import com.kubo.model.network.Risk;
import com.kubo.model.network.ServiceOwner;
import com.kubo.model.network.Vhost;
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
import java.util.function.Consumer;

/**
 * The way in, drawn as a living tree of particles: the internet is an arc of light on the left, the server an arc on
 * the right, and between them the traffic branches — first into the kinds of reach a port can have, then into the
 * ports themselves.
 *
 * <p>Filaments run left to right, the direction a request travels. A branch the internet can really reach carries
 * light all the way to the server; a branch the firewall stops has its filament cut at the membrane; a branch that
 * only exists inside the machine never touches the internet arc at all. Clicking a port lights its own relations —
 * the domains that reach it and everything else its process holds — and dims the rest.
 */
final class NetworkTree extends Pane {

    /** A right-click on a port, in screen coordinates. */
    record MenuRequest(Endpoint endpoint, double screenX, double screenY) {
    }

    /** The kinds of reach, which are the first branching of the tree. */
    private enum Band {
        EXPOSED("EXPOSED", "REACHABLE FROM THE INTERNET, AND SHOULD NOT BE"),
        OPEN("OPEN", "REACHABLE FROM THE INTERNET"),
        SHIELDED("SHIELDED", "BOUND TO EVERY INTERFACE, STOPPED BY THE FIREWALL"),
        PRIVATE("PRIVATE", "ON A PRIVATE NETWORK ONLY"),
        LOCAL("LOCAL", "LOOPBACK ONLY, NOTHING OUTSIDE CAN OPEN IT");

        final String title;
        final String note;

        Band(String title, String note) {
            this.title = title;
            this.note = note;
        }
    }

    private static final Color BACKGROUND = Color.web("#04040a");
    private static final Color TEXT = Color.web("#eaeaf2");
    private static final Color DIM = Color.web("#a9a9bb");
    private static final Color MUTED = Color.web("#6b6b80");
    private static final Color FAINT = Color.web("#1b1b26");

    private static final Color ALERT = Color.web("#ff2d55");
    private static final Color AMBER = Color.web("#ffb347");
    private static final Color CYAN = Color.web("#35e8ff");
    private static final Color VIOLET = Color.web("#8a4dff");
    private static final Color GREEN = Color.web("#35e0a1");
    private static final Color MAGENTA = Color.web("#ff3fd8");

    // The world, left to right: internet, firewall membrane, bands, ports, server.
    private static final double INTERNET_X = -820;
    private static final double MEMBRANE_X = -470;
    private static final double BAND_X = -90;
    private static final double PORT_X = 430;
    private static final double SERVER_X = 1420;
    /** How wide the whole figure is, which is what framing has to fit. */
    private static final double SPAN = SERVER_X - INTERNET_X + 320;
    /** The middle of the figure, so framing centres on it rather than on a guessed number. */
    private static final double CENTRE_X = (INTERNET_X + SERVER_X) / 2;
    /** The mirror's own thickness, in world units. */
    private static final double MIRROR_WIDTH = 96;

    private static final double PORT_ROW = 74;
    private static final double BAND_GAP = 78;
    private static final double FRICTION = 4.2;
    private static final double MIN_ZOOM = 0.22;
    private static final double MAX_ZOOM = 2.4;

    private static final Font BAND_FONT = Font.font("Segoe UI", FontWeight.SEMI_BOLD, 14);
    private static final Font PORT_FONT = Font.font("Consolas", FontWeight.BOLD, 13);
    private static final Font NAME_FONT = Font.font("Consolas", 11.5);
    private static final Font SMALL = Font.font("Segoe UI", 9.5);
    private static final Font ARC_FONT = Font.font("Segoe UI", FontWeight.SEMI_BOLD, 12);

    /** One branch of the tree: a kind of reach, its ports, and where it sits. */
    private static final class Branch {
        final Band band;
        final List<Endpoint> ports = new ArrayList<>();
        double y;
        double height;
        boolean open = true;
        double openness = 1;
        double glow;

        Branch(Band band) {
            this.band = band;
        }
    }

    private final Canvas canvas = new Canvas();
    private final LiveTimer timer;

    private NetworkMap map = NetworkMap.empty();
    private final Map<Band, Branch> branches = new LinkedHashMap<>();
    private List<Branch> shown = List.of();
    private String hostname = "SERVER";

    private Endpoint selected;
    private Endpoint hovered;
    private Band hoveredBand;
    private boolean loading;
    private boolean showLocal = true;

    private double camX;
    private double camY;
    private double targetX;
    private double targetY;
    private double zoom = 0.68;
    private double targetZoom = 0.68;
    private double velocityX;
    private double velocityY;
    private boolean dragging;
    private double pressX;
    private double pressY;
    private double lastX;
    private double lastY;
    private long lastNanos;
    private boolean placed;

    // Type follows the zoom, held between bounds so it never outgrows a row nor vanishes entirely.
    private double textScale = 1;
    private Font bandFont = BAND_FONT;
    private Font portFont = PORT_FONT;
    private Font nameFont = NAME_FONT;
    private Font smallFont = SMALL;

    private double clock;
    private double lastTime = -1;
    private boolean live = true;
    /** 0 while nothing is chosen, 1 once a port has the stage to itself. */
    private double focus;

    private Consumer<Endpoint> onSelect = endpoint -> {
    };
    private Consumer<MenuRequest> onMenu = request -> {
    };

    NetworkTree() {
        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinSize(0, 0);
        for (Band band : Band.values()) {
            Branch branch = new Branch(band);
            branches.put(band, branch);
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

    void setHostname(String hostname) {
        this.hostname = hostname == null || hostname.isBlank() ? "SERVER" : hostname;
    }

    void setMap(NetworkMap map) {
        this.map = map == null ? NetworkMap.empty() : map;
        rebuild();
        if (selected != null) {
            selected = allPorts().stream().filter(port -> same(port, selected)).findFirst().orElse(null);
        }
        if (!placed && !shown.isEmpty()) {
            placed = true;
            fit();
        }
    }

    void setShowIsolated(boolean showLocal) {
        this.showLocal = showLocal;
        rebuild();
    }

    boolean showsIsolated() {
        return showLocal;
    }

    void setLoading(boolean loading) {
        this.loading = loading;
    }

    void select(Endpoint endpoint) {
        selected = endpoint;
        if (endpoint != null) {
            Branch branch = branches.get(bandOf(endpoint));
            branch.open = true;
        }
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
        for (Branch branch : shown) {
            branch.openness = branch.open ? 1 : 0;
        }
        focus = selected == null ? 0 : 1;
        placeBranches();
        paint();
    }

    /** True while the view is dragged, gliding or easing: the moments worth drawing at full rate. */
    private boolean moving() {
        return dragging
                || Math.abs(velocityX) > 2 || Math.abs(velocityY) > 2
                || Math.abs(targetX - camX) > 0.5 || Math.abs(targetY - camY) > 0.5
                || Math.abs(targetZoom - zoom) > 0.001;
    }

    void zoomIn() {
        targetZoom = clamp(targetZoom * 1.2, MIN_ZOOM, MAX_ZOOM);
    }

    void zoomOut() {
        targetZoom = clamp(targetZoom / 1.2, MIN_ZOOM, MAX_ZOOM);
    }

    void fit() {
        placeBranches();
        double height = Math.max(240, totalHeight() + 160);
        double available = Math.max(200, getHeight() < 1 ? 620 : getHeight());
        double availableWidth = Math.max(200, getWidth() < 1 ? 1000 : getWidth());
        targetZoom = clamp(Math.min(available / height, availableWidth / SPAN), MIN_ZOOM, 0.95);
        targetX = CENTRE_X;
        targetY = 0;
        velocityX = 0;
        velocityY = 0;
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

    private void rebuild() {
        branches.values().forEach(branch -> branch.ports.clear());
        for (Endpoint endpoint : map.endpoints()) {
            Band band = bandOf(endpoint);
            if (band == Band.LOCAL && !showLocal) {
                continue;
            }
            branches.get(band).ports.add(endpoint);
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

    /**
     * Stacks the branches, each tall enough for the ports it is showing.
     */
    private void placeBranches() {
        double y = 0;
        for (Branch branch : shown) {
            branch.height = 40 + branch.ports.size() * PORT_ROW * branch.openness;
            branch.y = y + branch.height / 2;
            y += branch.height + BAND_GAP;
        }
        double total = Math.max(0, y - BAND_GAP);
        // Centre the whole tree on the world origin.
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

    private double portY(Branch branch, int index) {
        double spread = branch.ports.size() * PORT_ROW * branch.openness;
        return branch.y - spread / 2 + PORT_ROW * branch.openness * (index + 0.5);
    }

    // Geometry

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

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
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
        Endpoint port = portAt(event.getX(), event.getY());
        if (port != null) {
            selected = same(port, selected) ? null : port;
            onSelect.accept(selected);
            if (event.getButton() == MouseButton.SECONDARY && selected != null) {
                onMenu.accept(new MenuRequest(port, event.getScreenX(), event.getScreenY()));
            }
            return;
        }
        Band band = bandAt(event.getX(), event.getY());
        if (band != null) {
            Branch branch = branches.get(band);
            branch.open = !branch.open;
            return;
        }
        if (selected != null) {
            selected = null;
            onSelect.accept(null);
        }
    }

    private void handleScroll(ScrollEvent event) {
        if (event.isControlDown()) {
            double before = targetZoom;
            targetZoom = clamp(targetZoom * (event.getDeltaY() > 0 ? 1.1 : 1 / 1.1), MIN_ZOOM, MAX_ZOOM);
            double wx = worldX(event.getX());
            double wy = worldY(event.getY());
            double factor = 1 - before / targetZoom;
            targetX += (wx - targetX) * factor;
            targetY += (wy - targetY) * factor;
        } else {
            velocityY = clamp(velocityY - event.getDeltaY() * 7 / zoom, -5000, 5000);
            velocityX = clamp(velocityX - event.getDeltaX() * 7 / zoom, -5000, 5000);
        }
        event.consume();
    }

    private Endpoint portAt(double screenX, double screenY) {
        double x = screenX(PORT_X);
        for (Branch branch : shown) {
            if (branch.openness < 0.4) {
                continue;
            }
            for (int i = 0; i < branch.ports.size(); i++) {
                double y = screenY(portY(branch, i));
                if (screenX > x - 30 * zoom && screenX < x + 300 * zoom && Math.abs(screenY - y) < 22 * zoom) {
                    return branch.ports.get(i);
                }
            }
        }
        return null;
    }

    private Band bandAt(double screenX, double screenY) {
        double x = screenX(BAND_X);
        for (Branch branch : shown) {
            if (screenX > x - 130 * zoom && screenX < x + 130 * zoom
                    && Math.abs(screenY - screenY(branch.y)) < 30 * zoom) {
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
        focus += ((selected == null ? 0 : 1) - focus) * Math.min(1, dt * 6);
        for (Branch branch : shown) {
            branch.openness += ((branch.open ? 1 : 0) - branch.openness) * Math.min(1, dt * 7);
            double wanted = branch.band == hoveredBand ? 1 : 0;
            branch.glow += (wanted - branch.glow) * Math.min(1, dt * 8);
        }
        placeBranches();
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
        scaleType();
        drawDust(gc, width, height);

        if (shown.isEmpty()) {
            gc.setFill(MUTED);
            gc.setFont(BAND_FONT);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setTextBaseline(VPos.CENTER);
            gc.fillText(loading ? "LISTENING TO THE SERVER…" : "NOTHING IS LISTENING", width / 2, height / 2);
            return;
        }

        drawInternet(gc);
        drawMembrane(gc);
        for (Branch branch : shown) {
            drawBranch(gc, branch);
        }
        drawServer(gc);
        drawLegend(gc, width, height);
    }

    /**
     * Sizes the type for the current zoom. Below the thresholds a row drops its least important line rather than
     * letting two lines overlap, the way a map drops labels as you pull away from it.
     */
    private void scaleType() {
        textScale = clamp(zoom / 0.58, 0.74, 1.25);
        bandFont = Font.font("Segoe UI", FontWeight.SEMI_BOLD, 14 * textScale);
        portFont = Font.font("Consolas", FontWeight.BOLD, 13 * textScale);
        nameFont = Font.font("Consolas", 11.5 * textScale);
        smallFont = Font.font("Segoe UI", 9.5 * textScale);
    }

    /** True when there is room under a port for its second line. */
    private boolean showsDetail() {
        return PORT_ROW * zoom > 34;
    }

    /** True when there is room beside a port for the name of what holds it. */
    private boolean showsName() {
        return PORT_ROW * zoom > 22;
    }

    /**
     * The quiet field of motes the whole picture floats in.
     */
    private void drawDust(GraphicsContext gc, double width, double height) {
        gc.setFill(FAINT);
        for (int i = 0; i < 90; i++) {
            double seed = i * 12.9898;
            double x = (fract(Math.sin(seed) * 43758.5453) * width
                    + (live ? Math.sin(clock * 0.07 + i) * 14 : 0) + width) % width;
            double y = (fract(Math.sin(seed * 1.7) * 21421.1) * height
                    + (live ? Math.cos(clock * 0.05 + i) * 10 : 0) + height) % height;
            double size = 0.8 + fract(Math.sin(seed * 3.1) * 9111.3) * 1.4;
            gc.setGlobalAlpha(0.25 + 0.3 * fract(Math.sin(seed * 5.3) * 1234.5));
            gc.fillOval(x, y, size, size);
        }
        gc.setGlobalAlpha(1);
    }

    /**
     * The internet: an arc of magenta light, opening towards the server, made of particles.
     */
    private void drawInternet(GraphicsContext gc) {
        double cx = screenX(INTERNET_X);
        double cy = screenY(0);
        double radius = 240 * zoom;
        double half = Math.PI * 0.42;
        long reachable = shown.stream().filter(branch -> branch.band == Band.EXPOSED || branch.band == Band.OPEN)
                .mapToLong(branch -> branch.ports.size()).sum();

        // The arc itself, drawn as a dense run of particles that thickens at the middle.
        for (int i = 0; i < 320; i++) {
            double t = i / 319.0;
            double angle = -half + t * half * 2;
            double jitter = live ? Math.sin(clock * 1.2 + i * 0.35) * 2.2 : 0;
            double r = radius + jitter;
            double x = cx + Math.cos(angle) * r;
            double y = cy + Math.sin(angle) * r * 1.02;
            double weight = Math.cos(angle * 1.1);
            gc.setFill(MAGENTA);
            gc.setGlobalAlpha(0.25 + 0.6 * weight * weight);
            double size = (1 + 1.7 * weight * weight) * Math.max(0.5, zoom);
            gc.fillOval(x - size / 2, y - size / 2, size, size);
        }
        // A spray of motes behind the arc, the crowd beyond the wire.
        for (int i = 0; i < 150; i++) {
            double seed = i * 7.77;
            double angle = -half + fract(Math.sin(seed) * 4137.1) * half * 2;
            double away = radius - 10 - fract(Math.sin(seed * 2.3) * 8231.7) * 150 * zoom;
            double drift = live ? Math.sin(clock * 0.5 + i) * 4 : 0;
            double x = cx + Math.cos(angle) * (away + drift);
            double y = cy + Math.sin(angle) * (away + drift) * 1.02;
            gc.setFill(MAGENTA);
            gc.setGlobalAlpha(0.10 + 0.2 * fract(Math.sin(seed * 3.9) * 991.3));
            double size = 1.2 * Math.max(0.5, zoom);
            gc.fillOval(x, y, size, size);
        }
        gc.setGlobalAlpha(1);

        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        double ix = cx + radius * 0.5;
        double iy = cy - radius * 1.02 - 44;
        gc.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 12 * textScale));
        gc.setFill(MAGENTA);
        gc.fillText("INTERNET", ix, iy);
        gc.setFont(smallFont);
        gc.setFill(MUTED);
        gc.fillText(reachable + (reachable == 1 ? " WAY IN" : " WAYS IN") + "   ·   REQUESTS FLOW →", ix, iy + 16);
    }

    /**
     * The firewall, as a standing pane of mirror glass across the path.
     *
     * <p>A pane says what a line cannot: light that reaches it either passes through or comes back. A branch the
     * firewall stops has its filament end here and a reflection thrown back toward the internet, which is what the
     * cross on it means — this far and no further.
     *
     * <p>The glass is whole and lit while the firewall enforces, shattered and red when it genuinely filters
     * nothing, and fogged amber when Kubo could not read it: an unreadable firewall is not an absent one, and the
     * pane must not look like a hole.
     */
    private void drawMembrane(GraphicsContext gc) {
        Firewall firewall = map.firewall();
        boolean enforcing = firewall.isEnforcing();
        boolean known = firewall.isKnown();
        double x = screenX(MEMBRANE_X);
        double half = MIRROR_WIDTH * zoom / 2;
        double top = Math.max(18, screenY(-totalHeight() / 2 - 120));
        double bottom = Math.min(canvas.getHeight() - 30, screenY(totalHeight() / 2 + 120));
        if (bottom <= top) {
            return;
        }
        Color colour = enforcing ? CYAN : known ? ALERT : AMBER;
        double height = bottom - top;

        // The glass itself: darkest at its faces, brightest through the middle, the way a pane seen edge-on reads.
        gc.setGlobalAlpha(1);
        gc.setFill(new LinearGradient(x - half, 0, x + half, 0, false, CycleMethod.NO_CYCLE,
                new Stop(0, colour.deriveColor(0, 1, 1, 0)),
                new Stop(0.35, colour.deriveColor(0, 1, 1, enforcing ? 0.16 : 0.07)),
                new Stop(0.5, colour.deriveColor(0, 1, 1, enforcing ? 0.26 : 0.10)),
                new Stop(0.65, colour.deriveColor(0, 1, 1, enforcing ? 0.16 : 0.07)),
                new Stop(1, colour.deriveColor(0, 1, 1, 0))));
        gc.fillRect(x - half, top, half * 2, height);

        // Its two edges, where the light gathers on real glass.
        gc.setStroke(colour.deriveColor(0, 1, 1, enforcing ? 0.85 : 0.4));
        gc.setLineWidth(Math.max(1, 1.6 * zoom));
        gc.strokeLine(x - half, top, x - half, bottom);
        gc.setStroke(colour.deriveColor(0, 1, 1, enforcing ? 0.55 : 0.25));
        gc.strokeLine(x + half, top, x + half, bottom);

        if (enforcing) {
            // A sheen travelling down the pane: the one thing that makes a surface read as a mirror rather than a
            // coloured bar.
            double travel = live ? (clock * 0.16) % 1.4 - 0.2 : 0.45;
            double sheenY = top + travel * height;
            double sheenHeight = Math.max(40, height * 0.22);
            gc.setFill(new LinearGradient(0, sheenY - sheenHeight / 2, 0, sheenY + sheenHeight / 2, false,
                    CycleMethod.NO_CYCLE,
                    new Stop(0, colour.deriveColor(0, 1, 1, 0)),
                    new Stop(0.5, colour.deriveColor(0, 1, 1, 0.34)),
                    new Stop(1, colour.deriveColor(0, 1, 1, 0))));
            gc.fillRect(x - half, Math.max(top, sheenY - sheenHeight / 2),
                    half * 2, Math.min(sheenHeight, bottom - Math.max(top, sheenY - sheenHeight / 2)));

            // Faint horizontal rulings, so the surface has a grain to reflect in.
            gc.setStroke(colour);
            gc.setLineWidth(0.7);
            for (double y = top; y < bottom; y += 9 * Math.max(0.6, zoom)) {
                gc.setGlobalAlpha(0.05 + 0.05 * Math.sin((y + clock * 30) * 0.08));
                gc.strokeLine(x - half, y, x + half, y);
            }
            gc.setGlobalAlpha(1);
        } else if (known) {
            // Nothing filters: the pane is broken, and you can see straight through the gaps.
            gc.setStroke(ALERT);
            gc.setLineWidth(Math.max(1, 1.2 * zoom));
            for (int i = 0; i < 16; i++) {
                double y = top + (i + fract(Math.sin(i * 31.7) * 4113.1)) * height / 16;
                double lean = (fract(Math.sin(i * 12.9) * 2711.3) - 0.5) * half * 3;
                gc.setGlobalAlpha(0.45);
                gc.strokeLine(x - half, y, x + half + lean, y + height / 40);
            }
            gc.setGlobalAlpha(1);
        } else {
            // Kubo cannot see through it: fogged, not open.
            gc.setFill(AMBER);
            for (int i = 0; i < 90; i++) {
                double y = top + fract(Math.sin(i * 17.3) * 3271.7) * height;
                double dx = (fract(Math.sin(i * 7.1) * 1291.7) - 0.5) * half * 2;
                gc.setGlobalAlpha(0.10);
                double size = 2.4 * Math.max(0.5, zoom);
                gc.fillOval(x + dx - size / 2, y - size / 2, size, size);
            }
            gc.setGlobalAlpha(1);
        }

        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.BOTTOM);
        gc.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 12 * textScale));
        gc.setFill(colour);
        gc.fillText(firewall.title(), x, Math.max(30, top - 26));
        gc.setFont(smallFont);
        gc.setFill(enforcing ? MUTED : colour);
        gc.fillText(switch (firewall.state()) {
            case ACTIVE -> "ACTIVE  \u00b7  " + map.openPorts().size() + " PORTS ALLOWED";
            case INACTIVE -> "NOT FILTERING  \u00b7  EVERY PORT IS REACHABLE";
            case ABSENT -> "NO FIREWALL ON THIS MACHINE";
            case UNREADABLE -> "COULD NOT BE READ  \u00b7  NEEDS ROOT";
            case UNRECOGNISED -> "RUNNING  \u00b7  CANNOT READ ITS RULES";
        }, x, Math.max(46, top - 10));
    }

    /**
     * One kind of reach: its node, its filament from the internet, and the ports hanging off it.
     */
    private void drawBranch(GraphicsContext gc, Branch branch) {
        Color colour = colourOf(branch.band);
        double bx = screenX(BAND_X);
        double by = screenY(branch.y);
        boolean fromOutside = branch.band == Band.EXPOSED || branch.band == Band.OPEN;
        boolean stopped = branch.band == Band.SHIELDED;
        double dim = dimming(branch);

        // Internet → band. Only the bands the internet can actually reach are fed.
        if (fromOutside) {
            filament(gc, screenX(INTERNET_X) + 240 * zoom, screenY(0), bx, by, colour, 0.5 * dim, true, branch.band.ordinal());
        } else if (stopped) {
            // It arrives at the glass and comes back: the reflection is the firewall refusing it, and the cross is
            // where that happens.
            double mirror = screenX(MEMBRANE_X) - MIRROR_WIDTH * zoom / 2;
            double from = screenX(INTERNET_X) + 240 * zoom;
            filament(gc, from, screenY(0), mirror, by, colour, 0.22 * dim, false, branch.band.ordinal());
            filament(gc, mirror, by, from + (mirror - from) * 0.45, by + (screenY(0) - by) * 0.35,
                    colour, 0.18 * dim, false, branch.band.ordinal() + 11);
            cross(gc, mirror, by, CYAN, dim);
        }

        // The band node: a knot of particles.
        drawKnot(gc, bx, by, colour, 26 * zoom, branch.glow, dim);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFont(bandFont);
        gc.setGlobalAlpha(dim);
        gc.setFill(colour);
        gc.fillText(branch.band.title, bx - 42 * textScale, by - 9 * textScale);
        gc.setFont(smallFont);
        gc.setFill(MUTED);
        gc.fillText(branch.ports.size() + (branch.ports.size() == 1 ? " PORT" : " PORTS")
                + (branch.open ? "" : "  ·  CLICK TO OPEN"), bx - 42 * textScale, by + 11 * textScale);
        gc.setGlobalAlpha(1);

        if (branch.openness < 0.05) {
            return;
        }
        for (int i = 0; i < branch.ports.size(); i++) {
            drawPort(gc, branch, i, colour);
        }
    }

    /**
     * How much of its brightness something keeps while one port has the stage.
     */
    private double dimming(Branch branch) {
        if (focus < 0.02 || selected == null) {
            return 1;
        }
        boolean related = branch.ports.stream().anyMatch(this::isRelated);
        return related ? 1 : 1 - 0.78 * focus;
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

    private void drawPort(GraphicsContext gc, Branch branch, int index, Color colour) {
        double bx = screenX(BAND_X);
        double by = screenY(branch.y);
        double x = screenX(PORT_X);
        double y = screenY(portY(branch, index));
        Endpoint endpoint = branch.ports.get(index);
        boolean isSelected = same(endpoint, selected);
        boolean isHovered = same(endpoint, hovered);
        double alpha = branch.openness;
        double dim = selected == null ? 1 : isRelated(endpoint) ? 1 : 1 - 0.8 * focus;

        // Band → port.
        filament(gc, bx + 26 * zoom, by, x - 16 * zoom, y, colour, 0.55 * alpha * dim, true, index + branch.band.ordinal() * 7);

        // Port → server: everything that listens ultimately lands on the machine.
        filament(gc, x + 16 * zoom, y, screenX(SERVER_X) - 215 * zoom, screenY(0), CYAN, 0.28 * alpha * dim, true,
                index * 3 + 1);

        gc.setGlobalAlpha(alpha * dim);
        double radius = (isSelected ? 15 : 12) * zoom;
        drawKnot(gc, x, y, colour, radius, isSelected ? 1 : isHovered ? 0.6 : 0, alpha * dim);

        boolean detail = showsDetail();
        double lift = detail ? 9 * textScale : 0;
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFont(portFont);
        gc.setFill(isSelected || isHovered ? TEXT : colour);
        gc.fillText(String.valueOf(endpoint.port()), x + 26 * textScale, y - lift);

        if (showsName()) {
            gc.setFont(nameFont);
            gc.setFill(isSelected ? TEXT : DIM);
            gc.fillText(clip(endpoint.owner().label(), 22), x + 86 * textScale, y - lift);
        }

        if (detail) {
            gc.setFont(smallFont);
            gc.setFill(MUTED);
            String kind = switch (endpoint.owner().kind()) {
                case PM2 -> "PM2";
                case DOCKER -> "DOCKER";
                case SYSTEM -> "SYSTEM";
                case UNKNOWN -> "UNNAMED";
            };
            gc.fillText(kind + "  ·  " + endpoint.socket().bindAddress() + "  ·  " + endpoint.socket().protocol(),
                    x + 26 * textScale, y + 12 * textScale);
        }

        // The domains that reach this port, hanging just after it.
        if (endpoint.isBehindNginx()) {
            double dx = x + 268 * textScale;
            gc.setGlobalAlpha(alpha * dim * 0.9);
            int shown = showsDetail() ? 2 : 1;
            for (int i = 0; i < endpoint.servedBy().size() && i < shown; i++) {
                Vhost vhost = endpoint.servedBy().get(i);
                gc.setFont(smallFont);
                gc.setFill(vhost.ssl() ? GREEN : AMBER);
                gc.fillText("◇ " + clip(vhost.title(), 16), dx, y - lift + i * 15 * textScale);
            }
        }
        gc.setGlobalAlpha(1);
    }

    /**
     * The server: the cyan arc everything lands on, facing the internet.
     */
    private void drawServer(GraphicsContext gc) {
        double cx = screenX(SERVER_X);
        double cy = screenY(0);
        double radius = 210 * zoom;
        double half = Math.PI * 0.42;

        for (int i = 0; i < 320; i++) {
            double t = i / 319.0;
            double angle = Math.PI - half + t * half * 2;
            double jitter = live ? Math.sin(clock * 1.1 + i * 0.3) * 2.2 : 0;
            double r = radius + jitter;
            double x = cx + Math.cos(angle) * r;
            double y = cy + Math.sin(angle) * r * 1.02;
            double weight = Math.cos((angle - Math.PI) * 1.1);
            gc.setFill(CYAN);
            gc.setGlobalAlpha(0.25 + 0.6 * weight * weight);
            double size = (1 + 1.7 * weight * weight) * Math.max(0.5, zoom);
            gc.fillOval(x - size / 2, y - size / 2, size, size);
        }
        for (int i = 0; i < 150; i++) {
            double seed = i * 5.31;
            double angle = Math.PI - half + fract(Math.sin(seed) * 3311.7) * half * 2;
            double away = radius - 10 - fract(Math.sin(seed * 2.1) * 7717.3) * 150 * zoom;
            double drift = live ? Math.cos(clock * 0.45 + i) * 4 : 0;
            gc.setFill(CYAN);
            gc.setGlobalAlpha(0.10 + 0.2 * fract(Math.sin(seed * 4.4) * 881.1));
            double size = 1.2 * Math.max(0.5, zoom);
            gc.fillOval(cx + Math.cos(angle) * (away + drift), cy + Math.sin(angle) * (away + drift) * 1.02,
                    size, size);
        }
        gc.setGlobalAlpha(1);

        // The caption sits clear above the arc, where nothing can run into the curve.
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        double sx = cx - radius * 0.5;
        double sy = cy - radius * 1.02 - 44;
        gc.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 12 * textScale));
        gc.setFill(CYAN);
        gc.fillText(clip(hostname, 18), sx, sy);
        gc.setFont(smallFont);
        gc.setFill(MUTED);
        gc.fillText("THIS SERVER  ·  " + map.endpoints().size() + " LISTENING PORTS", sx, sy + 16);
        if (loading) {
            gc.setStroke(CYAN);
            gc.setLineWidth(1.6);
            double r = 26;
            gc.strokeArc(sx - r, sy + 44 - r, r * 2, r * 2, -(clock * 240) % 360, 90, ArcType.OPEN);
        }
    }

    /**
     * A filament of light between two points, with particles running along it in the direction of travel.
     */
    private void filament(GraphicsContext gc, double x1, double y1, double x2, double y2, Color colour,
                          double strength, boolean flowing, int seed) {
        if (strength <= 0.01 || x2 <= x1) {
            return;
        }
        double c1 = x1 + (x2 - x1) * 0.45;
        double c2 = x2 - (x2 - x1) * 0.45;
        gc.setStroke(colour);
        gc.setLineWidth(Math.max(0.6, 1.1 * zoom));
        gc.setGlobalAlpha(strength * 0.5);
        gc.beginPath();
        gc.moveTo(x1, y1);
        gc.bezierCurveTo(c1, y1, c2, y2, x2, y2);
        gc.stroke();

        if (!flowing || !live) {
            gc.setGlobalAlpha(1);
            return;
        }
        for (int i = 0; i < 4; i++) {
            double t = (clock * 0.34 + i / 4.0 + (seed % 9) * 0.07) % 1;
            double[] point = bezier(x1, y1, c1, c2, x2, y2, t);
            double size = (1.4 + 1.6 * Math.sin(Math.PI * t)) * Math.max(0.5, zoom);
            gc.setFill(colour);
            gc.setGlobalAlpha(strength * Math.sin(Math.PI * t) * 1.5);
            gc.fillOval(point[0] - size / 2, point[1] - size / 2, size, size);
        }
        gc.setGlobalAlpha(1);
    }

    /**
     * A node drawn as a knot of particles rather than a plain disc.
     */
    private void drawKnot(GraphicsContext gc, double x, double y, Color colour, double radius, double glow,
                          double alpha) {
        gc.setFill(colour.deriveColor(0, 1, 1, 0.16 + 0.2 * glow));
        gc.setGlobalAlpha(alpha);
        gc.fillOval(x - radius, y - radius, radius * 2, radius * 2);
        int count = 26;
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2 / count + (live ? clock * (0.2 + 0.1 * glow) : 0);
            double r = radius * (0.45 + 0.5 * fract(Math.sin(i * 12.3) * 4421.7))
                    + (live ? Math.sin(clock * 2 + i) * 1.6 : 0);
            double size = (1 + 1.2 * fract(Math.sin(i * 7.1) * 991.7)) * Math.max(0.5, zoom);
            gc.setFill(colour);
            gc.setGlobalAlpha(alpha * (0.4 + 0.6 * glow));
            gc.fillOval(x + Math.cos(angle) * r - size / 2, y + Math.sin(angle) * r * 0.92 - size / 2, size, size);
        }
        gc.setFill(colour);
        gc.setGlobalAlpha(alpha);
        double core = radius * 0.28;
        gc.fillOval(x - core, y - core, core * 2, core * 2);
        gc.setGlobalAlpha(1);
    }

    /**
     * The mark where a branch meets the firewall and goes no further. It is drawn at a readable size whatever the
     * zoom, the way a callout on a drawing stays legible, because it carries the whole meaning of that branch.
     */
    private void cross(GraphicsContext gc, double x, double y, Color colour, double alpha) {
        double arm = 8 * textScale;
        gc.setFill(colour.deriveColor(0, 1, 1, 0.16 * alpha));
        double halo = arm * 2.2;
        gc.fillOval(x - halo, y - halo, halo * 2, halo * 2);
        gc.setStroke(colour);
        gc.setLineWidth(2.2 * textScale);
        gc.setGlobalAlpha(alpha);
        gc.strokeLine(x - arm, y - arm, x + arm, y + arm);
        gc.strokeLine(x - arm, y + arm, x + arm, y - arm);
        gc.setGlobalAlpha(1);
    }

    private void drawLegend(GraphicsContext gc, double width, double height) {
        gc.setFont(SMALL);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BOTTOM);
        gc.setFill(MUTED);
        gc.fillText(selected == null
                        ? "CLICK A PORT TO SEE WHAT REACHES IT  ·  CLICK A BRANCH TO FOLD IT  ·  DRAG TO PAN  ·  "
                        + "CTRL+WHEEL TO ZOOM"
                        : "SHOWING " + selected.port() + " AND EVERYTHING ITS PROCESS HOLDS  ·  CLICK IT AGAIN TO "
                        + "SHOW THEM ALL", 24, height - 14);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFill(loading ? CYAN : MUTED);
        gc.fillText(loading ? "READING…" : Math.round(zoom * 100) + "%", width - 24, height - 14);
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
            case UNREADABLE, UNRECOGNISED -> AMBER;
        };
    }

    private static double[] bezier(double x1, double y1, double c1, double c2, double x2, double y2, double t) {
        double u = 1 - t;
        double x = u * u * u * x1 + 3 * u * u * t * c1 + 3 * u * t * t * c2 + t * t * t * x2;
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

    private static String clip(String text, int max) {
        String clean = text == null ? "" : text.replace('\n', ' ');
        return clean.length() > max ? clean.substring(0, max - 1) + "…" : clean;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    static String describe(Band band) {
        return band.note.toUpperCase(Locale.ROOT);
    }
}
