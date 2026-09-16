package com.kubo.model.network;

import com.kubo.model.server.ListeningPort.Exposure;

import java.util.List;
import java.util.Objects;

/**
 * One listening port with everything Kubo could learn about it: who holds it, which domains reach it through
 * Nginx, whether the firewall stands in front of it, and whether that leaves it dangerously open.
 *
 * @param servedBy the Nginx server blocks that forward to this port
 */
public record Endpoint(ListeningSocket socket, ServiceOwner owner, List<Vhost> servedBy, Reach reach) {

    /**
     * How far this port can actually be reached, judged from the address it binds and the firewall in front.
     */
    public enum Reach {
        /** Loopback only: nothing outside the server can open it. */
        ISOLATED,
        /** Bound to a private network, such as a Docker bridge. */
        PRIVATE,
        /** Bound to every interface, but the firewall stops the internet reaching it. */
        SHIELDED,
        /** Bound to every interface with nothing in the way: open to the internet. */
        OPEN
    }

    public Endpoint {
        Objects.requireNonNull(socket, "socket");
        owner = owner == null ? ServiceOwner.unknown() : owner;
        servedBy = List.copyOf(servedBy == null ? List.of() : servedBy);
        Objects.requireNonNull(reach, "reach");
    }

    /**
     * Works out how far a socket reaches, given the firewall in front of it.
     */
    public static Reach reachOf(ListeningSocket socket, Firewall firewall) {
        Exposure exposure = socket.exposure();
        if (exposure == Exposure.LOCAL) {
            return Reach.ISOLATED;
        }
        if (exposure == Exposure.INTERNAL) {
            return Reach.PRIVATE;
        }
        return firewall.blocks(socket.port()) ? Reach.SHIELDED : Reach.OPEN;
    }

    public int port() {
        return socket.port();
    }

    public boolean isOpenToTheWorld() {
        return reach == Reach.OPEN;
    }

    /**
     * True when a domain reaches this port through Nginx.
     */
    public boolean isBehindNginx() {
        return !servedBy.isEmpty();
    }
}
