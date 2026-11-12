package com.kubo.ui.connection;

import com.kubo.ui.LiveTimer;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

/**
 * This computer on the left, the server on the right, and the connection between them as a wire with its
 * four steps on it: the key file unlocked, the server reached, its host key known, the sign in.
 *
 * <p>Each step lights as the connection reaches it, says how long it took once it is through, and the one a
 * failure happened at is cut, in red. While Kubo waits for the user to trust an unknown server, the wire stops
 * at the host key, and the server waits in amber.
 */
final class ConnectionLink extends Region {

    enum Phase { IDLE, CONNECTING, HOST_KEY, CONNECTED, FAILED }

    /** Where a step stands. */
    private enum Mark { WAITING, ACTIVE, DONE, FAILED }

    static final int KEY = 0;
    static final int REACH = 1;
    static final int HOST_KEY = 2;
    static final int SIGN_IN = 3;

    private static final Color GREEN = Color.web("#35e0a1");
    private static final Color AMBER = Color.web("#ffb347");
    private static final Color RED = Color.web("#ff2d55");
    private static final Color CYAN = Color.web("#35e8ff");
    private static final Color PINK = Color.web("#e75879");
    private static final Color TEXT = Color.web("#eaeaf2");
    private static final Color DIM = Color.web("#a9a9bb");
    private static final Color MUTED = Color.web("#6b6b80");
    private static final Color FAINT = Color.web("#2a2a36");
    private static final Color WELL = Color.web("#0b0b12");

    private static final Font NAME = Font.font("Consolas", FontWeight.BOLD, 13);
    private static final Font BIG = Font.font("Consolas", FontWeight.BOLD, 17);
    private static final Font SMALL = Font.font("Consolas", 10.5);
    private static final double HEIGHT = 136;

    private final Canvas canvas = new Canvas();
    private final LiveTimer timer;
    private final Mark[] marks = {Mark.WAITING, Mark.WAITING, Mark.WAITING, Mark.WAITING};
    private final long[] took = {-1, -1, -1, -1};
    private final long[] began = new long[4];
    private Phase phase = Phase.IDLE;
    private boolean key;
    private String local = localName();
    private String user = "";
    private String host = "";
    private int port = 22;
    private String outcome = "";
    private long total = -1;
    /** Whether this attempt asked the user to trust the server, rather than finding it already known. */
    private boolean askedTrust;
    private double frozen = -1;

    ConnectionLink() {
        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinHeight(HEIGHT);
        setPrefHeight(HEIGHT);
        setMaxHeight(HEIGHT);
        setMinWidth(0);
        timer = new LiveTimer(this, LiveTimer.AMBIENT, seconds -> paint());
        timer.start();
    }

    /** What the form points at now, or empty names when it points at nothing yet. */
    void setTarget(String user, String host, int port) {
        this.user = user == null ? "" : user;
        this.host = host == null ? "" : host;
        this.port = port;
        paint();
    }

    void setKey(boolean key) {
        this.key = key;
        paint();
    }

    /** Back to nothing happening: every step waiting. */
    void reset() {
        phase = Phase.IDLE;
        outcome = "";
        total = -1;
        askedTrust = false;
        for (int i = 0; i < marks.length; i++) {
            marks[i] = Mark.WAITING;
            took[i] = -1;
        }
        paint();
    }

    /** An attempt begins. With a password there is no key file to unlock: that step is through at once. */
    void begin(boolean withKey) {
        reset();
        key = withKey;
        phase = Phase.CONNECTING;
        if (withKey) {
            activate(KEY);
        } else {
            marks[KEY] = Mark.DONE;
            took[KEY] = 0;
        }
    }

    /** The connection reached this step: the ones before it are through. */
    void reach(int step) {
        long now = System.nanoTime();
        for (int i = 0; i < step; i++) {
            if (marks[i] == Mark.ACTIVE) {
                marks[i] = Mark.DONE;
                took[i] = (now - began[i]) / 1_000_000;
            } else if (marks[i] == Mark.WAITING) {
                // A known host key is checked without a word: it is through, with no time of its own.
                marks[i] = Mark.DONE;
            }
        }
        activate(step);
        if (step == HOST_KEY) {
            phase = Phase.HOST_KEY;
            askedTrust = true;
        } else if (phase == Phase.HOST_KEY) {
            phase = Phase.CONNECTING;
        }
        paint();
    }

    /** The user trusted the server: the host key step is through, and the sign in comes next. */
    void trusted() {
        marks[HOST_KEY] = Mark.DONE;
        took[HOST_KEY] = -1;
        phase = Phase.CONNECTING;
        paint();
    }

    void connected(long millis) {
        long now = System.nanoTime();
        for (int i = 0; i < marks.length; i++) {
            if (marks[i] == Mark.ACTIVE) {
                took[i] = (now - began[i]) / 1_000_000;
            }
            marks[i] = Mark.DONE;
        }
        total = millis;
        phase = Phase.CONNECTED;
        outcome = "";
        paint();
    }

    /** The attempt failed at this step, in these words. */
    void failed(int step, String words) {
        for (int i = 0; i < step; i++) {
            if (marks[i] != Mark.DONE) {
                marks[i] = Mark.DONE;
            }
        }
        marks[step] = Mark.FAILED;
        for (int i = step + 1; i < marks.length; i++) {
            marks[i] = Mark.WAITING;
        }
        outcome = words == null ? "" : words;
        phase = Phase.FAILED;
        paint();
    }

    Phase phase() {
        return phase;
    }

    void stop() {
        timer.stop();
    }

    /** Only for the previews: the times a real attempt would have measured, and the picture held still. */
    void timesForPreview(long keyMs, long reachMs, long signInMs) {
        took[KEY] = keyMs;
        took[REACH] = reachMs;
        took[SIGN_IN] = signInMs;
    }

    void freezeForPreview(double seconds, String localName) {
        frozen = seconds;
        local = localName;
        paint();
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        paint();
    }

    private void activate(int step) {
        marks[step] = Mark.ACTIVE;
        began[step] = System.nanoTime();
    }

    private double clock() {
        return frozen >= 0 ? frozen : System.nanoTime() / 1e9;
    }

    // Drawing

    private void paint() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setGlobalAlpha(1);
        gc.clearRect(0, 0, w, h);
        if (w < 260) {
            return;
        }
        double t = clock();
        gc.setTextBaseline(VPos.CENTER);
        gc.setLineCap(StrokeLineCap.ROUND);
        double mid = 46;
        double leftX = 52;
        double rightX = w - 52;
        double from = leftX + 30;
        double to = rightX - 30;

        drawWire(gc, from, to, mid, t);
        drawComputer(gc, leftX, mid);
        drawServer(gc, rightX, mid, t);
        double span = to - from;
        for (int i = 0; i < marks.length; i++) {
            drawStep(gc, i, from + span * (i + 1) / 5.0, mid, t);
        }
        drawOutcome(gc, w, mid);
    }

    /** Where the light on the wire gets to: the active step, the failed one, or the server. */
    private double reachX(double from, double to) {
        double span = to - from;
        for (int i = 0; i < marks.length; i++) {
            if (marks[i] == Mark.ACTIVE || marks[i] == Mark.FAILED) {
                return from + span * (i + 1) / 5.0;
            }
        }
        return phase == Phase.CONNECTED ? to : from;
    }

    private void drawWire(GraphicsContext gc, double from, double to, double mid, double t) {
        // The whole way, waiting: a dashed line that says where the connection would go.
        gc.setGlobalAlpha(1);
        gc.setStroke(host.isEmpty() ? FAINT : Color.web("#3a3a4a"));
        gc.setLineWidth(1.2);
        gc.setLineDashes(4, 6);
        gc.strokeLine(from, mid, to, mid);
        gc.setLineDashes((double[]) null);
        if (phase == Phase.IDLE) {
            return;
        }
        double end = reachX(from, to);
        Color colour = switch (phase) {
            case CONNECTED -> GREEN;
            case FAILED -> RED;
            case HOST_KEY -> AMBER;
            default -> PINK;
        };
        gc.setGlobalAlpha(0.9);
        gc.setStroke(colour);
        gc.setLineWidth(1.6);
        gc.strokeLine(from, mid, end, mid);
        if (phase == Phase.FAILED) {
            return;
        }
        // Light travelling along it: towards the server while it is being reached, both ways once it answers.
        int count = phase == Phase.CONNECTED ? 5 : 3;
        for (int i = 0; i < count; i++) {
            double p = (t * (phase == Phase.CONNECTED ? 0.35 : 0.6) + i / (double) count) % 1;
            double x = from + (end - from) * p;
            gc.setGlobalAlpha(0.9 * Math.sin(Math.PI * p));
            gc.setFill(colour);
            gc.fillOval(x - 2, mid - 2, 4, 4);
            if (phase == Phase.CONNECTED) {
                double back = to - (to - from) * p;
                gc.setGlobalAlpha(0.6 * Math.sin(Math.PI * p));
                gc.setFill(CYAN);
                gc.fillOval(back - 1.5, mid + 4.5, 3, 3);
            }
        }
        gc.setGlobalAlpha(1);
    }

    /** This computer: a screen on a stand, and its name under it. */
    private void drawComputer(GraphicsContext gc, double x, double mid) {
        gc.setGlobalAlpha(1);
        gc.setFill(WELL);
        gc.fillRoundRect(x - 26, mid - 20, 52, 34, 5, 5);
        gc.setStroke(phase == Phase.IDLE ? Color.web("#3a3a4a") : PINK);
        gc.setLineWidth(1.2);
        gc.strokeRoundRect(x - 25.5, mid - 19.5, 51, 33, 5, 5);
        gc.strokeLine(x, mid + 14, x, mid + 20);
        gc.strokeLine(x - 10, mid + 20.5, x + 10, mid + 20.5);
        gc.setFill(phase == Phase.IDLE ? MUTED : PINK);
        gc.setFont(SMALL);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(">_", x, mid - 3);
        gc.setFont(NAME);
        gc.setFill(TEXT);
        gc.fillText("you", x, mid + 40);
        gc.setFont(SMALL);
        gc.setFill(MUTED);
        gc.fillText(clip(local, SMALL, 120), x, mid + 56);
    }

    /** The server: a rack of three, lit by how the connection stands, its address under it. */
    private void drawServer(GraphicsContext gc, double x, double mid, double t) {
        Color colour = switch (phase) {
            case CONNECTED -> GREEN;
            case FAILED -> RED;
            case HOST_KEY -> AMBER;
            case CONNECTING -> DIM;
            case IDLE -> host.isEmpty() ? FAINT : Color.web("#4a4a5c");
        };
        if (phase == Phase.HOST_KEY || phase == Phase.CONNECTED) {
            double beat = 0.5 + 0.5 * Math.sin(t * (phase == Phase.HOST_KEY ? 3.2 : 1.6));
            gc.setGlobalAlpha(0.18 + 0.18 * beat);
            gc.setFill(new RadialGradient(0, 0, x, mid, 44, false, CycleMethod.NO_CYCLE,
                    new Stop(0, colour), new Stop(1, Color.TRANSPARENT)));
            gc.fillOval(x - 44, mid - 44, 88, 88);
        }
        gc.setGlobalAlpha(1);
        for (int i = 0; i < 3; i++) {
            double y = mid - 20 + i * 13;
            gc.setFill(WELL);
            gc.fillRoundRect(x - 24, y, 48, 10, 3, 3);
            gc.setStroke(colour);
            gc.setLineWidth(1.1);
            if (host.isEmpty()) {
                gc.setLineDashes(3, 3);
            }
            gc.strokeRoundRect(x - 23.5, y + 0.5, 47, 9, 3, 3);
            gc.setLineDashes((double[]) null);
            boolean lit = phase == Phase.CONNECTED || (phase == Phase.CONNECTING && (int) (t * 4 + i) % 3 == 0);
            gc.setFill(lit ? colour : Color.web("#2a2a36"));
            gc.fillOval(x + 14, y + 3, 4, 4);
        }
        if (phase == Phase.HOST_KEY) {
            gc.setFill(AMBER);
            gc.setFont(BIG);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText("?", x - 36, mid - 24);
        }
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFont(NAME);
        gc.setFill(host.isEmpty() ? MUTED : TEXT);
        gc.fillText(clip(host.isEmpty() ? "server" : host, NAME, 150), x, mid + 40);
        gc.setFont(SMALL);
        gc.setFill(MUTED);
        gc.fillText(host.isEmpty() ? "user@host" : (user.isEmpty() ? "" : user + " · ") + "port " + port,
                x, mid + 56);
    }

    private static String word(int step, boolean key) {
        return switch (step) {
            case KEY -> key ? "key file" : "password";
            case REACH -> "reach";
            case HOST_KEY -> "host key";
            default -> "sign in";
        };
    }

    private void drawStep(GraphicsContext gc, int step, double x, double mid, double t) {
        Mark mark = marks[step];
        Color colour = switch (mark) {
            case DONE -> phase == Phase.CONNECTED ? GREEN : Color.web("#5fd3a6");
            case ACTIVE -> step == HOST_KEY && phase == Phase.HOST_KEY ? AMBER : PINK;
            case FAILED -> RED;
            case WAITING -> Color.web("#3a3a4a");
        };
        gc.setGlobalAlpha(1);
        if (mark == Mark.ACTIVE) {
            double beat = 0.5 + 0.5 * Math.sin(t * 4);
            gc.setGlobalAlpha(0.25 + 0.3 * beat);
            gc.setFill(new RadialGradient(0, 0, x, mid, 16, false, CycleMethod.NO_CYCLE,
                    new Stop(0, colour), new Stop(1, Color.TRANSPARENT)));
            gc.fillOval(x - 16, mid - 16, 32, 32);
            gc.setGlobalAlpha(1);
        }
        gc.setFill(Color.web("#07080e"));
        gc.fillOval(x - 7, mid - 7, 14, 14);
        gc.setStroke(colour);
        gc.setLineWidth(1.4);
        gc.strokeOval(x - 6.5, mid - 6.5, 13, 13);
        if (mark == Mark.DONE) {
            gc.setFill(colour);
            gc.fillOval(x - 3.5, mid - 3.5, 7, 7);
        } else if (mark == Mark.FAILED) {
            gc.strokeLine(x - 3.5, mid - 3.5, x + 3.5, mid + 3.5);
            gc.strokeLine(x - 3.5, mid + 3.5, x + 3.5, mid - 3.5);
        } else if (mark == Mark.ACTIVE) {
            gc.setFill(colour);
            gc.fillOval(x - 2, mid - 2, 4, 4);
        }
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFont(SMALL);
        gc.setFill(mark == Mark.WAITING ? MUTED : mark == Mark.FAILED ? RED : DIM);
        gc.fillText(word(step, key), x, mid + 22);
        String time = took[step] > 0 ? took[step] + " ms"
                : step == HOST_KEY && mark == Mark.DONE ? (askedTrust ? "trusted" : "known") : "";
        if (!time.isEmpty()) {
            gc.setFill(MUTED);
            gc.fillText(time, x, mid - 18);
        }
    }

    /** Under the wire, in a few words: how it ended, or what it is waiting for. */
    private void drawOutcome(GraphicsContext gc, double w, double mid) {
        String words;
        Color colour;
        switch (phase) {
            case CONNECTED -> {
                words = "connected" + (total >= 0 ? " in " + total + " ms" : "");
                colour = GREEN;
            }
            case FAILED -> {
                words = outcome;
                colour = RED;
            }
            case HOST_KEY -> {
                words = "never seen before: check its fingerprint";
                colour = AMBER;
            }
            default -> {
                return;
            }
        }
        gc.setGlobalAlpha(1);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFont(SMALL);
        gc.setFill(colour);
        gc.fillText(clip(words, SMALL, w - 260), w / 2, mid + 50);
    }

    private static String localName() {
        String name = System.getenv("COMPUTERNAME");
        if (name == null || name.isBlank()) {
            name = System.getenv("HOSTNAME");
        }
        return name == null || name.isBlank() ? "this computer" : name.toLowerCase(java.util.Locale.ROOT);
    }

    private static double width(String text, Font font) {
        Text measure = new Text(text);
        measure.setFont(font);
        return measure.getLayoutBounds().getWidth();
    }

    private static String clip(String text, Font font, double room) {
        if (width(text, font) <= room) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && width(cut + "…", font) > room) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }
}
