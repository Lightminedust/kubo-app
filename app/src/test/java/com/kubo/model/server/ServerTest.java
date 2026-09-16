package com.kubo.model.server;

import com.kubo.model.server.ListeningPort.Protocol;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTest {

    private static final ServerIdentity IDENTITY = new ServerIdentity("vps", "Debian GNU/Linux 13", "6.12.0",
            "AMD EPYC", 4, 8_000_000_000L, new ServerIdentity.UserIdentity("xana", false, true, true));

    @Test
    void metricsAreEmptyBeforeTheFirstReading() {
        Server server = new Server(IDENTITY, null, List.of(), List.of());

        assertTrue(server.metrics().isEmpty());
    }

    @Test
    void nullListsBecomeEmpty() {
        Server server = new Server(IDENTITY, null, null, null);

        assertTrue(server.openPorts().isEmpty());
        assertTrue(server.disks().isEmpty());
    }

    @Test
    void listsAreCopiedAndReadOnly() {
        List<ListeningPort> ports = new ArrayList<>();
        ports.add(new ListeningPort(Protocol.TCP, "0.0.0.0", 22, "sshd"));
        Server server = new Server(IDENTITY, null, ports, List.of());

        ports.clear();

        assertEquals(1, server.openPorts().size());
        assertThrows(UnsupportedOperationException.class, () -> server.openPorts().clear());
    }

    @Test
    void requiresIdentity() {
        assertThrows(NullPointerException.class, () -> new Server(null, null, List.of(), List.of()));
    }
}
