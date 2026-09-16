package com.kubo.model.network;

import java.util.Locale;
import java.util.Objects;

/**
 * One application managed by PM2.
 *
 * @param status  PM2's own word: "online", "stopped", "errored", "launching"…
 * @param pid     the running process, or 0 when the app is stopped
 * @param uptimeMillis when the process started, as a wall-clock instant in milliseconds
 */
public record PmApp(String name, int pmId, int pid, String status, double cpuPercent, long memoryBytes,
                    long uptimeMillis, int restarts) {

    public PmApp {
        Objects.requireNonNull(name, "name");
        status = status == null ? "" : status;
    }

    public boolean isOnline() {
        return status.equalsIgnoreCase("online");
    }

    public boolean hasFailed() {
        return status.toLowerCase(Locale.ROOT).startsWith("error");
    }
}
