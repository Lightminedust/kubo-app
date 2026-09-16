package com.kubo.monitor.parse;

import com.kubo.model.server.ServerMetrics.LoadAverage;
import com.kubo.model.server.ServerMetrics.MemoryMetrics;
import com.kubo.monitor.parse.MetricsSample.CpuTimes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Reads the counters behind CPU, memory, network, load and uptime, all from /proc (no root needed).
 */
public final class MetricsParser {

    public static final String COMMAND = Sections.script(
            "cpu", "head -n1 /proc/stat",
            "memory", "cat /proc/meminfo",
            "network", "cat /proc/net/dev",
            "load", "cat /proc/loadavg",
            "uptime", "cat /proc/uptime");

    // Virtual interfaces repeat traffic already counted on the physical one.
    private static final List<String> VIRTUAL_INTERFACE_PREFIXES = List.of("lo", "docker", "br-", "veth", "virbr");

    private MetricsParser() {
    }

    /**
     * @throws IllegalArgumentException when a section is missing or malformed
     */
    public static MetricsSample parse(String output, Instant timestamp) {
        Map<String, List<String>> sections = Sections.split(output);
        try {
            long[] network = network(Sections.lines(sections, "network"));
            String[] load = Sections.firstLine(sections, "load").split("\\s+");
            return new MetricsSample(
                    timestamp,
                    Double.parseDouble(Sections.firstLine(sections, "uptime").split("\\s+")[0]),
                    cpu(Sections.firstLine(sections, "cpu")),
                    network[0],
                    network[1],
                    new LoadAverage(Double.parseDouble(load[0]), Double.parseDouble(load[1]), Double.parseDouble(load[2])),
                    processCount(load),
                    memory(Sections.lines(sections, "memory")));
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Malformed metrics: " + e.getMessage(), e);
        }
    }

    /**
     * "cpu  user nice system idle iowait irq softirq steal guest guest_nice"; guest time is already inside user.
     */
    static CpuTimes cpu(String line) {
        String[] fields = line.strip().split("\\s+");
        if (!fields[0].equals("cpu") || fields.length < 5) {
            throw new IllegalArgumentException("Unexpected /proc/stat line: " + line);
        }
        long total = 0;
        long[] values = new long[8];
        for (int i = 0; i < values.length && i + 1 < fields.length; i++) {
            values[i] = Long.parseLong(fields[i + 1]);
            total += values[i];
        }
        long idle = values[3] + values[4];
        return new CpuTimes(total - idle, values[7], total);
    }

    static MemoryMetrics memory(List<String> lines) {
        Map<String, String> values = Sections.keyValues(lines);
        long total = kilobytes(values, "MemTotal");
        long available = values.containsKey("MemAvailable")
                ? kilobytes(values, "MemAvailable")
                // Kernels older than 3.14 have no MemAvailable.
                : kilobytes(values, "MemFree") + kilobytes(values, "Buffers") + kilobytes(values, "Cached");
        long swapTotal = values.containsKey("SwapTotal") ? kilobytes(values, "SwapTotal") : 0;
        long swapFree = values.containsKey("SwapFree") ? kilobytes(values, "SwapFree") : 0;
        return new MemoryMetrics(total, Math.min(available, total), swapTotal, Math.min(swapFree, swapTotal));
    }

    /**
     * Total received and transmitted bytes on physical interfaces.
     */
    static long[] network(List<String> lines) {
        long received = 0;
        long transmitted = 0;
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = line.substring(0, colon).strip();
            if (name.contains("|") || VIRTUAL_INTERFACE_PREFIXES.stream().anyMatch(name::startsWith)) {
                continue;
            }
            String[] fields = line.substring(colon + 1).strip().split("\\s+");
            if (fields.length < 9) {
                continue;
            }
            received += Long.parseLong(fields[0]);
            transmitted += Long.parseLong(fields[8]);
        }
        return new long[]{received, transmitted};
    }

    private static int processCount(String[] load) {
        // Fourth field is "running/total".
        String field = load[3];
        int slash = field.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("Unexpected /proc/loadavg process field: " + field);
        }
        return Integer.parseInt(field.substring(slash + 1));
    }

    private static long kilobytes(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return Long.parseLong(value) * 1024;
    }
}
