package com.kubo.ui.packages;

import com.kubo.model.packages.PackageEntry;
import com.kubo.model.packages.PackageInventory;
import com.kubo.model.packages.PackageRole;
import com.kubo.ui.BranchCamera;
import com.kubo.ui.Fibers;
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
import javafx.scene.paint.Paint;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Everything installed, as the branching shape the file tree uses: one column per level, one row per thing.
 *
 * <p>The server is the root. It branches into what packages are <em>for</em> — what the machine needs, shared code,
 * what serves, what builds, what a person runs, what is only text. A role branches into its dpkg sections, and a
 * section into its packages. Rows never move sideways and never share a line, so a thousand packages stay readable
 * at any zoom; what does not fit is dropped rather than drawn over something else.
 */
final class PackageTree extends Pane {

    // World units: one row per thing, one gap per level.
    private static final double ROW = 38;
    private static final double COLUMN_GAP = 400;
    /** A dot never takes more than this share of its row, so rings and halos stay inside it. */
    private static final double DOT_SHARE = 0.26;
    private static final double HEADER = BranchCamera.HEADER_HEIGHT;
    /** Below this many pixels between rows, names would touch, so they are dropped instead of overlapped. */
    private static final double LABEL_MIN_ROW = 11;
    private static final int OFFSCREEN_FIBERS = 60;
    private static final int GRADIENT_LIMIT = 160;

    private static final Color BACKGROUND = PackagePalette.BACKGROUND;
    private static final Color TEXT = PackagePalette.TEXT;
    private static final Color DIM = PackagePalette.DIM;
    private static final Color QUIET = PackagePalette.QUIET;
    private static final Color MUTED = PackagePalette.MUTED;
    private static final Color FAINT = PackagePalette.FAINT;
    private static final Color ALERT = PackagePalette.ALERT;
    private static final Color AMBER = PackagePalette.AMBER;
    private static final Color ROOT_COLOR = PackagePalette.ROOT;

    private static final Font HEADER_FONT = Font.font("Segoe UI", FontWeight.SEMI_BOLD, 10.5);
    private static final Font SMALL_FONT = Font.font("Segoe UI", 9);

    /** What a row stands for, which decides what opening it does. */
    private enum Kind { ROOT, ROLE, SECTION, PACKAGE }

    private static final class Node {
        Kind kind;
        String key;
        String label;
        PackageRole role;
        String section;
        PackageEntry entry;
        int count;
        long bytes;
        int manual;
        int waiting;
        int security;

        Color colour() {
            if (kind == Kind.ROOT) {
                return ROOT_COLOR;
            }
            return kind == Kind.PACKAGE ? PackagePalette.of(entry, role) : PackagePalette.of(role);
        }

        /** Counts logarithmically, so one huge branch does not dwarf the rest. */
        double radius() {
            if (kind == Kind.PACKAGE) {
                return 2.2 + Math.min(5, Math.log10(1 + Math.max(1, bytes) / 1024.0));
            }
            return 3.4 + Math.min(9, 1.8 * Math.log(1 + count) / Math.log(2));
        }

        /** The share of this branch someone actually asked for. */
        double asked() {
            return count == 0 ? 0 : manual / (double) count;
        }
    }

    private static final class Column {
        String title = "";
        List<Node> nodes = List.of();
        /** The row that opened the next column, or -1. */
        int opened = -1;
        int selected = -1;
        double top;
        double revealStart = -1;
    }

    private final Canvas canvas = new Canvas();
    /** Off-screen, only ever asked how wide a string is in a given font. */
    private final Text ruler = new Text();
    private final BranchCamera camera = new BranchCamera();
    private final LiveTimer timer;
    private final List<Column> columns = new ArrayList<>();

    private PackageInventory inventory = PackageInventory.empty();
    private PackageRole openedRole;
    private String openedSection;
    private String selectedName;
    private boolean askedForOnly;
    private boolean loading;

    private int hoverColumn = -1;
    private int hoverRow = -1;
    private boolean dragged;
    private double clock;
    private double lastTime = -1;

    private BiConsumer<PackageRole, String> onOpen = (role, section) -> {
    };
    private Consumer<PackageEntry> onPackage = entry -> {
    };

    PackageTree() {
        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinSize(0, 0);
        setOnMouseMoved(this::handleMove);
        setOnMouseExited(event -> {
            hoverColumn = -1;
            hoverRow = -1;
        });
        setOnMousePressed(this::handlePress);
        setOnMouseDragged(this::handleDrag);
        setOnMouseReleased(this::handleRelease);
        setOnScroll(this::handleScroll);

        timer = new LiveTimer(this, () -> camera.moving() ? LiveTimer.EVERY_PULSE : LiveTimer.AMBIENT, time -> {
            double dt = lastTime < 0 ? 0 : Math.min(0.1, time - lastTime);
            lastTime = time;
            clock = time;
            camera.step(dt);
            paint();
        });
        timer.start();
    }

    void setOnOpen(BiConsumer<PackageRole, String> onOpen) {
        this.onOpen = onOpen;
    }

    void setOnPackage(Consumer<PackageEntry> onPackage) {
        this.onPackage = onPackage;
    }

    void setInventory(PackageInventory inventory) {
        this.inventory = inventory == null ? PackageInventory.empty() : inventory;
        boolean first = columns.isEmpty();
        rebuild();
        if (first) {
            fit();
        }
    }

    void setLoading(boolean loading) {
        this.loading = loading;
    }

    /**
     * Hides everything that only arrived as a dependency, at every level.
     */
    void setAskedForOnly(boolean askedForOnly) {
        this.askedForOnly = askedForOnly;
        rebuild();
    }

    /**
     * Opens a branch without a click, for previews.
     */
    void openForPreview(PackageRole role, String section) {
        openedRole = role;
        openedSection = section;
        rebuild();
        fit();
    }

    void stop() {
        timer.stop();
    }

    void freeze(double time) {
        timer.stop();
        clock = time;
        camera.camX = camera.targetCamX;
        camera.camY = camera.targetCamY;
        camera.zoom = camera.targetZoom;
        for (Column column : columns) {
            column.revealStart = -10;
        }
        paint();
    }

    /**
     * Zooms without a gesture, for previews.
     */
    void zoomForPreview(int steps) {
        for (int i = 0; i < steps; i++) {
            zoomIn();
        }
        camera.zoom = camera.targetZoom;
        camera.camX = camera.targetCamX;
        camera.camY = camera.targetCamY;
    }

    void zoomIn() {
        camera.zoomAround(1.25, getWidth() / 2, (HEADER + getHeight()) / 2, true);
    }

    void zoomOut() {
        camera.zoomAround(1 / 1.25, getWidth() / 2, (HEADER + getHeight()) / 2, true);
    }

    /**
     * Frames the whole open path.
     */
    void fit() {
        if (columns.isEmpty() || getWidth() < 1) {
            return;
        }
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Column column : columns) {
            if (column.nodes.isEmpty()) {
                continue;
            }
            // A long column is framed around where its parent joins it rather than end to end.
            int anchor = column.opened >= 0 ? column.opened
                    : column.selected >= 0 ? column.selected : column.nodes.size() / 2;
            int from = Math.max(0, anchor - 10);
            int to = Math.min(column.nodes.size() - 1, anchor + 10);
            minY = Math.min(minY, column.top + from * ROW);
            maxY = Math.max(maxY, column.top + to * ROW);
        }
        if (minY > maxY) {
            return;
        }
        // Four levels never fit at a readable size, so framing follows the depth being worked at: the last three
        // columns, with room on the right for the deepest column's names.
        int last = columns.size() - 1;
        double from = columnX(Math.max(0, last - 2)) - 50;
        double to = columnX(last) + COLUMN_GAP * 0.82;
        camera.fit(new double[]{from, to, minY - ROW, maxY + ROW}, getWidth(), getHeight());
    }

    /**
     * Closes everything down to the roles.
     */
    void closeAll() {
        openedRole = null;
        openedSection = null;
        selectedName = null;
        rebuild();
        fit();
        onOpen.accept(null, null);
    }

    // Building the open path

    private void rebuild() {
        List<PackageEntry> all = inventory.packages().stream()
                .filter(entry -> !askedForOnly || entry.manual()).toList();

        Column root = column(0, "THE SERVER");
        Node whole = new Node();
        whole.kind = Kind.ROOT;
        whole.key = "root";
        whole.label = "INSTALLED";
        whole.count = all.size();
        whole.bytes = all.stream().mapToLong(PackageEntry::installedBytes).sum();
        whole.manual = (int) all.stream().filter(PackageEntry::manual).count();
        whole.waiting = (int) all.stream().filter(PackageEntry::upgradable).count();
        whole.security = (int) all.stream().filter(PackageEntry::security).count();
        root.nodes = List.of(whole);
        root.opened = 0;
        root.top = -ROW / 2;

        // Level one: what packages are for.
        Column roles = column(1, "WHAT IT IS FOR");
        Map<PackageRole, List<PackageEntry>> byRole = new LinkedHashMap<>();
        for (PackageRole role : PackageRole.values()) {
            byRole.put(role, new ArrayList<>());
        }
        for (PackageEntry entry : all) {
            byRole.get(entry.role()).add(entry);
        }
        List<Node> roleNodes = new ArrayList<>();
        for (Map.Entry<PackageRole, List<PackageEntry>> entry : byRole.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            Node node = branch(Kind.ROLE, entry.getKey().name(), entry.getKey().title(), entry.getKey(), null,
                    entry.getValue());
            roleNodes.add(node);
        }
        roleNodes.sort(Comparator.comparingInt((Node node) -> node.count).reversed());
        roles.nodes = roleNodes;
        roles.opened = indexOfRole(roleNodes, openedRole);
        roles.selected = roles.opened;
        roles.top = centred(root, 0, roleNodes.size());
        trim(2);
        if (roles.opened < 0) {
            openedRole = null;
            openedSection = null;
            return;
        }

        // Level two: the dpkg sections inside that role.
        Node openedRoleNode = roleNodes.get(roles.opened);
        Column sections = column(2, openedRoleNode.label + " · SECTIONS");
        Map<String, List<PackageEntry>> byFamily = new LinkedHashMap<>();
        for (PackageEntry entry : all) {
            if (entry.role() == openedRole) {
                byFamily.computeIfAbsent(entry.family(), key -> new ArrayList<>()).add(entry);
            }
        }
        List<Node> sectionNodes = new ArrayList<>();
        byFamily.forEach((family, members) -> sectionNodes.add(
                branch(Kind.SECTION, openedRole.name() + "/" + family, family.toUpperCase(Locale.ROOT),
                        openedRole, family, members)));
        sectionNodes.sort(Comparator.comparingInt((Node node) -> node.count).reversed());
        sections.nodes = sectionNodes;
        sections.opened = indexOfSection(sectionNodes, openedSection);
        sections.selected = sections.opened;
        sections.top = centred(roles, roles.opened, sectionNodes.size());
        trim(3);
        if (sections.opened < 0) {
            openedSection = null;
            return;
        }

        // Level three: the packages themselves.
        Node openedSectionNode = sectionNodes.get(sections.opened);
        Column leaves = column(3, openedSectionNode.label + " · PACKAGES");
        List<Node> packageNodes = new ArrayList<>();
        List<PackageEntry> members = new ArrayList<>(byFamily.getOrDefault(openedSection, List.of()));
        // What was asked for first, then the heaviest: the ones a person recognises come to the top.
        members.sort(Comparator.comparing(PackageEntry::manual).reversed()
                .thenComparing(Comparator.comparingLong(PackageEntry::installedKb).reversed())
                .thenComparing(PackageEntry::name));
        for (PackageEntry entry : members) {
            Node node = new Node();
            node.kind = Kind.PACKAGE;
            node.key = entry.name();
            node.label = entry.name();
            node.role = openedRole;
            node.section = openedSection;
            node.entry = entry;
            node.count = 1;
            node.bytes = entry.installedBytes();
            node.manual = entry.manual() ? 1 : 0;
            node.waiting = entry.upgradable() ? 1 : 0;
            node.security = entry.security() ? 1 : 0;
            packageNodes.add(node);
        }
        leaves.nodes = packageNodes;
        leaves.selected = indexOfName(packageNodes, selectedName);
        leaves.top = centred(sections, sections.opened, packageNodes.size());
        trim(4);
    }

    private Node branch(Kind kind, String key, String label, PackageRole role, String section,
                        List<PackageEntry> members) {
        Node node = new Node();
        node.kind = kind;
        node.key = key;
        node.label = label;
        node.role = role;
        node.section = section;
        node.count = members.size();
        node.bytes = members.stream().mapToLong(PackageEntry::installedBytes).sum();
        node.manual = (int) members.stream().filter(PackageEntry::manual).count();
        node.waiting = (int) members.stream().filter(PackageEntry::upgradable).count();
        node.security = (int) members.stream().filter(PackageEntry::security).count();
        return node;
    }

    /**
     * A column hangs level with the row that opened it, so the eye follows one line across the levels.
     */
    private double centred(Column parent, int parentRow, int count) {
        return parent.top + parentRow * ROW - (count - 1) * ROW / 2;
    }

    private Column column(int index, String title) {
        while (columns.size() <= index) {
            Column column = new Column();
            column.revealStart = clock;
            columns.add(column);
        }
        Column column = columns.get(index);
        if (!column.title.equals(title)) {
            // A different branch: it grows out again rather than swapping in place.
            column.revealStart = clock;
        }
        column.title = title;
        column.opened = -1;
        column.selected = -1;
        return column;
    }

    private void trim(int size) {
        while (columns.size() > size) {
            columns.remove(columns.size() - 1);
        }
    }

    private static int indexOfRole(List<Node> nodes, PackageRole role) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).role == role) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfSection(List<Node> nodes, String section) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).section.equals(section)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfName(List<Node> nodes, String name) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).label.equals(name)) {
                return i;
            }
        }
        return -1;
    }

    // Geometry

    private static double columnX(int k) {
        return k * COLUMN_GAP;
    }

    private double nodeY(Column column, int row) {
        return column.top + row * ROW;
    }

    private double sx(double worldX) {
        return camera.sx(worldX);
    }

    private double sy(double worldY) {
        return camera.sy(worldY);
    }

    /**
     * How far a row has grown out of its parent, staggered so a column unfurls rather than blinking on.
     */
    private double progress(Column column, int row) {
        if (column.revealStart < 0) {
            return 1;
        }
        double t = (clock - column.revealStart - row * 0.008) / 0.45;
        return Fibers.clamp(t, 0, 1);
    }

    private int firstVisible(Column column) {
        return (int) Math.max(0, Math.floor((camera.worldY(HEADER) - column.top) / ROW) - 1);
    }

    private int lastVisible(Column column) {
        return (int) Math.min(column.nodes.size() - 1,
                Math.ceil((camera.worldY(getHeight()) - column.top) / ROW) + 1);
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
    }

    // Input

    private void handleMove(MouseEvent event) {
        int[] hit = hit(event.getX(), event.getY());
        hoverColumn = hit == null ? -1 : hit[0];
        hoverRow = hit == null ? -1 : hit[1];
        setCursor(hit == null ? Cursor.DEFAULT : Cursor.HAND);
    }

    private void handlePress(MouseEvent event) {
        dragged = false;
        camera.press(event.getX(), event.getY(), System.nanoTime());
    }

    private void handleDrag(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        dragged = true;
        camera.drag(event.getX(), event.getY(), System.nanoTime());
        setCursor(Cursor.CLOSED_HAND);
    }

    private void handleRelease(MouseEvent event) {
        camera.release(System.nanoTime());
        if (!dragged && Math.hypot(event.getX() - camera.pressX(), event.getY() - camera.pressY()) < 5) {
            open(hit(event.getX(), event.getY()));
        }
        handleMove(event);
    }

    private void handleScroll(ScrollEvent event) {
        if (event.isControlDown()) {
            camera.zoomAround(event.getDeltaY() > 0 ? 1.1 : 1 / 1.1, event.getX(), event.getY(), true);
        } else {
            camera.wheel(event.getDeltaX(), event.getDeltaY());
        }
        event.consume();
    }

    /**
     * The row under a point, or null. The name counts as part of the row, so the small dot is never the only target.
     */
    private int[] hit(double x, double y) {
        if (y < HEADER) {
            return null;
        }
        double worldX = camera.worldX(x);
        double worldY = camera.worldY(y);
        for (int k = 0; k < columns.size(); k++) {
            double dx = worldX - columnX(k);
            if (dx < -18 || dx > COLUMN_GAP - 40) {
                continue;
            }
            Column column = columns.get(k);
            if (column.nodes.isEmpty()) {
                continue;
            }
            int row = (int) Math.round((worldY - column.top) / ROW);
            if (row < 0 || row >= column.nodes.size()) {
                continue;
            }
            if (Math.abs(worldY - nodeY(column, row)) > ROW * 0.5) {
                continue;
            }
            return new int[]{k, row};
        }
        return null;
    }

    private void open(int[] hit) {
        if (hit == null) {
            return;
        }
        Column column = columns.get(hit[0]);
        Node node = column.nodes.get(hit[1]);
        switch (node.kind) {
            case ROOT -> {
                closeAll();
                return;
            }
            case ROLE -> {
                boolean same = node.role == openedRole;
                openedRole = same ? null : node.role;
                openedSection = null;
                selectedName = null;
                rebuild();
                onOpen.accept(openedRole, null);
            }
            case SECTION -> {
                boolean same = node.section.equals(openedSection);
                openedSection = same ? null : node.section;
                selectedName = null;
                rebuild();
                onOpen.accept(openedRole, openedSection);
            }
            case PACKAGE -> {
                selectedName = node.label;
                column.selected = hit[1];
                onPackage.accept(node.entry);
                return;
            }
            default -> {
                return;
            }
        }
        // Follow what was just opened, without losing the branch it came from.
        int next = Math.min(columns.size() - 1, hit[0] + 1);
        Column grown = columns.get(next);
        camera.focus(columnX(next), grown.nodes.isEmpty() ? nodeY(column, hit[1])
                : nodeY(grown, Math.max(0, grown.opened)), getWidth(), getHeight(), false);
    }

    /**
     * Selects a package from outside the canvas, and brings it on screen.
     */
    void reveal(PackageEntry entry) {
        if (entry == null || columns.size() < 4) {
            return;
        }
        Column leaves = columns.get(3);
        int row = indexOfName(leaves.nodes, entry.name());
        if (row < 0) {
            return;
        }
        selectedName = entry.name();
        leaves.selected = row;
        camera.reveal(columnX(3), nodeY(leaves, row), getWidth(), getHeight());
    }

    // Drawing

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

        if (columns.isEmpty() || columns.get(1).nodes.isEmpty()) {
            gc.setFill(MUTED);
            gc.setFont(HEADER_FONT);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setTextBaseline(VPos.CENTER);
            gc.fillText(loading ? "READING THE PACKAGE DATABASE…" : "NOTHING INSTALLED", width / 2, height / 2);
            return;
        }

        drawGuides(gc, height);
        for (int k = 1; k < columns.size(); k++) {
            drawBranch(gc, k);
        }
        for (int k = 0; k < columns.size(); k++) {
            drawNodes(gc, k, width);
        }
        drawHeaders(gc, width);
        drawFooter(gc, width, height);
    }

    private void drawGuides(GraphicsContext gc, double height) {
        gc.setStroke(FAINT);
        gc.setLineWidth(1);
        gc.setGlobalAlpha(1);
        for (int k = 0; k < columns.size(); k++) {
            Column column = columns.get(k);
            if (column.nodes.isEmpty()) {
                continue;
            }
            double x = sx(columnX(k));
            double top = Math.max(HEADER, sy(column.top - ROW));
            double bottom = Math.min(height, sy(nodeY(column, column.nodes.size() - 1) + ROW));
            if (bottom > top) {
                gc.strokeLine(x, top, x, bottom);
            }
        }
    }

    /**
     * The fan from the row that was opened to every row of the next column.
     */
    private void drawBranch(GraphicsContext gc, int k) {
        Column parent = columns.get(k - 1);
        Column column = columns.get(k);
        if (parent.opened < 0 || column.nodes.isEmpty()) {
            return;
        }
        Node parentNode = parent.nodes.get(parent.opened);
        double zoom = camera.zoom;
        double px = sx(columnX(k - 1));
        double py = sy(nodeY(parent, parent.opened));
        double cx = sx(columnX(k));
        Color parentColor = parentNode.colour();

        int count = column.nodes.size();
        int first = firstVisible(column);
        int last = lastVisible(column);
        int onScreen = Math.max(0, last - first + 1);
        // Off-screen rows still get a sample of fibers, so the fan keeps its true shape.
        int step = Math.max(1, (count - onScreen) / OFFSCREEN_FIBERS);
        boolean gradients = count <= GRADIENT_LIMIT;
        int strands = count <= 40 ? 3 : count <= 120 ? 2 : 1;
        double baseAlpha = Fibers.clamp(12.0 / count + 0.1, 0.08, 0.45);
        double lineWidth = 0.8 * Fibers.clamp(zoom, 0.6, 1.4);
        double scale = Fibers.clamp(zoom, 0.45, 1.3);

        for (int i = 0; i < count; i++) {
            boolean visible = i >= first && i <= last;
            if (!visible && i % step != 0) {
                continue;
            }
            double grown = progress(column, i);
            if (grown <= 0) {
                continue;
            }
            Node child = column.nodes.get(i);
            double y = sy(nodeY(column, i));
            boolean highlighted = i == column.opened || i == column.selected
                    || hoverColumn == k && hoverRow == i;
            Paint stroke = gradients
                    ? new LinearGradient(px, 0, cx, 0, false, CycleMethod.NO_CYCLE,
                            new Stop(0, parentColor), new Stop(1, child.colour()))
                    : child.colour();
            int drawn = highlighted ? 1 : strands;
            double alpha = highlighted ? 0.95 : baseAlpha * (visible ? 1 : 0.6);
            for (int strand = 0; strand < drawn; strand++) {
                double jitter = drawn == 1 ? 0 : (Fibers.hash(child.key, strand) - 0.5) * 18 * scale;
                Fibers.fiber(gc, px, py, cx, y, jitter, grown, stroke, alpha * (strand == 0 ? 1 : 0.55),
                        highlighted ? lineWidth * 2 : lineWidth);
            }
        }
        gc.setGlobalAlpha(1);
    }

    private void drawNodes(GraphicsContext gc, int k, double width) {
        Column column = columns.get(k);
        if (column.nodes.isEmpty()) {
            return;
        }
        double zoom = camera.zoom;
        double x = sx(columnX(k));
        if (x < -COLUMN_GAP * zoom || x > width + 40) {
            return;
        }
        double row = ROW * zoom;
        double maxRadius = Math.max(1.2, row * DOT_SHARE);
        // Type is sized by the room between two rows, not by the zoom, so names stay readable when the whole
        // tree is framed and only vanish when they would genuinely collide.
        double fontSize = Fibers.clamp(row * 0.42, 8, 12.5);
        boolean labels = row >= LABEL_MIN_ROW;
        Font font = Font.font("Segoe UI", fontSize);
        Font numbers = Font.font("Consolas", fontSize * 0.92);
        double labelX = x + maxRadius + 14;
        int maxCharacters = Math.max(6, (int) ((COLUMN_GAP * zoom - maxRadius - 150) / (fontSize * 0.56)));

        int first = firstVisible(column);
        int last = lastVisible(column);
        for (int i = first; i <= last; i++) {
            double grown = k == 0 ? 1 : progress(column, i);
            if (grown <= 0) {
                continue;
            }
            Node node = column.nodes.get(i);
            double y = sy(nodeY(column, i));
            Color colour = node.colour();
            boolean opened = i == column.opened;
            boolean selected = i == column.selected;
            boolean hover = hoverColumn == k && hoverRow == i;
            double radius = Math.max(0.8, Math.min(node.radius() * zoom, maxRadius))
                    * Fibers.easeOutBack(grown);

            if (opened || selected || hover) {
                gc.setFill(colour);
                gc.setGlobalAlpha(0.22);
                // Bounded twice: never into the row above, never under the name beside it.
                double halo = Math.min(radius * 2.1, Math.min(row * 0.46, maxRadius + 10));
                gc.fillOval(x - halo, y - halo, halo * 2, halo * 2);
            }
            gc.setFill(colour);
            gc.setGlobalAlpha(0.95 * grown);
            gc.fillOval(x - radius, y - radius, radius * 2, radius * 2);

            // A branch wears the share of it someone asked for as a ring; a package needing an update wears an arc.
            double ringGap = Fibers.clamp(3 * zoom, 2, 4);
            if (node.kind != Kind.PACKAGE && node.asked() > 0.001) {
                double ring = radius + ringGap;
                gc.setStroke(colour);
                gc.setLineWidth(1.6 * Fibers.clamp(zoom, 0.6, 1.4));
                gc.setGlobalAlpha(0.8 * grown);
                gc.strokeArc(x - ring, y - ring, ring * 2, ring * 2, 90, -360 * node.asked(), ArcType.OPEN);
            }
            if (node.security > 0 || node.waiting > 0) {
                double ring = radius + ringGap * 2.2;
                gc.setStroke(node.security > 0 ? ALERT : AMBER);
                gc.setLineWidth(1.6 * Fibers.clamp(zoom, 0.6, 1.4));
                gc.setGlobalAlpha(0.9 * grown);
                double sweep = node.kind == Kind.PACKAGE ? 360 : 360.0 * node.waiting / Math.max(1, node.count);
                gc.strokeArc(x - ring, y - ring, ring * 2, ring * 2, 90, -Math.max(20, sweep), ArcType.OPEN);
            }
            if (selected) {
                gc.setStroke(TEXT);
                gc.setLineWidth(1);
                gc.setGlobalAlpha(0.9);
                gc.strokeOval(x - radius - 5, y - radius - 5, (radius + 5) * 2, (radius + 5) * 2);
            }
            if (loading && node.kind == Kind.ROOT) {
                double ring = radius + 10;
                gc.setStroke(PackagePalette.SCAN);
                gc.setLineWidth(1.5);
                gc.setGlobalAlpha(0.9);
                gc.strokeArc(x - ring, y - ring, ring * 2, ring * 2, -(clock * 420) % 360, 110, ArcType.OPEN);
            }
            if (labels) {
                drawLabel(gc, node, labelX, y, font, numbers, fontSize, maxCharacters,
                        opened || selected || hover, grown, zoom);
            }
        }
        gc.setGlobalAlpha(1);
    }

    /**
     * One line: the name, then what it holds, then why it is marked — all inside the column's own width, so a row
     * can never write over the column beside it.
     */
    private void drawLabel(GraphicsContext gc, Node node, double x, double y, Font font, Font numbers,
                           double fontSize, int maxCharacters, boolean emphasized, double grown, double zoom) {
        String name = cut(node.label, maxCharacters);
        String count = node.kind == Kind.PACKAGE ? node.entry.version() : String.valueOf(node.count);
        double room = COLUMN_GAP * zoom - 46;
        double nameWidth = width(name, font);
        double countWidth = width(count, numbers);

        String note = "";
        boolean alarming = false;
        if (fontSize >= 8.5) {
            if (node.kind == Kind.PACKAGE) {
                alarming = node.entry.security() || node.entry.upgradable();
                note = node.entry.security() ? "SECURITY"
                        : node.entry.upgradable() ? "→ " + node.entry.candidate()
                        : node.entry.autoRemovable() ? "UNUSED"
                        : node.entry.manual() ? "ASKED FOR" : "";
            } else {
                // Short on purpose: how much disk. How much of it was asked for is the ring on the dot, and what
                // needs an update is the arc, so the line does not have to carry them too.
                alarming = false;
                note = bytes(node.bytes);
            }
            // Whatever is left after the name and the count, and nothing more.
            if (width(note, SMALL_FONT) > room - nameWidth - countWidth - 26) {
                note = "";
            }
        }

        double used = nameWidth + countWidth + 16
                + (note.isEmpty() ? 0 : width(note, SMALL_FONT) + 12);
        // The line sits on the background so a fiber never runs through it.
        gc.setGlobalAlpha(0.72 * grown);
        gc.setFill(BACKGROUND);
        gc.fillRect(x - 3, y - fontSize * 0.75, Math.min(room, used), fontSize * 1.5);

        gc.setGlobalAlpha(grown);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFont(font);
        gc.setFill(emphasized ? TEXT : node.kind == Kind.PACKAGE ? QUIET : DIM);
        gc.fillText(name, x, y);

        gc.setFont(numbers);
        gc.setFill(MUTED);
        gc.fillText(count, x + nameWidth + 8, y);

        if (!note.isEmpty()) {
            gc.setFont(SMALL_FONT);
            gc.setFill(alarming ? node.kind == Kind.PACKAGE && !node.entry.security() ? AMBER : ALERT : MUTED);
            gc.fillText(note, x + nameWidth + countWidth + 20, y);
        }
    }

    /**
     * Sticky titles, so which level you are looking at never has to be worked out.
     */
    private void drawHeaders(GraphicsContext gc, double width) {
        gc.setFill(new LinearGradient(0, 0, 0, HEADER + 18, false, CycleMethod.NO_CYCLE,
                new Stop(0, BACKGROUND), new Stop(0.72, BACKGROUND),
                new Stop(1, Color.web("#050508", 0))));
        gc.setGlobalAlpha(1);
        gc.fillRect(0, 0, width, HEADER + 18);

        gc.setTextBaseline(VPos.CENTER);
        gc.setTextAlign(TextAlignment.LEFT);
        for (int k = 0; k < columns.size(); k++) {
            Column column = columns.get(k);
            if (column.nodes.isEmpty()) {
                continue;
            }
            double x = sx(columnX(k));
            if (x < -COLUMN_GAP || x > width) {
                continue;
            }
            double left = Fibers.clamp(x - 10, 14, Math.max(14, width - 180));
            Color colour = k == 0 ? ROOT_COLOR : column.nodes.get(Math.max(0, column.opened)).colour();
            gc.setFill(colour);
            gc.setGlobalAlpha(0.9);
            gc.fillRect(left, HEADER - 34, 14, 2);
            gc.setFont(HEADER_FONT);
            gc.setFill(DIM);
            double room = Math.min(COLUMN_GAP * camera.zoom, width - left) - 18;
            gc.fillText(cut(column.title, Math.max(4, (int) (room / 6.2))), left, HEADER - 20);
            gc.setFont(SMALL_FONT);
            gc.setFill(MUTED);
            gc.fillText(column.nodes.size() + (column.nodes.size() == 1 ? " ENTRY" : " ROWS"), left, HEADER - 6);
        }
        gc.setGlobalAlpha(1);
    }

    private void drawFooter(GraphicsContext gc, double width, double height) {
        // Its own band, so the hints never sit on a row.
        gc.setGlobalAlpha(1);
        gc.setFill(new LinearGradient(0, height - 46, 0, height, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#050508", 0)), new Stop(0.45, BACKGROUND), new Stop(1, BACKGROUND)));
        gc.fillRect(0, height - 46, width, 46);
        gc.setFont(SMALL_FONT);
        gc.setTextBaseline(VPos.BOTTOM);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(MUTED);
        gc.fillText("CLICK A ROW TO OPEN IT  ·  DRAG TO PAN  ·  CTRL+WHEEL TO ZOOM", 18, height - 24);
        gc.fillText("THE RING AROUND A DOT IS THE SHARE SOMEONE ASKED FOR; THE REST CAME IN AS DEPENDENCIES",
                18, height - 10);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFill(loading ? PackagePalette.SCAN : MUTED);
        gc.fillText(loading ? "READING…" : Math.round(camera.zoom * 100) + "%", width - 18, height - 10);
    }

    /**
     * How wide a string really is, so a line's parts never sit on each other.
     */
    private double width(String text, Font font) {
        ruler.setFont(font);
        ruler.setText(text);
        return ruler.getLayoutBounds().getWidth();
    }

    private static String cut(String text, int characters) {
        return text.length() <= characters ? text : text.substring(0, Math.max(1, characters - 1)) + "…";
    }

    private static String bytes(long value) {
        if (value >= 1_073_741_824L) {
            return String.format(Locale.ROOT, "%.1f GB", value / 1_073_741_824.0);
        }
        if (value >= 1_048_576L) {
            return String.format(Locale.ROOT, "%.0f MB", value / 1_048_576.0);
        }
        return String.format(Locale.ROOT, "%.0f KB", value / 1024.0);
    }
}
