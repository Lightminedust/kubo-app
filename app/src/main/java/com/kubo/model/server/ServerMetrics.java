package com.kubo.model.server;

import java.time.Instant;
import java.util.Objects;

/**
 * Real-time system performance metrics snapshot.
 *
 * @param cpuPercent   CPU busy time between the two last readings, 0 to 100
 * @param stealPercent CPU time the hypervisor gave to other virtual machines, 0 to 100
 */
public record ServerMetrics(
    Instant timestamp,
    double cpuPercent,
    double stealPercent,
    LoadAverage loadAverage,
    MemoryMetrics memory,
    NetworkMetrics network,
    long uptimeSeconds,
    int processCount
) {
    public ServerMetrics {
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        Objects.requireNonNull(loadAverage, "loadAverage cannot be null");
        Objects.requireNonNull(memory, "memory cannot be null");
        Objects.requireNonNull(network, "network cannot be null");

        requirePercent(cpuPercent, "cpuPercent");
        requirePercent(stealPercent, "stealPercent");
        if (uptimeSeconds < 0) {
            throw new IllegalArgumentException("uptimeSeconds cannot be negative");
        }
        if (processCount < 0) {
            throw new IllegalArgumentException("processCount cannot be negative");
        }
    }

    public record LoadAverage(double oneMin, double fiveMin, double fifteenMin) {
        public LoadAverage {
            requireNonNegative(oneMin, "oneMin");
            requireNonNegative(fiveMin, "fiveMin");
            requireNonNegative(fifteenMin, "fifteenMin");
        }
    }

    /**
     * Memory as reported by /proc/meminfo. "Used" is derived, so it can never disagree with the totals.
     */
    public record MemoryMetrics(
        long totalBytes,
        long availableBytes,
        long totalSwapBytes,
        long freeSwapBytes
    ) {
        public MemoryMetrics {
            if (totalBytes <= 0) {
                throw new IllegalArgumentException("totalBytes must be greater than 0");
            }
            if (availableBytes < 0 || availableBytes > totalBytes) {
                throw new IllegalArgumentException("availableBytes must be between 0 and totalBytes");
            }
            if (totalSwapBytes < 0) {
                throw new IllegalArgumentException("totalSwapBytes cannot be negative");
            }
            if (freeSwapBytes < 0 || freeSwapBytes > totalSwapBytes) {
                throw new IllegalArgumentException("freeSwapBytes must be between 0 and totalSwapBytes");
            }
        }

        public long usedBytes() {
            return totalBytes - availableBytes;
        }

        public double usagePercentage() {
            return (double) usedBytes() / totalBytes * 100.0;
        }

        public long usedSwapBytes() {
            return totalSwapBytes - freeSwapBytes;
        }
    }

    public record NetworkMetrics(long rxBytesPerSec, long txBytesPerSec) {
        public NetworkMetrics {
            if (rxBytesPerSec < 0 || txBytesPerSec < 0) {
                throw new IllegalArgumentException("network rates cannot be negative");
            }
        }
    }

    private static void requirePercent(double value, String name) {
        // Written this way so NaN is rejected too.
        if (!(value >= 0 && value <= 100)) {
            throw new IllegalArgumentException(name + " must be between 0 and 100");
        }
    }

    private static void requireNonNegative(double value, String name) {
        if (!(value >= 0)) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
    }
}
