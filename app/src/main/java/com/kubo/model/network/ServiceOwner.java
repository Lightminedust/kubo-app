package com.kubo.model.network;

import java.util.Objects;

/**
 * Who holds a listening socket, once the PID has been matched against PM2, Docker and the system.
 *
 * @param label  what to call it on screen, e.g. "gemmie-api"
 * @param detail a second line, e.g. the image of a container or the PM2 status
 * @param handle what an action needs to address it: the PM2 app name, the container id, or the empty string
 */
public record ServiceOwner(Kind kind, String label, String detail, String handle, int pid) {

    public enum Kind {
        /** A Node application PM2 keeps running. */
        PM2,
        /** A process inside a Docker container. */
        DOCKER,
        /** A plain system daemon: sshd, nginx, postgres… */
        SYSTEM,
        /** {@code ss} could not name the process, which needs root. */
        UNKNOWN
    }

    public ServiceOwner {
        Objects.requireNonNull(kind, "kind");
        label = label == null ? "" : label;
        detail = detail == null ? "" : detail;
        handle = handle == null ? "" : handle;
    }

    public static ServiceOwner unknown() {
        return new ServiceOwner(Kind.UNKNOWN, "unnamed process", "run Kubo as root to see who owns it", "",
                ListeningSocket.UNKNOWN_PID);
    }

    public static ServiceOwner system(String name, int pid) {
        return new ServiceOwner(Kind.SYSTEM, name.isBlank() ? "unnamed process" : name, "system process", "", pid);
    }

    /**
     * True when Kubo can restart or stop it through PM2 or Docker rather than only killing the process.
     */
    public boolean isManaged() {
        return kind == Kind.PM2 || kind == Kind.DOCKER;
    }
}
