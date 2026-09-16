package com.kubo.ui;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Paint;

/**
 * The strands Kubo draws between two things that belong together, and the small maths behind them.
 *
 * <p>This is the file tree's own idiom, pulled out so every screen that shows a branching shape draws it the same
 * way: a bezier that leaves its parent sideways, bends by a per-strand jitter so parallel strands stay apart, and
 * can be drawn only part of the way while it is still growing.
 */
public final class Fibers {

    public static final double TAU = Math.PI * 2;

    private Fibers() {
    }

    /**
     * A point on the fiber between two nodes; {@code jitter} bends it so parallel strands stay apart.
     */
    public static double[] bezier(double x1, double y1, double x2, double y2, double jitter, double t) {
        double dx = x2 - x1;
        double c1x = x1 + dx * 0.45;
        double c1y = y1 + jitter;
        double c2x = x2 - dx * 0.55;
        double c2y = y2 - jitter * 0.4;
        double u = 1 - t;
        double x = u * u * u * x1 + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * x2;
        double y = u * u * u * y1 + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * y2;
        return new double[]{x, y};
    }

    /**
     * Draws one strand. Below a full {@code progress} it is drawn only that far along, which is how a branch grows
     * out of its parent rather than appearing.
     */
    public static void fiber(GraphicsContext gc, double x1, double y1, double x2, double y2, double jitter,
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

    public static double easeOutBack(double t) {
        double c1 = 1.70158;
        double c3 = c1 + 1;
        return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
    }

    /**
     * A stable pseudo-random number in [0, 1] for a text, so particles and strands keep their place from frame to
     * frame.
     */
    public static double hash(String text, int salt) {
        int value = text.hashCode() * 31 + salt * 7919;
        value ^= value >>> 15;
        value *= 0x2c1b3c6d;
        value ^= value >>> 12;
        return (value & 0xffff) / 65535.0;
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
