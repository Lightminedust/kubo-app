package com.kubo.model.docker;

import java.util.Locale;

/**
 * The lifecycle state of a container, as Docker reports it.
 */
public enum ContainerState {

    RUNNING,
    PAUSED,
    RESTARTING,
    CREATED,
    EXITED,
    DEAD,
    REMOVING,
    OTHER;

    public static ContainerState parse(String value) {
        if (value == null) {
            return OTHER;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "running" -> RUNNING;
            case "paused" -> PAUSED;
            case "restarting" -> RESTARTING;
            case "created" -> CREATED;
            case "exited" -> EXITED;
            case "dead" -> DEAD;
            case "removing" -> REMOVING;
            default -> OTHER;
        };
    }

    public boolean isRunning() {
        return this == RUNNING || this == RESTARTING;
    }

    /**
     * True when the container can be stopped (it is doing something).
     */
    public boolean canStop() {
        return this == RUNNING || this == RESTARTING || this == PAUSED;
    }

    /**
     * True when the container can be started (it is not already running).
     */
    public boolean canStart() {
        return this == CREATED || this == EXITED || this == DEAD;
    }
}
