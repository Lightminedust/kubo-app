package com.kubo.model.docker;

import java.util.List;
import java.util.Objects;

/**
 * One container as seen in the list: what it is, its state and health, the Compose stack it belongs to, its
 * published ports, and the live figures when running.
 *
 * @param health  the healthcheck result, or {@link Health#NONE} when it has no healthcheck
 * @param project the Compose project (stack) it belongs to, empty when it is not part of one
 * @param service its service name inside that project, empty when none
 * @param stats   CPU and memory now, or null when the container is not running or figures have not arrived yet
 */
public record DockerContainer(String id, String name, String image, ContainerState state, Health health,
                              String status, String project, String service, List<PortMapping> ports,
                              ContainerStats stats) {

    public DockerContainer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(state, "state");
        health = health == null ? Health.NONE : health;
        image = image == null ? "" : image;
        status = status == null ? "" : status;
        project = project == null ? "" : project;
        service = service == null ? "" : service;
        ports = List.copyOf(ports == null ? List.of() : ports);
    }

    /**
     * The first twelve characters of the id, as Docker shows them.
     */
    public String shortId() {
        return id.length() > 12 ? id.substring(0, 12) : id;
    }

    public DockerContainer withStats(ContainerStats stats) {
        return new DockerContainer(id, name, image, state, health, status, project, service, ports, stats);
    }

    public boolean inStack() {
        return !project.isEmpty();
    }

    /**
     * Ports actually reachable from the host.
     */
    public List<PortMapping> publishedPorts() {
        return ports.stream().filter(PortMapping::isPublished).toList();
    }
}
