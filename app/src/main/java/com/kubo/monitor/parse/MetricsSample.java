package com.kubo.monitor.parse;

import com.kubo.model.server.ServerMetrics;
import com.kubo.model.server.ServerMetrics.LoadAverage;
import com.kubo.model.server.ServerMetrics.MemoryMetrics;
import com.kubo.model.server.ServerMetrics.NetworkMetrics;

import java.time.Instant;
import java.util.Objects;

/**
 * One raw reading of the server's counters. CPU usage and network rates only exist
 * between two readings: see {@link #since(MetricsSample)}.
 *
 * @param uptimeSeconds server clock, used to measure the time between two readings without network jitter
 * @param receivedBytes total received on physical interfaces since boot
 */
public record MetricsSample(
        Instant timestamp,
        double uptimeSeconds,
        CpuTimes cpu,
        long receivedBytes,
        long transmittedBytes,
        LoadAverage load,
        int processCount,
        MemoryMetrics memory
) {

    /**
     * Cumulative CPU time from the first line of /proc/stat, in clock ticks.
     *
     * @param busy  everything except idle and iowait (steal included)
     * @param steal time the hypervisor gave to other virtual machines
     */
    public record CpuTimes(long busy, long steal, long total) {
    }

    public MetricsSample {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(cpu, "cpu");
        Objects.requireNonNull(load, "load");
        Objects.requireNonNull(memory, "memory");
    }

    /**
     * Metrics over the interval since {@code previous}. Counters that went backwards (after a reboot)
     * count as no activity.
     */
    public ServerMetrics since(MetricsSample previous) {
        Objects.requireNonNull(previous, "previous");

        long totalTicks = cpu.total() - previous.cpu().total();
        long busyTicks = Math.max(0, cpu.busy() - previous.cpu().busy());
        long stealTicks = Math.max(0, cpu.steal() - previous.cpu().steal());
        double cpuPercent = 0;
        double stealPercent = 0;
        if (totalTicks > 0) {
            // CPU% is the work done for this server; stolen time is reported apart.
            cpuPercent = percent(busyTicks - stealTicks, totalTicks);
            stealPercent = percent(stealTicks, totalTicks);
        }

        double elapsed = uptimeSeconds - previous.uptimeSeconds();
        long receivedRate = 0;
        long transmittedRate = 0;
        if (elapsed > 0) {
            receivedRate = Math.round(Math.max(0, receivedBytes - previous.receivedBytes()) / elapsed);
            transmittedRate = Math.round(Math.max(0, transmittedBytes - previous.transmittedBytes()) / elapsed);
        }

        return new ServerMetrics(timestamp, cpuPercent, stealPercent, load, memory,
                new NetworkMetrics(receivedRate, transmittedRate), (long) uptimeSeconds, processCount);
    }

    private static double percent(long part, long total) {
        return Math.min(100, Math.max(0, part * 100.0 / total));
    }
}
