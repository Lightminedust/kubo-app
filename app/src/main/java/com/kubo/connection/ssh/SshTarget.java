package com.kubo.connection.ssh;

/**
 * Where to open an SSH connection: user, host and port.
 */
public record SshTarget(String username, String host, int port) {

    public static final int DEFAULT_PORT = 22;

    public SshTarget {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be null or blank");
        }
        if (containsWhitespace(username)) {
            throw new IllegalArgumentException("Username cannot contain whitespace: '" + username + "'");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host cannot be null or blank");
        }
        if (containsWhitespace(host)) {
            throw new IllegalArgumentException("Host cannot contain whitespace: '" + host + "'");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
    }

    /**
     * Parses a target string in the format "user@host", "user@host:port" or "user@[ipv6]:port".
     * An IPv6 address without brackets is read as a host with the default port.
     *
     * @param value the string representation of the target
     * @return a validated SshTarget instance
     * @throws IllegalArgumentException if the format is invalid or values fail validation
     */
    public static SshTarget parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Target string cannot be null or blank");
        }

        String target = value.strip();
        int atIndex = target.lastIndexOf('@');
        if (atIndex <= 0 || atIndex == target.length() - 1) {
            throw new IllegalArgumentException("Target must be in format 'user@host' or 'user@host:port'");
        }

        String username = target.substring(0, atIndex);
        HostAndPort hostAndPort = parseHostAndPort(target.substring(atIndex + 1));

        return new SshTarget(username, hostAndPort.host(), hostAndPort.port());
    }

    private static HostAndPort parseHostAndPort(String value) {
        if (value.startsWith("[")) {
            int closingIndex = value.indexOf(']');
            if (closingIndex == -1) {
                throw new IllegalArgumentException("Missing closing bracket in IPv6 address: " + value);
            }
            String host = value.substring(1, closingIndex);
            String rest = value.substring(closingIndex + 1);
            if (rest.isEmpty()) {
                return new HostAndPort(host, DEFAULT_PORT);
            }
            if (!rest.startsWith(":")) {
                throw new IllegalArgumentException("Unexpected characters after IPv6 address: " + rest);
            }
            return new HostAndPort(host, parsePort(rest.substring(1)));
        }

        int colonIndex = value.indexOf(':');
        if (colonIndex == -1 || colonIndex != value.lastIndexOf(':')) {
            // No port, or a bare IPv6 address whose colons are not a port separator.
            return new HostAndPort(value, DEFAULT_PORT);
        }
        return new HostAndPort(value.substring(0, colonIndex), parsePort(value.substring(colonIndex + 1)));
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port number: " + value, e);
        }
    }

    private static boolean containsWhitespace(String value) {
        return value.chars().anyMatch(Character::isWhitespace);
    }

    @Override
    public String toString() {
        // Brackets keep IPv6 hosts unambiguous, so parse(toString()) gives back the same target.
        String displayHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return username + "@" + displayHost + ":" + port;
    }

    private record HostAndPort(String host, int port) {}
}
