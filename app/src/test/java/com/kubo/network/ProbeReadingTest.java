package com.kubo.network;

import com.kubo.model.network.ProbeReading;
import com.kubo.model.network.ProbeReading.PortState;
import com.kubo.model.network.ProbeReading.Verdict;
import com.kubo.tools.ProbeCommands;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The verdict above the raw answer, for a ping, a trace and a port. */
class ProbeReadingTest {

    @Test
    @DisplayName("a ping that all came back fast is good, and says how fast")
    void readsAGoodPing() {
        ProbeReading.Ping ping = ProbeReading.Ping.parse("""
                3 packets transmitted, 3 received, 0% packet loss, time 2003ms
                rtt min/avg/max/mdev = 0.310/0.412/0.502/0.078 ms
                """);

        assertEquals(Verdict.GOOD, ping.verdict());
        assertEquals("3 of 3 answered, 0.4 ms on average.", ping.sentence());
    }

    @Test
    @DisplayName("a lost packet is bad however fast the others were, and nothing back is said plainly")
    void readsLoss() {
        assertEquals(Verdict.BAD, ProbeReading.Ping.parse(
                "3 packets transmitted, 2 received, 33% packet loss\nrtt min/avg/max/mdev = 1/2/3/1 ms").verdict());
        ProbeReading.Ping none = ProbeReading.Ping.parse("3 packets transmitted, 0 received, 100% packet loss");
        assertEquals(Verdict.BAD, none.verdict());
        assertEquals("Nothing answered: 3 sent, none came back.", none.sentence());
    }

    @Test
    @DisplayName("slow is slow, and BusyBox's wording reads as well as the usual one")
    void readsSlowAndBusyBox() {
        assertEquals(Verdict.SLOW, ProbeReading.Ping.parse(
                "3 packets transmitted, 3 packets received, 0% packet loss\n"
                        + "round-trip min/avg/max = 40.1/45.2/50.3 ms").verdict());
        assertEquals(Verdict.UNKNOWN, ProbeReading.Ping.parse("ping: unknown host").verdict());
    }

    /** A real mtr report: a private router, a router rationing its replies, and a route that ends. */
    private static final String TRACE = """
            Start: 2026-09-23T10:00:00+0000
            HOST: server                      Loss%   Snt   Last   Avg  Best  Wrst StDev
              1.|-- 173.212.254.1              0.0%     3    0.4   0.5   0.3   0.7   0.1
              2.|-- ???                       100.0     3    0.0   0.0   0.0   0.0   0.0
              3.|-- 80.81.192.1               33.3%     3    9.8  10.2   9.8  10.6   0.4
              4.|-- 1.1.1.1                    0.0%     3   11.0  11.1  10.9  11.3   0.2
            """;

    @Test
    @DisplayName("each hop is read, number, address, loss and time")
    void readsHops() {
        List<ProbeReading.Hop> hops = ProbeReading.hops(TRACE);

        assertEquals(4, hops.size());
        assertEquals("173.212.254.1", hops.get(0).host());
        assertTrue(hops.get(1).silent());
        assertEquals(33.3, hops.get(2).loss(), 0.01);
        assertEquals(11.1, hops.get(3).averageMs(), 0.01);
    }

    @Test
    @DisplayName("a router that keeps quiet, or rations its replies, is not where anything breaks")
    void readsMtrTheWayItHasToBeRead() {
        List<ProbeReading.Hop> hops = ProbeReading.hops(TRACE);

        assertEquals(Verdict.GOOD, ProbeReading.judge(hops, 0));
        assertEquals(Verdict.UNKNOWN, ProbeReading.judge(hops, 1), "silent, but the hops after it answer");
        assertEquals(Verdict.UNKNOWN, ProbeReading.judge(hops, 2), "loses some, but the end loses nothing");
        assertEquals(Verdict.GOOD, ProbeReading.judge(hops, 3));
        assertTrue(ProbeReading.note(hops, 1).contains("traffic goes through"));
    }

    @Test
    @DisplayName("the route stops at the first hop after which nothing answers")
    void findsWhereTheRouteStops() {
        List<ProbeReading.Hop> hops = ProbeReading.hops("""
                  1.|-- 173.212.254.1              0.0%     3    0.4   0.5   0.3   0.7   0.1
                  2.|-- 80.81.192.1                0.0%     3    9.8  10.2   9.8  10.6   0.4
                  3.|-- ???                       100.0     3    0.0   0.0   0.0   0.0   0.0
                  4.|-- ???                       100.0     3    0.0   0.0   0.0   0.0   0.0
                """);

        assertEquals(Verdict.GOOD, ProbeReading.judge(hops, 1));
        assertEquals(Verdict.BAD, ProbeReading.judge(hops, 2));
        assertEquals("Nothing answers from here on: this is where the route stops.", ProbeReading.note(hops, 2));
    }

    @Test
    @DisplayName("loss that lasts to the end is real loss")
    void readsRealLoss() {
        List<ProbeReading.Hop> hops = ProbeReading.hops("""
                  1.|-- 173.212.254.1              0.0%     3    0.4   0.5   0.3   0.7   0.1
                  2.|-- 1.1.1.1                   33.3%     3   11.0  11.1  10.9  11.3   0.2
                """);

        assertEquals(Verdict.SLOW, ProbeReading.judge(hops, 1));
        assertTrue(ProbeReading.note(hops, 1).contains("33% lost"));
    }

    @Test
    @DisplayName("a port check tells a refusal from silence, and a missing name from both: taken from real runs")
    void readsThePortCheck() {
        assertEquals(PortState.OPEN, PortState.parse("EXIT:0"));
        assertEquals(PortState.SILENT, PortState.parse("EXIT:124"));
        assertEquals(PortState.REFUSED, PortState.parse(
                "127.0.0.1: line 1: /dev/tcp/127.0.0.1/1: Connection refused\nEXIT:1"));
        assertEquals(PortState.NO_NAME, PortState.parse(
                "no-such-host.invalid: line 1: no-such-host.invalid: Name or service not known\n"
                        + "no-such-host.invalid: line 1: /dev/tcp/no-such-host.invalid/80: Invalid argument\nEXIT:1"),
                "bash exits 1 for this too, and it means the opposite of a refusal");
        assertEquals(PortState.UNREACHABLE, PortState.parse("x: No route to host\nEXIT:1"));
        assertEquals(PortState.UNKNOWN, PortState.parse("bash: not found"));
    }

    @Test
    @DisplayName("the host and the port reach bash as arguments, never as part of the script")
    void keepsTheTargetOutOfTheScript() {
        String command = ProbeCommands.checkPort("gemmi-e.com", 443);

        assertEquals("timeout 3 bash -c 'exec 3<>\"/dev/tcp/$0/$1\"' 'gemmi-e.com' 443 2>&1; echo \"EXIT:$?\"",
                command);
        assertThrows(IllegalArgumentException.class, () -> ProbeCommands.checkPort("a;b", 443));
        assertThrows(IllegalArgumentException.class, () -> ProbeCommands.checkPort("gemmi-e.com", 70000));
    }
}
