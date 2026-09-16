package com.kubo.monitor.parse;

import com.kubo.model.network.ListeningSocket;
import com.kubo.model.server.ListeningPort;
import com.kubo.network.NetworkCommands;

import java.util.Comparator;
import java.util.List;

/**
 * Reads the sockets waiting for connections. Without root, processes of other users are not named.
 *
 * <p>The line reading itself lives in {@link NetworkCommands}, which also keeps the owning PID that the network
 * screen needs; the monitor only wants the ports, so it drops the rest.
 */
public final class PortsParser {

    public static final String COMMAND = "ss -tulnpH";

    private PortsParser() {
    }

    /**
     * Lines that cannot be read are skipped rather than failing the whole list.
     */
    public static List<ListeningPort> parse(String output) {
        return NetworkCommands.parseSockets(output).stream()
                .map(ListeningSocket::listening)
                .distinct()
                .sorted(Comparator.comparingInt(ListeningPort::port).thenComparing(ListeningPort::bindAddress))
                .toList();
    }

    /**
     * "tcp LISTEN 0 4096 0.0.0.0:22 0.0.0.0:* users:(("sshd",pid=812,fd=3))"
     */
    static ListeningPort parseLine(String line) {
        return NetworkCommands.parseSocket(line).map(ListeningSocket::listening).orElse(null);
    }
}
