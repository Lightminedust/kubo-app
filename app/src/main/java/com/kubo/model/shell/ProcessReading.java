package com.kubo.model.shell;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One look at what the machine is doing: every process, and the numbers that say how hard it is working.
 *
 * <p>Asked for in a single command. Three round trips to say what one can say is three times the wait on a
 * link with any latency, and the three answers would be from three different moments, which is how a table
 * ends up disagreeing with the header above it.
 *
 * <p>The totals come from the kernel rather than from adding the columns up. The per-process figure ps gives
 * is an average over the life of the process, so a server up for a month reports almost nothing for a program
 * that has been pinning a core for the last minute, and adding those averages together produces a number that
 * is not a measurement of anything.
 */
public record ProcessReading(List<SystemProcess> processes, double load, int running, int total,
                             long memoryTotal, long memoryUsed, int cores) {

    /** Where the processes stop and the machine's own figures start. */
    private static final String LOAD_MARK = "@@load";
    private static final String MEMORY_MARK = "@@mem";
    private static final String CORES_MARK = "@@cpus";

    /**
     * Everything, in one command, with nothing in it that anybody typed.
     *
     * <p>LC_ALL=C so the numbers come back with dots wherever the server thinks it is.
     */
    public static final String COMMAND = "LC_ALL=C ps -eo pid=,ppid=,user=,pcpu=,pmem=,rss=,stat=,etimes=,args=; "
            + "echo " + LOAD_MARK + "; cat /proc/loadavg; "
            + "echo " + MEMORY_MARK + "; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; "
            + "echo " + CORES_MARK + "; nproc";

    public ProcessReading {
        processes = List.copyOf(Objects.requireNonNull(processes, "processes"));
    }

    /** Nothing read yet. */
    public static ProcessReading none() {
        return new ProcessReading(List.of(), 0, 0, 0, 0, 0, 1);
    }

    /**
     * How loaded the machine is, 0 to 1.
     *
     * <p>Against the number of cores, which is the only way the figure means anything. A load of 4 is a
     * machine in trouble on one core and a machine half asleep on eight, and colouring it red either way
     * would be a warning nobody could act on.
     */
    public double loadShare() {
        return Math.min(1, load / Math.max(1, cores));
    }

    /** How much of the memory is in use, 0 to 1. */
    public double memoryShare() {
        return memoryTotal <= 0 ? 0 : Math.min(1, (double) memoryUsed / memoryTotal);
    }

    /**
     * Cuts the answer into its parts and reads each one.
     *
     * <p>A marker counts only when it is the whole line. Searching for the text of one anywhere found it in
     * the wrong place every single time, because ps lists the shell that is running this very command, and
     * that shell's arguments are the command, markers and all. So every figure was read out of Kubo's own
     * command line: the load came out as 0, the memory as nothing, and the core count as one.
     */
    public static ProcessReading parse(String output) {
        if (output == null || output.isBlank()) {
            return none();
        }
        List<String> listing = new ArrayList<>();
        List<String> loadLines = new ArrayList<>();
        List<String> memoryLines = new ArrayList<>();
        List<String> coreLines = new ArrayList<>();
        List<String> into = listing;
        for (String line : output.lines().toList()) {
            String mark = line.strip();
            if (mark.equals(LOAD_MARK)) {
                into = loadLines;
            } else if (mark.equals(MEMORY_MARK)) {
                into = memoryLines;
            } else if (mark.equals(CORES_MARK)) {
                into = coreLines;
            } else {
                into.add(line);
            }
        }

        List<SystemProcess> processes = SystemProcess.parse(String.join("\n", listing));
        double load = 0;
        int running = 0;
        int total = processes.size();
        for (String line : loadLines) {
            String[] fields = line.strip().split("\\s+");
            if (fields.length < 4) {
                continue;
            }
            load = number(fields[0]);
            // "2/382": how many are on a processor right now, out of how many there are.
            String[] counts = fields[3].split("/");
            if (counts.length == 2) {
                running = (int) number(counts[0]);
                total = (int) number(counts[1]);
            }
        }

        long memoryTotal = 0;
        long available = 0;
        for (String line : memoryLines) {
            String text = line.strip();
            if (text.startsWith("MemTotal:")) {
                memoryTotal = kilobytes(text);
            } else if (text.startsWith("MemAvailable:")) {
                available = kilobytes(text);
            }
        }
        // Available, not free. Free counts the cache as taken, which is why a healthy Linux server looks to
        // be out of memory to anybody reading the wrong line.
        long used = memoryTotal > 0 ? Math.max(0, memoryTotal - available) : 0;

        int cores = 1;
        for (String line : coreLines) {
            int said = (int) number(line.strip());
            if (said > 0) {
                cores = said;
            }
        }
        return new ProcessReading(processes, load, running, total, memoryTotal, used, cores);
    }

    private static long kilobytes(String line) {
        String[] parts = line.split("\\s+");
        return parts.length >= 2 ? (long) number(parts[1]) * 1024 : 0;
    }

    private static double number(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
