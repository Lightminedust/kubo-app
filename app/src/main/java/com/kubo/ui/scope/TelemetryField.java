package com.kubo.ui.scope;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/** Static drafting marks behind the live instruments. Redrawn only when resized. */
final class TelemetryField extends Region {
    private final Canvas canvas = new Canvas();

    TelemetryField() {
        getChildren().add(canvas);
        setMouseTransparent(true);
        setMinSize(0, 0);
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth(), h = getHeight();
        canvas.setWidth(w);
        canvas.setHeight(h);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.setStroke(Color.web("#121421"));
        g.setLineWidth(0.5);
        for (double x = 24.5; x < w; x += 32) g.strokeLine(x, 8, x, h - 8);
        for (double y = 16.5; y < h; y += 32) g.strokeLine(12, y, w - 12, y);
        g.setStroke(Color.web("#423044"));
        g.setLineWidth(1);
        for (double x : new double[]{12.5, w - 12.5}) {
            for (double y : new double[]{12.5, h - 12.5}) {
                double dx = x < w / 2 ? 16 : -16;
                double dy = y < h / 2 ? 16 : -16;
                g.strokeLine(x, y, x + dx, y);
                g.strokeLine(x, y, x, y + dy);
            }
        }
        g.setFill(Color.web("#ff2d55"));
        g.fillRect(12, 12, 30, 2);
        g.fillRect(46, 12, 6, 2);
        g.fillRect(w - 42, h - 14, 21, 2);
        g.setStroke(Color.web("#6c2038"));
        g.strokePolyline(new double[]{w - 96, w - 76, w - 28},
                new double[]{13, 21, 21}, 3);
    }
}
