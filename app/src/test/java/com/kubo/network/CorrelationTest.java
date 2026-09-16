package com.kubo.network;

import com.kubo.model.docker.ContainerState;
import com.kubo.model.docker.DockerContainer;
import com.kubo.model.docker.Health;
import com.kubo.model.docker.PortMapping;
import com.kubo.model.network.Endpoint;
import com.kubo.model.network.Firewall;
import com.kubo.model.network.ListeningSocket;
import com.kubo.model.network.PmApp;
import com.kubo.model.network.Risk;
import com.kubo.model.network.ServiceOwner;
import com.kubo.model.network.Vhost;
import com.kubo.model.server.ListeningPort;
import com.kubo.model.server.ListeningPort.Protocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine that turns four separate readings into one chain: firewall, domain, port, process.
 */
class CorrelationTest {

    private static ListeningSocket socket(String address, int port, String process, int pid) {
        return new ListeningSocket(new ListeningPort(Protocol.TCP, address, port, process), pid);
    }

    private static Firewall active(String rules) {
        return NetworkCommands.parseFirewall("Status: active\n" + rules);
    }

    @Test
    void aPidThatBelongsToPm2IsNamedAfterItsApplication() {
        List<Endpoint> endpoints = NetworkService.correlate(
                List.of(socket("127.0.0.1", 3000, "node", 1234)),
                List.of(new PmApp("gemmie-api", 0, 1234, "online", 2.5, 1024, 0, 3)),
                List.of(), List.of(), active("[ 1] 22/tcp ALLOW IN    Anywhere"));

        ServiceOwner owner = endpoints.get(0).owner();
        assertEquals(ServiceOwner.Kind.PM2, owner.kind());
        assertEquals("gemmie-api", owner.label());
        assertEquals("gemmie-api", owner.handle());
        assertTrue(owner.isManaged());
    }

    @Test
    void aPublishedContainerPortIsNamedAfterItsContainer() {
        DockerContainer container = new DockerContainer("abc123", "gemmie-redis", "redis:7", ContainerState.RUNNING,
                Health.NONE, "Up", "", "", List.of(PortMapping.published("0.0.0.0", 6379, 6379, "tcp")), null);

        List<Endpoint> endpoints = NetworkService.correlate(
                List.of(socket("0.0.0.0", 6379, "docker-proxy", 900)),
                List.of(), List.of(container), List.of(), active(""));

        ServiceOwner owner = endpoints.get(0).owner();
        assertEquals(ServiceOwner.Kind.DOCKER, owner.kind());
        assertEquals("gemmie-redis", owner.label());
        assertEquals("redis:7", owner.detail());
        assertEquals("abc123", owner.handle());
    }

    @Test
    void anOrdinaryDaemonKeepsItsOwnName() {
        List<Endpoint> endpoints = NetworkService.correlate(
                List.of(socket("0.0.0.0", 22, "sshd", 812)),
                List.of(), List.of(), List.of(), active(""));

        assertEquals(ServiceOwner.Kind.SYSTEM, endpoints.get(0).owner().kind());
        assertEquals("sshd", endpoints.get(0).owner().label());
        assertFalse(endpoints.get(0).owner().isManaged());
    }

    @Test
    void aSocketWithoutAProcessSaysSoRatherThanGuessing() {
        List<Endpoint> endpoints = NetworkService.correlate(
                List.of(socket("0.0.0.0", 5432, "", ListeningSocket.UNKNOWN_PID)),
                List.of(), List.of(), List.of(), active(""));

        assertEquals(ServiceOwner.Kind.UNKNOWN, endpoints.get(0).owner().kind());
    }

    @Test
    void aDomainIsTiedToThePortItForwardsTo() {
        Vhost vhost = new Vhost(List.of("gemmie.rw"), List.of(443), true,
                List.of(NetworkCommands.target("http://127.0.0.1:3000")), "/etc/ssl/cert.pem", "");

        List<Endpoint> endpoints = NetworkService.correlate(
                List.of(socket("127.0.0.1", 3000, "node", 1234), socket("127.0.0.1", 4000, "node", 5678)),
                List.of(), List.of(), List.of(vhost), active(""));

        assertTrue(endpoints.get(0).isBehindNginx());
        assertEquals("gemmie.rw", endpoints.get(0).servedBy().get(0).title());
        assertFalse(endpoints.get(1).isBehindNginx());
    }

    @Test
    void reachTellsIsolatedFromShieldedFromOpen() {
        Firewall firewall = active("""
                [ 1] 22/tcp                     ALLOW IN    Anywhere
                [ 2] 27017                      DENY IN     Anywhere
                """);

        assertEquals(Endpoint.Reach.ISOLATED, Endpoint.reachOf(socket("127.0.0.1", 3000, "node", 1), firewall));
        assertEquals(Endpoint.Reach.PRIVATE, Endpoint.reachOf(socket("172.17.0.1", 5000, "x", 1), firewall));
        assertEquals(Endpoint.Reach.OPEN, Endpoint.reachOf(socket("0.0.0.0", 22, "sshd", 1), firewall));
        assertEquals(Endpoint.Reach.SHIELDED, Endpoint.reachOf(socket("0.0.0.0", 27017, "mongod", 1), firewall));
        // A port with no rule at all is shielded by UFW's default deny.
        assertEquals(Endpoint.Reach.SHIELDED, Endpoint.reachOf(socket("0.0.0.0", 9999, "x", 1), firewall));
    }

    @Test
    void anExposedDatabaseIsAnAlertAndAnIsolatedOneIsNot() {
        Firewall off = NetworkCommands.parseFirewall("Status: inactive");

        Endpoint exposed = NetworkService.correlate(
                List.of(socket("0.0.0.0", 27017, "mongod", 700)), List.of(), List.of(), List.of(), off).get(0);
        Endpoint isolated = NetworkService.correlate(
                List.of(socket("127.0.0.1", 27017, "mongod", 700)), List.of(), List.of(), List.of(), off).get(0);

        Risk risk = Risk.of(exposed).orElseThrow();
        assertEquals(Risk.Level.ALERT, risk.level());
        assertTrue(risk.title().contains("MongoDB"));
        assertTrue(Risk.of(isolated).isEmpty());
    }

    @Test
    void aWebPortOpenToTheWorldIsNormalAndRaisesNothing() {
        Firewall firewall = active("[ 1] 80,443/tcp ALLOW IN    Anywhere");

        Endpoint web = NetworkService.correlate(
                List.of(socket("0.0.0.0", 443, "nginx", 999)), List.of(), List.of(), List.of(), firewall).get(0);

        assertTrue(web.isOpenToTheWorld());
        assertTrue(Risk.of(web).isEmpty());
    }

    @Test
    void aShieldedDatabaseRaisesNothingEvenThoughItBindsEverything() {
        Firewall firewall = active("[ 1] 22/tcp ALLOW IN    Anywhere");

        Endpoint shielded = NetworkService.correlate(
                List.of(socket("0.0.0.0", 6379, "redis-server", 800)),
                List.of(), List.of(), List.of(), firewall).get(0);

        assertEquals(Endpoint.Reach.SHIELDED, shielded.reach());
        assertTrue(Risk.of(shielded).isEmpty());
    }
}
