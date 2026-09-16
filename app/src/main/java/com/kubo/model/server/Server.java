package com.kubo.model.server;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregates all model components of a remote Linux server.
 *
 * @param latestMetrics null until two readings have been taken, since CPU usage is measured between them;
 *                      prefer {@link #metrics()}
 */
public record Server(
    ServerIdentity identity,
    ServerMetrics latestMetrics,
    List<ListeningPort> openPorts,
    List<DiskPartition> disks
) {
    public Server {
        Objects.requireNonNull(identity, "identity cannot be null");
        openPorts = openPorts == null ? List.of() : List.copyOf(openPorts);
        disks = disks == null ? List.of() : List.copyOf(disks);
    }

    public Optional<ServerMetrics> metrics() {
        return Optional.ofNullable(latestMetrics);
    }
}
