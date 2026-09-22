package com.kubo.model.server;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Open network port and binding information.
 *
 * @param bindAddress the local address as printed by {@code ss}, e.g. "0.0.0.0", "[::]", "127.0.0.53%lo"
 * @param processName the owning process, or an empty string when unknown (other users' processes need root)
 */
public record ListeningPort(
    Protocol protocol,
    String bindAddress,
    int port,
    String processName
) {
    public enum Protocol { TCP, UDP }

    /**
     * Who can reach the port, judged from the address it listens on (the firewall is not taken into account).
     */
    public enum Exposure {
        /** Loopback only: reachable from the server itself. */
        LOCAL,
        /** Private network, e.g. a Docker bridge or a provider's internal network. */
        INTERNAL,
        /** All interfaces or a public address: reachable from the internet unless a firewall blocks it. */
        PUBLIC
    }

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]*:[0-9a-fA-F:.]*");

    public ListeningPort {
        Objects.requireNonNull(protocol, "protocol cannot be null");
        Objects.requireNonNull(bindAddress, "bindAddress cannot be null");
        Objects.requireNonNull(processName, "processName cannot be null");

        if (bindAddress.isBlank()) {
            throw new IllegalArgumentException("bindAddress cannot be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
    }

    public Exposure exposure() {
        return exposureOf(bindAddress);
    }

    /**
     * How far a bare address can be reached from.
     *
     * <p>Public so that anything else reading an address off this server judges it the same way. Two places
     * applying their own idea of what counts as private is how a screen ends up calling the same address
     * internal in one panel and exposed in another.
     */
    public static Exposure exposureOf(String bindAddress) {
        String address = normalize(bindAddress);
        if (address.equals("*")) {
            return Exposure.PUBLIC;
        }
        InetAddress inet = parseLiteral(address);
        if (inet == null) {
            // Unparseable: assume the worst rather than hide an exposed port.
            return Exposure.PUBLIC;
        }

        if (inet.isAnyLocalAddress()) {
            return Exposure.PUBLIC;
        }
        if (inet.isLoopbackAddress()) {
            return Exposure.LOCAL;
        }
        if (inet.isSiteLocalAddress() || inet.isLinkLocalAddress() || isUniqueLocalIpv6(inet)) {
            return Exposure.INTERNAL;
        }
        return Exposure.PUBLIC;
    }

    /**
     * Parses an IP literal without ever doing a DNS lookup; returns null for anything else.
     */
    private static InetAddress parseLiteral(String address) {
        try {
            if (IPV4.matcher(address).matches()) {
                String[] parts = address.split("\\.");
                byte[] bytes = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int value = Integer.parseInt(parts[i]);
                    if (value > 255) {
                        return null;
                    }
                    bytes[i] = (byte) value;
                }
                return InetAddress.getByAddress(bytes);
            }
            if (IPV6.matcher(address).matches()) {
                // A string containing ':' is always parsed as an IPv6 literal, never resolved.
                return InetAddress.getByName(address);
            }
        } catch (UnknownHostException e) {
            return null;
        }
        return null;
    }

    /**
     * Removes the interface scope ("%lo") and IPv6 brackets that {@code ss} adds.
     */
    private static String normalize(String address) {
        String result = address.strip();
        int scope = result.indexOf('%');
        if (scope >= 0) {
            result = result.substring(0, scope);
        }
        if (result.startsWith("[")) {
            result = result.substring(1);
        }
        if (result.endsWith("]")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * fc00::/7, the IPv6 equivalent of 10.0.0.0/8 (not covered by {@link InetAddress#isSiteLocalAddress()}).
     */
    private static boolean isUniqueLocalIpv6(InetAddress inet) {
        return inet instanceof Inet6Address && (inet.getAddress()[0] & 0xfe) == 0xfc;
    }
}
