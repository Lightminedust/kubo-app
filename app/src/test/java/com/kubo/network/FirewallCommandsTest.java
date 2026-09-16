package com.kubo.network;

import com.kubo.model.network.Firewall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one thing this screen must never get wrong is telling someone nothing is filtered when something is.
 */
class FirewallCommandsTest {

    @Test
    @DisplayName("the tools are found in the order Kubo should try them")
    void detection() {
        assertEquals(List.of(Firewall.Kind.UFW, Firewall.Kind.IPTABLES),
                FirewallCommands.parseTools("ufw\niptables\n"));
        assertEquals(List.of(Firewall.Kind.FIREWALLD, Firewall.Kind.NFTABLES, Firewall.Kind.IPTABLES),
                FirewallCommands.parseTools("firewall-cmd\nnft\niptables"));
        assertEquals(List.of(), FirewallCommands.parseTools(""));
    }

    // UFW

    @Test
    @DisplayName("an active UFW gives its numbered rules and the ports they name")
    void ufwActive() {
        Firewall firewall = FirewallCommands.parseUfw("""
                Status: active

                     To                         Action      From
                     --                         ------      ----
                [ 1] 22/tcp                     ALLOW IN    Anywhere
                [ 2] 80,443/tcp                 ALLOW IN    Anywhere
                [ 3] 5432/tcp                   ALLOW IN    10.0.0.0/8
                [ 4] 22/tcp (v6)                ALLOW IN    Anywhere (v6)
                """);

        assertEquals(Firewall.Kind.UFW, firewall.kind());
        assertTrue(firewall.isEnforcing());
        assertTrue(firewall.isKnown());
        assertEquals(4, firewall.rules().size());
        assertFalse(firewall.blocks(443), "443 is allowed from anywhere");
        assertTrue(firewall.blocks(5432), "5432 is only allowed from a private range");
        assertTrue(firewall.blocks(9999), "no rule names it, so the default policy denies it");
    }

    @Test
    @DisplayName("a UFW that is off, or that answered nothing, protects nothing and says which it is")
    void ufwOffOrUnreadable() {
        Firewall off = FirewallCommands.parseUfw("Status: inactive");
        assertEquals(Firewall.State.INACTIVE, off.state());
        assertFalse(off.blocks(22));
        assertTrue(off.isKnown(), "being switched off is an answer");

        Firewall refused = FirewallCommands.parseUfw("ERROR: You need to be root to run this script");
        assertEquals(Firewall.State.UNREADABLE, refused.state());
        assertFalse(refused.blocks(22));
        assertFalse(refused.isKnown(), "not being able to read it is not an answer");
    }

    // firewalld

    @Test
    @DisplayName("firewalld's zone gives both its bare ports and the services it names")
    void firewalldRunning() {
        Firewall firewall = FirewallCommands.parseFirewalld("""
                running
                ---
                public (active)
                  target: default
                  interfaces: eth0
                  sources:
                  services: ssh http https dhcpv6-client
                  ports: 8080/tcp 9000-9002/udp
                  protocols:
                """);

        assertEquals(Firewall.Kind.FIREWALLD, firewall.kind());
        assertTrue(firewall.isEnforcing());
        assertFalse(firewall.blocks(22), "the ssh service names port 22");
        assertFalse(firewall.blocks(443), "https names 443");
        assertFalse(firewall.blocks(8080), "a bare port is allowed too");
        assertFalse(firewall.blocks(9001), "a range is spread over its ports");
        assertTrue(firewall.blocks(3306), "nothing names it");
    }

    @Test
    @DisplayName("a service firewalld knows and Kubo does not is kept, without inventing a port for it")
    void firewalldUnknownService() {
        Firewall firewall = FirewallCommands.parseFirewalld("running\n---\npublic\n  services: ssh wireguard\n");
        assertEquals(2, firewall.rules().size());
        assertTrue(firewall.rules().stream()
                .anyMatch(rule -> rule.to().equals("wireguard") && rule.ports().isEmpty()));
    }

    @Test
    @DisplayName("firewalld stopped is off; firewalld that refuses to answer is unknown, not off")
    void firewalldStoppedOrRefused() {
        assertEquals(Firewall.State.INACTIVE, FirewallCommands.parseFirewalld("not running\n---\n").state());

        Firewall refused = FirewallCommands.parseFirewalld("Authorization failed.\n---\nAuthorization failed.");
        assertEquals(Firewall.State.UNREADABLE, refused.state());
        assertFalse(refused.isKnown());
    }

    // iptables

    @Test
    @DisplayName("iptables dropping by default is a real firewall, and its accept rules are its open ports")
    void iptablesDropPolicy() {
        Firewall firewall = FirewallCommands.parseIptables("""
                -P INPUT DROP
                -P FORWARD DROP
                -P OUTPUT ACCEPT
                -A INPUT -i lo -j ACCEPT
                -A INPUT -p tcp -m tcp --dport 22 -j ACCEPT
                -A INPUT -p tcp -m tcp --dport 443 -j ACCEPT
                """);

        assertEquals(Firewall.Kind.IPTABLES, firewall.kind());
        assertTrue(firewall.isEnforcing());
        assertFalse(firewall.blocks(22));
        assertTrue(firewall.blocks(8080));
    }

    @Test
    @DisplayName("iptables accepting everything and dropping nothing is not filtering")
    void iptablesWideOpen() {
        Firewall firewall = FirewallCommands.parseIptables("-P INPUT ACCEPT\n-P FORWARD ACCEPT\n-P OUTPUT ACCEPT");
        assertEquals(Firewall.State.INACTIVE, firewall.state());
        assertTrue(firewall.isKnown());
    }

    @Test
    @DisplayName("iptables accepting by default but dropping some things is filtering Kubo will not summarise")
    void iptablesMixed() {
        Firewall firewall = FirewallCommands.parseIptables("""
                -P INPUT ACCEPT
                -A INPUT -s 10.0.0.9/32 -j DROP
                -A INPUT -p tcp -m tcp --dport 22 -j ACCEPT
                """);
        assertEquals(Firewall.State.UNRECOGNISED, firewall.state());
        assertFalse(firewall.isKnown(), "it must not be reported as wide open");
        assertFalse(firewall.blocks(22), "and it must not be counted as protection either");
    }

    @Test
    @DisplayName("iptables answering nothing means it could not be read, not that it is off")
    void iptablesSilent() {
        assertEquals(Firewall.State.UNREADABLE, FirewallCommands.parseIptables("").state());
    }

    // nftables

    @Test
    @DisplayName("an nftables input hook that drops by default gives its accepted ports")
    void nftablesDropPolicy() {
        Firewall firewall = FirewallCommands.parseNftables("""
                table inet filter {
                  chain input {
                    type filter hook input priority 0; policy drop;
                    iif "lo" accept
                    ct state established,related accept
                    tcp dport { 22, 80, 443 } accept
                    udp dport 51820 accept
                  }
                }
                """);

        assertEquals(Firewall.Kind.NFTABLES, firewall.kind());
        assertTrue(firewall.isEnforcing());
        assertFalse(firewall.blocks(80));
        assertFalse(firewall.blocks(51820));
        assertTrue(firewall.blocks(3306));
    }

    @Test
    @DisplayName("an empty nftables ruleset filters nothing")
    void nftablesEmpty() {
        Firewall firewall = FirewallCommands.parseNftables("");
        assertEquals(Firewall.State.INACTIVE, firewall.state());
        assertTrue(firewall.isKnown());
    }

    @Test
    @DisplayName("an nftables ruleset that drops without a drop policy is not called wide open")
    void nftablesUnrecognised() {
        Firewall firewall = FirewallCommands.parseNftables("""
                table inet filter {
                  chain input {
                    type filter hook input priority 0; policy accept;
                    ip saddr 10.0.0.9 drop
                  }
                }
                """);
        assertEquals(Firewall.State.UNRECOGNISED, firewall.state());
        assertFalse(firewall.isKnown());
    }

    // What can still be learned without root

    @Test
    @DisplayName("systemd or ufw.conf saying it is on is enough to know the firewall is running")
    void runningCanBeSeenWithoutRoot() {
        assertEquals(FirewallCommands.Liveness.RUNNING,
                FirewallCommands.parseLiveness("SERVICE active\nENABLED=yes"));
        assertEquals(FirewallCommands.Liveness.RUNNING, FirewallCommands.parseLiveness("SERVICE active"));
        assertEquals(FirewallCommands.Liveness.RUNNING, FirewallCommands.parseLiveness("SERVICE unknown\nENABLED=yes"));
    }

    @Test
    @DisplayName("only both sources agreeing that it is off counts as off")
    void offNeedsBothSourcesToAgree() {
        assertEquals(FirewallCommands.Liveness.STOPPED,
                FirewallCommands.parseLiveness("SERVICE inactive\nENABLED=no"));

        // The unit can be inactive while rules are still loaded in the kernel, so one source is not enough to
        // tell someone their server is unprotected.
        assertEquals(FirewallCommands.Liveness.UNKNOWN, FirewallCommands.parseLiveness("SERVICE inactive"));
        assertEquals(FirewallCommands.Liveness.UNKNOWN, FirewallCommands.parseLiveness("ENABLED=no"));
        assertEquals(FirewallCommands.Liveness.UNKNOWN, FirewallCommands.parseLiveness(""));
    }

    @Test
    @DisplayName("the liveness check needs no root and reads nothing but a status")
    void livenessOnlyAsks() {
        String command = FirewallCommands.liveness(Firewall.Kind.UFW);
        assertTrue(command.contains("systemctl is-active ufw"), command);
        assertTrue(command.contains("/etc/ufw/ufw.conf"), command);
        assertFalse(command.contains("sudo"), command);
        for (String writing : new String[]{" rm ", " enable", " disable", " allow ", " delete "}) {
            assertFalse(command.contains(writing), command + " must not contain " + writing);
        }
        assertEquals("echo UNKNOWN", FirewallCommands.liveness(Firewall.Kind.IPTABLES),
                "there is no service to ask about raw iptables");
    }

    @Test
    @DisplayName("a firewall known to be running but unreadable is never counted as protection")
    void runningButUnreadableProtectsNothingAsFarAsKuboKnows() {
        Firewall firewall = Firewall.of(Firewall.Kind.UFW, Firewall.State.UNRECOGNISED);

        assertFalse(firewall.isKnown());
        assertFalse(firewall.blocks(22));
        assertTrue(firewall.problem().contains("ROOT"), firewall.problem());
    }

    @Test
    @DisplayName("every state that is not an answer says so, and none of them count as protection")
    void nothingUnknownIsEverCountedAsProtection() {
        for (Firewall.State state : Firewall.State.values()) {
            Firewall firewall = Firewall.of(Firewall.Kind.FIREWALLD, state);
            if (state != Firewall.State.ACTIVE) {
                assertFalse(firewall.blocks(22), state + " must not be counted as protection");
            }
            if (state == Firewall.State.UNREADABLE || state == Firewall.State.UNRECOGNISED) {
                assertFalse(firewall.isKnown(), state + " is not an answer");
                assertFalse(firewall.problem().isEmpty(), state + " must explain itself");
            }
        }
    }
}
