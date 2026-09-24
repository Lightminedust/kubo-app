package com.kubo.network;

import com.kubo.model.network.Firewall;
import com.kubo.model.network.FirewallRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Switching the whole firewall on and off, and the two different ways that goes wrong.
 *
 * <p>Off is dangerous because it opens everything at once. On is dangerous because it can close the way back in
 * and only shows it at the next connection, long after the click.
 */
class FirewallSwitchTest {

    private static final Firewall.Defaults DENY_IN = new Firewall.Defaults("deny", "allow", "disabled");
    private static final Firewall.Defaults ALLOW_IN = new Firewall.Defaults("allow", "allow", "disabled");

    private static FirewallRule allows(int port) {
        return new FirewallRule(1, port + "/tcp", FirewallRule.Action.ALLOW, "Anywhere", false, List.of(port));
    }

    private static Firewall firewall(Firewall.State state, Firewall.Defaults defaults, FirewallRule... rules) {
        return new Firewall(Firewall.Kind.UFW, state, List.of(rules), defaults);
    }

    @Test
    @DisplayName("switching off a firewall that is enforcing always asks, and says what it opens")
    void switchingOffAlwaysAsks() {
        Firewall on = firewall(Firewall.State.ACTIVE, DENY_IN, allows(22), allows(443));

        Firewall.Lockout warning = on.ifSwitchedOff();

        assertTrue(warning.risky());
        assertTrue(warning.reason().contains("Every port"), warning.reason());
        assertTrue(warning.reason().contains("2 rules stay written down"), warning.reason());
    }

    @Test
    @DisplayName("switching off one that is already off has nothing to warn about")
    void switchingOffWhatIsOffIsNothing() {
        assertFalse(firewall(Firewall.State.INACTIVE, DENY_IN).ifSwitchedOff().risky());
    }

    @Test
    @DisplayName("switching on is refused without a second look when nothing lets SSH through")
    void switchingOnWithoutSshAsks() {
        Firewall off = firewall(Firewall.State.INACTIVE, DENY_IN, allows(443));

        Firewall.Lockout warning = off.ifSwitchedOn(22);

        assertTrue(warning.risky());
        assertTrue(warning.reason().contains("port 22"), warning.reason());
        assertTrue(warning.reason().contains("next SSH connection will be refused"), warning.reason());
    }

    @Test
    @DisplayName("switching on is quiet when a rule already lets SSH through")
    void switchingOnWithSshIsFine() {
        Firewall off = firewall(Firewall.State.INACTIVE, DENY_IN, allows(22), allows(443));

        assertFalse(off.ifSwitchedOn(22).risky());
    }

    @Test
    @DisplayName("the real SSH port is used, not a guess at 22")
    void usesTheRealSshPort() {
        Firewall off = firewall(Firewall.State.INACTIVE, DENY_IN, allows(2222));

        assertFalse(off.ifSwitchedOn(2222).risky(), "2222 is allowed and is the port in use");
        assertTrue(off.ifSwitchedOn(22).risky(), "22 is not allowed, and nothing came in on it");
    }

    @Test
    @DisplayName("a default that lets everything in keeps SSH open whatever the rules say")
    void aDefaultAllowKeepsItOpen() {
        Firewall.Lockout warning = firewall(Firewall.State.INACTIVE, ALLOW_IN, allows(443)).ifSwitchedOn(22);

        assertFalse(warning.risky());
        assertTrue(warning.reason().contains("stays open"), warning.reason());
    }

    @Test
    @DisplayName("a firewall switched off shows no rules, and that is said rather than read as none")
    void cannotPromiseWhatItCannotRead() {
        Firewall.Lockout warning = firewall(Firewall.State.INACTIVE, DENY_IN).ifSwitchedOn(22);

        assertTrue(warning.risky());
        assertTrue(warning.reason().contains("cannot read the rules"), warning.reason());
    }

    @Test
    @DisplayName("switching on one that is already on has nothing to warn about")
    void switchingOnWhatIsOnIsNothing() {
        assertFalse(firewall(Firewall.State.ACTIVE, DENY_IN, allows(22)).ifSwitchedOn(22).risky());
    }

    @Test
    @DisplayName("only the two firewalls with one switch each can be switched")
    void onlySwitchesWhatItCanFind() {
        assertTrue(new Firewall(Firewall.Kind.UFW, Firewall.State.ACTIVE, List.of()).canBeSwitched());
        assertTrue(new Firewall(Firewall.Kind.FIREWALLD, Firewall.State.ACTIVE, List.of()).canBeSwitched());
        assertFalse(new Firewall(Firewall.Kind.NFTABLES, Firewall.State.ACTIVE, List.of()).canBeSwitched());
        assertFalse(new Firewall(Firewall.Kind.IPTABLES, Firewall.State.ACTIVE, List.of()).canBeSwitched());
        assertFalse(Firewall.absent().canBeSwitched());
    }

    @Test
    @DisplayName("the commands are fixed text, and firewalld's also survives a reboot")
    void writesTheRightCommands() {
        assertEquals("ufw --force enable", FirewallCommands.switchOn(Firewall.Kind.UFW));
        assertEquals("ufw disable", FirewallCommands.switchOff(Firewall.Kind.UFW));
        assertEquals("systemctl enable --now firewalld", FirewallCommands.switchOn(Firewall.Kind.FIREWALLD));
        assertEquals("systemctl disable --now firewalld", FirewallCommands.switchOff(Firewall.Kind.FIREWALLD));

        // --force is not carelessness: it answers UFW's own prompt, which a session with no terminal cannot,
        // and the question it skips is asked by ifSwitchedOn instead.
        assertTrue(FirewallCommands.switchOn(Firewall.Kind.UFW).contains("--force"));
        assertFalse(FirewallCommands.switchOff(Firewall.Kind.UFW).contains("--force"),
                "disable asks nothing, so there is nothing to force past");
    }

    @Test
    @DisplayName("a firewall with no switch is refused rather than sent a command meant for another")
    void refusesWhatItCannotSwitch() {
        for (Firewall.Kind kind : List.of(Firewall.Kind.NFTABLES, Firewall.Kind.IPTABLES, Firewall.Kind.NONE)) {
            assertThrows(IllegalArgumentException.class, () -> FirewallCommands.switchOn(kind));
            assertThrows(IllegalArgumentException.class, () -> FirewallCommands.switchOff(kind));
        }
    }
}
