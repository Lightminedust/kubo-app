package com.kubo.model.docker;

import java.util.List;
import java.util.Objects;

/**
 * A Docker network and the containers attached to it.
 *
 * @param driver     "bridge", "host", "overlay"…
 * @param containers names of the containers on this network
 */
public record DockerNetwork(String id, String name, String driver, String scope, List<String> containers) {

    public DockerNetwork {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        driver = driver == null ? "" : driver;
        scope = scope == null ? "" : scope;
        containers = List.copyOf(containers == null ? List.of() : containers);
    }

    public DockerNetwork withContainers(List<String> containers) {
        return new DockerNetwork(id, name, driver, scope, containers);
    }

    /**
     * The default networks Docker always has, which the user rarely touches.
     */
    public boolean isBuiltIn() {
        return name.equals("bridge") || name.equals("host") || name.equals("none");
    }
}
