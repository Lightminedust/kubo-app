package com.kubo.model.network;

import java.util.Objects;

/**
 * One entry of the routing table.
 *
 * <p>The one that matters most is the default route: it names the interface every packet leaves by, and the
 * gateway it is handed to. A server with no default route answers on its own network and nowhere else.
 *
 * @param destination "default", or a network in CIDR form
 * @param gateway     empty for a route that is directly attached to the link
 * @param source      the address packets leave with, empty when the kernel did not print one
 * @param metric      {@link #NO_METRIC} when none was printed. Lower wins when two routes match.
 */
public record Route(String destination, String gateway, String device, String source, int metric) {

    public static final int NO_METRIC = -1;

    public Route {
        Objects.requireNonNull(destination, "destination");
        if (destination.isBlank()) {
            throw new IllegalArgumentException("a route has a destination");
        }
        gateway = gateway == null ? "" : gateway;
        device = device == null ? "" : device;
        source = source == null ? "" : source;
    }

    public boolean isDefault() {
        return destination.equals("default") || destination.equals("0.0.0.0/0") || destination.equals("::/0");
    }

    public boolean ipv6() {
        return destination.contains(":") || gateway.contains(":");
    }
}
