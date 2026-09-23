package com.kubo.network;

import com.kubo.model.network.Connection;
import com.kubo.model.network.Counter;
import com.kubo.model.server.ListeningPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the connections that are open and the bytes each interface has carried.
 */
class TrafficCommandsTest {

    private static final Set<Integer> LISTENING = Set.of(22, 443, 5432);

    @Test
    @DisplayName("an established connection gives both ends and the process holding it")
    void readsAConnection() {
        String output = """
                tcp   ESTAB  0      0      10.0.0.5:22      86.15.2.9:51234  users:(("sshd",pid=8213,fd=5))
                """;

        List<Connection> connections = TrafficCommands.parseConnections(output, LISTENING);

        assertEquals(1, connections.size());
        Connection ssh = connections.get(0);
        assertEquals(ListeningPort.Protocol.TCP, ssh.protocol());
        assertEquals("10.0.0.5", ssh.localAddress());
        assertEquals(22, ssh.localPort());
        assertEquals("86.15.2.9", ssh.remoteAddress());
        assertEquals(51234, ssh.remotePort());
        assertEquals("sshd", ssh.process());
        assertEquals(8213, ssh.pid());
    }

    @Test
    @DisplayName("the direction is decided by which end holds a port this server listens on")
    void tellsTheDirectionApart() {
        String output = """
                tcp   ESTAB 0 0  10.0.0.5:22      86.15.2.9:51234 users:(("sshd",pid=8213,fd=5))
                tcp   ESTAB 0 0  10.0.0.5:44210   140.82.121.4:443 users:(("curl",pid=9001,fd=3))
                """;

        List<Connection> connections = TrafficCommands.parseConnections(output, LISTENING);

        assertTrue(connections.get(0).inbound(), "somebody called our SSH port");
        assertEquals(22, connections.get(0).servicePort());
        assertFalse(connections.get(1).inbound(), "we called out to an HTTPS server");
        assertEquals(443, connections.get(1).servicePort());
    }

    @Test
    @DisplayName("a call from the internet is told apart from one on the private network")
    void tellsTheInternetApart() {
        String output = """
                tcp   ESTAB 0 0  10.0.0.5:443   86.15.2.9:51234 users:(("nginx",pid=700,fd=9))
                tcp   ESTAB 0 0  10.0.0.5:5432  10.0.0.9:40122  users:(("postgres",pid=800,fd=7))
                """;

        List<Connection> connections = TrafficCommands.parseConnections(output, LISTENING);

        assertTrue(connections.get(0).fromTheInternet());
        assertFalse(connections.get(1).fromTheInternet(), "the database call came off the private network");
        assertEquals(ListeningPort.Exposure.INTERNAL, connections.get(1).remoteExposure());
    }

    @Test
    @DisplayName("IPv6 addresses keep their shape once the brackets are off")
    void readsIpv6() {
        String output = "tcp ESTAB 0 0 [2a02:c207::1]:443 [2001:4860:4860::8888]:33012 users:((\"nginx\",pid=7,fd=9))";

        Connection connection = TrafficCommands.parseConnections(output, LISTENING).get(0);

        assertEquals("2a02:c207::1", connection.localAddress());
        assertEquals(443, connection.localPort());
        assertEquals("2001:4860:4860::8888", connection.remoteAddress());
        assertEquals(33012, connection.remotePort());
    }

    @Test
    @DisplayName("without root the connection is still listed, only unnamed")
    void survivesWithoutRoot() {
        String output = "tcp ESTAB 0 0 10.0.0.5:443 86.15.2.9:51234";

        Connection connection = TrafficCommands.parseConnections(output, LISTENING).get(0);

        assertFalse(connection.named());
        assertEquals(Connection.UNKNOWN_PID, connection.pid());
        assertEquals(443, connection.localPort());
    }

    @Test
    @DisplayName("UDP lines, which some versions print without a state column, are read too")
    void readsUdp() {
        String output = "udp  UNCONN 0 0 10.0.0.5:68 10.0.0.1:67 users:((\"dhclient\",pid=500,fd=6))";

        Connection connection = TrafficCommands.parseConnections(output, LISTENING).get(0);

        assertEquals(ListeningPort.Protocol.UDP, connection.protocol());
        assertEquals(68, connection.localPort());
        assertEquals(67, connection.remotePort());
    }

    @Test
    @DisplayName("the counters come out of /proc/net/dev, headers and all")
    void readsCounters() {
        String output = """
                Inter-|   Receive                                                |  Transmit
                 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
                    lo: 1000 10 0 0 0 0 0 0 1000 10 0 0 0 0 0 0
                  eth0: 90811223 120345 0 0 0 0 0 0 4455667 88123 0 0 0 0 0 0
                """;

        List<Counter> counters = TrafficCommands.parseCounters(output);

        assertEquals(List.of("lo", "eth0"), counters.stream().map(Counter::device).toList());
        Counter eth0 = counters.get(1);
        assertEquals(90811223L, eth0.rxBytes());
        assertEquals(4455667L, eth0.txBytes());
        assertEquals(120345L, eth0.rxPackets());
        assertEquals(88123L, eth0.txPackets());
    }

    @Test
    @DisplayName("two readings a second apart give bytes per second")
    void computesTheRate() {
        Counter before = new Counter("eth0", 1_000_000, 500_000, 0, 0);
        Counter after = new Counter("eth0", 1_500_000, 600_000, 0, 0);

        Counter.Rate rate = after.since(before, Duration.ofSeconds(2));

        assertEquals(250_000, rate.received(), 0.01);
        assertEquals(50_000, rate.sent(), 0.01);
        assertEquals(300_000, rate.total(), 0.01);
    }

    @Test
    @DisplayName("a counter that went backwards reports nothing rather than a negative rate")
    void refusesToInventARate() {
        Counter before = new Counter("eth0", 9_000_000, 9_000_000, 0, 0);
        Counter afterReboot = new Counter("eth0", 1_000, 1_000, 0, 0);

        assertEquals(Counter.Rate.NONE, afterReboot.since(before, Duration.ofSeconds(2)));
        assertEquals(Counter.Rate.NONE, afterReboot.since(before, Duration.ZERO));
        assertEquals(Counter.Rate.NONE, afterReboot.since(null, Duration.ofSeconds(2)));
    }
    @Test
    @DisplayName("a row with no State column is read, which is what ss prints when one state was asked for")
    void readsRowsWithoutAState() {
        // Taken from a real server, through sudo. ss dropped the State column because the command asks for
        // one state, and the process field made the line the same length a line with a State would have been.
        String output = """
                tcp   0  0  172.19.0.1:54032  172.19.0.2:6379  users:(("docker-proxy",pid=1487,fd=48))
                tcp   0  0  203.0.113.10:22   86.15.2.9:51234  users:(("sshd",pid=8213,fd=5))""";

        List<Connection> connections = TrafficCommands.parseConnections(output, Set.of(22, 6379));

        assertEquals(2, connections.size(), "counting columns threw every one of these away");
        assertEquals("172.19.0.2", connections.get(0).remoteAddress());
        assertEquals(6379, connections.get(0).remotePort());
        assertEquals("docker-proxy", connections.get(0).process());
        assertEquals(1487, connections.get(0).pid());
        assertEquals("sshd", connections.get(1).process());
        assertEquals(22, connections.get(1).localPort());
        assertTrue(connections.get(1).inbound());
    }

    @Test
    @DisplayName("the same row with a State column is read the same way")
    void readsRowsWithAState() {
        String output = "tcp  ESTAB 0 0  203.0.113.10:22  86.15.2.9:51234  users:((\"sshd\",pid=8213,fd=5))";

        List<Connection> connections = TrafficCommands.parseConnections(output, Set.of(22));

        assertEquals(1, connections.size());
        assertEquals("203.0.113.10", connections.get(0).localAddress());
        assertEquals("86.15.2.9", connections.get(0).remoteAddress());
        assertEquals("sshd", connections.get(0).process());
    }

    @Test
    @DisplayName("an IPv6 row with no State column is read, brackets, colons and all")
    void readsIpv6WithoutAState() {
        String output = "tcp  0 0  [2a02:c207:2307:3638::1]:443  [2001:db8::9]:51234  "
                + "users:((\"nginx\",pid=700,fd=9))";

        List<Connection> connections = TrafficCommands.parseConnections(output, Set.of(443));

        assertEquals(1, connections.size());
        assertEquals("2a02:c207:2307:3638::1", connections.get(0).localAddress());
        assertEquals(443, connections.get(0).localPort());
        assertEquals("2001:db8::9", connections.get(0).remoteAddress());
    }
}
