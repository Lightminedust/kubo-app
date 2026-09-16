package com.kubo.ui.scope;

import com.kubo.model.server.ServerMetrics;
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
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The server drawn as one living system, where every motion is a measurement:
 * <ul>
 * <li>CPU: a particle sphere that spins faster, swirls harder and heats from cyan to ember as usage grows.</li>
 * <li>Steal: grey particles torn away from the sphere, in proportion to the CPU time the hypervisor takes.</li>
 * <li>Memory and swap: two rings around the sphere, lit for the share in use.</li>
 * <li>Load: waves leaving the sphere, more frequent and hotter as the load per core rises.</li>
 * </ul>
 *
 * <p>It shows the machine and nothing else: what the network exposes belongs to NETWORK.SCOPE.
 * <ul>
 * </ul>
 */
public final class CoreField extends Pane {

    private static final double TAU = Math.PI * 2;

    // Distances in sphere radii; orbits are ellipses to use the landscape canvas.
    private static final double MEMORY_RING = 1.45;
    private static final double SWAP_RING = 1.54;
    /** How far the outer ring sits from the core, in sphere radii. */
    private static final double OUTER = 1.95;
    private static final double STRETCH_X = 1.0;
    private static final double STRETCH_Y = 0.9;
    private static final int LATITUDE_BANDS = 16;
    private static final double TILT = 0.38;
    private static final double PULSE_LIFETIME = 3.2;

    private static final Color BACKGROUND = Color.web("#0a0a0f");
    private static final Color TEXT = Color.web("#e6e6ec");
    private static final Color MUTED = Color.web("#6e6e7d");
    private static final Color FAINT = Color.web("#24242d");
    private static final Color CYAN = Color.web("#35e8ff");
    private static final Color BLUE = Color.web("#3f7bff");
    private static final Color VIOLET = Color.web("#8a4dff");
    private static final Color MAGENTA = Color.web("#ff3fd8");
    private static final Color ALERT = Color.web("#ff2d55");
    private static final Color AMBER = Color.web("#ffb347");
    private static final Color EMBER = Color.web("#ff7a3d");
    private static final Color GHOST = Color.web("#8a8a99");
    private static final Color MOTE = Color.web("#9fb8ab");
    private static final Color[] HEAT = gradient(128,
            new double[]{0, 0.35, 0.55, 0.75, 0.9, 1},
            new Color[]{CYAN, BLUE, VIOLET, MAGENTA, ALERT, EMBER});

    private static final Font LABEL_FONT = Font.font("Segoe UI", FontWeight.SEMI_BOLD, 10.5);
    private static final Font VALUE_FONT = Font.font("Segoe UI", FontWeight.LIGHT, 18);
    private static final Font SMALL_FONT = Font.font("Segoe UI", 9.5);
    private static final Font CORE_FONT = Font.font("Segoe UI", FontWeight.LIGHT, 46);

    private static final int SPHERE_COUNT = 3400;
    private static final int MEMORY_COUNT = 1000;
    private static final int SWAP_DOTS = 180;
    private static final int FLOW_CAPACITY = 600;
    private static final int PULSE_CAPACITY = 8;
    private static final int MOTE_COUNT = 50;

    private final Canvas canvas = new Canvas();
    private final AnimationTimer timer;
    private final Random random = new Random(7);

    private final double[] theta = new double[SPHERE_COUNT];
    private final double[] phi = new double[SPHERE_COUNT];
    private final double[] drift = new double[SPHERE_COUNT];
    private final double[] radial = new double[SPHERE_COUNT];
    private final double[] size = new double[SPHERE_COUNT];
    private final double[] phase = new double[SPHERE_COUNT];
    private final double[] ghost = new double[SPHERE_COUNT];

    private final double[] memoryAngle = new double[MEMORY_COUNT];
    private final double[] memoryOffset = new double[MEMORY_COUNT];
    private final double[] memoryPhase = new double[MEMORY_COUNT];

    private final boolean[] flowActive = new boolean[FLOW_CAPACITY];
    private final boolean[] flowOutgoing = new boolean[FLOW_CAPACITY];
    private final double[] flowAngle = new double[FLOW_CAPACITY];
    private final double[] flowProgress = new double[FLOW_CAPACITY];
    private final double[] flowSpeed = new double[FLOW_CAPACITY];

    private final double[] pulseAge = new double[PULSE_CAPACITY];
    private final double[][] motes = new double[MOTE_COUNT][];

    // Values from the latest reading...
    private double cpuTarget;
    private double stealTarget;
    private double memoryTarget;
    private double swapTarget;
    private double loadTarget;
    private double incomingTarget;
    private double outgoingTarget;
    private boolean hasData;
    private boolean offline;

    // ...eased into what is drawn, so changes read as motion rather than jumps.
    private double cpu;
    private double steal;
    private double memory;
    private double swap;
    private double load;
    private double incoming;
    private double outgoing;
    private double presence;
    private double offlineLevel;

    private double rotation;
    private double incomingDebt;
    private double outgoingDebt;
    private double pulseClock;
    private double lastTime = -1;

    private String cpuText = "";
    private double cpuTextWidth;
    private String memoryText = "";
    private String memoryDetail = "";
    private String loadText = "";
    private String loadDetail = "";
    private String stealText = "";
    private String swapText = "";
    private String swapDetail = "";

    public CoreField() {
        Random seed = new Random(11);
        for (int i = 0; i < SPHERE_COUNT; i++) {
            theta[i] = seed.nextDouble() * TAU;
            // Uniform over the sphere's surface rather than bunched at the poles.
            double latitude = Math.asin(2 * seed.nextDouble() - 1);
            if (seed.nextDouble() < 0.75) {
                // Most particles follow latitude streams: they give the sphere its volume and show the swirl.
                double band = Math.round((latitude / Math.PI + 0.5) * LATITUDE_BANDS) / (double) LATITUDE_BANDS;
                latitude = (band - 0.5) * Math.PI + seed.nextGaussian() * 0.012;
            }
            phi[i] = latitude;
            drift[i] = seed.nextDouble();
            radial[i] = seed.nextGaussian() * 0.035;
            size[i] = 0.8 + seed.nextDouble() * 1.2;
            phase[i] = seed.nextDouble() * TAU;
            ghost[i] = seed.nextDouble();
        }
        for (int i = 0; i < MEMORY_COUNT; i++) {
            memoryAngle[i] = seed.nextDouble();
            memoryOffset[i] = seed.nextGaussian() * 0.012;
            memoryPhase[i] = seed.nextDouble() * TAU;
        }
        for (int i = 0; i < MOTE_COUNT; i++) {
            motes[i] = new double[]{seed.nextDouble(), seed.nextDouble(), 0.8 + seed.nextDouble() * 1.6,
                    0.06 + seed.nextDouble() * 0.2, seed.nextDouble() * TAU};
        }
        Arrays.fill(pulseAge, -1);

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
                renderFrame((now - start) / 1e9, true);
            }
        };
        timer.start();
    }

    public void setMetrics(ServerMetrics metrics, int cores) {
        long totalSwap = metrics.memory().totalSwapBytes();
        cpuTarget = metrics.cpuPercent() / 100;
        stealTarget = metrics.stealPercent() / 100;
        memoryTarget = metrics.memory().usagePercentage() / 100;
        swapTarget = totalSwap == 0 ? 0 : (double) metrics.memory().usedSwapBytes() / totalSwap;
        loadTarget = metrics.loadAverage().oneMin() / Math.max(1, cores);
        incomingTarget = Format.rateLevel(metrics.network().rxBytesPerSec());
        outgoingTarget = Format.rateLevel(metrics.network().txBytesPerSec());
        hasData = true;

        setCpuText(String.format(Locale.ROOT, "%.0f", metrics.cpuPercent()));
        memoryText = Format.percent(metrics.memory().usagePercentage());
        memoryDetail = Format.bytes(metrics.memory().usedBytes()) + " / " + Format.bytes(metrics.memory().totalBytes())
                + (totalSwap == 0 ? "  ·  NO SWAP" : "  ·  SWAP " + Format.percent(swapTarget * 100));
        loadText = String.format(Locale.ROOT, "%.2f", metrics.loadAverage().oneMin());
        loadDetail = cores + (cores == 1 ? " CORE" : " CORES") + "  ·  "
                + String.format(Locale.ROOT, "%.0f %% PER CORE", loadTarget * 100);
        stealText = Format.percent(metrics.stealPercent());
        swapText = totalSwap == 0 ? "NONE" : Format.bytes(metrics.memory().usedSwapBytes());
        swapDetail = totalSwap == 0 ? "NO SWAP CONFIGURED" : "OF " + Format.bytes(totalSwap);
    }

    /**
     * Connected but no metrics yet: the sphere idles until the second reading.
     */
    public void setWaiting() {
        hasData = false;
    }

    public void setOffline(boolean offline) {
        this.offline = offline;
    }

    public void stop() {
        timer.stop();
    }

    static Color heat(double level) {
        return HEAT[(int) Math.round(clamp(level) * (HEAT.length - 1))];
    }

    /**
     * Jumps to the current targets without easing, for previews.
     */
    void snapToState() {
        cpu = cpuTarget;
        steal = stealTarget;
        memory = memoryTarget;
        swap = swapTarget;
        load = loadTarget;
        incoming = incomingTarget;
        outgoing = outgoingTarget;
        presence = hasData ? 1 : 0;
        offlineLevel = offline ? 1 : 0;
    }

    /**
     * Advances the simulation without drawing, then draws the last frame; for previews.
     */
    void simulate(double seconds) {
        lastTime = -1;
        for (double time = 0; time < seconds; time += 1.0 / 30) {
            renderFrame(time, false);
        }
        renderFrame(seconds, true);
    }

    void renderFrame(double time, boolean draw) {
        double dt = lastTime < 0 ? 0 : Math.min(0.1, time - lastTime);
        lastTime = time;
        update(dt);
        if (draw) {
            paint(time);
        }
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
    }

    private void update(double dt) {
        double ease = Math.min(1, dt * 1.6);
        cpu += (cpuTarget - cpu) * ease;
        steal += (stealTarget - steal) * ease;
        memory += (memoryTarget - memory) * ease;
        swap += (swapTarget - swap) * ease;
        load += (loadTarget - load) * ease;
        incoming += (incomingTarget - incoming) * ease;
        outgoing += (outgoingTarget - outgoing) * ease;
        presence += ((hasData ? 1 : 0) - presence) * ease;
        offlineLevel += ((offline ? 1 : 0) - offlineLevel) * Math.min(1, dt * 1.2);

        double activity = (1 - offlineLevel) * presence;
        rotation += dt * (0.06 + cpu * 2.2) * (1 - offlineLevel);

        incomingDebt += dt * Math.pow(incoming, 3) * 50 * activity;
        outgoingDebt += dt * Math.pow(outgoing, 3) * 50 * activity;
        while (incomingDebt >= 1) {
            spawnFlow(false);
            incomingDebt -= 1;
        }
        while (outgoingDebt >= 1) {
            spawnFlow(true);
            outgoingDebt -= 1;
        }
        for (int i = 0; i < FLOW_CAPACITY; i++) {
            if (flowActive[i]) {
                flowProgress[i] += dt * flowSpeed[i];
                flowActive[i] = flowProgress[i] < 1;
            }
        }

        pulseClock += dt * (0.2 + Math.min(load, 2.5) * 0.75) * activity;
        if (pulseClock >= 1) {
            pulseClock -= 1;
            emitPulse();
        }
        for (int i = 0; i < PULSE_CAPACITY; i++) {
            if (pulseAge[i] >= 0) {
                pulseAge[i] += dt;
                if (pulseAge[i] > PULSE_LIFETIME) {
                    pulseAge[i] = -1;
                }
            }
        }
    }

    private void spawnFlow(boolean outgoingFlow) {
        for (int i = 0; i < FLOW_CAPACITY; i++) {
            if (!flowActive[i]) {
                flowActive[i] = true;
                flowOutgoing[i] = outgoingFlow;
                flowAngle[i] = random.nextDouble() * TAU;
                flowProgress[i] = 0;
                flowSpeed[i] = 0.35 + random.nextDouble() * 0.35;
                return;
            }
        }
    }

    private void emitPulse() {
        for (int i = 0; i < PULSE_CAPACITY; i++) {
            if (pulseAge[i] < 0) {
                pulseAge[i] = 0;
                return;
            }
        }
    }

    private void paint(double time) {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width < 1 || height < 1) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setGlobalAlpha(1);
        gc.setFill(BACKGROUND);
        gc.fillRect(0, 0, width, height);

        double cx = width / 2;
        double cy = height / 2 - 6;
        double radius = Math.max(28, Math.min(
                (width / 2 - 64) / (OUTER * STRETCH_X),
                (height / 2 - 34) / (OUTER * STRETCH_Y)));

        drawMotes(gc, width, height, time);
        drawOrbits(gc, cx, cy, radius);
        drawPulses(gc, cx, cy, radius);
        drawGlow(gc, cx, cy, radius);
        drawSphere(gc, cx, cy, radius, time);
        drawMemory(gc, cx, cy, radius, time);
        drawFlows(gc, cx, cy, radius);
        drawCoreReading(gc, cx, cy, radius);
        drawAnnotations(gc, width, height, cx, cy, radius);
        gc.setGlobalAlpha(1);
    }

    private void drawMotes(GraphicsContext gc, double width, double height, double time) {
        gc.setFill(MOTE);
        for (double[] mote : motes) {
            double x = mote[0] * width + Math.sin(time * 0.05 + mote[4]) * 8;
            double y = mote[1] * height + Math.cos(time * 0.04 + mote[4]) * 8;
            gc.setGlobalAlpha(mote[3] * (0.6 + 0.4 * Math.sin(time * 0.3 + mote[4])));
            gc.fillOval(x - mote[2], y - mote[2], mote[2] * 2, mote[2] * 2);
        }
    }

    private void drawOrbits(GraphicsContext gc, double cx, double cy, double radius) {
        gc.setLineWidth(1);
        gc.setStroke(FAINT);
        gc.setGlobalAlpha(0.9);
        // One faint ring, far enough out to give the sphere air.
        double rx = radius * OUTER * STRETCH_X;
        double ry = radius * OUTER * STRETCH_Y;
        gc.strokeOval(cx - rx, cy - ry, rx * 2, ry * 2);
        gc.setGlobalAlpha(1);
    }

    private void drawPulses(GraphicsContext gc, double cx, double cy, double radius) {
        Color color = load < 0.7 ? BLUE : load < 1 ? AMBER : ALERT;
        gc.setStroke(color);
        gc.setLineWidth(1.2);
        for (double age : pulseAge) {
            if (age < 0) {
                continue;
            }
            double t = age / PULSE_LIFETIME;
            double distance = radius * (1.08 + t * (OUTER - 1.08));
            double rx = distance * (1 + (STRETCH_X - 1) * t);
            double ry = distance * (1 + (STRETCH_Y - 1) * t);
            gc.setGlobalAlpha((1 - t) * (1 - t) * 0.5);
            gc.strokeOval(cx - rx, cy - ry, rx * 2, ry * 2);
        }
    }

    private void drawGlow(GraphicsContext gc, double cx, double cy, double radius) {
        double strength = (0.12 + 0.22 * cpu) * presence * (1 - 0.85 * offlineLevel);
        RadialGradient glow = new RadialGradient(0, 0, cx, cy, radius * 1.7, false, CycleMethod.NO_CYCLE,
                new Stop(0, heat(cpu).deriveColor(0, 1, 1, strength)), new Stop(1, Color.TRANSPARENT));
        gc.setGlobalAlpha(1);
        gc.setFill(glow);
        gc.fillOval(cx - radius * 1.7, cy - radius * 1.7, radius * 3.4, radius * 3.4);
    }

    private void drawSphere(GraphicsContext gc, double cx, double cy, double radius, double time) {
        double turbulence = 0.012 + cpu * 0.11;
        // Amplified so a few percent of steal is visible: 5 % steal detaches 20 % of the particles.
        double stolenShare = Math.min(0.6, steal * 4) * (1 - offlineLevel);
        double breathe = time * (0.7 + cpu * 3.0);
        double cosTilt = Math.cos(TILT);
        double sinTilt = Math.sin(TILT);
        double dim = 0.35 + 0.65 * presence;
        int lastHeat = HEAT.length - 1;

        for (int i = 0; i < SPHERE_COUNT; i++) {
            // Particles rotate at different speeds, so a busy CPU turns the sphere into a swirl.
            double t = theta[i] + rotation * (0.55 + drift[i] * 0.9);
            double p = phi[i] + Math.sin(breathe * 0.35 + phase[i]) * turbulence * 0.8;
            double x3 = Math.cos(p) * Math.sin(t);
            double y3 = Math.sin(p);
            double z3 = Math.cos(p) * Math.cos(t);
            double y = y3 * cosTilt - z3 * sinTilt;
            double z = y3 * sinTilt + z3 * cosTilt;
            double depth = (z + 1) / 2;

            double distance = radius * (1 + radial[i] * (1 + turbulence * 12)
                    + Math.sin(breathe + phase[i]) * turbulence * 0.35);
            boolean stolen = ghost[i] < stolenShare;
            double escape = (time * 0.25 + phase[i] / TAU) % 1;
            if (stolen) {
                distance *= 1 + escape * 1.1;
            }
            distance *= 1 + offlineLevel * (0.5 + ghost[i] * 1.6);

            Color color;
            // Strong depth falloff: the far side stays a haze, which is what makes it read as a sphere.
            double alpha = (0.04 + 0.96 * depth * depth) * dim * (1 - 0.6 * offlineLevel);
            if (stolen) {
                color = GHOST;
                alpha *= 0.7 * (1 - escape);
            } else if (offlineLevel > 0.5) {
                color = GHOST;
            } else {
                double level = cpu * presence + (drift[i] - 0.5) * 0.22 + (depth - 0.5) * 0.08;
                color = HEAT[(int) Math.round(clamp(level) * lastHeat)];
                alpha *= 0.9;
            }

            double particle = size[i] * (0.55 + 0.8 * depth);
            gc.setGlobalAlpha(alpha);
            gc.setFill(color);
            gc.fillRect(cx + x3 * distance - particle / 2, cy + y * distance - particle / 2, particle, particle);
        }
    }

    private void drawMemory(GraphicsContext gc, double cx, double cy, double radius, double time) {
        Color lit = memory < 0.7 ? CYAN : memory < 0.9 ? AMBER : ALERT;
        double used = memory * presence;
        double fade = 1 - 0.6 * offlineLevel;
        for (int i = 0; i < MEMORY_COUNT; i++) {
            // Filled clockwise from the top, like a gauge.
            double angle = -Math.PI / 2 + memoryAngle[i] * TAU;
            double distance = radius * (MEMORY_RING + memoryOffset[i]);
            boolean inUse = memoryAngle[i] < used;
            gc.setFill(inUse ? lit : MUTED);
            gc.setGlobalAlpha((inUse ? 0.6 + 0.35 * Math.sin(time * 2 + memoryPhase[i]) : 0.14) * fade);
            gc.fillRect(cx + Math.cos(angle) * distance - 0.9, cy + Math.sin(angle) * distance - 0.9, 1.8, 1.8);
        }

        for (int i = 0; i < SWAP_DOTS; i++) {
            double fraction = (double) i / SWAP_DOTS;
            double angle = -Math.PI / 2 + fraction * TAU;
            boolean inUse = fraction < swap * presence;
            gc.setFill(inUse ? VIOLET : FAINT);
            gc.setGlobalAlpha((inUse ? 0.9 : 0.8) * fade);
            gc.fillRect(cx + Math.cos(angle) * radius * SWAP_RING - 0.8,
                    cy + Math.sin(angle) * radius * SWAP_RING - 0.8, 1.6, 1.6);
        }

        if (presence > 0.01) {
            double end = -Math.PI / 2 + used * TAU;
            gc.setStroke(TEXT);
            gc.setLineWidth(1);
            gc.setGlobalAlpha(0.8 * fade);
            gc.strokeLine(cx + Math.cos(end) * radius * (MEMORY_RING - 0.07), cy + Math.sin(end) * radius * (MEMORY_RING - 0.07),
                    cx + Math.cos(end) * radius * (SWAP_RING + 0.05), cy + Math.sin(end) * radius * (SWAP_RING + 0.05));
        }
    }

    private void drawFlows(GraphicsContext gc, double cx, double cy, double radius) {
        double inner = radius * 1.08;
        double outer = radius * OUTER * 1.15;
        gc.setLineWidth(1);
        for (int i = 0; i < FLOW_CAPACITY; i++) {
            if (!flowActive[i]) {
                continue;
            }
            double progress = flowProgress[i];
            boolean out = flowOutgoing[i];
            double distance = out ? inner + (outer - inner) * progress : outer - (outer - inner) * progress;
            double spread = (distance - inner) / (outer - inner);
            double angle = flowAngle[i] + (out ? progress : -(1 - progress)) * 0.45;
            double sx = 1 + (STRETCH_X - 1) * spread;
            double sy = 1 + (STRETCH_Y - 1) * spread;
            double tail = distance + (out ? -9 : 9);

            gc.setStroke(out ? MAGENTA : CYAN);
            gc.setGlobalAlpha(Math.sin(progress * Math.PI) * 0.6);
            gc.strokeLine(cx + Math.cos(angle) * distance * sx, cy + Math.sin(angle) * distance * sy,
                    cx + Math.cos(angle) * tail * sx, cy + Math.sin(angle) * tail * sy);
        }
    }

    private void drawCoreReading(GraphicsContext gc, double cx, double cy, double radius) {
        RadialGradient shade = new RadialGradient(0, 0, cx, cy, radius * 0.6, false, CycleMethod.NO_CYCLE,
                new Stop(0, BACKGROUND.deriveColor(0, 1, 1, 0.8)), new Stop(1, Color.TRANSPARENT));
        gc.setGlobalAlpha(1);
        gc.setFill(shade);
        gc.fillOval(cx - radius * 0.6, cy - radius * 0.6, radius * 1.2, radius * 1.2);

        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.BASELINE);
        if (offlineLevel > 0.5) {
            gc.setFont(LABEL_FONT);
            gc.setFill(ALERT);
            gc.fillText("OFFLINE", cx, cy + 4);
            return;
        }
        if (!hasData) {
            gc.setFont(LABEL_FONT);
            gc.setFill(MUTED);
            gc.fillText("READING", cx, cy + 4);
            return;
        }
        gc.setFont(SMALL_FONT);
        gc.setFill(MUTED);
        gc.fillText("CPU", cx, cy - 32);
        gc.setFont(CORE_FONT);
        gc.setFill(TEXT);
        gc.fillText(cpuText, cx - 7, cy + 18);
        gc.setFont(LABEL_FONT);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("%", cx - 7 + cpuTextWidth / 2 + 3, cy + 17);
    }

    private void drawAnnotations(GraphicsContext gc, double width, double height, double cx, double cy, double radius) {
        if (!hasData) {
            return;
        }
        double fade = 1 - 0.6 * offlineLevel;
        double top = 44;
        double bottom = height - 50;

        annotation(gc, 24, top, true, "MEMORY", memoryText, memoryDetail,
                memory < 0.7 ? TEXT : memory < 0.9 ? AMBER : ALERT, CYAN, fade);
        annotation(gc, width - 24, top, false, "SWAP", swapText, swapDetail, TEXT, MAGENTA, fade);
        annotation(gc, 24, bottom, true, "LOAD", loadText, loadDetail,
                load < 0.7 ? TEXT : load < 1 ? AMBER : ALERT, BLUE, fade);
        annotation(gc, width - 24, bottom, false, "STEAL", stealText, "CPU TIME TAKEN BY THE HOST",
                steal < 0.05 ? TEXT : ALERT, GHOST, fade);
    }

    /**
     * A corner caption; its rule carries the color used for that measurement in the picture.
     */
    private void annotation(GraphicsContext gc, double x, double y, boolean alignLeft, String title, String value,
                            String detail, Color valueColor, Color key, double fade) {
        gc.setGlobalAlpha(fade);
        gc.setTextAlign(alignLeft ? TextAlignment.LEFT : TextAlignment.RIGHT);
        gc.setTextBaseline(VPos.BASELINE);
        gc.setFont(LABEL_FONT);
        gc.setFill(MUTED);
        gc.fillText(title, x, y);
        gc.setFont(VALUE_FONT);
        gc.setFill(valueColor);
        gc.fillText(value, x, y + 22);
        gc.setFont(SMALL_FONT);
        gc.setFill(MUTED);
        gc.fillText(detail, x, y + 38);

        double ruleY = y - 16.5;
        double ruleEnd = alignLeft ? x + 22 : x - 22;
        gc.setStroke(key);
        gc.setLineWidth(1);
        gc.setGlobalAlpha(0.9 * fade);
        gc.strokeLine(x, ruleY, ruleEnd, ruleY);
        gc.setFill(key);
        gc.fillOval(ruleEnd - 2, ruleY - 2, 4, 4);
    }

    private void setCpuText(String text) {
        if (!text.equals(cpuText)) {
            cpuText = text;
            Text measure = new Text(text);
            measure.setFont(CORE_FONT);
            cpuTextWidth = measure.getLayoutBounds().getWidth();
        }
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static Color[] gradient(int size, double[] stops, Color[] colors) {
        Color[] result = new Color[size];
        for (int i = 0; i < size; i++) {
            double t = (double) i / (size - 1);
            int segment = 0;
            while (segment < stops.length - 2 && t > stops[segment + 1]) {
                segment++;
            }
            double local = (t - stops[segment]) / (stops[segment + 1] - stops[segment]);
            result[i] = colors[segment].interpolate(colors[segment + 1], clamp(local));
        }
        return result;
    }
}
