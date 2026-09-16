package com.kubo.monitor.parse;

import com.kubo.model.server.ServerMetrics;
import com.kubo.model.server.ServerMetrics.MemoryMetrics;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetricsParserTest {

    private static final Instant T0 = Instant.parse("2026-09-15T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-15T10:00:02Z");

    private static String reading(String cpuLine, long eth0Received, long eth0Transmitted, String uptime) {
        return """
                @@cpu
                %s
                @@memory
                MemTotal:        8000000 kB
                MemFree:         1000000 kB
                MemAvailable:    6000000 kB
                Buffers:          200000 kB
                Cached:          3000000 kB
                SwapTotal:       2000000 kB
                SwapFree:        1500000 kB
                @@network
                Inter-|   Receive                                                |  Transmit
                 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
                    lo:  123456     100    0    0    0     0          0         0   123456     100    0    0    0     0       0          0
                  eth0: %d   4000    0    0    0     0          0         0  %d   3000    0    0    0     0       0          0
                docker0:  900000    800    0    0    0     0          0         0   800000    700    0    0    0     0       0          0
                vethab12cd: 500000  400    0    0    0     0          0         0   400000    300    0    0    0     0       0          0
                @@load
                0.42 0.30 0.25 3/287 12345
                @@uptime
                %s
                """.formatted(cpuLine, eth0Received, eth0Transmitted, uptime);
    }

    @Test
    void parsesOneReading() {
        MetricsSample sample = MetricsParser.parse(
                reading("cpu  10000 500 3000 80000 1000 0 200 300 0 0", 5_000_000, 2_000_000, "1000.00 3000.00"), T0);

        assertEquals(T0, sample.timestamp());
        assertEquals(1000.0, sample.uptimeSeconds());
        assertEquals(new MetricsSample.CpuTimes(14_000, 300, 95_000), sample.cpu());
        assertEquals(5_000_000, sample.receivedBytes());
        assertEquals(2_000_000, sample.transmittedBytes());
        assertEquals(0.42, sample.load().oneMin());
        assertEquals(0.25, sample.load().fifteenMin());
        assertEquals(287, sample.processCount());
        assertEquals(new MemoryMetrics(8_000_000L * 1024, 6_000_000L * 1024, 2_000_000L * 1024, 1_500_000L * 1024),
                sample.memory());
    }

    @Test
    void metricsAreComputedBetweenTwoReadings() {
        MetricsSample first = MetricsParser.parse(
                reading("cpu  10000 500 3000 80000 1000 0 200 300 0 0", 5_000_000, 2_000_000, "1000.00 3000.00"), T0);
        MetricsSample second = MetricsParser.parse(
                reading("cpu  10600 500 3200 80900 1100 0 250 450 0 0", 5_004_000, 2_001_000, "1002.00 3007.50"), T1);

        ServerMetrics metrics = second.since(first);

        // 2000 ticks elapsed: 1000 busy, of which 150 stolen.
        assertEquals(42.5, metrics.cpuPercent(), 1e-9);
        assertEquals(7.5, metrics.stealPercent(), 1e-9);
        assertEquals(2_000, metrics.network().rxBytesPerSec());
        assertEquals(500, metrics.network().txBytesPerSec());
        assertEquals(1002, metrics.uptimeSeconds());
        assertEquals(T1, metrics.timestamp());
    }

    @Test
    void countersThatWentBackwardsCountAsNoActivity() {
        MetricsSample beforeReboot = MetricsParser.parse(
                reading("cpu  10000 500 3000 80000 1000 0 200 300 0 0", 5_000_000, 2_000_000, "1000.00 3000.00"), T0);
        MetricsSample afterReboot = MetricsParser.parse(
                reading("cpu  50 0 20 900 10 0 0 0 0 0", 1_000, 500, "10.00 30.00"), T1);

        ServerMetrics metrics = afterReboot.since(beforeReboot);

        assertEquals(0, metrics.cpuPercent());
        assertEquals(0, metrics.stealPercent());
        assertEquals(0, metrics.network().rxBytesPerSec());
        assertEquals(0, metrics.network().txBytesPerSec());
    }

    @Test
    void networkIgnoresLoopbackAndVirtualInterfaces() {
        long[] totals = MetricsParser.network(List.of(
                "    lo:  100 1 0 0 0 0 0 0 100 1 0 0 0 0 0 0",
                "  eth0:1000 1 0 0 0 0 0 0 2000 1 0 0 0 0 0 0",
                "  ens3:  30 1 0 0 0 0 0 0   40 1 0 0 0 0 0 0",
                "br-4f2a:  999 1 0 0 0 0 0 0  999 1 0 0 0 0 0 0"));

        assertArrayEquals(new long[]{1030, 2040}, totals);
    }

    @Test
    void memoryWithoutMemAvailableUsesFreeBuffersAndCache() {
        MemoryMetrics memory = MetricsParser.memory(List.of(
                "MemTotal: 1000 kB", "MemFree: 100 kB", "Buffers: 50 kB", "Cached: 250 kB"));

        assertEquals(400L * 1024, memory.availableBytes());
        assertEquals(0, memory.totalSwapBytes());
    }

    @Test
    void failsOnMalformedReading() {
        String valid = reading("cpu  10000 500 3000 80000 1000 0 200 300 0 0", 5_000_000, 2_000_000, "1000.00 3000.00");

        assertThrows(IllegalArgumentException.class, () -> MetricsParser.parse("", T0));
        assertThrows(IllegalArgumentException.class, () -> MetricsParser.parse(valid.replace("cpu  10000", "intr 10000"), T0));
        assertThrows(IllegalArgumentException.class, () -> MetricsParser.parse(valid.replace("3/287", "287"), T0));
        assertThrows(IllegalArgumentException.class, () -> MetricsParser.parse(valid.replace("MemTotal", "MemTotl"), T0));
    }
}
