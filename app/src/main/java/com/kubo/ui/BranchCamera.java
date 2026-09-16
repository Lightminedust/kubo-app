package com.kubo.ui;


/**
 * Where the tree is looked at from: a world position at the top-left corner of the screen and a zoom, gliding
 * toward targets, with the inertia of a throw. Pure arithmetic, in screen pixels and world units.
 */
public final class BranchCamera {

    // Screen height kept for the sticky column titles.
    public static final double HEADER_HEIGHT = 88;
    public static final double MIN_ZOOM = 0.05;
    public static final double MAX_ZOOM = 2.5;
    public static final double FIT_MIN_ZOOM = 0.3;
    private static final double FRICTION = 4.2;
    private static final double WHEEL_GAIN = 9;
    private static final double MAX_SPEED = 9_000;

    public double camX;
    public double camY;
    public double zoom = 1;
    public double targetCamX;
    public double targetCamY;
    public double targetZoom = 1;

    // Inertia: world units per second, slowed by friction once the user lets go.
    private double velocityX;
    private double velocityY;
    private boolean dragging;
    private double pressX;
    private double pressY;
    private double pressCamX;
    private double pressCamY;
    private double lastDragX;
    private double lastDragY;
    private long lastDragNanos;

    public double sx(double worldX) {
        return (worldX - camX) * zoom;
    }

    public double sy(double worldY) {
        return (worldY - camY) * zoom;
    }

    public double worldX(double screenX) {
        return camX + screenX / zoom;
    }

    public double worldY(double screenY) {
        return camY + screenY / zoom;
    }

    public void resetZoomInstantly() {
        zoom = 1;
        targetZoom = 1;
        stopInertia();
    }

    public void stopInertia() {
        velocityX = 0;
        velocityY = 0;
    }

    /**
     * Advances by {@code dt} seconds: a throw glides and slows down, then everything eases toward its target.
     */
    public void step(double dt) {
        if (!dragging && (Math.abs(velocityX) > 2 || Math.abs(velocityY) > 2)) {
            camX += velocityX * dt;
            camY += velocityY * dt;
            double decay = Math.exp(-dt * FRICTION);
            velocityX *= decay;
            velocityY *= decay;
            targetCamX = camX;
            targetCamY = camY;
        } else if (!dragging) {
            stopInertia();
        }
        double ease = Math.min(1, dt * 6);
        camX += (targetCamX - camX) * ease;
        camY += (targetCamY - camY) * ease;
        zoom += (targetZoom - zoom) * ease;
    }

    /**
     * Puts a column right of centre, vertically centred on a world position.
     */
    public void focus(double columnX, double worldY, double width, double height, boolean snap) {
        stopInertia();
        targetCamX = Math.max(-80 / targetZoom, columnX - width / targetZoom * 0.55);
        targetCamY = worldY - (HEADER_HEIGHT + (height - HEADER_HEIGHT) / 2) / targetZoom;
        if (snap) {
            camX = targetCamX;
            camY = targetCamY;
            zoom = targetZoom;
        }
    }

    /**
     * Puts a node in the middle of the screen.
     */
    public void center(double columnX, double worldY, double width, double height) {
        stopInertia();
        targetCamX = columnX - width / targetZoom * 0.45;
        targetCamY = worldY - (HEADER_HEIGHT + (height - HEADER_HEIGHT) / 2) / targetZoom;
    }

    /**
     * Pans just enough to bring a node on screen.
     */
    public void reveal(double columnX, double worldY, double width, double height) {
        double y = sy(worldY);
        double x = sx(columnX);
        if (y < HEADER_HEIGHT + 20 || y > height - 20) {
            stopInertia();
            targetCamY = worldY - (HEADER_HEIGHT + (height - HEADER_HEIGHT) / 2) / targetZoom;
        }
        if (x < 20 || x > width - 180) {
            stopInertia();
            targetCamX = columnX - width / targetZoom * 0.45;
        }
    }

    /**
     * Zooms keeping the world point under a screen position still.
     */
    public void zoomAround(double factor, double screenX, double screenY, boolean animated) {
        stopInertia();
        double base = animated ? targetZoom : zoom;
        double next = Fibers.clamp(base * factor, MIN_ZOOM, MAX_ZOOM);
        double anchorX = (animated ? targetCamX : camX) + screenX / base;
        double anchorY = (animated ? targetCamY : camY) + screenY / base;
        targetZoom = next;
        targetCamX = anchorX - screenX / next;
        targetCamY = anchorY - screenY / next;
        if (!animated) {
            zoom = targetZoom;
            camX = targetCamX;
            camY = targetCamY;
        }
    }

    /**
     * Frames world bounds (min x, max x, min y, max y) below the column titles.
     */
    public void fit(double[] bounds, double width, double height) {
        double minX = bounds[0];
        double maxX = bounds[1];
        double minY = bounds[2];
        double maxY = bounds[3];
        double usable = height - HEADER_HEIGHT - 16;
        stopInertia();
        targetZoom = Fibers.clamp(Math.min(width / (maxX - minX), usable / (maxY - minY)), FIT_MIN_ZOOM, 1.2);
        targetCamX = minX - (width / targetZoom - (maxX - minX)) / 2;
        targetCamY = (minY + maxY) / 2 - (HEADER_HEIGHT + usable / 2) / targetZoom;
    }

    // Gestures

    public void press(double x, double y, long nanos) {
        pressX = x;
        pressY = y;
        pressCamX = camX;
        pressCamY = camY;
        lastDragX = x;
        lastDragY = y;
        lastDragNanos = nanos;
        stopInertia();
    }

    public double pressX() {
        return pressX;
    }

    public double pressY() {
        return pressY;
    }

    /**
     * The map follows the hand; the hand's smoothed speed becomes the throw when it lets go.
     */
    public void drag(double x, double y, long nanos) {
        dragging = true;
        camX = pressCamX - (x - pressX) / zoom;
        camY = pressCamY - (y - pressY) / zoom;
        targetCamX = camX;
        targetCamY = camY;

        double seconds = (nanos - lastDragNanos) / 1e9;
        if (seconds > 0.004) {
            double speedX = -(x - lastDragX) / zoom / seconds;
            double speedY = -(y - lastDragY) / zoom / seconds;
            velocityX = Fibers.clamp(velocityX * 0.35 + speedX * 0.65, -MAX_SPEED, MAX_SPEED);
            velocityY = Fibers.clamp(velocityY * 0.35 + speedY * 0.65, -MAX_SPEED, MAX_SPEED);
            lastDragX = x;
            lastDragY = y;
            lastDragNanos = nanos;
        }
    }

    public void release(long nanos) {
        if (dragging && (nanos - lastDragNanos) / 1e9 > 0.08) {
            // The hand stopped before letting go: no throw.
            stopInertia();
        }
        dragging = false;
    }

    /**
     * Goes back to where the press started, since the first pixels of a drag already panned.
     *
     * @return the panned position, to {@link #restore} if the drag is a pan after all
     */
    public double[] rewindToPress() {
        double[] panned = {camX, camY};
        camX = pressCamX;
        camY = pressCamY;
        return panned;
    }

    public void restore(double[] panned) {
        camX = panned[0];
        camY = panned[1];
    }

    /**
     * Ends a drag without a throw, where the camera is now.
     */
    public void settle() {
        dragging = false;
        targetCamX = camX;
        targetCamY = camY;
        stopInertia();
    }

    public void wheel(double horizontal, double vertical) {
        velocityX = Fibers.clamp(velocityX - horizontal / zoom * WHEEL_GAIN, -MAX_SPEED, MAX_SPEED);
        velocityY = Fibers.clamp(velocityY - vertical / zoom * WHEEL_GAIN, -MAX_SPEED, MAX_SPEED);
    }
}
