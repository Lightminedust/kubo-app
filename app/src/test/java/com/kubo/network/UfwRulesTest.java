package com.kubo.network;

import com.kubo.model.network.Firewall;
import com.kubo.model.network.FirewallRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The UFW rules of a real server, read the way somebody deciding what to delete needs them.
 */
class UfwRulesTest {

    /** What the new command prints: the defaults line, then the numbered rules. Taken from a real server. */
    private static final String OUTPUT = """
            Default: deny (incoming), allow (outgoing), disabled (routed)
            Status: active

                 To                         Action      From
                 --                         ------      ----
            [ 1] OpenSSH                    ALLOW IN    Anywhere
            [ 2] 80                         ALLOW IN    Anywhere
            [ 3] 443                        ALLOW IN    Anywhere
            [ 4] Nginx Full                 ALLOW IN    Anywhere
            [ 5] 3080                       DENY IN     Anywhere
            [ 6] 22/tcp                     ALLOW IN    Anywhere
            [ 7] OpenSSH (v6)               ALLOW IN    Anywhere (v6)
            [ 8] 80 (v6)                    ALLOW IN    Anywhere (v6)
            [ 9] 443 (v6)                   ALLOW IN    Anywhere (v6)
            [10] Nginx Full (v6)            ALLOW IN    Anywhere (v6)
            [11] 3080 (v6)                  DENY IN     Anywhere (v6)
            """;

    private static Firewall firewall() {
        return FirewallCommands.parseUfw(OUTPUT);
    }

    private static Firewall.RuleGroup group(String to) {
        return firewall().grouped().stream().filter(group -> group.rule().to().equals(to)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the default policy is read, which is the answer for every port no rule names")
    void readsTheDefaults() {
        Firewall.Defaults defaults = firewall().defaults();

        assertEquals("deny", defaults.incoming());
        assertEquals("allow", defaults.outgoing());
        assertEquals("disabled", defaults.routed());
        assertFalse(defaults.letsEverythingIn());
    }

    @Test
    @DisplayName("an application profile is known by its ports, so OpenSSH is known to be 22")
    void knowsTheProfiles() {
        FirewallRule openSsh = firewall().rules().get(0);
        FirewallRule nginx = firewall().rules().get(3);

        assertEquals(List.of(22), openSsh.ports());
        assertEquals(List.of(80, 443), nginx.ports());
        assertEquals(List.of(22), firewall().rules().get(6).ports(), "the IPv6 half too");
    }

    @Test
    @DisplayName("eleven lines are six rules once each IPv4 rule is paired with its IPv6 twin")
    void pairsTheFamilies() {
        List<Firewall.RuleGroup> groups = firewall().grouped();

        assertEquals(6, groups.size());
        assertEquals("v4 + v6", group("OpenSSH").families());
        assertEquals(List.of(7, 1), group("OpenSSH").numbers(), "highest first: UFW renumbers after each delete");
        assertEquals("v4 only", group("22/tcp").families());
        assertEquals(List.of(11, 5), group("3080").numbers());
    }

    @Test
    @DisplayName("deleting OpenSSH is safe for Kubo while 22/tcp still lets it in, and IPv6 loses it")
    void deletingOneOfTwoSshRules() {
        Firewall.Lockout lockout = firewall().ifDeleted(group("OpenSSH"), 22);

        assertTrue(lockout.risky(), "22/tcp has no IPv6 twin, so IPv6 loses SSH");
        assertTrue(lockout.reason().contains("IPv6"));
        assertTrue(lockout.reason().contains("Kubo is not affected"));
    }

    @Test
    @DisplayName("deleting 22/tcp is safe: OpenSSH still lets 22 through, on both families")
    void deletingTheSpareSshRule() {
        Firewall.Lockout lockout = firewall().ifDeleted(group("22/tcp"), 22);

        assertFalse(lockout.risky());
        assertEquals("OpenSSH still lets 22 through, so SSH stays open.", lockout.reason());
    }

    @Test
    @DisplayName("deleting the only rule for the port Kubo is connected through says so before it happens")
    void deletingTheLastSshRule() {
        String only = """
                Default: deny (incoming), allow (outgoing), disabled (routed)
                Status: active
                [ 1] 22/tcp                     ALLOW IN    Anywhere
                [ 2] 80                         ALLOW IN    Anywhere
                """;
        Firewall firewall = FirewallCommands.parseUfw(only);

        Firewall.Lockout lockout = firewall.ifDeleted(firewall.grouped().get(0), 22);

        assertTrue(lockout.risky());
        assertTrue(lockout.reason().contains("Kubo's included"));
        assertTrue(lockout.reason().contains("provider's console"));
    }

    @Test
    @DisplayName("a rule that has nothing to do with SSH, or a deny, is never a lockout")
    void otherRulesAreSafe() {
        assertFalse(firewall().ifDeleted(group("80"), 22).risky());
        assertFalse(firewall().ifDeleted(group("3080"), 22).risky());
        assertEquals("", firewall().ifDeleted(group("80"), 22).reason());
    }

    @Test
    @DisplayName("Kubo on another SSH port checks that port, not 22")
    void usesTheRealSshPort() {
        assertFalse(firewall().ifDeleted(group("OpenSSH"), 2222).risky(),
                "OpenSSH is 22; a server on 2222 does not depend on it");
    }

    @Test
    @DisplayName("when everything not listed is let in anyway, deleting an SSH rule locks nobody out")
    void letsEverythingIn() {
        String open = """
                Default: allow (incoming), allow (outgoing), disabled (routed)
                Status: active
                [ 1] 22/tcp                     ALLOW IN    Anywhere
                """;
        Firewall firewall = FirewallCommands.parseUfw(open);

        Firewall.Lockout lockout = firewall.ifDeleted(firewall.grouped().get(0), 22);

        assertFalse(lockout.risky());
        assertTrue(lockout.reason().contains("let in anyway"));
        assertFalse(firewall.blocks(3306), "a port no rule names is not blocked when the default lets it in");
    }

    @Test
    @DisplayName("without the defaults line, the rules still read and the defaults say they are unknown")
    void survivesTheOldCommand() {
        Firewall firewall = FirewallCommands.parseUfw(OUTPUT.lines().skip(1).reduce("", (a, b) -> a + b + "\n"));

        assertEquals(11, firewall.rules().size());
        assertFalse(firewall.defaults().known());
        assertTrue(firewall.blocks(3306), "unknown defaults are taken as deny, as before");
    }
    @Test
    @DisplayName("two rules are deleted highest number first, or the second number means another rule")
    void deletesHighestFirst() {
        assertEquals("ufw --force delete 11 && ufw --force delete 5",
                NetworkCommands.ufwDeleteRules(List.of(5, 11)));
        assertEquals("ufw --force delete 7 && ufw --force delete 1",
                NetworkCommands.ufwDeleteRules(group("OpenSSH").numbers()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> NetworkCommands.ufwDeleteRules(List.of()));
    }

    @Test
    @DisplayName("a refusal goes to one address, or to everyone when no address is given")
    void refusesFromOneOrAll() {
        assertEquals("ufw deny from '203.0.113.9' to any port 5432 proto tcp",
                NetworkCommands.ufwDenyFrom("203.0.113.9", 5432,
                        com.kubo.model.server.ListeningPort.Protocol.TCP));
        assertEquals("ufw deny 3080/tcp", NetworkCommands.ufwDenyFrom("", 3080,
                com.kubo.model.server.ListeningPort.Protocol.TCP));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> NetworkCommands.ufwDenyFrom("x; rm -rf /", 22, com.kubo.model.server.ListeningPort.Protocol.TCP));
    }
}
