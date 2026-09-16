package com.kubo.ui.files;

import com.kubo.ui.BranchCamera;

import com.kubo.model.files.RemoteEntry;
import com.kubo.ui.files.BranchCanvas.MenuRequest;
import com.kubo.ui.files.BranchTree.Column;
import com.kubo.ui.files.BranchTree.Ghost;
import com.kubo.ui.files.BranchTree.Hit;
import com.kubo.ui.files.BranchTree.SortKey;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.kubo.ui.files.BranchStyle.ALERT;
import static com.kubo.ui.files.BranchStyle.BACKGROUND;
import static com.kubo.ui.files.BranchStyle.CHIP_FONT;
import static com.kubo.ui.files.BranchStyle.CODE;
import static com.kubo.ui.files.BranchStyle.DENIED_TEXT;
import static com.kubo.ui.files.BranchStyle.FAINT;
import static com.kubo.ui.files.BranchStyle.FILE_TEXT;
import static com.kubo.ui.files.BranchStyle.FOLDER;
import static com.kubo.ui.files.BranchStyle.FOLDER_TEXT;
import static com.kubo.ui.files.BranchStyle.HEADER_FONT;
import static com.kubo.ui.files.BranchStyle.LINK;
import static com.kubo.ui.files.BranchStyle.LOCAL;
import static com.kubo.ui.files.BranchStyle.MUTED;
import static com.kubo.ui.files.BranchStyle.PROBE_COLORS;
import static com.kubo.ui.files.BranchStyle.SMALL_FONT;
import static com.kubo.ui.files.BranchStyle.TAU;
import static com.kubo.ui.files.BranchStyle.TEXT;
import static com.kubo.ui.files.BranchStyle.TOOLTIP_FONT;
import static com.kubo.ui.files.BranchStyle.bezier;
import static com.kubo.ui.files.BranchStyle.clamp;
import static com.kubo.ui.files.BranchStyle.color;
import static com.kubo.ui.files.BranchStyle.easeOutBack;
import static com.kubo.ui.files.BranchStyle.hash;
import static com.kubo.ui.files.BranchStyle.radius;
import static com.kubo.ui.files.BranchTree.COLUMN_GAP;
import static com.kubo.ui.files.BranchTree.LABEL_MIN_FONT;
import static com.kubo.ui.files.BranchTree.ROW;
import static com.kubo.ui.files.BranchTree.columnX;
import static com.kubo.ui.files.BranchTree.indexOf;

/**
 * Draws the tree as seen through the camera: guides, fibers, dots and names, sticky column titles, and the marks
 * the canvas sets (hover, selection, open card, drop target, transfers).
 */
final class BranchPainter {

    private static final int GRADIENT_LIMIT = 160;
    private static final int OFFSCREEN_FIBERS = 60;
    private static final double HEADER_HEIGHT = BranchCamera.HEADER_HEIGHT;

    record SortChip(int column, SortKey key, double x, double y, double width, double height) {
        boolean contains(double px, double py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }
    }

    private final BranchTree tree;
    private final BranchCamera camera;
    private final List<SortChip> chips = new ArrayList<>();
    private final Map<Integer, Font> labelFonts = new HashMap<>();
    private double clock;
    private double height;

    // Marks set by the canvas.
    Hit hovered;
    String selectedPath;
    String measuringPath;
    MenuRequest menuTarget;
    int shownSpace = -1;
    double spaceAlpha;
    RemoteEntry dropTarget;
    String dropProblem;
    Set<String> incoming = Set.of();
    Set<String> outgoing = Set.of();
    String carried;
    double mouseX = -1;
    double mouseY = -1;

    BranchPainter(BranchTree tree, BranchCamera camera) {
        this.tree = tree;
        this.camera = camera;
    }

    /**
     * The sort control under a screen point, from the last frame drawn.
     */
    SortChip chipAt(double x, double y) {
        for (SortChip chip : chips) {
            if (chip.contains(x, y)) {
                return chip;
            }
        }
        return null;
    }

    void paint(GraphicsContext gc, double width, double height) {
        this.clock = tree.clock;
        this.height = height;
        List<Column> columns = tree.columns();
        gc.setGlobalAlpha(1);
        gc.setFill(BACKGROUND);
        gc.fillRect(0, 0, width, height);
        gc.setFill(new RadialGradient(0, 0, width * 0.62, height * 1.08, height * 0.85, false, CycleMethod.NO_CYCLE,
                new Stop(0, FOLDER.deriveColor(0, 1, 1, 0.12)), new Stop(1, Color.TRANSPARENT)));
        gc.fillRect(0, 0, width, height);

        drawGuides(gc);
        int dropColumn = dropTarget == null ? -1 : tree.columnOf(dropTarget);
        Color dropColor = dropProblem == null ? LOCAL : ALERT;
        String dropCaption = dropTarget == null ? "" : dropProblem != null ? "·  " + dropProblem
                : "·  DROP TO SEND HERE";
        boolean space = dropTarget == null && shownSpace >= 1 && shownSpace < columns.size() && spaceAlpha > 0.01;
        String spaceCaption = "·  CLICK: NEW FILE  ·  NEW FOLDER  ·  PERMISSIONS";
        if (space) {
            drawSpace(gc, shownSpace, width, true, FOLDER, spaceAlpha, spaceCaption);
        }
        if (dropColumn >= 1) {
            drawSpace(gc, dropColumn, width, true, dropColor, 1, dropCaption);
        }
        for (int k = 1; k < columns.size(); k++) {
            drawBranch(gc, k);
        }
        drawTravellingLights(gc);
        for (int k = 0; k < columns.size(); k++) {
            drawNodes(gc, k, width);
        }
        if (space) {
            drawSpace(gc, shownSpace, width, false, FOLDER, spaceAlpha, spaceCaption);
        }
        if (dropColumn >= 1) {
            drawSpace(gc, dropColumn, width, false, dropColor, 1, dropCaption);
        } else if (dropTarget != null) {
            drawDropNode(gc, dropColor, dropCaption);
        }
        drawTransfers(gc);
        drawHeaders(gc, width);
        drawHoverName(gc);
        gc.setGlobalAlpha(1);
    }

    private double sx(double worldX) {
        return camera.sx(worldX);
    }

    private double sy(double worldY) {
        return camera.sy(worldY);
    }

    private int firstVisible(Column column) {
        double worldTop = camera.camY + (HEADER_HEIGHT - 40) / camera.zoom;
        return (int) clamp(Math.floor((worldTop - column.top) / ROW), 0, column.visible.size());
    }

    private int lastVisible(Column column) {
        double worldBottom = camera.camY + (height + 40) / camera.zoom;
        return (int) clamp(Math.ceil((worldBottom - column.top) / ROW), -1, column.visible.size() - 1);
    }

    private void drawGuides(GraphicsContext gc) {
        gc.setStroke(FAINT);
        gc.setLineWidth(1);
        gc.setGlobalAlpha(0.9);
        List<Column> columns = tree.columns();
        for (int k = 0; k < columns.size(); k++) {
            Column column = columns.get(k);
            double x = Math.floor(sx(columnX(k))) + 0.5;
            double top = clamp(sy(column.top - ROW), HEADER_HEIGHT, height);
            double bottom = sy(column.top + Math.max(1, column.visible.size()) * ROW);
            // A folder the user cannot read turns its whole line red.
            boolean denied = !column.error.isEmpty();
            gc.setStroke(denied ? ALERT : FAINT);
            gc.setGlobalAlpha(denied ? 0.75 : 0.9);
            gc.strokeLine(x, denied ? HEADER_HEIGHT : top, x, denied ? height : clamp(bottom, HEADER_HEIGHT, height));
            if (bottom > HEADER_HEIGHT && bottom < height - 8) {
                for (int tick = 0; tick <= 30; tick++) {
                    double tickX = Math.floor(x + tick * 6) + 0.5;
                    gc.strokeLine(tickX, bottom, tickX, bottom + (tick % 5 == 0 ? 6 : 3));
                }
            }
        }
    }

    /**
     * A folder's space: a faint field behind its fibers, then crop marks and a caption over them.
     */
    private void drawSpace(GraphicsContext gc, int k, double width, boolean behind, Color color, double alpha,
                           String caption) {
        double[] bounds = tree.spaceBounds(k);
        boolean last = k == tree.size() - 1;
        double left = Math.max(-4, sx(columnX(k - 1) + ROW));
        double right = Math.min(width + 4, sx(columnX(k) + (last ? COLUMN_GAP * 0.85 : ROW)));
        double top = Math.max(HEADER_HEIGHT - 4, sy(bounds[0]));
        double bottom = Math.min(height + 4, sy(bounds[1]));
        if (right - left < 8 || bottom - top < 8) {
            return;
        }
        if (behind) {
            gc.setGlobalAlpha(0.06 * alpha);
            gc.setFill(color);
            gc.fillRect(left, top, right - left, bottom - top);
            return;
        }
        gc.setGlobalAlpha(0.85 * alpha);
        gc.setStroke(color);
        gc.setLineWidth(1);
        double mark = 12;
        double[][] corners = {{left, top, 1, 1}, {right, top, -1, 1}, {left, bottom, 1, -1}, {right, bottom, -1, -1}};
        for (double[] corner : corners) {
            double x = Math.floor(corner[0]) + 0.5;
            double y = Math.floor(corner[1]) + 0.5;
            gc.strokeLine(x, y, x + mark * corner[2], y);
            gc.strokeLine(x, y, x, y + mark * corner[3]);
        }
        Column column = tree.column(k);
        String name = column.directory.path().equals("/") ? "ROOT" : column.directory.name().toUpperCase(Locale.ROOT);
        // Above the folder's first entry, where no name of this column or the previous one reaches.
        double captionX = Math.max(left + 12, sx(columnX(k)) - 6);
        double captionY = Math.max(top + 18, HEADER_HEIGHT + 14);
        gc.setFont(CHIP_FONT);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BOTTOM);
        gc.setGlobalAlpha(alpha);
        gc.setFill(color);
        gc.fillText(name, captionX, captionY);
        gc.setFill(color == FOLDER ? MUTED : color);
        gc.setFont(SMALL_FONT);
        gc.fillText(caption, captionX + 6 + name.length() * 6.4, captionY);
    }

    /**
     * A drop onto a folder that is not open: a ring on its dot and the caption beside it.
     */
    private void drawDropNode(GraphicsContext gc, Color color, String caption) {
        double[] point = screenPoint(dropTarget.path());
        if (point == null) {
            return;
        }
        double ring = 14 + Math.sin(clock * 6) * 2;
        gc.setStroke(color);
        gc.setLineWidth(1.5);
        gc.setGlobalAlpha(0.95);
        gc.strokeOval(point[0] - ring, point[1] - ring, ring * 2, ring * 2);
        gc.setGlobalAlpha(0.3);
        gc.strokeOval(point[0] - ring - 8, point[1] - ring - 8, (ring + 8) * 2, (ring + 8) * 2);
        gc.setGlobalAlpha(1);
        gc.setFill(color);
        gc.setFont(CHIP_FONT);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText(dropTarget.name().toUpperCase(Locale.ROOT) + "  " + caption, point[0] + ring + 12, point[1] - ring - 4);
    }

    /**
     * Uploads and downloads in progress, and an entry being carried out of the tree.
     */
    private void drawTransfers(GraphicsContext gc) {
        for (String folder : incoming) {
            drawStream(gc, folder, true);
        }
        for (String path : outgoing) {
            drawStream(gc, path, false);
        }
        if (carried != null) {
            double[] point = screenPoint(carried);
            if (point != null) {
                double ring = 10 + Math.sin(clock * 8) * 2;
                gc.setStroke(CODE);
                gc.setLineWidth(1.4);
                gc.setGlobalAlpha(0.9);
                gc.strokeOval(point[0] - ring, point[1] - ring, ring * 2, ring * 2);
            }
        }
    }

    /**
     * A stream between an entry and the bottom-left corner, where this computer's card sits: toward the entry
     * for an upload, away from it for a download.
     */
    private void drawStream(GraphicsContext gc, String path, boolean inward) {
        double[] point = screenPoint(path);
        if (point == null) {
            return;
        }
        double cornerX = -10;
        double cornerY = height - 40;
        Color main = inward ? LOCAL : CODE;
        Color accent = inward ? CODE : LOCAL;
        for (int i = 0; i < 34; i++) {
            double phase = (clock * 0.55 + i / 34.0 + hash(path, i) * 0.05) % 1;
            double t = inward ? phase : 1 - phase;
            double jitter = (hash(path, i + 7) - 0.5) * 60;
            double[] p = bezier(cornerX, cornerY, point[0], point[1], jitter, t);
            double size = 1.2 + 2.2 * t;
            gc.setFill(i % 4 == 0 ? accent : main);
            gc.setGlobalAlpha(Math.sin(Math.PI * t) * 0.85);
            gc.fillOval(p[0] - size / 2, p[1] - size / 2, size, size);
        }
        double pulse = (clock * 1.4) % 1;
        double ring = inward ? 6 + pulse * 22 : 28 - pulse * 22;
        gc.setStroke(main);
        gc.setLineWidth(1.2);
        gc.setGlobalAlpha(inward ? (1 - pulse) * 0.8 : pulse * 0.8);
        gc.strokeOval(point[0] - ring, point[1] - ring, ring * 2, ring * 2);
    }

    private double[] screenPoint(String path) {
        double[] world = tree.nodePoint(path);
        return world == null ? null : new double[]{sx(world[0]), sy(world[1])};
    }

    private void drawBranch(GraphicsContext gc, int k) {
        Column parentColumn = tree.column(k - 1);
        Column column = tree.column(k);
        int parentIndex = indexOf(parentColumn.visible, parentColumn.openedPath);
        if (parentIndex < 0) {
            return;
        }
        RemoteEntry parent = parentColumn.visible.get(parentIndex);
        double zoom = camera.zoom;
        double px = sx(columnX(k - 1));
        double py = sy(tree.nodeY(parentColumn, parentIndex));
        double cx = sx(columnX(k));
        double scale = clamp(zoom, 0.45, 1.3);

        if (column.loading) {
            drawLoading(gc, px, py, cx, clock - column.loadingStart, scale);
            return;
        }
        if (!column.error.isEmpty() || column.visible.isEmpty()) {
            boolean failed = !column.error.isEmpty();
            fiber(gc, px, py, cx, py, 0, 1, failed ? ALERT : MUTED, failed ? 0.7 : 0.35, 1);
            gc.setGlobalAlpha(1);
            gc.setFill(failed ? ALERT : MUTED);
            gc.fillOval(cx - 3, py - 3, 6, 6);
            gc.setFont(SMALL_FONT);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.CENTER);
            String message = failed ? column.error.toUpperCase(Locale.ROOT)
                    : column.entries.isEmpty() ? "EMPTY FOLDER" : "NOTHING TO SHOW WITH THIS FILTER";
            gc.fillText(message, cx + 12, py);
            return;
        }

        int count = column.visible.size();
        int first = firstVisible(column);
        int last = lastVisible(column);
        int onScreen = Math.max(0, last - first + 1);
        // Off-screen children still get a sample of fibers, so the fan keeps its shape.
        int step = Math.max(1, (count - onScreen) / OFFSCREEN_FIBERS);
        boolean gradients = count <= GRADIENT_LIMIT;
        int strands = count <= 40 ? 3 : count <= 120 ? 2 : 1;
        double baseAlpha = clamp(12.0 / count + 0.1, 0.08, 0.45);
        double lineWidth = 0.8 * clamp(zoom, 0.6, 1.4);
        Color parentColor = color(parent);

        for (int i = 0; i < count; i++) {
            boolean visible = i >= first && i <= last;
            if (!visible && i % step != 0) {
                continue;
            }
            double progress = tree.progress(column, i);
            if (progress <= 0) {
                continue;
            }
            RemoteEntry child = column.visible.get(i);
            double y = sy(tree.nodeY(column, i));
            Color childColor = color(child);
            boolean denied = child.isDirectory() && !child.readable();
            boolean highlighted = child.path().equals(column.openedPath) || child.path().equals(selectedPath)
                    || hovered != null && hovered.entry().path().equals(child.path());
            Paint stroke = denied ? ALERT
                    : gradients
                    ? new LinearGradient(px, 0, cx, 0, false, CycleMethod.NO_CYCLE, new Stop(0, parentColor), new Stop(1, childColor))
                    : childColor;
            int drawn = highlighted || denied ? 1 : strands;
            double alpha = highlighted ? 0.95 : denied ? Math.max(0.55, baseAlpha) : baseAlpha * (visible ? 1 : 0.6);
            for (int strand = 0; strand < drawn; strand++) {
                double jitter = drawn == 1 ? 0 : (hash(child.path(), strand) - 0.5) * 18 * scale;
                fiber(gc, px, py, cx, y, jitter, progress, stroke, alpha * (strand == 0 ? 1 : 0.55),
                        highlighted ? lineWidth * 2 : lineWidth);
            }
        }
        if (column.refreshing) {
            // Reading the folder again after a change: a spinning mark on it.
            double ring = 14 * scale;
            gc.setStroke(CODE);
            gc.setLineWidth(1.5);
            gc.setGlobalAlpha(0.9);
            gc.strokeArc(px - ring, py - ring, ring * 2, ring * 2, -((clock - column.loadingStart) * 420) % 360, 120,
                    ArcType.OPEN);
        }
    }

    /**
     * The loading animation stays on the folder being read: pulses, orbiting sparks and fibers probing
     * toward the column that is about to appear.
     */
    private void drawLoading(GraphicsContext gc, double px, double py, double nextX, double t, double scale) {
        double gap = nextX - px;
        gc.setLineWidth(1);
        for (int ring = 0; ring < 2; ring++) {
            double phase = (t * 0.9 + ring * 0.5) % 1;
            double radius = (8 + phase * 28) * scale;
            gc.setStroke(FOLDER);
            gc.setGlobalAlpha((1 - phase) * 0.55);
            gc.strokeOval(px - radius, py - radius, radius * 2, radius * 2);
        }
        for (int j = 0; j < 9; j++) {
            double wave = 0.5 + 0.5 * Math.sin(t * 2.4 + j * 1.3);
            double reach = gap * (0.2 + 0.55 * wave);
            double targetY = py + (j - 4) * 22 * scale * (0.7 + 0.3 * Math.sin(t * 1.3 + j));
            Color color = PROBE_COLORS[j % PROBE_COLORS.length];
            fiber(gc, px, py, px + reach, targetY, 0, 1, color, 0.15 + 0.4 * wave, 0.8);
            gc.setFill(color);
            gc.setGlobalAlpha(0.45 + 0.55 * wave);
            gc.fillOval(px + reach - 2, targetY - 2, 4, 4);
        }
        for (int i = 0; i < 12; i++) {
            double angle = t * 3.2 + i * TAU / 12;
            double radius = (14 + 2.5 * Math.sin(t * 5 + i)) * scale;
            gc.setFill(i % 2 == 0 ? FOLDER : CODE);
            gc.setGlobalAlpha(0.25 + 0.75 * i / 12.0);
            gc.fillOval(px + Math.cos(angle) * radius - 1.3, py + Math.sin(angle) * radius - 1.3, 2.6, 2.6);
        }
        gc.setGlobalAlpha(0.55 + 0.45 * Math.sin(t * 4));
        gc.setFill(MUTED);
        gc.setFont(SMALL_FONT);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText("READING", px, py + 38 * scale);
    }

    /**
     * A light running along the opened path, so the current branch reads at a glance.
     */
    private void drawTravellingLights(GraphicsContext gc) {
        for (int k = 1; k < tree.size(); k++) {
            Column parentColumn = tree.column(k - 1);
            Column column = tree.column(k);
            int parentIndex = indexOf(parentColumn.visible, parentColumn.openedPath);
            int childIndex = indexOf(column.visible, column.openedPath);
            if (parentIndex < 0 || childIndex < 0 || tree.progress(column, childIndex) < 1) {
                continue;
            }
            double t = (clock * 0.45 + k * 0.23) % 1;
            double[] point = bezier(sx(columnX(k - 1)), sy(tree.nodeY(parentColumn, parentIndex)),
                    sx(columnX(k)), sy(tree.nodeY(column, childIndex)), 0, t);
            gc.setFill(Color.WHITE);
            gc.setGlobalAlpha(0.2);
            gc.fillOval(point[0] - 6, point[1] - 6, 12, 12);
            gc.setGlobalAlpha(0.95);
            gc.fillOval(point[0] - 1.8, point[1] - 1.8, 3.6, 3.6);
        }
    }

    private void drawNodes(GraphicsContext gc, int k, double width) {
        Column column = tree.column(k);
        double zoom = camera.zoom;
        double x = sx(columnX(k));
        if (x < -COLUMN_GAP * zoom || x > width + 40) {
            return;
        }
        double row = ROW * zoom;
        double maxRadius = Math.max(1.2, row * 0.42);
        double fontSize = Math.min(11, row * 0.5);
        boolean labels = fontSize >= LABEL_MIN_FONT;
        Font font = labelFont(fontSize);
        double labelX = x + Math.min(maxRadius, 10) + 7;
        int maxCharacters = Math.max(6, (int) ((COLUMN_GAP * zoom - 60) / (fontSize * 0.55)));

        int first = firstVisible(column);
        int last = lastVisible(column);
        for (int i = first; i <= last; i++) {
            double progress = k == 0 ? 1 : tree.progress(column, i);
            if (progress <= 0) {
                continue;
            }
            RemoteEntry entry = column.visible.get(i);
            double y = sy(tree.nodeY(column, i));
            Color color = color(entry);
            boolean opened = entry.path().equals(column.openedPath);
            boolean selected = entry.path().equals(selectedPath);
            boolean hover = hovered != null && hovered.column() == k && hovered.entry().path().equals(entry.path());
            double radius = Math.max(0.8, Math.min(radius(entry) * zoom, maxRadius)) * easeOutBack(progress);

            if (opened || selected || hover) {
                gc.setFill(color);
                gc.setGlobalAlpha(0.22);
                gc.fillOval(x - radius * 2.3, y - radius * 2.3, radius * 4.6, radius * 4.6);
            }
            if (entry.isDirectory() && !entry.readable() || entry.type() == RemoteEntry.Type.LINK) {
                gc.setStroke(entry.type() == RemoteEntry.Type.LINK ? LINK : ALERT);
                gc.setLineWidth(1.2);
                gc.setGlobalAlpha(0.95 * progress);
                gc.strokeOval(x - radius, y - radius, radius * 2, radius * 2);
            } else {
                gc.setFill(color);
                gc.setGlobalAlpha(0.95 * progress);
                gc.fillOval(x - radius, y - radius, radius * 2, radius * 2);
            }
            if (selected) {
                gc.setStroke(TEXT);
                gc.setLineWidth(1);
                gc.setGlobalAlpha(0.9);
                gc.strokeOval(x - radius - 4, y - radius - 4, (radius + 4) * 2, (radius + 4) * 2);
            }
            if (entry.path().equals(measuringPath)) {
                double ring = radius + 8;
                gc.setStroke(CODE);
                gc.setLineWidth(1.5);
                gc.setGlobalAlpha(0.9);
                gc.strokeArc(x - ring, y - ring, ring * 2, ring * 2, -(clock * 360) % 360, 110, ArcType.OPEN);
            }
            Double born = column.born.get(entry.path());
            if (born != null && clock - born < 1.4) {
                // Just created, copied or moved here.
                double t = (clock - born) / 1.4;
                double ring = radius + 4 + t * 26 * clamp(zoom, 0.5, 1.3);
                gc.setStroke(CODE);
                gc.setLineWidth(1.4);
                gc.setGlobalAlpha(1 - t);
                gc.strokeOval(x - ring, y - ring, ring * 2, ring * 2);
            }
            if (menuTarget != null && !menuTarget.onSpace() && entry.path().equals(menuTarget.entry().path())) {
                double ring = radius + 7 + Math.sin(clock * 5) * 1.5;
                gc.setStroke(FOLDER);
                gc.setLineWidth(1.2);
                gc.setGlobalAlpha(0.95);
                gc.strokeOval(x - ring, y - ring, ring * 2, ring * 2);
                gc.strokeLine(x - ring - 6, y, x - ring - 2, y);
                gc.strokeLine(x, y - ring - 6, x, y - ring - 2);
                gc.strokeLine(x, y + ring + 2, x, y + ring + 6);
            }
            if (labels) {
                drawLabel(gc, entry, labelX, y, font, fontSize, maxCharacters, opened || selected || hover, progress);
            }
        }
        drawGhosts(gc, column, x);
    }

    /**
     * Deleted or moved away: the dot bursts into particles that fade.
     */
    private void drawGhosts(GraphicsContext gc, Column column, double x) {
        column.ghosts.removeIf(ghost -> clock - ghost.start() > 1.0);
        double scale = clamp(camera.zoom, 0.5, 1.3);
        for (Ghost ghost : column.ghosts) {
            double t = (clock - ghost.start()) / 1.0;
            double y = sy(ghost.worldY());
            Color color = color(ghost.entry());
            for (int i = 0; i < 16; i++) {
                double angle = i * TAU / 16 + hash(ghost.entry().path(), i) * 0.8;
                double distance = (4 + t * (18 + 22 * hash(ghost.entry().path(), i + 31))) * scale;
                double size = 2.4 * (1 - t) + 0.4;
                gc.setFill(i % 3 == 0 ? ALERT : color);
                gc.setGlobalAlpha((1 - t) * 0.9);
                gc.fillOval(x + Math.cos(angle) * distance - size / 2, y + Math.sin(angle) * distance - size / 2, size, size);
            }
        }
    }

    private void drawLabel(GraphicsContext gc, RemoteEntry entry, double x, double y, Font font, double fontSize,
                           int maxCharacters, boolean emphasized, double progress) {
        String name = entry.name().replace('\n', ' ');
        if (name.length() > maxCharacters) {
            name = name.substring(0, maxCharacters - 1) + "…";
        }
        String count = entry.isDirectory() && entry.childCount() > 0 ? String.valueOf(entry.childCount()) : "";
        double nameWidth = name.length() * fontSize * 0.54;

        gc.setGlobalAlpha(0.6 * progress);
        gc.setFill(BACKGROUND);
        gc.fillRect(x - 2, y - fontSize * 0.7, nameWidth + (count.isEmpty() ? 4 : count.length() * fontSize * 0.5 + 10),
                fontSize * 1.4);

        gc.setFont(font);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        gc.setGlobalAlpha(progress);
        boolean denied = entry.isDirectory() && !entry.readable();
        gc.setFill(denied ? DENIED_TEXT : emphasized ? TEXT : entry.isDirectory() ? FOLDER_TEXT : FILE_TEXT);
        gc.fillText(name, x, y);
        if (!count.isEmpty()) {
            gc.setFill(MUTED);
            gc.fillText(count, x + nameWidth + 6, y);
        }
    }

    /**
     * Sticky column titles with their own sort controls.
     */
    private void drawHeaders(GraphicsContext gc, double width) {
        chips.clear();
        gc.setGlobalAlpha(1);
        gc.setFill(new LinearGradient(0, 0, 0, HEADER_HEIGHT + 18, false, CycleMethod.NO_CYCLE,
                new Stop(0, BACKGROUND), new Stop(0.78, BACKGROUND.deriveColor(0, 1, 1, 0.94)),
                new Stop(1, Color.TRANSPARENT)));
        gc.fillRect(0, 0, width, HEADER_HEIGHT + 18);

        double columnWidth = COLUMN_GAP * camera.zoom;
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BASELINE);
        for (int k = 0; k < tree.size(); k++) {
            Column column = tree.column(k);
            double x = sx(columnX(k)) - 4;
            // Too close together to title every column: keep the last one.
            if (x > width || x + Math.max(columnWidth, 120) < 0 || columnWidth < 70 && k < tree.size() - 1) {
                continue;
            }
            int maxCharacters = Math.max(3, (int) ((Math.min(columnWidth, 300) - 40) / 6.2));
            String title = k == 0 ? "SERVER" : column.directory.path().equals("/") ? "ROOT"
                    : column.directory.name().toUpperCase(Locale.ROOT);
            if (title.length() > maxCharacters) {
                title = title.substring(0, maxCharacters - 1) + "…";
            }
            boolean system = k == 0 || tree.isSystemSide(column.directory);

            gc.setGlobalAlpha(1);
            gc.setFont(SMALL_FONT);
            gc.setFill(system ? MUTED : FOLDER);
            gc.fillText(system ? "SYS" : "USR", x - 6, 30);
            gc.setFont(HEADER_FONT);
            gc.setFill(column.error.isEmpty() ? TEXT : ALERT);
            gc.fillText(title, x + 18, 30);
            if (columnWidth < 150) {
                continue;
            }

            gc.setFont(SMALL_FONT);
            gc.setFill(column.error.isEmpty() ? MUTED : ALERT);
            gc.fillText(headerDetail(k, column), x + 18, 45);

            if (k == 0 || column.loading || !column.error.isEmpty() || column.entries.isEmpty() || columnWidth < 200) {
                continue;
            }
            double chipX = x + 18;
            gc.setFill(MUTED);
            gc.fillText("SORT", chipX, 66);
            chipX += 32;
            gc.setFont(CHIP_FONT);
            for (SortKey key : SortKey.values()) {
                boolean active = column.sortKey == key;
                String text = key.label + (active ? (column.ascending ? " ↑" : " ↓") : "");
                double textWidth = text.length() * 5.6;
                gc.setFill(active ? TEXT : MUTED);
                gc.fillText(text, chipX, 66);
                if (active) {
                    gc.setStroke(FOLDER);
                    gc.setLineWidth(1);
                    gc.strokeLine(chipX, 70.5, chipX + textWidth, 70.5);
                }
                chips.add(new SortChip(k, key, chipX - 4, 55, textWidth + 8, 18));
                chipX += textWidth + 12;
            }
        }
    }

    private static String headerDetail(int k, Column column) {
        if (k == 0) {
            return "/";
        }
        if (column.loading) {
            return "READING…";
        }
        if (column.refreshing) {
            return "READING AGAIN…";
        }
        if (!column.error.isEmpty()) {
            return "PERMISSION DENIED";
        }
        int filtered = column.entries.size() - column.visible.size();
        return column.entries.size() + (column.entries.size() == 1 ? " ITEM" : " ITEMS")
                + (filtered > 0 ? "  ·  " + filtered + " FILTERED" : "")
                + (column.truncated ? "  ·  FIRST " + column.entries.size() : "");
    }

    /**
     * When zoomed too far out for names, the entry under the cursor still shows its name.
     */
    private void drawHoverName(GraphicsContext gc) {
        if (hovered == null || ROW * camera.zoom * 0.5 >= LABEL_MIN_FONT || mouseX < 0) {
            return;
        }
        String name = hovered.entry().name().replace('\n', ' ');
        double textWidth = name.length() * 6.2;
        gc.setGlobalAlpha(0.92);
        gc.setFill(Color.web("#14141b"));
        gc.fillRect(mouseX + 14, mouseY - 22, textWidth + 14, 20);
        gc.setGlobalAlpha(1);
        gc.setFont(TOOLTIP_FONT);
        gc.setFill(TEXT);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText(name, mouseX + 21, mouseY - 12);
    }

    private Font labelFont(double size) {
        return labelFonts.computeIfAbsent((int) Math.round(size * 2), key -> Font.font("Segoe UI", key / 2.0));
    }

    private static void fiber(GraphicsContext gc, double x1, double y1, double x2, double y2, double jitter,
                              double progress, Paint paint, double alpha, double width) {
        gc.setStroke(paint);
        gc.setGlobalAlpha(alpha);
        gc.setLineWidth(width);
        gc.beginPath();
        gc.moveTo(x1, y1);
        if (progress >= 1) {
            double dx = x2 - x1;
            gc.bezierCurveTo(x1 + dx * 0.45, y1 + jitter, x2 - dx * 0.55, y2 - jitter * 0.4, x2, y2);
        } else {
            int segments = 24;
            int last = (int) Math.ceil(segments * progress);
            for (int s = 1; s <= last; s++) {
                double[] point = bezier(x1, y1, x2, y2, jitter, Math.min(progress, (double) s / segments));
                gc.lineTo(point[0], point[1]);
            }
        }
        gc.stroke();
    }
}
