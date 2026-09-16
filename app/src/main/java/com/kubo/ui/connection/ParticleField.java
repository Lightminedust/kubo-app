package com.kubo.ui.connection;

import javafx.animation.AnimationTimer;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.Locale;
import java.util.Random;

/**
 * The connection screen's illustration: a fall of ones and zeros, in Kubo's own palette, that reads the state of
 * the attempt through colour and speed rather than through a diagram.
 *
 * <p>The rain has two sides. The left columns fall in the server's violet, the right in this computer's cyan — two
 * systems side by side. While Kubo connects the fall quickens; when the session opens the whole field resolves to
 * one green, the two sides become one; a refusal washes it amber and a failure breaks it red.
 *
 * <p>The public surface is unchanged from the earlier illustration, so the connection screen drives it the same
 * way: {@link #setState}, {@link #setRemote}, {@link #setHandshake}, and the preview hooks.
 */
public final class ParticleField extends Pane {

    public enum State {
        IDLE,
        CONNECTING,
        AWAITING_HOST_KEY,
        CONNECTED,
        FAILED
    }

    private static final Color BACKGROUND = Color.web("#04040a");
    private static final Color LOCAL = Color.web("#35e8ff");
    private static final Color REMOTE = Color.web("#9d4dff");
    private static final Color LIVE = Color.web("#35e0a1");
    private static final Color ALERT = Color.web("#ff2d55");
    private static final Color AMBER = Color.web("#ffb347");
    private static final Color TEXT = Color.web("#e8e8f2");
    private static final Color MUTED = Color.web("#6b6b80");

    /** One cell of the grid, in pixels; the glyphs are sized to sit inside it. */
    private static final double CELL = 17;
    private static final Font GLYPH_FONT = Font.font("Consolas", 15);
    private static final Font STATE_FONT = Font.font("Segoe UI", FontWeight.SEMI_BOLD, 12);
    private static final Font VALUE_FONT = Font.font("Consolas", 11);

    private final Canvas canvas = new Canvas();
    private final AnimationTimer timer;
    private final Random seed = new Random(7);

    /** Per column: head position in cells, its speed, and the length of its lit trail. */
    private double[] head = new double[0];
    private double[] speed = new double[0];
    private int[] trail = new int[0];
    private int columns;
    private int rows;

    private State state = State.IDLE;
    private String remote = "NO TARGET YET";
    private String handshake = "IDLE";

    // Everything the state drives, eased toward its target so a change is a transition, not a jump.
    private double glow = 0.5;
    private double targetGlow = 0.5;
    /** How far the rain is washed toward the state colour: 0 keeps each side's own hue. */
    private double wash;
    private double targetWash;
    private Color washColour = LIVE;
    /** A burst that fires the moment the session opens or the attempt breaks. */
    private double flash;

    private double clock;
    private double lastTime = -1;

    public ParticleField() {
        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinSize(0, 0);

        timer = new AnimationTimer() {
            private long start = -1;

            @Override
            public void handle(long now) {
                if (start < 0) {
                    start = now;
                }
                render((now - start) / 1e9);
            }
        };
        timer.start();
    }

    public void setState(State state) {
        if (this.state == state) {
            return;
        }
        this.state = state;
        if (state == State.CONNECTED || state == State.FAILED) {
            flash = 1;
        }
        targetGlow = switch (state) {
            case IDLE -> 0.45;
            case CONNECTING -> 1;
            case AWAITING_HOST_KEY -> 0.75;
            case CONNECTED -> 0.9;
            case FAILED -> 0.5;
        };
        targetWash = switch (state) {
            case IDLE, CONNECTING -> 0;
            case AWAITING_HOST_KEY -> 0.6;
            case CONNECTED -> 1;
            case FAILED -> 0.85;
        };
        washColour = switch (state) {
            case AWAITING_HOST_KEY -> AMBER;
            case FAILED -> ALERT;
            default -> LIVE;
        };
    }

    public void setRemote(String subtitle) {
        this.remote = subtitle == null || subtitle.isBlank() ? "NO TARGET YET" : subtitle;
    }

    public void setHandshake(String text) {
        this.handshake = text == null ? "" : text;
    }

    public void stop() {
        timer.stop();
    }

    /**
     * Jumps straight to where the current state settles, for previews.
     */
    public void snapToState() {
        glow = targetGlow;
        wash = targetWash;
        flash = 0;
    }

    /**
     * Draws one frame at a fixed time, for previews.
     */
    public void renderFrame(double time) {
        clock = time;
        ensureGrid();
        // Seed the rain at a spread of positions so a single frozen frame still looks like falling rain.
        for (int i = 0; i < columns; i++) {
            head[i] = (i * 7.3 + time * speed[i]) % (rows + trail[i]);
        }
        paint();
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
    }

    private void render(double time) {
        double dt = lastTime < 0 ? 0 : Math.min(0.1, time - lastTime);
        lastTime = time;
        clock = time;
        glow += (targetGlow - glow) * Math.min(1, dt * 3);
        wash += (targetWash - wash) * Math.min(1, dt * 3);
        flash = Math.max(0, flash - dt * 1.4);
        ensureGrid();

        double rush = 4 + glow * 12;
        for (int i = 0; i < columns; i++) {
            head[i] += speed[i] * rush * dt;
            if (head[i] - trail[i] > rows) {
                respawn(i);
            }
        }
        paint();
    }

    /**
     * Builds or rebuilds the column grid when the canvas is resized.
     */
    private void ensureGrid() {
        int wantColumns = Math.max(1, (int) Math.ceil(canvas.getWidth() / CELL));
        int wantRows = Math.max(1, (int) Math.ceil(canvas.getHeight() / CELL));
        if (wantColumns == columns && wantRows == rows) {
            return;
        }
        columns = wantColumns;
        rows = wantRows;
        head = new double[columns];
        speed = new double[columns];
        trail = new int[columns];
        for (int i = 0; i < columns; i++) {
            respawn(i);
            // Scatter the first positions so the rain does not start as one flat line.
            head[i] = seed.nextDouble() * rows;
        }
    }

    private void respawn(int column) {
        head[column] = -seed.nextInt(Math.max(1, rows));
        speed[column] = 0.6 + seed.nextDouble() * 1.1;
        trail[column] = 6 + seed.nextInt(14);
    }

    private void paint() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width < 1 || height < 1) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setGlobalAlpha(1);
        gc.setFill(BACKGROUND);
        gc.fillRect(0, 0, width, height);
        gc.setFont(GLYPH_FONT);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);

        for (int col = 0; col < columns; col++) {
            // Left half is the server's violet, right half this computer's cyan; the wash pulls both toward the
            // state colour so the two sides become one on connect.
            Color base = col < columns / 2 ? REMOTE : LOCAL;
            Color colour = wash > 0.001 ? base.interpolate(washColour, wash) : base;
            double x = col * CELL + CELL / 2;
            // The rain lives in the middle of the screen and fades out over the form and the saved sessions, so it
            // never runs behind the text on either side.
            double band = confine(x, width);
            if (band <= 0.01) {
                continue;
            }
            int headCell = (int) Math.floor(head[col]);

            for (int k = 0; k < trail[col]; k++) {
                int cell = headCell - k;
                if (cell < 0 || cell > rows) {
                    continue;
                }
                double y = cell * CELL + CELL / 2;
                // A stable-but-flickering glyph: mostly fixed per cell, occasionally flipping near the head.
                boolean one = (hash(col, cell) + (k < 2 ? (int) (clock * 6) : 0)) % 2 == 0;
                double fade = 1 - k / (double) trail[col];
                if (k == 0) {
                    // The head: brightest, tinted toward white.
                    gc.setGlobalAlpha((0.85 + 0.15 * glow) * band);
                    gc.setFill(colour.interpolate(Color.WHITE, 0.55));
                    gc.fillText(one ? "1" : "0", x, y);
                } else {
                    gc.setGlobalAlpha((0.05 + 0.45 * fade * fade) * (0.5 + 0.6 * glow) * band);
                    gc.setFill(colour);
                    gc.fillText(one ? "1" : "0", x, y);
                }
            }
        }
        gc.setGlobalAlpha(1);

        // A bright band that opens when the session opens: the two sides meeting.
        if (flash > 0.01 && state == State.CONNECTED) {
            gc.setFill(LIVE.deriveColor(0, 1, 1, 0.16 * flash));
            double sweepY = height * (0.2 + 0.6 * (1 - flash));
            gc.fillRect(width * 0.3, sweepY - 26, width * 0.4, 52);
        }

        drawVignette(gc, width, height);
        drawCaption(gc, width, height);
    }

    /**
     * Darkens the edges so the fall gathers toward the middle and the words stay readable.
     */
    private void drawVignette(GraphicsContext gc, double width, double height) {
        gc.setFill(new RadialGradient(0, 0, width / 2, height / 2, Math.max(width, height) * 0.62, false,
                CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#04040a", 0)),
                new Stop(0.7, Color.web("#04040a", 0.25)),
                new Stop(1, Color.web("#04040a", 0.92))));
        gc.fillRect(0, 0, width, height);
    }

    private void drawCaption(GraphicsContext gc, double width, double height) {
        Color colour = switch (state) {
            case FAILED -> ALERT;
            case CONNECTED -> LIVE;
            case AWAITING_HOST_KEY -> AMBER;
            case CONNECTING -> LOCAL;
            case IDLE -> MUTED;
        };
        String label = switch (state) {
            case IDLE -> "NOT CONNECTED";
            case CONNECTING -> "CONNECTING";
            case AWAITING_HOST_KEY -> "WAITING FOR YOU TO TRUST THE HOST KEY";
            case CONNECTED -> "SESSION OPEN";
            case FAILED -> "STOPPED HERE";
        };
        double cx = width / 2;
        double cy = height / 2;

        // A plate behind the words so no glyph runs through them.
        gc.setFill(Color.web("#04040a", 0.72));
        gc.fillRect(cx - 220, cy - 34, 440, 68);

        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFont(STATE_FONT);
        gc.setFill(colour);
        gc.fillText(label, cx, cy - 8);
        gc.setFont(VALUE_FONT);
        gc.setFill(MUTED);
        gc.fillText(remote.toUpperCase(Locale.ROOT), cx, cy + 10);
        if (!handshake.isBlank() && state != State.IDLE) {
            gc.fillText(handshake.toUpperCase(Locale.ROOT), cx, cy + 26);
        }
    }

    /**
     * How much of the rain survives at this horizontal position: full through the middle, gone toward the two text
     * columns. The ramp is soft so the fall dissolves rather than being cut.
     */
    private static double confine(double x, double width) {
        double from = x / width;
        double edge = Math.min(from, 1 - from);
        // Clear below a third of the width on either side, where the form and the saved sessions sit; the rain
        // fills only the corridor between them.
        return clamp((edge - 0.33) / 0.09, 0, 1);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * A stable choice of glyph for a cell, so a column reads as a falling stream rather than random noise.
     */
    private static int hash(int col, int cell) {
        int value = col * 73856093 ^ cell * 19349663;
        value ^= value >>> 13;
        return Math.abs(value);
    }
}
