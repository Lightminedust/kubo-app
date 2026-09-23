package com.kubo.model.network;

import com.kubo.model.docker.DockerNetwork;
import com.kubo.model.server.ListeningPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the numbers on this screen into places.
 *
 * <p>172.19.0.2 asks the reader to know that Docker hands out that range, which interface owns it, and that the
 * thing on the other end is a container. Every one of those is knowable from what has already been read, so the
 * screen answers it instead of the reader.
 *
 * <p>Nothing is guessed. An address that belongs to no interface and no bridge is shown as it is, because
 * "somewhere on the internet" is all that is actually known about it.
 */
public final class Naming {

    /**
     * What kind of place the other end of a connection is.
     *
     * <p>Separate from the words {@link #place} produces, because a screen wants to sort and filter on this and
     * a reader wants to read the words. Deriving one from the other would mean matching on display text.
     */
    public enum Place {
        /** The server talking to itself. */
        LOOPBACK,
        /** One of this server's own addresses, reached the long way round. */
        THIS_SERVER,
        /** A container, on a Docker bridge this server owns. */
        DOCKER,
        /** A private address that is neither this server nor one of its containers. */
        PRIVATE,
        /** A public address: somewhere on the internet. */
        INTERNET
    }

    /** A Docker bridge and the network it carries. */
    private record Bridge(String link, String label, long network, long mask, boolean ipv6) {
    }

    private final List<String> ownAddresses;
    private final List<Bridge> bridges;
    private final String mainLink;

    private Naming(List<String> ownAddresses, List<Bridge> bridges, String mainLink) {
        this.ownAddresses = ownAddresses;
        this.bridges = bridges;
        this.mainLink = mainLink;
    }

    public static Naming none() {
        return new Naming(List.of(), List.of(), "");
    }

    /**
     * Builds the naming from what the machine reports.
     *
     * <p>A Docker bridge is called {@code br-} followed by the first twelve characters of the network's id, so
     * the interface and the network Docker knows by name can be matched with no extra command. {@code docker0}
     * is the default bridge and has no id in its name.
     *
     * @param networks the Docker networks, empty when Docker is absent or was not read
     */
    public static Naming of(Identity identity, List<DockerNetwork> networks) {
        List<String> own = new ArrayList<>();
        List<Bridge> bridges = new ArrayList<>();
        for (Link link : identity.links()) {
            for (Link.Address address : link.addresses()) {
                own.add(address.address());
                if (!isBridge(link.name()) || address.ipv6()) {
                    continue;
                }
                long value = toLong(address.address());
                if (value < 0) {
                    continue;
                }
                long mask = address.prefix() == 0 ? 0 : (-1L << (32 - address.prefix())) & 0xFFFFFFFFL;
                bridges.add(new Bridge(link.name(), bridgeLabel(link.name(), networks), value & mask, mask, false));
            }
        }
        String main = identity.defaultRoute().map(Route::device).orElse("");
        return new Naming(List.copyOf(own), List.copyOf(bridges), main);
    }

    private static boolean isBridge(String link) {
        return link.equals("docker0") || link.startsWith("br-");
    }

    private static String bridgeLabel(String link, List<DockerNetwork> networks) {
        if (link.equals("docker0")) {
            return "Docker";
        }
        String id = link.substring("br-".length());
        for (DockerNetwork network : networks) {
            if (network.id().startsWith(id)) {
                return "Docker " + network.name();
            }
        }
        return "Docker network";
    }

    /**
     * What kind of place an address is. {@link Place#INTERNET} for anything public, and for an address this
     * server cannot place at all: from here, a public address is the internet.
     */
    public Place kindOf(String address) {
        if (address == null || address.isBlank()) {
            return Place.INTERNET;
        }
        if (ListeningPort.exposureOf(address) == ListeningPort.Exposure.LOCAL) {
            return Place.LOOPBACK;
        }
        if (ownAddresses.contains(address)) {
            return Place.THIS_SERVER;
        }
        long value = toLong(address);
        if (value >= 0) {
            for (Bridge bridge : bridges) {
                if ((value & bridge.mask()) == bridge.network()) {
                    return Place.DOCKER;
                }
            }
        }
        if (ListeningPort.exposureOf(address) == ListeningPort.Exposure.INTERNAL) {
            return Place.PRIVATE;
        }
        return Place.INTERNET;
    }

    /**
     * Where an address is, in words. Empty when nothing is known beyond the address itself.
     */
    public String place(String address) {
        if (address == null || address.isBlank()) {
            return "";
        }
        if (ListeningPort.exposureOf(address) == ListeningPort.Exposure.LOCAL) {
            return "this server";
        }
        if (ownAddresses.contains(address)) {
            return "this server";
        }
        long value = toLong(address);
        if (value >= 0) {
            for (Bridge bridge : bridges) {
                if ((value & bridge.mask()) == bridge.network()) {
                    return bridge.label();
                }
            }
        }
        if (ListeningPort.exposureOf(address) == ListeningPort.Exposure.INTERNAL) {
            return "private network";
        }
        return "";
    }

    /** "172.19.0.2 · Docker app_default", or the address alone when nothing else is known. */
    public String describe(String address) {
        String place = place(address);
        return place.isEmpty() ? address : address + "  ·  " + place;
    }

    /**
     * What an interface is for, in words: the way out, a Docker bridge, a container link, a VPN, loopback.
     */
    public String role(Link link) {
        String name = link.name();
        if (link.loopback()) {
            return "the server talking to itself";
        }
        if (name.equals(mainLink)) {
            return "the way in and out";
        }
        if (isBridge(name)) {
            return bridgeLabel(name, List.of()).equals("Docker") ? "Docker default bridge" : "a Docker network";
        }
        if (name.startsWith("veth")) {
            return "one container";
        }
        if (name.startsWith("wg") || name.startsWith("tailscale") || name.startsWith("tun")
                || name.startsWith("tap")) {
            return "a VPN";
        }
        return "";
    }

    /** True for the interfaces Docker makes, which multiply with the containers. */
    public static boolean virtual(Link link) {
        String name = link.name();
        return isBridge(name) || name.startsWith("veth");
    }

    /** The label a Docker bridge carries, by interface name. */
    public Map<String, String> bridgeLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (Bridge bridge : bridges) {
            labels.put(bridge.link(), bridge.label());
        }
        return labels;
    }

    /**
     * An IPv4 literal as a number, or -1 for anything else. IPv6 is left alone: Docker hands out v4 on its
     * bridges, and a half-right answer about a v6 address would be worse than none.
     */
    private static long toLong(String address) {
        String[] parts = address.split("\\.");
        if (parts.length != 4) {
            return -1;
        }
        long value = 0;
        for (String part : parts) {
            try {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255) {
                    return -1;
                }
                value = (value << 8) | octet;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return value;
    }
}
