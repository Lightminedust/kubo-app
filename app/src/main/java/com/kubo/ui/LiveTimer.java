package com.kubo.ui;

import javafx.animation.AnimationTimer;
import javafx.beans.InvalidationListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * The frame clock of every animated canvas in Kubo.
 *
 * <p>A plain {@link AnimationTimer} draws on every pulse for as long as it runs — and that includes the time its
 * window spends minimized. Nothing is presented then, so nothing paces the pulses any more, and they spin. Measured
 * on the connection screen: about a quarter of one core with the window visible, more than a whole core minimized.
 *
 * <p>This clock does two things about it:
 * <ul>
 *   <li>it stops outright while its window is minimized or closed, and starts again when the window comes back.
 *       A JavaFX animation that is stopped costs nothing at all;</li>
 *   <li>it draws at most {@code maxFps} frames a second. Every animation in Kubo is driven by elapsed time, not by
 *       counting frames, so a lower rate changes nothing about how fast things move — only how often the picture
 *       is redrawn.</li>
 * </ul>
 * Frames are also skipped, without stopping, while the canvas sits in a hidden part of a window.
 *
 * <p>The frame callback receives the seconds elapsed since the first frame, which keep counting through a pause:
 * a sphere that was turning is still turning at the right angle when the window comes back.
 */
public final class LiveTimer {

    /** No cap: a frame on every pulse. For views being moved by hand, where smoothness is the point. */
    public static final double EVERY_PULSE = 0;

    /** Enough for drifting particles and slow sweeps, at half the work of a 60 Hz pulse. */
    public static final double AMBIENT = 30;

    private final Node node;
    private final DoubleSupplier maxFps;
    private final DoubleConsumer frame;
    private final AnimationTimer timer;
    private final InvalidationListener recheck = observable -> update();

    private boolean wanted;
    private boolean running;
    private long origin = -1;
    private long lastFrame = -1;

    public LiveTimer(Node node, double maxFps, DoubleConsumer frame) {
        this(node, () -> maxFps, frame);
    }

    /**
     * @param maxFps asked again before every frame, so a view can draw faster while it is being moved
     */
    public LiveTimer(Node node, DoubleSupplier maxFps, DoubleConsumer frame) {
        this.node = node;
        this.maxFps = maxFps;
        this.frame = frame;
        this.timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                tick(now);
            }
        };
        node.sceneProperty().addListener((observable, before, scene) -> {
            watch(scene);
            update();
        });
        watch(node.getScene());
    }

    /** Asks for frames. They only come while the window is on screen. */
    public void start() {
        wanted = true;
        update();
    }

    /** Stops for good, until {@link #start()} is called again — whatever the window does meanwhile. */
    public void stop() {
        wanted = false;
        update();
    }

    private void tick(long now) {
        if (origin < 0) {
            origin = now;
        }
        if (!due(now, lastFrame, maxFps.getAsDouble()) || !treeVisible(node)) {
            return;
        }
        lastFrame = now;
        frame.accept((now - origin) / 1e9);
    }

    /**
     * Whether enough time has passed since the last frame to draw another. A tenth of an interval is forgiven, so
     * that a 60 Hz pulse asked for 30 frames lands on every second pulse rather than drifting to every third.
     */
    static boolean due(long now, long last, double maxFps) {
        if (maxFps <= 0 || last < 0) {
            return true;
        }
        return now - last >= 1e9 / maxFps * 0.9;
    }

    private void watch(Scene scene) {
        if (scene == null) {
            return;
        }
        scene.windowProperty().addListener((observable, before, window) -> {
            watchWindow(window);
            update();
        });
        watchWindow(scene.getWindow());
    }

    private void watchWindow(Window window) {
        if (window == null) {
            return;
        }
        window.showingProperty().addListener(recheck);
        if (window instanceof Stage stage) {
            stage.iconifiedProperty().addListener(recheck);
        }
    }

    private void update() {
        boolean run = wanted && onScreen();
        if (run && !running) {
            timer.start();
        } else if (!run && running) {
            timer.stop();
        }
        running = run;
    }

    private boolean onScreen() {
        Scene scene = node.getScene();
        Window window = scene == null ? null : scene.getWindow();
        if (window == null || !window.isShowing()) {
            return false;
        }
        return !(window instanceof Stage stage && stage.isIconified());
    }

    private static boolean treeVisible(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }
}
