package com.kubo.ui;

import javafx.geometry.Rectangle2D;

/** Geometry for the custom frame, keeping the title-bar corners flush with the work area. */
final class WindowArea {
    private WindowArea() {}

    static Rectangle2D expanded(Rectangle2D screen, Rectangle2D work, boolean windows) {
        // Keep the shell reveal strip at the bottom only. Insetting the top or right
        // sends corner clicks to the application behind Kubo instead of our close button.
        double bottom = windows && work.getMaxY() >= screen.getMaxY() ? 2 : 0;
        return new Rectangle2D(work.getMinX(), work.getMinY(),
                work.getWidth(), Math.max(1, work.getHeight() - bottom));
    }

    static double overlap(Rectangle2D a, Rectangle2D b) {
        return Math.max(0, Math.min(a.getMaxX(), b.getMaxX()) - Math.max(a.getMinX(), b.getMinX()))
                * Math.max(0, Math.min(a.getMaxY(), b.getMaxY()) - Math.max(a.getMinY(), b.getMinY()));
    }
}
