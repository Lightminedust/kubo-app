package com.kubo.services;

import com.kubo.model.services.Action;
import com.kubo.model.services.Lifeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of the screen that changes the server.
 *
 * <p>These guard two things above all: that no shape of unit name turns into a second command, and that the
 * one mistake with no way back — cutting the connection you are holding — is refused rather than confirmed.
 */
class ServiceActionTest {

    @Test
    @DisplayName("the verb is one of five fixed words and the unit reaches the shell quoted")
    void commandsAreTemplates() {
        assertEquals("LC_ALL=C systemctl restart 'nginx.service'",
                ServiceCommands.act(Action.RESTART, "nginx.service"));

        String hostile = ServiceCommands.act(Action.STOP, "a'b; rm -rf /");
        assertFalse(hostile.contains("; rm -rf / "), hostile);
        assertTrue(hostile.contains(com.kubo.files.ShellQuote.quote("a'b; rm -rf /")), hostile);
    }

    @Test
    @DisplayName("stopping or disabling the way in is refused outright, not merely confirmed")
    void theDoorIsNotSomethingToBeSureAbout() {
        for (String door : new String[] {"ssh.service", "sshd.service"}) {
            assertNotNull(Lifeline.refusal(Action.STOP, door), door);
            assertNotNull(Lifeline.refusal(Action.DISABLE, door), door);
        }
        // Being sure is not the problem a confirmation solves here: a mis-click is, and there is no way back.
        assertTrue(Lifeline.refusal(Action.STOP, "ssh.service").contains("no way back"));
    }

    @Test
    @DisplayName("taking the network away is refused for the same reason")
    void theNetworkUnderItIsAlsoTheWayIn() {
        assertNotNull(Lifeline.refusal(Action.STOP, "systemd-networkd.service"));
        assertNotNull(Lifeline.refusal(Action.DISABLE, "NetworkManager.service"));
        assertNotNull(Lifeline.refusal(Action.STOP, "networking.service"));
    }

    @Test
    @DisplayName("restarting the way in is allowed: an ssh restart keeps the sessions already open")
    void restartingTheDoorIsAllowed() {
        assertNull(Lifeline.refusal(Action.RESTART, "ssh.service"),
                "sshd keeps established sessions across a restart, so this window survives it");
        assertNull(Lifeline.refusal(Action.START, "ssh.service"));
        assertNull(Lifeline.refusal(Action.ENABLE, "ssh.service"));
    }

    @Test
    @DisplayName("every other service may be stopped: the refusal is for the connection, not for caution")
    void everythingElseIsAllowed() {
        for (Action action : Action.values()) {
            assertNull(Lifeline.refusal(action, "nginx.service"), action.name());
            assertNull(Lifeline.refusal(action, "docker.service"), action.name());
        }
    }

    @Test
    @DisplayName("only the three that take something away ask before they act")
    void addingIsNotTheSameAsTakingAway() {
        assertTrue(Action.STOP.takesAway());
        assertTrue(Action.DISABLE.takesAway());
        assertTrue(Action.RESTART.takesAway());
        assertFalse(Action.START.takesAway());
        assertFalse(Action.ENABLE.takesAway(), "enabling can be undone by disabling; it takes nothing away now");
    }

    @Test
    @DisplayName("a journal reading is pinned, quoted, and never asks for more lines than it can hold")
    void journalIsBounded() {
        String command = ServiceCommands.journal("nginx.service", 200);

        assertTrue(command.startsWith("LC_ALL=C "), command);
        assertTrue(command.contains("--lines 200"), command);
        assertTrue(command.contains(com.kubo.files.ShellQuote.quote("nginx.service")), command);
        assertTrue(ServiceCommands.journal("a.service", 999_999).contains("--lines 2000"),
                "a screen cannot show a million lines, and asking for them costs the server the work anyway");
        assertTrue(ServiceCommands.journal("a.service", -5).contains("--lines 1"));
    }
}
