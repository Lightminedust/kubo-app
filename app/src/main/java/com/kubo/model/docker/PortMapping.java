package com.kubo.model.docker;

import com.kubo.model.server.ListeningPort;
import com.kubo.model.server.ListeningPort.Exposure;
import com.kubo.model.server.ListeningPort.Protocol;

import java.util.Objects;

/**
 * A container port and, when published, the host address and port it is reachable on.
 *
 * @param hostIp        the host address the port is bound to, empty when the port is only exposed inside Docker
 * @param hostPort      the host port, 0 when not published
 * @param containerPort the port inside the container
 * @param protocol      "tcp" or "udp"
 */
public record PortMapping(String hostIp, int hostPort, int containerPort, String protocol) {

    public PortMapping {
        hostIp = hostIp == null ? "" : hostIp;
        protocol = protocol == null || protocol.isBlank() ? "tcp" : protocol;
    }

    /**
     * True when the port is reachable from outside the container (it has a host mapping).
     */
    public boolean isPublished() {
        return hostPort > 0;
    }

    /**
     * How far a published port reaches, reusing the host's own classification of a bind address.
     * An unpublished port stays inside Docker.
     */
    public Exposure exposure() {
        if (!isPublished()) {
            return Exposure.LOCAL;
        }
        Protocol proto = protocol.equalsIgnoreCase("udp") ? Protocol.UDP : Protocol.TCP;
        return new ListeningPort(proto, hostIp.isEmpty() ? "0.0.0.0" : hostIp, hostPort, "").exposure();
    }

    @Override
    public String toString() {
        String inside = containerPort + "/" + protocol;
        return isPublished() ? (hostIp.isEmpty() ? "" : hostIp + ":") + hostPort + " → " + inside : inside;
    }

    public static PortMapping exposedOnly(int containerPort, String protocol) {
        return new PortMapping("", 0, containerPort, protocol);
    }

    public static PortMapping published(String hostIp, int hostPort, int containerPort, String protocol) {
        return new PortMapping(Objects.requireNonNull(hostIp, "hostIp"), hostPort, containerPort, protocol);
    }
}
