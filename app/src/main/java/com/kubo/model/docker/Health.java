package com.kubo.model.docker;

import java.util.Locale;

/**
 * A container's health, when it declares a healthcheck.
 */
public enum Health {

    NONE,
    STARTING,
    HEALTHY,
    UNHEALTHY;

    /**
     * Reads the health out of a {@code docker ps} status such as "Up 3 days (healthy)".
     */
    public static Health fromStatus(String status) {
        if (status == null) {
            return NONE;
        }
        String lower = status.toLowerCase(Locale.ROOT);
        if (lower.contains("(healthy)")) {
            return HEALTHY;
        }
        if (lower.contains("(unhealthy)")) {
            return UNHEALTHY;
        }
        if (lower.contains("health: starting") || lower.contains("(starting)")) {
            return STARTING;
        }
        return NONE;
    }

    public boolean isKnown() {
        return this != NONE;
    }
}
