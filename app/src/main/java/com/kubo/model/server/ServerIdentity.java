package com.kubo.model.server;

import java.util.Objects;

/**
 * Static system identity gathered once upon SSH connection.
 */
public record ServerIdentity(
    String hostname,
    String osRelease,
    String kernelVersion,
    String cpuModel,
    int cores,
    long totalMemoryBytes,
    UserIdentity user
) {
    public ServerIdentity {
        Objects.requireNonNull(hostname, "hostname cannot be null");
        Objects.requireNonNull(osRelease, "osRelease cannot be null");
        Objects.requireNonNull(kernelVersion, "kernelVersion cannot be null");
        Objects.requireNonNull(cpuModel, "cpuModel cannot be null");
        Objects.requireNonNull(user, "user cannot be null");

        if (cores <= 0) {
            throw new IllegalArgumentException("cores must be greater than 0");
        }
        if (totalMemoryBytes <= 0) {
            throw new IllegalArgumentException("totalMemoryBytes must be greater than 0");
        }
    }

    public record UserIdentity(
        String username,
        boolean isRoot,
        boolean inSudoGroup,
        boolean inDockerGroup
    ) {
        public UserIdentity {
            Objects.requireNonNull(username, "username cannot be null");
        }
    }
}