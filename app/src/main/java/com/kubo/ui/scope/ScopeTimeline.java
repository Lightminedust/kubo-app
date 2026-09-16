package com.kubo.ui.scope;

import com.kubo.model.server.ServerMetrics;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.Locale;

/**
 * The last minutes of CPU and memory as two thin traces, newest reading on the right.
 */
public final class ScopeTimeline extends Pane {

    /**
     * Three minutes of readings at the monitor's pace.
     */
    public static final int CAPACITY = 90;

    private static final Color TEXT = Color.web("#e6e6ec");
    private static final Color MUTED = Color.web("#6e6e7d");
    private static final Color FAINT = Color.web("#22222b");
    private static final String[] NAMES = {"CPU", "MEMORY"};
    private static final Color[] COLORS = {Color.web("#ff3fd8"), Color.web("#35e8ff")};
    private static final Font LABEL_FONT = Font.font("Segoe UI", FontWeight.SEMI_BOLD, 9.5);
    private static final Font VALUE_FONT = Font.font("Consolas", 10.5);

    private final Canvas canvas = new Canvas();
    private final double[][] levels = new double[NAMES.length][CAPACITY];
    private final String[] values = {"—", "—"};
    private int head;
    private int count;

    public ScopeTimeline() {
        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinSize(0, 0);
    }

    public void push(ServerMetrics metrics) {
        int index = (head + count) % CAPACITY;
        if (count < CAPACITY) {
            count++;
        } else {
            head = (head + 1) % CAPACITY;
        }
        levels[0][index] = metrics.cpuPercent() / 100;
        levels[1][index] = metrics.memory().usagePercentage() / 100;
        values[0] = String.format(Locale.ROOT, "%5.1f %%", metrics.cpuPercent());
        values[1] = String.format(Locale.ROOT, "%5.1f %%", metrics.memory().usagePercentage());
        draw();
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        draw();
    }

    private void draw() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width < 1 || height < 1) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, width, height);

        double rowHeight = height / NAMES.length;
        double plotLeft = 70;
        double plotRight = width - 92;
        double step = (plotRight - plotLeft) / (CAPACITY - 1);

        for (int row = 0; row < NAMES.length; row++) {
            double baseline = rowHeight * (row + 1) - 7;
            double amplitude = rowHeight - 14;

            gc.setGlobalAlpha(1);
            gc.setTextBaseline(VPos.BASELINE);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setFont(LABEL_FONT);
            gc.setFill(MUTED);
            gc.fillText(NAMES[row], 0, baseline);
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.setFont(VALUE_FONT);
            gc.setFill(TEXT);
            gc.fillText(values[row], width, baseline);

            gc.setStroke(FAINT);
            gc.setLineWidth(1);
            gc.strokeLine(plotLeft, baseline + 0.5, plotRight, baseline + 0.5);

            if (count == 0) {
                continue;
            }
            gc.setStroke(COLORS[row]);
            gc.setLineWidth(1.1);
            gc.setGlobalAlpha(0.5);
            gc.beginPath();
            for (int i = 0; i < count; i++) {
                double x = plotRight - (count - 1 - i) * step;
                double y = baseline - levels[row][(head + i) % CAPACITY] * amplitude;
                if (i == 0) {
                    gc.moveTo(x, y);
                } else {
                    gc.lineTo(x, y);
                }
            }
            gc.stroke();

            gc.setFill(COLORS[row]);
            for (int i = 0; i < count; i++) {
                double x = plotRight - (count - 1 - i) * step;
                double y = baseline - levels[row][(head + i) % CAPACITY] * amplitude;
                boolean newest = i == count - 1;
                double dot = newest ? 4 : 1.6;
                gc.setGlobalAlpha(newest ? 1 : 0.35 + 0.65 * i / count);
                gc.fillOval(x - dot / 2, y - dot / 2, dot, dot);
            }
        }
        gc.setGlobalAlpha(1);
    }
}
