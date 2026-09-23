package com.kubo.network;

import com.kubo.model.network.Connection;
import com.kubo.model.network.Counter;
import com.kubo.model.server.ListeningPort;

import java.util.ArrayList;
import java.util.List;

/**
 * Reading what is moving right now: the connections that are open, and the bytes each interface has carried.
 *
 * <p>Both only read, and neither needs root. Without root {@code ss} still lists every connection, it just cannot
 * name the processes that belong to other users.
 */
public final class TrafficCommands {

    /**
     * Every established connection, TCP and UDP, with the process holding each one.
     *
     * <p>Only established ones: the listening sockets are already read elsewhere, and the half-open states are
     * noise on a screen meant to answer "who is on this machine right now".
     */
    public static final String CONNECTIONS = "ss -tunpH state established";

    /** The kernel's per-interface byte counters. One reading is a number; two are a rate. */
    public static final String COUNTERS = "cat /proc/net/dev";

    private TrafficCommands() {
    }

    /**
     * Connections from {@code ss -tunpH state established}.
     *
     * <p>A line looks like this, with the columns separated by runs of spaces:
     * <pre>tcp  ESTAB 0 0  10.0.0.5:22  86.15.2.9:51234  users:(("sshd",pid=8213,fd=5))</pre>
     *
     * <p>The State column is not always there. Asking for one state with {@code state established} makes some
     * versions of ss drop it, since every row would say the same word. So the two addresses are found by their
     * shape rather than counted to: they are the first two fields in a row that read as an address and a port.
     * Netid is a word, Recv-Q and Send-Q are plain numbers, a state is a word, and the process field comes after
     * both addresses, so the first pair is always the right pair.
     *
     * <p>This is why, on a server where the connected user saw every connection, root saw none. With root the
     * process field is filled in, which made the line one field longer, which moved the column this used to
     * count to, and every row was thrown away as unreadable. Counting columns was the bug.
     *
     * @param listening the ports this server listens on, which is what decides the direction of each connection
     */
    public static List<Connection> parseConnections(String output, java.util.Set<Integer> listening) {
        List<Connection> connections = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            String[] parts = text.split("\\s+");
            if (parts.length < 5) {
                continue;
            }
            ListeningPort.Protocol protocol = parts[0].startsWith("udp")
                    ? ListeningPort.Protocol.UDP : ListeningPort.Protocol.TCP;
            int local = addressPair(parts);
            if (local < 0) {
                continue;
            }
            String[] here = splitAddress(parts[local]);
            String[] there = splitAddress(parts[local + 1]);
            int localPort;
            int remotePort;
            try {
                localPort = Integer.parseInt(here[1]);
                remotePort = Integer.parseInt(there[1]);
            } catch (NumberFormatException ignored) {
                continue;
            }
            String process = processName(text);
            int pid = processId(text);
            // The end whose port this server listens on is the end that was called.
            boolean inbound = listening != null && listening.contains(localPort) && !listening.contains(remotePort);
            try {
                connections.add(new Connection(protocol, here[0], localPort, there[0], remotePort,
                        process, pid, inbound));
            } catch (IllegalArgumentException ignored) {
                // A line in a shape this does not read, such as a kernel socket with no port.
            }
        }
        return connections;
    }

    /**
     * Where the local address sits in a row: the first of two fields in a row that both read as an address
     * and a port.
     *
     * @return the index of the local address, or -1 when no such pair is in this line
     */
    private static int addressPair(String[] parts) {
        for (int i = 1; i + 1 < parts.length; i++) {
            if (splitAddress(parts[i]) != null && splitAddress(parts[i + 1]) != null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Splits "10.0.0.5:22", "[2a02::1]:443" or "*:68" into address and port.
     *
     * @return two elements, or null when the text is not an address and a port
     */
    private static String[] splitAddress(String text) {
        int colon = text.lastIndexOf(':');
        if (colon <= 0 || colon == text.length() - 1) {
            return null;
        }
        String address = text.substring(0, colon);
        if (address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1);
        }
        int scope = address.indexOf('%');
        if (scope >= 0) {
            address = address.substring(0, scope);
        }
        return new String[]{address, text.substring(colon + 1)};
    }

    /** The name inside users:(("sshd",pid=8213,fd=5)), or empty when ss printed none. */
    private static String processName(String line) {
        int start = line.indexOf("((\"");
        if (start < 0) {
            return "";
        }
        int end = line.indexOf('"', start + 3);
        return end < 0 ? "" : line.substring(start + 3, end);
    }

    private static int processId(String line) {
        int start = line.indexOf("pid=");
        if (start < 0) {
            return Connection.UNKNOWN_PID;
        }
        int end = start + 4;
        while (end < line.length() && Character.isDigit(line.charAt(end))) {
            end++;
        }
        try {
            return Integer.parseInt(line.substring(start + 4, end));
        } catch (NumberFormatException e) {
            return Connection.UNKNOWN_PID;
        }
    }

    /**
     * Counters from /proc/net/dev.
     *
     * <p>The file has two header lines, then one line per interface:
     * <pre>  eth0: 1234567 8901 0 0 0 0 0 0  765432 4321 0 0 0 0 0 0</pre>
     * The first eight numbers are receive, the next eight are transmit.
     */
    public static List<Counter> parseCounters(String output) {
        List<Counter> counters = new ArrayList<>();
        for (String line : output.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String device = line.substring(0, colon).strip();
            String[] numbers = line.substring(colon + 1).strip().split("\\s+");
            if (device.isEmpty() || numbers.length < 16) {
                continue;
            }
            try {
                counters.add(new Counter(device, Long.parseLong(numbers[0]), Long.parseLong(numbers[8]),
                        Long.parseLong(numbers[1]), Long.parseLong(numbers[9])));
            } catch (IllegalArgumentException ignored) {
                // The header lines land here and are meant to.
            }
        }
        return counters;
    }
}
