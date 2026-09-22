package com.kubo.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/**
 * A thin line with a light running along it, for the moments Kubo is waiting on the server.
 *
 * <p>It is here because a spinning circle says that something is happening without saying what to, and because
 * it belongs beside the thing being read rather than over the whole window. It is a few pixels tall, so it goes
 * under a field, above a panel, or across the head of a card without moving anything else.
 *
 * <p>It draws nothing while it is hidden: the clock behind it stops with the node, so a card left open in the
 * background costs nothing.
 */
public final class Pulse extends Region {

    private static final Color TRACK = Color.web("#1b1b28");
    private static final Color LIGHT = Color.web("#35e8ff");

    private final Canvas canvas = new Canvas();
    private final LiveTimer timer;
    private boolean running;

    public Pulse() {
        this(2.5);
    }

    /**
     * @param thickness the height of the line, in pixels
     */
    public Pulse(double thickness) {
        canvas.setHeight(thickness);
        setPrefHeight(thickness);
        setMinHeight(thickness);
        setMaxHeight(thickness);
        getChildren().add(canvas);
        widthProperty().addListener((observable, before, now) -> {
            canvas.setWidth(now.doubleValue());
            paint(0);
        });
        timer = new LiveTimer(this, LiveTimer.EVERY_PULSE, this::paint);
        setVisible(false);
        setManaged(false);
    }

    /**
     * Shows the line and starts it, or hides it and stops it. Hidden, it takes no room and no time.
     */
    public void setRunning(boolean run) {
        if (running == run) {
            return;
        }
        running = run;
        setVisible(run);
        setManaged(run);
        if (run) {
            timer.start();
        } else {
            timer.stop();
        }
    }

    public boolean running() {
        return running;
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.relocate(0, 0);
    }

    private void paint(double seconds) {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width < 1) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, width, height);
        gc.setFill(TRACK);
        gc.fillRect(0, 0, width, height);

        // One pass every second and a half, and a light that fades at both ends rather than a block sliding by.
        double cycle = 1.5;
        double head = ((seconds % cycle) / cycle) * (width + 180) - 90;
        double reach = 80;
        int strips = 24;
        for (int i = 0; i < strips; i++) {
            double x = head - reach + (i * (reach * 2) / strips);
            if (x < 0 || x > width) {
                continue;
            }
            double falloff = 1 - Math.abs(x - head) / reach;
            gc.setGlobalAlpha(falloff * falloff);
            gc.setFill(LIGHT);
            gc.fillRect(x, 0, (reach * 2) / strips + 1, height);
        }
        gc.setGlobalAlpha(1);
    }
}
