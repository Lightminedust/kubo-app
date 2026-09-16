package com.kubo.monitor.parse;

import com.kubo.model.server.ListeningPort;
import com.kubo.model.server.ListeningPort.Exposure;
import com.kubo.model.server.ListeningPort.Protocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortsParserTest {

    private static final String SS_OUTPUT = """
            udp   UNCONN 0      0            127.0.0.53%lo:53         0.0.0.0:*
            udp   UNCONN 0      0                  0.0.0.0:68         0.0.0.0:*
            tcp   LISTEN 0      4096               0.0.0.0:22         0.0.0.0:*    users:(("sshd",pid=812,fd=3))
            tcp   LISTEN 0      4096             127.0.0.1:5432       0.0.0.0:*
            tcp   LISTEN 0      4096                  [::]:22            [::]:*    users:(("sshd",pid=812,fd=4))
            tcp   LISTEN 0      4096                     *:80               *:*    users:(("docker-proxy",pid=1201,fd=4))
            tcp   LISTEN 0      4096            172.17.0.1:9000       0.0.0.0:*
            """;

    @Test
    void parsesEveryListeningSocket() {
        List<ListeningPort> ports = PortsParser.parse(SS_OUTPUT);

        assertEquals(List.of(
                new ListeningPort(Protocol.TCP, "0.0.0.0", 22, "sshd"),
                new ListeningPort(Protocol.TCP, "[::]", 22, "sshd"),
                new ListeningPort(Protocol.UDP, "127.0.0.53%lo", 53, ""),
                new ListeningPort(Protocol.UDP, "0.0.0.0", 68, ""),
                new ListeningPort(Protocol.TCP, "*", 80, "docker-proxy"),
                new ListeningPort(Protocol.TCP, "127.0.0.1", 5432, ""),
                new ListeningPort(Protocol.TCP, "172.17.0.1", 9000, "")
        ), ports);
    }

    @Test
    void parsedAddressesKeepTheirExposure() {
        List<ListeningPort> ports = PortsParser.parse(SS_OUTPUT);

        assertEquals(Exposure.PUBLIC, find(ports, "0.0.0.0", 22).exposure());
        assertEquals(Exposure.PUBLIC, find(ports, "[::]", 22).exposure());
        assertEquals(Exposure.LOCAL, find(ports, "127.0.0.53%lo", 53).exposure());
        assertEquals(Exposure.LOCAL, find(ports, "127.0.0.1", 5432).exposure());
        assertEquals(Exposure.INTERNAL, find(ports, "172.17.0.1", 9000).exposure());
    }

    @Test
    void unreadableLinesAreSkipped() {
        assertNull(PortsParser.parseLine("Netid State Recv-Q Send-Q Local Address:Port Peer Address:Port"));
        assertNull(PortsParser.parseLine("tcp LISTEN 0 4096 0.0.0.0:abc 0.0.0.0:*"));
        assertNull(PortsParser.parseLine("tcp LISTEN 0 4096 0.0.0.0:0 0.0.0.0:*"));
        assertNull(PortsParser.parseLine("sctp LISTEN 0 4096 0.0.0.0:22 0.0.0.0:*"));
        assertNull(PortsParser.parseLine("tcp LISTEN"));
        assertTrue(PortsParser.parse("").isEmpty());
    }

    private static ListeningPort find(List<ListeningPort> ports, String address, int port) {
        return ports.stream()
                .filter(candidate -> candidate.bindAddress().equals(address) && candidate.port() == port)
                .findFirst()
                .orElseThrow();
    }
}
