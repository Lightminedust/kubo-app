package com.kubo.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * The sign in front of the prompt, which says whether the console is waiting for you or you for it.
 *
 * <p>It was a `$` that never changed. So somebody typed a command, pressed Enter, saw the same `$` and the same
 * empty field, and had no way of telling whether anything had left at all. On a link with any latency the
 * honest answer was often "not yet", and the only way to find out was to type it again and hope.
 *
 * <p>Idle it is the prompt character, still, and `#` when root is on. Running it is a block that fills and
 * empties in place, which is what a terminal cursor does while something has the line. Waiting for an answer
 * it turns amber and holds, because that state is not "be patient", it is "your turn".
 */
public final class RunningMark extends Canvas {

    /** What the mark is saying. */
    public enum State {
        /** Nothing is running; type a command. */
        IDLE,
        /** A command has gone and has not come back. */
        RUNNING,
        /** A command is asking for something. */
        ASKING
    }

    private static final Color IDLE_COLOUR = Color.web("#9d4dff");
    private static final Color ROOT_COLOUR = Color.web("#ff2d55");
    private static final Color RUNNING_COLOUR = Color.web("#35e8ff");
    private static final Color ASKING_COLOUR = Color.web("#e8c35a");

    private final LiveTimer timer;
    private State state = State.IDLE;
    private boolean root;
    private double seconds;

    public RunningMark() {
        super(16, 20);
        // Only while something is moving. Idle it draws once and the clock stops with it.
        timer = new LiveTimer(this, 30, now -> {
            seconds = now;
            draw();
        });
        draw();
    }

    public void set(State next, boolean asRoot) {
        if (state == next && root == asRoot) {
            return;
        }
        state = next;
        root = asRoot;
        if (next == State.RUNNING) {
            timer.start();
        } else {
            timer.stop();
        }
        draw();
    }

    public State state() {
        return state;
    }

    private void draw() {
        GraphicsContext pen = getGraphicsContext2D();
        pen.clearRect(0, 0, getWidth(), getHeight());
        switch (state) {
            case IDLE -> {
                pen.setFill(root ? ROOT_COLOUR : IDLE_COLOUR);
                pen.setFont(javafx.scene.text.Font.font("Consolas", 14));
                pen.fillText(root ? "#" : "$", 3, 15);
            }
            case RUNNING -> {
                // A block that fills from the bottom and empties again, the way a cursor breathes while
                // something else has the line. No percentage, because there is nothing honest to measure.
                double phase = (Math.sin(seconds * 3.2) + 1) / 2;
                double height = 3 + 11 * phase;
                pen.setFill(RUNNING_COLOUR.deriveColor(0, 1, 1, 0.22));
                pen.fillRect(3, 3, 9, 14);
                pen.setFill(RUNNING_COLOUR);
                pen.fillRect(3, 3 + (14 - height), 9, height);
            }
            case ASKING -> {
                pen.setFill(ASKING_COLOUR);
                pen.fillRect(3, 3, 9, 14);
                pen.setFill(Color.web("#07070b"));
                pen.setFont(javafx.scene.text.Font.font("Consolas", 12));
                pen.fillText("?", 5, 14);
            }
        }
    }
}
