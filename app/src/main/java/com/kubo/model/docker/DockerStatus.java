package com.kubo.model.docker;

/**
 * Whether Docker can be used on this server.
 *
 * @param version    the Docker Engine version, empty unless usable
 * @param needsRoot  true when Docker is there but the user must act as root to reach it
 */
public record DockerStatus(Availability availability, String version, boolean needsRoot) {

    public enum Availability {
        /**
         * Docker is installed and the user can talk to it.
         */
        USABLE,
        /**
         * Docker is installed but the user cannot reach the daemon (not root, not in the docker group).
         */
        NO_PERMISSION,
        /**
         * Docker is not installed.
         */
        ABSENT
    }

    public DockerStatus {
        version = version == null ? "" : version;
    }

    public boolean usable() {
        return availability == Availability.USABLE;
    }
}
