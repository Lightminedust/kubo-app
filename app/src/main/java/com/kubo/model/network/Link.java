package com.kubo.model.network;

import com.kubo.model.server.ListeningPort;

import java.util.List;
import java.util.Objects;

/**
 * One network interface of the server, with the addresses configured on it.
 *
 * <p>This is what the network screen was missing entirely: it listed the ports a server answers on without ever
 * saying which addresses those ports live at.
 *
 * @param state what the kernel calls the link: UP, DOWN, UNKNOWN. Loopback is always UNKNOWN, which is normal.
 * @param mac   empty when it could not be read, which is the case on loopback and on tunnels
 * @param mtu   {@link #UNKNOWN_MTU} when it could not be read
 */
public record Link(String name, String state, String mac, int mtu, List<Address> addresses) {

    public static final int UNKNOWN_MTU = -1;

    public Link {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a link has a name");
        }
        state = state == null || state.isBlank() ? "UNKNOWN" : state.toUpperCase(java.util.Locale.ROOT);
        mac = mac == null ? "" : mac;
        addresses = List.copyOf(addresses == null ? List.of() : addresses);
    }

    /**
     * One address on the link.
     *
     * @param prefix the mask length, so 24 for a /24
     */
    public record Address(String address, int prefix, boolean ipv6) {

        public Address {
            Objects.requireNonNull(address, "address");
            if (address.isBlank()) {
                throw new IllegalArgumentException("an address has a value");
            }
            if (prefix < 0 || prefix > 128) {
                throw new IllegalArgumentException("prefix must be between 0 and 128");
            }
        }

        public String cidr() {
            return address + "/" + prefix;
        }

        /**
         * How far this address can be reached from, judged exactly as a listening port's bind address is.
         */
        public ListeningPort.Exposure exposure() {
            return ListeningPort.exposureOf(address);
        }
    }

    public boolean up() {
        return state.equals("UP") || state.equals("UNKNOWN");
    }

    public boolean loopback() {
        return name.equals("lo");
    }

    /**
     * The addresses on this link that the internet could reach, if nothing filters them.
     */
    public List<Address> reachable() {
        return addresses.stream()
                .filter(address -> address.exposure() == ListeningPort.Exposure.PUBLIC)
                .toList();
    }
}
