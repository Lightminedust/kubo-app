package com.kubo.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.util.Duration;

/**
 * The braille spinner a terminal turns, alone: for a button or a row too small for a {@link Pulse}.
 *
 * <p>It turns only while it is in a window and shown, so a hidden one costs nothing.
 */
public final class Spinner extends Label {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴",
            "⠦", "⠧", "⠇", "⠏"};

    private final Timeline spin;
    private int frame;

    /** The glyph this long into a spin, for something drawn on a canvas that turns the same way. */
    public static String glyph(double seconds) {
        return FRAMES[Math.floorMod((long) Math.floor(seconds / 0.08), FRAMES.length)];
    }

    public Spinner() {
        super(FRAMES[0]);
        getStyleClass().add("reading-spinner");
        setMinSize(USE_PREF_SIZE, USE_PREF_SIZE);
        spin = new Timeline(new KeyFrame(Duration.millis(80), event -> {
            frame = (frame + 1) % FRAMES.length;
            setText(FRAMES[frame]);
        }));
        spin.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((observable, before, now) -> update());
        visibleProperty().addListener((observable, before, now) -> update());
        update();
    }

    private void update() {
        if (getScene() != null && isVisible()) {
            spin.play();
        } else {
            spin.stop();
        }
    }
}
