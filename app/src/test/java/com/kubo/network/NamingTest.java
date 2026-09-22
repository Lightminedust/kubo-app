package com.kubo.network;

import com.kubo.model.docker.DockerNetwork;
import com.kubo.model.network.Connection;
import com.kubo.model.network.Conversation;
import com.kubo.model.network.Identity;
import com.kubo.model.network.Link;
import com.kubo.model.network.Naming;
import com.kubo.model.network.PortNames;
import com.kubo.model.network.Resolvers;
import com.kubo.model.network.Route;
import com.kubo.model.server.ListeningPort.Protocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning the numbers into places, and twenty five identical sockets into one sentence.
 */
class NamingTest {

    private static final Identity MACHINE = new Identity(
            List.of(new Link("lo", "UNKNOWN", "", 65536,
                            List.of(new Link.Address("127.0.0.1", 8, false))),
                    new Link("eth0", "UP", "96:00:02:d1:4f:aa", 1500,
                            List.of(new Link.Address("203.0.113.10", 24, false))),
                    new Link("docker0", "UP", "02:42:1b:9c:11:02", 1500,
                            List.of(new Link.Address("172.17.0.1", 16, false))),
                    new Link("br-41317674355a", "UP", "02:42:aa:bb:cc:dd", 1500,
                            List.of(new Link.Address("172.19.0.1", 16, false)))),
            List.of(new Route("default", "203.0.113.1", "eth0", "", 100)),
            Resolvers.none(), "203.0.113.10");

    private static final List<DockerNetwork> NETWORKS = List.of(
            new DockerNetwork("41317674355ab9", "app-frontend_default", "bridge", "local", List.of()));

    private static final Naming NAMING = Naming.of(MACHINE, NETWORKS);

    @Test
    @DisplayName("an address on a Docker bridge is named after the network Docker knows")
    void namesDockerNetworks() {
        assertEquals("Docker app-frontend_default", NAMING.place("172.19.0.2"));
        assertEquals("Docker", NAMING.place("172.17.0.5"), "the default bridge has no id in its name");
    }

    @Test
    @DisplayName("this server is called this server, whichever of its own addresses is used")
    void namesItself() {
        assertEquals("this server", NAMING.place("127.0.0.1"));
        assertEquals("this server", NAMING.place("203.0.113.10"));
    }

    @Test
    @DisplayName("an address nothing is known about is left alone rather than guessed at")
    void inventsNothing() {
        assertEquals("", NAMING.place("86.15.2.9"));
        assertEquals("86.15.2.9", NAMING.describe("86.15.2.9"));
        assertEquals("private network", NAMING.place("10.4.4.4"));
    }

    @Test
    @DisplayName("each interface is told what it is for")
    void describesInterfaces() {
        assertEquals("the way in and out", NAMING.role(MACHINE.links().get(1)));
        assertEquals("the server talking to itself", NAMING.role(MACHINE.links().get(0)));
        assertEquals("Docker default bridge", NAMING.role(MACHINE.links().get(2)));
        assertEquals("a Docker network", NAMING.role(MACHINE.links().get(3)));
        assertEquals("one container", NAMING.role(new Link("vethfb8a471", "UP", "", 1500, List.of())));
        assertEquals("a VPN", NAMING.role(new Link("wg0", "UP", "", 1420, List.of())));
    }

    @Test
    @DisplayName("the interfaces that multiply with the containers are told from the real ones")
    void tellsVirtualApart() {
        assertTrue(Naming.virtual(new Link("vethfb8a471", "UP", "", 1500, List.of())));
        assertTrue(Naming.virtual(new Link("br-41317674355a", "UP", "", 1500, List.of())));
        assertFalse(Naming.virtual(MACHINE.links().get(1)));
        assertFalse(Naming.virtual(MACHINE.links().get(0)));
    }

    @Test
    @DisplayName("a pool of sockets to one service becomes one line with a count")
    void groupsAPool() {
        List<Connection> pool = new java.util.ArrayList<>();
        for (int port = 54000; port < 54014; port++) {
            pool.add(new Connection(Protocol.TCP, "172.19.0.1", port, "172.19.0.2", 6379, "node", 900, false));
        }
        pool.add(new Connection(Protocol.TCP, "203.0.113.10", 22, "86.15.2.9", 51234, "sshd", 8213, true));

        List<Conversation> conversations = Conversation.group(pool, NAMING);

        assertEquals(2, conversations.size());
        Conversation ssh = conversations.get(0);
        assertTrue(ssh.fromTheInternet(), "what came off the internet is read first");
        assertEquals(1, ssh.count());
        assertEquals("22 SSH", ssh.service());

        Conversation redis = conversations.get(1);
        assertEquals(14, redis.count());
        assertEquals("6379 Redis", redis.service());
        assertEquals("172.19.0.2  ·  Docker app-frontend_default", redis.remote());
        assertEquals("node", redis.process());
    }

    @Test
    @DisplayName("the same service reached by two different addresses stays two lines")
    void keepsDifferentEndsApart() {
        List<Conversation> conversations = Conversation.group(List.of(
                new Connection(Protocol.TCP, "127.0.0.1", 46904, "127.0.0.1", 6379, "node", 900, false),
                new Connection(Protocol.TCP, "172.19.0.1", 54032, "172.19.0.2", 6379, "node", 900, false)),
                NAMING);

        assertEquals(2, conversations.size());
    }

    @Test
    @DisplayName("a port nobody agreed on keeps its number and gains no name")
    void namesOnlyWhatIsKnown() {
        assertEquals("Redis", PortNames.of(6379));
        assertEquals("", PortNames.of(54032));
        assertEquals("54032", PortNames.describe(54032));
        assertEquals("443 HTTPS", PortNames.describe(443));
    }
}
