package com.kubo.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The probes take a target typed by the operator, so what they refuse matters more than what they build.
 */
class ProbeCommandsTest {

    @Test
    @DisplayName("an address or a host name is accepted, and quoted")
    void acceptsRealTargets() {
        assertEquals("ping -c 3 -W 2 '173.212.254.1'", ProbeCommands.ping("173.212.254.1"));
        assertEquals("whois '45.135.232.17'", ProbeCommands.whois("45.135.232.17"));
        assertEquals("mtr -r -n -c 3 'gemmi-e.com'", ProbeCommands.route("gemmi-e.com"));
        assertEquals("dig +time=2 +tries=1 @1.1.1.1 'gemmi-e.com'",
                ProbeCommands.dig("gemmi-e.com", "1.1.1.1"));
        assertEquals("dig +time=2 +tries=1 'gemmi-e.com'", ProbeCommands.dig("gemmi-e.com", ""));
        assertEquals("ping -c 3 -W 2 '2a02:c207::1'", ProbeCommands.ping("2a02:c207::1"));
    }

    @Test
    @DisplayName("nothing that is not a target reaches a command")
    void refusesEverythingElse() {
        for (String bad : List.of("1.2.3.4; rm -rf /", "$(id)", "`id`", "a b", "", "   ",
                "--help", "host/../etc", "\"; cat /etc/shadow; \"")) {
            assertThrows(IllegalArgumentException.class, () -> ProbeCommands.ping(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> ProbeCommands.whois(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> ProbeCommands.route(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> ProbeCommands.dig(bad, ""), bad);
        }
        // The resolver is a target too, and it was the easiest one to forget.
        assertThrows(IllegalArgumentException.class, () -> ProbeCommands.dig("gemmi-e.com", "1.1.1.1; id"));
    }

    @Test
    @DisplayName("every probe ends on its own, because nobody here can stop one that does not")
    void everyProbeIsBounded() {
        assertTrue(ProbeCommands.ping("1.1.1.1").contains("-c 3"), "a ping with no count never returns");
        assertTrue(ProbeCommands.ping("1.1.1.1").contains("-W 2"), "and one with no timeout hangs on silence");
        assertTrue(ProbeCommands.route("1.1.1.1").contains("-c 3"));
        assertTrue(ProbeCommands.route("1.1.1.1").contains("-r"), "a report, not a full screen tool");
        assertTrue(ProbeCommands.dig("a.com", "").contains("+tries=1"));
        assertTrue(ProbeCommands.bandwidthByProgram("eth0").contains("-c 4"));
        assertTrue(ProbeCommands.bandwidthByProgram("eth0").contains("-t"), "lines, not a full screen tool");
    }

    @Test
    @DisplayName("an interface name is checked the same way")
    void checksInterfaceNames() {
        assertEquals("nethogs -t -c 4 'eth0'", ProbeCommands.bandwidthByProgram("eth0"));
        assertEquals("nethogs -t -c 4 'br-41317674355a'", ProbeCommands.bandwidthByProgram("br-41317674355a"));
        for (String bad : List.of("eth0; id", "", "../dev", "a b")) {
            assertThrows(IllegalArgumentException.class, () -> ProbeCommands.bandwidthByProgram(bad), bad);
        }
    }
}
