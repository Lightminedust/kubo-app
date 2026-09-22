package com.kubo.model.network;

import java.time.Duration;
import java.util.Objects;

/**
 * The bytes an interface has carried since boot, as the kernel counts them.
 *
 * <p>These numbers alone say nothing useful: a server that has been up for a year has carried terabytes either
 * way. What a reader wants is the rate, and a rate needs two readings. {@link #since(Counter, Duration)} is that
 * subtraction, kept here so the screen never invents one of its own.
 */
public record Counter(String device, long rxBytes, long txBytes, long rxPackets, long txPackets) {

    public Counter {
        Objects.requireNonNull(device, "device");
        if (device.isBlank()) {
            throw new IllegalArgumentException("a counter belongs to a device");
        }
    }

    /**
     * Bytes per second in each direction between an earlier reading and this one.
     *
     * <p>A counter that went backwards means the interface was reset or the machine rebooted between the two
     * readings. That gives a meaningless negative rate, so it is reported as zero until the next reading gives
     * a usable pair again.
     *
     * @return receive rate and transmit rate, in bytes per second
     */
    public Rate since(Counter earlier, Duration elapsed) {
        if (earlier == null || elapsed == null || elapsed.isZero() || elapsed.isNegative()) {
            return Rate.NONE;
        }
        double seconds = elapsed.toNanos() / 1_000_000_000.0;
        long rx = rxBytes - earlier.rxBytes();
        long tx = txBytes - earlier.txBytes();
        if (rx < 0 || tx < 0) {
            return Rate.NONE;
        }
        return new Rate(rx / seconds, tx / seconds);
    }

    /** Bytes per second, received and sent. */
    public record Rate(double received, double sent) {

        public static final Rate NONE = new Rate(0, 0);

        public double total() {
            return received + sent;
        }
    }
}
