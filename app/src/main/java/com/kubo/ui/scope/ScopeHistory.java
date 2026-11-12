package com.kubo.ui.scope;

import com.kubo.model.server.ServerMetrics;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.Locale;

/**
 * The last three minutes of the machine as three bands: the processor, the memory, the network.
 *
 * <p>Filled rather than drawn as a line. A line at three percent is a thread along the floor of an empty
 * box; the same three percent filled is a band, and a memory reading that never moves is a solid block
 * whose height is the answer. No axes: the frame says how long, and the height says how much.
 *
 * <p>Stacked when there is height for it, side by side when there is not, so a small window still gets
 * three graphs rather than three labels over nothing.
 */
public final class ScopeHistory extends Pane {

    static final int CAPACITY = 90;
    private static final long WINDOW = 180_000;
    /**
     * Longer than this between two samples and the monitor was cut off or held back: the pause is drawn as a
     * dotted bridge rather than as a line that pretends to know what happened. Behind another window the
     * monitor still reads every ten seconds, so that alone never makes a pause.
     */
    private static final long GAP = 25_000;
    /** Below this, a band stacked on the others has no room for a graph under its label. */
    private static final double STACKED_MINIMUM = 72;
    /** The ceilings a quiet processor is drawn against, so idle still has a shape. Always said on screen. */
    private static final double[] CPU_SCALES = {0.10, 0.25, 0.50};

    private static final Color CPU = Color.web("#35e8ff");
    private static final Color RAM = Color.web("#b58cff");
    private static final Color DOWN = Color.web("#35e0a1");
    private static final Color UP = Color.web("#ff6b93");
    private static final Color RULE = Color.web("#1c1c27");
    private static final Color GUIDE = Color.web("#191922");
    private static final Color VALUE = Color.web("#d6d6e0");
    private static final Color MUTED = Color.web("#5f5f72");
    private static final Font NAME = Font.font("Consolas", 12);
    private static final Font NOTE = Font.font("Consolas", 11);
    private static final double STRIP = 20;

    private final Canvas canvas = new Canvas();
    private final long[] times = new long[CAPACITY];
    private final double[] cpu = new double[CAPACITY];
    private final double[] ram = new double[CAPACITY];
    private final double[] down = new double[CAPACITY];
    private final double[] up = new double[CAPACITY];
    private int head;
    private int count;
    private long last = Long.MIN_VALUE;

    public ScopeHistory() {
        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinSize(0, 0);
        setAccessibleText("Processor, memory and network over the last three minutes");
    }

    public void push(ServerMetrics metrics) {
        long at = metrics.timestamp().toEpochMilli();
        if (at == last) {
            return;
        }
        last = at;
        int index = (head + count) % CAPACITY;
        if (count < CAPACITY) {
            count++;
        } else {
            head = (head + 1) % CAPACITY;
        }
        times[index] = at;
        cpu[index] = clamp(metrics.cpuPercent() / 100);
        ram[index] = clamp(metrics.memory().usagePercentage() / 100);
        down[index] = metrics.network().rxBytesPerSec();
        up[index] = metrics.network().txBytesPerSec();
        draw();
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        draw();
    }

    private void draw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        if (w < 120 || h < 60) {
            return;
        }
        int newest = (head + count - 1) % CAPACITY;
        boolean any = count > 0;

        // A quiet processor against a hundred percent is a line on the floor. Drawn against the smallest of
        // a few round ceilings that holds it, it has a shape, and the ceiling is written beside it.
        double busiest = peak(cpu);
        double cpuScale = 1;
        for (double ceiling : CPU_SCALES) {
            if (busiest * 1.25 <= ceiling) {
                cpuScale = ceiling;
                break;
            }
        }
        String cpuNote = cpuScale < 1 ? "scale 0–" + Math.round(cpuScale * 100) + "%" : null;

        // The network has no ceiling, so its scale is the busiest moment on screen, said in the corner.
        double netPeak = Math.max(1024, Math.max(peak(down), peak(up)));
        String rates = any ? "↓ " + Format.rate((long) down[newest]) + "  ↑ " + Format.rate((long) up[newest])
                : "—";

        boolean stacked = h / 3 >= STACKED_MINIMUM;
        double gap = stacked ? 0 : 22;
        double cellWidth = stacked ? w : (w - gap * 2) / 3;
        double cellHeight = stacked ? h / 3 : h;
        for (int i = 0; i < 3; i++) {
            double x = stacked ? 0 : i * (cellWidth + gap);
            double y = stacked ? i * cellHeight : 0;
            switch (i) {
                case 0 -> {
                    strip(g, x, y, cellWidth, "cpu", CPU, any ? percent(cpu[newest]) : "—", cpuNote);
                    area(g, x, y, cellWidth, cellHeight, cpu, cpuScale, CPU, true);
                }
                case 1 -> {
                    strip(g, x, y, cellWidth, "ram", RAM, any ? percent(ram[newest]) : "—", null);
                    area(g, x, y, cellWidth, cellHeight, ram, 1, RAM, true);
                }
                default -> {
                    strip(g, x, y, cellWidth, "net", DOWN, rates,
                            stacked ? "peak " + Format.rate((long) netPeak) : null);
                    area(g, x, y, cellWidth, cellHeight, down, netPeak * 1.15, DOWN, true);
                    area(g, x, y, cellWidth, cellHeight, up, netPeak * 1.15, UP, false);
                }
            }
        }

        g.setStroke(RULE);
        g.setLineWidth(1);
        for (int i = 1; i < 3; i++) {
            if (stacked) {
                double y = Math.round(cellHeight * i) + .5;
                g.strokeLine(0, y, w, y);
            } else {
                double x = Math.round(i * (cellWidth + gap) - gap / 2) + .5;
                g.strokeLine(x, 0, x, h);
            }
        }
    }

    /** The name of a band, what it reads now, and on the right whatever its scale needs said. */
    private void strip(GraphicsContext g, double x, double top, double width, String name, Color colour,
                       String now, String note) {
        double baseline = top + 15;
        g.setTextBaseline(VPos.BASELINE);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(NAME);
        g.setFill(colour);
        g.fillText(name, x, baseline);
        g.setFill(VALUE);
        g.fillText(now, x + width(name, NAME) + 12, baseline);
        if (note != null) {
            g.setFont(NOTE);
            g.setFill(MUTED);
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(note, x + width, baseline);
        }
    }

    /** One series as a filled band, or as a line alone for a second series laid over the first. */
    private void area(GraphicsContext g, double x, double top, double width, double height, double[] values,
                      double scale, Color colour, boolean filled) {
        double plotTop = top + STRIP + 6;
        double bottom = top + height - 8;
        if (bottom - plotTop < 12) {
            return;
        }
        // The newest point carries a dot; the plot stops short of the edge so the dot is whole.
        double right = x + width - 7;
        double span = width - 7;
        if (filled) {
            // One faint guide at half, so a low band still reads as low against something.
            g.setStroke(GUIDE);
            g.setLineWidth(1);
            g.setLineDashes(2, 4);
            double middle = Math.round((plotTop + bottom) / 2) + .5;
            g.strokeLine(x, middle, x + width, middle);
            g.setLineDashes((double[]) null);
        }
        if (count == 0) {
            return;
        }

        double[] xs = new double[count];
        double[] ys = new double[count];
        boolean[] pauseBefore = new boolean[count];
        int points = 0;
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            int index = (head + i) % CAPACITY;
            if (last - times[index] > WINDOW) {
                continue;
            }
            double level = Math.max(0, Math.min(1, values[index] / scale));
            xs[points] = right - (last - times[index]) * span / WINDOW;
            ys[points] = bottom - level * (bottom - plotTop);
            pauseBefore[points] = previous != Long.MIN_VALUE && times[index] - previous > GAP;
            points++;
            previous = times[index];
        }
        if (points == 0) {
            return;
        }

        // One run of samples at a time; across a pause, a faint dotted bridge from where it stopped to where
        // it picked up again, so it still reads as one series with a hole, not as scattered pieces.
        int start = 0;
        for (int i = 1; i <= points; i++) {
            if (i == points || pauseBefore[i]) {
                segment(g, xs, ys, start, i, plotTop, bottom, colour, filled);
                if (i < points) {
                    g.setStroke(colour.deriveColor(0, 1, 1, 0.35));
                    g.setLineWidth(1);
                    g.setLineDashes(2, 4);
                    g.strokeLine(xs[i - 1], ys[i - 1], xs[i], ys[i]);
                    g.setLineDashes((double[]) null);
                }
                start = i;
            }
        }
        // Only the newest reading carries the dot: one series, one "now".
        if (filled) {
            double endX = xs[points - 1];
            double endY = ys[points - 1];
            g.setFill(colour.deriveColor(0, 1, 1, 0.25));
            g.fillOval(endX - 5, endY - 5, 10, 10);
            g.setFill(colour);
            g.fillOval(endX - 2.5, endY - 2.5, 5, 5);
        }
    }

    /** The samples from {@code from} up to {@code to}, excluded, as one filled band or one line. */
    private void segment(GraphicsContext g, double[] xs, double[] ys, int from, int to, double plotTop,
                         double bottom, Color colour, boolean filled) {
        if (filled) {
            g.setFill(new LinearGradient(0, plotTop, 0, bottom, false, CycleMethod.NO_CYCLE,
                    new Stop(0, colour.deriveColor(0, 1, 1, 0.42)),
                    new Stop(1, colour.deriveColor(0, 1, 1, 0.10))));
            g.beginPath();
            g.moveTo(xs[from], bottom);
            for (int i = from; i < to; i++) {
                g.lineTo(xs[i], ys[i]);
            }
            g.lineTo(xs[to - 1], bottom);
            g.closePath();
            g.fill();
        }
        g.setStroke(colour);
        g.setLineWidth(1.5);
        g.beginPath();
        g.moveTo(xs[from], ys[from]);
        for (int i = from + 1; i < to; i++) {
            g.lineTo(xs[i], ys[i]);
        }
        g.stroke();
    }

    /** The highest value of one series inside the window. */
    private double peak(double[] values) {
        double highest = 0;
        for (int i = 0; i < count; i++) {
            int index = (head + i) % CAPACITY;
            if (last - times[index] <= WINDOW) {
                highest = Math.max(highest, values[index]);
            }
        }
        return highest;
    }

    private static String percent(double share) {
        double value = share * 100;
        return String.format(Locale.ROOT, value < 10 ? "%.1f%%" : "%.0f%%", value);
    }

    private static double width(String text, Font font) {
        Text measure = new Text(text);
        measure.setFont(font);
        return measure.getLayoutBounds().getWidth();
    }

    private static double clamp(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }
}
