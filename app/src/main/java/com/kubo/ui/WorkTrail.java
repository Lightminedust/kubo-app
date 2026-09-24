package com.kubo.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * What Kubo is asking the server, while it waits for the answer.
 *
 * <p>A screen that reads eight things over one connection takes as long as those eight things take, and until
 * now showed a line saying READING and nothing else. That is the same picture as a hung command, and the only
 * way to tell them apart was to wait and see.
 *
 * <p>So it shows the commands themselves, arriving one by one, oldest fading back. It is the terminal that
 * whoever is watching would have run by hand, and every word of it is true: these are exactly the commands
 * going out, the same ones the console lists and the log keeps. Nothing here is invented to look busy.
 */
public final class WorkTrail extends VBox {

    /** How many lines stay on screen. Enough to see a rhythm, not enough to become a wall. */
    private static final int KEPT = 7;

    private final Deque<Label> lines = new ArrayDeque<>();
    private final Label cursor = new Label("▌");
    private final LiveTimer blink;
    private boolean running;

    public WorkTrail() {
        getStyleClass().add("work-trail");
        setSpacing(2);
        setPadding(new Insets(12, 14, 12, 14));
        setMinHeight(Region.USE_PREF_SIZE);
        cursor.getStyleClass().add("work-trail-cursor");
        getChildren().add(cursor);
        // Two blinks a second, which is a terminal's own rhythm. It costs nothing while the window is away:
        // the timer stops itself with the scene.
        blink = new LiveTimer(this, 8, seconds -> cursor.setOpacity(((long) (seconds * 2)) % 2 == 0 ? 1 : 0.15));
    }

    /** Clears what was there and starts the cursor. */
    public void start() {
        running = true;
        lines.forEach(line -> getChildren().remove(line));
        lines.clear();
        cursor.setVisible(true);
        cursor.setManaged(true);
        blink.start();
    }

    /**
     * Says the work is over.
     *
     * <p>The lines stay. Somebody who looked away for ten seconds should still be able to see what was asked,
     * and a panel that empties itself the instant it finishes answers nothing.
     */
    public void finish() {
        running = false;
        cursor.setVisible(false);
        cursor.setManaged(false);
        blink.stop();
        lines.forEach(line -> line.setOpacity(0.45));
    }

    public boolean running() {
        return running;
    }

    /** Adds one command, as it goes out. */
    public void add(String command) {
        if (command == null || command.isBlank()) {
            return;
        }
        // Long reads are one line here, however many lines they are on the wire: this is a rhythm, not a log.
        String text = command.strip().replaceAll("\\s+", " ");
        Label line = new Label("$ " + text);
        line.getStyleClass().add("work-trail-line");
        line.setMinHeight(Region.USE_PREF_SIZE);
        line.setMaxWidth(Double.MAX_VALUE);
        line.setWrapText(false);
        // The newest is the brightest; the ones behind it recede rather than disappear.
        lines.addLast(line);
        getChildren().add(getChildren().size() - 1, line);
        while (lines.size() > KEPT) {
            getChildren().remove(lines.removeFirst());
        }
        int at = 0;
        for (Label each : lines) {
            each.setOpacity(0.25 + 0.75 * (at + 1) / (double) lines.size());
            at++;
        }
    }

    /** Only for the previews: a trail with these commands already in it. */
    public void showForPreview(List<String> commands, boolean finished) {
        start();
        commands.forEach(this::add);
        if (finished) {
            finish();
        }
    }
}
