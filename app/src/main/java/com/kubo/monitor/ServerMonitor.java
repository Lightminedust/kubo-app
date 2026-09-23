package com.kubo.monitor;

import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.model.server.DiskPartition;
import com.kubo.model.server.ListeningPort;
import com.kubo.model.server.Server;
import com.kubo.model.server.ServerIdentity;
import com.kubo.model.server.ServerMetrics;
import com.kubo.monitor.parse.DisksParser;
import com.kubo.monitor.parse.IdentityParser;
import com.kubo.monitor.parse.MetricsParser;
import com.kubo.monitor.parse.MetricsSample;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Keeps a {@link Server} up to date by polling over an open connection: identity once, metrics every
 * {@link #METRICS_INTERVAL}, ports and disks every {@link #INVENTORY_INTERVAL}. Every command goes into the
 * session history.
 *
 * <p>All polling runs on one background thread, so commands never overlap. Callbacks are invoked on that
 * thread: a UI must hand them over to its own thread.
 */
public final class ServerMonitor implements AutoCloseable {

    public static final Duration METRICS_INTERVAL = Duration.ofSeconds(2);
    public static final Duration INVENTORY_INTERVAL = Duration.ofSeconds(30);

    private final SshConnection connection;
    private final CommandService commands;
    private final Consumer<Server> onUpdate;
    private final Consumer<String> onFailure;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-monitor");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();

    // Only touched by the scheduler thread.
    private ServerIdentity identity;
    private MetricsSample previousSample;
    private ServerMetrics metrics;
    private List<ListeningPort> ports = List.of();
    private List<DiskPartition> disks = List.of();

    private volatile Server current;
    private volatile boolean degraded;
    private volatile Consumer<String> onProblem = message -> {
    };
    private volatile BooleanSupplier awake = () -> true;

    private static final System.Logger LOG = System.getLogger(ServerMonitor.class.getName());

    /**
     * @param onUpdate  receives each new snapshot
     * @param onFailure receives a message once, when monitoring stops because the identity cannot be read
     *                  or the connection is lost
     */
    public ServerMonitor(SshConnection connection, CommandService commands,
                         Consumer<Server> onUpdate, Consumer<String> onFailure) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.onUpdate = Objects.requireNonNull(onUpdate, "onUpdate");
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
    }

    /**
     * Starts polling in the background; returns immediately.
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Monitor already started");
        }
        scheduler.execute(() -> {
            try {
                initialize();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Monitoring could not start", e);
                fail("Monitoring could not start: " + describe(e));
            }
        });
    }

    /**
     * The latest snapshot, empty until the identity has been read.
     */
    public Optional<Server> current() {
        return Optional.ofNullable(current);
    }

    @Override
    public void close() {
        stopped.set(true);
        scheduler.shutdownNow();
    }

    private void initialize() {
        CommandEntry entry = run(IdentityParser.COMMAND);
        if (!entry.ran()) {
            fail("Cannot read the server identity: " + entry.error());
            return;
        }
        try {
            identity = IdentityParser.parse(entry.output());
        } catch (IllegalArgumentException e) {
            fail("Cannot read the server identity: " + e.getMessage());
            return;
        }

        refreshInventory();
        refreshMetrics();
        if (stopped.get()) {
            return;
        }
        publish();

        long metricsMs = METRICS_INTERVAL.toMillis();
        long inventoryMs = INVENTORY_INTERVAL.toMillis();
        scheduler.scheduleWithFixedDelay(guarded(this::refreshMetrics), metricsMs, metricsMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(guarded(this::refreshInventory), inventoryMs, inventoryMs, TimeUnit.MILLISECONDS);
    }

    private void refreshMetrics() {
        CommandEntry entry = run(MetricsParser.COMMAND);
        if (!entry.ran()) {
            return;
        }
        try {
            MetricsSample sample = MetricsParser.parse(entry.output(), entry.executedAt());
            if (previousSample != null) {
                metrics = sample.since(previousSample);
            }
            previousSample = sample;
        } catch (IllegalArgumentException e) {
            // Keep the previous values; the raw output stays in the command history.
        }
    }

    /**
     * Disks only: what is listening is the network screen's business, and asking for it here would send a command
     * every cycle that nothing reads.
     */
    private void refreshInventory() {
        CommandEntry disksEntry = run(DisksParser.COMMAND);
        if (disksEntry.ran()) {
            disks = DisksParser.parse(disksEntry.output());
        }
    }

    /**
     * Runs a command and records it. Stops the monitor when the connection is gone.
     */
    private CommandEntry run(String command) {
        CommandEntry entry = connection.exec(command);
        commands.add(entry, CommandService.Origin.MONITOR);
        if (!entry.ran() && !connection.isConnected()) {
            fail("Connection lost: " + entry.error());
        }
        return entry;
    }

    private Runnable guarded(Runnable refresh) {
        return () -> {
            if (stopped.get() || !awake.getAsBoolean()) {
                return;
            }
            try {
                refresh.run();
                if (!stopped.get()) {
                    publish();
                    if (degraded) {
                        degraded = false;
                        onProblem.accept("");
                    }
                }
            } catch (RuntimeException e) {
                // Caught so the task keeps running, but never silent: logged and shown as a degraded state.
                LOG.log(System.Logger.Level.WARNING, "Monitoring step failed", e);
                degraded = true;
                onProblem.accept(describe(e));
            }
        };
    }

    /**
     * Says whether anybody is looking at this screen. While it answers false the polls do nothing: the connection
     * runs one command at a time, and a screen behind another one would take it from the screen in front.
     *
     * <p>Read on the monitor's thread, so it must be safe to call from there.
     */
    public void setAwake(BooleanSupplier awake) {
        this.awake = Objects.requireNonNull(awake, "awake");
    }

    /**
     * Reads the metrics once now, off the usual rhythm. For a screen coming back to the front with figures that
     * stopped moving while it was behind.
     */
    public void readNow() {
        if (stopped.get() || !started.get()) {
            return;
        }
        try {
            scheduler.execute(guarded(this::refreshMetrics));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Closed between the check above and here. There is nothing to read for a screen that is going away.
        }
    }

    /**
     * Receives a short message when a polling step fails while monitoring goes on, and an empty one once
     * a step succeeds again. Called on the monitor's thread.
     */
    public void setOnProblem(Consumer<String> onProblem) {
        this.onProblem = Objects.requireNonNull(onProblem, "onProblem");
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private void publish() {
        current = new Server(identity, metrics, ports, disks);
        onUpdate.accept(current);
    }

    private void fail(String message) {
        if (stopped.compareAndSet(false, true)) {
            scheduler.shutdown();
            onFailure.accept(message);
        }
    }
}
