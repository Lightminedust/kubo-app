package com.kubo.privilege;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivilegeTest {

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("root being possible is not the same as root being ready")
    void availableDoesNotMeanUsable() {
        Privilege privilege = new Privilege(RootAccess.SUDO_PASSWORD);

        // The state every screen is in the moment it opens. Anything deciding whether to wrap a command as root has
        // to ask both questions: asking only available() and then calling asRoot() throws, which is how a whole
        // network read once died on a server where sudo needs a password.
        org.junit.jupiter.api.Assertions.assertTrue(privilege.available());
        org.junit.jupiter.api.Assertions.assertTrue(privilege.needsPassword());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> privilege.asRoot("ufw status numbered"));
    }

    @Test
    void detectionReadsEachLevel() {
        assertEquals(RootAccess.ROOT, RootAccess.parse("ROOT\n"));
        assertEquals(RootAccess.SUDO_NOPASSWD, RootAccess.parse("SUDO_NOPASSWD"));
        assertEquals(RootAccess.SUDO_PASSWORD, RootAccess.parse("SUDO_PASSWORD"));
        assertEquals(RootAccess.NONE, RootAccess.parse("anything else"));
        assertTrue(RootAccess.DETECT.contains("sudo -n true"));
    }

    @Test
    void rootRunsTheCommandUnchanged() {
        Privilege privilege = new Privilege(RootAccess.ROOT);
        Privilege.Command command = privilege.asRoot("docker ps");

        assertEquals("docker ps", command.command());
        assertNull(command.input());
        assertFalse(privilege.needsPassword());
    }

    @Test
    void passwordlessSudoWrapsWithoutInput() {
        Privilege privilege = new Privilege(RootAccess.SUDO_NOPASSWD);
        Privilege.Command command = privilege.asRoot("docker ps");

        assertEquals("sudo -n -- /bin/sh -c 'docker ps'", command.command());
        assertNull(command.input());
        assertFalse(privilege.needsPassword());
    }

    @Test
    void passwordSudoNeedsThePasswordThenSendsItOnStdin() {
        Privilege privilege = new Privilege(RootAccess.SUDO_PASSWORD);

        assertTrue(privilege.needsPassword());
        assertThrows(IllegalStateException.class, () -> privilege.asRoot("docker ps"));

        privilege.setPassword("hunter2".toCharArray());
        Privilege.Command command = privilege.asRoot("docker ps");

        assertEquals("sudo -S -p '' -- /bin/sh -c 'docker ps'", command.command());
        assertArrayEquals("hunter2\n".getBytes(StandardCharsets.UTF_8), command.input());
        assertFalse(privilege.needsPassword());
    }

    @Test
    void aCommandThatNeedsRootIsRefusedWhenThereIsNoRoot() {
        Privilege privilege = new Privilege(RootAccess.NONE);

        assertFalse(privilege.available());
        assertThrows(IllegalStateException.class, () -> privilege.asRoot("docker ps"));
    }

    @Test
    void theStoredPasswordCanBeWiped() {
        Privilege privilege = new Privilege(RootAccess.SUDO_PASSWORD);
        privilege.setPassword("hunter2".toCharArray());
        assertTrue(privilege.hasPassword());

        privilege.wipe();

        assertFalse(privilege.hasPassword());
        assertTrue(privilege.needsPassword());
    }

    @Test
    void theCallersPasswordCopyIsIndependent() {
        Privilege privilege = new Privilege(RootAccess.SUDO_PASSWORD);
        char[] typed = "hunter2".toCharArray();
        privilege.setPassword(typed);
        java.util.Arrays.fill(typed, '\0');

        assertArrayEquals("hunter2\n".getBytes(StandardCharsets.UTF_8), privilege.asRoot("x").input());
    }
}
