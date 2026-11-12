package com.kubo.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

/**
 * The short form of the {@link Journal}, for the places a whole journal would be too much: one line, the
 * braille spinner a terminal turns and what it is waiting for.
 *
 * <p>It belongs beside the thing being waited on, under a field or at the head of a card, so it takes one line
 * and only while it runs. Hidden, it takes no room and its clock is stopped.
 */
public final class Pulse extends HBox {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴",
            "⠦", "⠧", "⠇", "⠏"};

    private final Label spinner = new Label(FRAMES[0]);
    private final Label text = new Label("waiting for the server");
    private final Timeline spin;
    private boolean running;
    private int frame;

    public Pulse() {
        this(0);
    }

    /**
     * @param ignored kept for the places that asked for a line of a given thickness; it is one line of text now
     */
    public Pulse(double ignored) {
        super(8);
        getStyleClass().add("pulse");
        spinner.getStyleClass().add("reading-spinner");
        text.getStyleClass().add("home-muted");
        setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(spinner, text);
        spin = new Timeline(new KeyFrame(Duration.millis(80), event -> {
            frame = (frame + 1) % FRAMES.length;
            spinner.setText(FRAMES[frame]);
        }));
        spin.setCycleCount(Timeline.INDEFINITE);
        setVisible(false);
        setManaged(false);
    }

    /** What it says it is waiting for. */
    public void setText(String what) {
        text.setText(what == null || what.isBlank() ? "waiting for the server" : what);
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
            spin.play();
        } else {
            spin.stop();
        }
    }

    public boolean running() {
        return running;
    }
}
