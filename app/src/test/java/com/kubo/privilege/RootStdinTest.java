package com.kubo.privilege;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Running a command as root when that command reads its own data from standard input.
 *
 * <p>This is the one place where getting it wrong is silently destructive: the sudo password and the file's bytes
 * travel on the same stream, so whether sudo eats a password line has to be certain. If it does not, the password
 * is written into the user's file.
 */
class RootStdinTest {

    private static Privilege withPassword(RootAccess access, String password) {
        Privilege privilege = new Privilege(access);
        privilege.setPassword(password.toCharArray());
        return privilege;
    }

    @Test
    @DisplayName("a command that reads stdin always forces sudo to prompt, so exactly one password line is eaten")
    void alwaysPromptsSoTheCountIsCertain() {
        Privilege privilege = withPassword(RootAccess.SUDO_PASSWORD, "hunter2");

        Privilege.Command command = privilege.asRootReadingInput("cat > /etc/nginx/kubo.conf");

        // -k drops any cached sudo ticket: without it, a cached ticket means sudo reads nothing and the password
        // line lands in the file being written.
        assertTrue(command.command().contains("sudo -k -S -p ''"), command.command());
        assertTrue(command.command().contains("cat > /etc/nginx/kubo.conf"), command.command());
        assertArrayEquals("hunter2\n".getBytes(StandardCharsets.UTF_8), command.input());
    }

    @Test
    @DisplayName("the ordinary wrapper still lets sudo cache, because nothing else shares its stdin")
    void theOrdinaryWrapperKeepsTheCache() {
        Privilege privilege = withPassword(RootAccess.SUDO_PASSWORD, "hunter2");

        assertFalse(privilege.asRoot("ls /etc").command().contains(" -k "),
                "a command with no input of its own has no reason to drop the ticket every time");
    }

    @Test
    @DisplayName("no password is sent when none is needed, so nothing can leak into the data")
    void nothingIsPrependedWhenNoPasswordIsInvolved() {
        // Already root: the command runs as-is.
        Privilege asRoot = new Privilege(RootAccess.ROOT);
        Privilege.Command direct = asRoot.asRootReadingInput("cat > /etc/hosts");
        assertEquals("cat > /etc/hosts", direct.command());
        assertNull(direct.input(), "a password line here would be written straight into the file");

        // sudo without a password: -n never reads stdin at all.
        Privilege nopasswd = new Privilege(RootAccess.SUDO_NOPASSWD);
        Privilege.Command sudo = nopasswd.asRootReadingInput("cat > /etc/hosts");
        assertTrue(sudo.command().startsWith("sudo -n"), sudo.command());
        assertNull(sudo.input(), "a password line here would be written straight into the file");
    }

    @Test
    @DisplayName("a user who cannot become root is refused, and a missing password is refused")
    void refusedWhenRootIsImpossibleOrUnprepared() {
        assertThrows(IllegalStateException.class,
                () -> new Privilege(RootAccess.NONE).asRootReadingInput("cat > /etc/hosts"));

        assertThrows(IllegalStateException.class,
                () -> new Privilege(RootAccess.SUDO_PASSWORD).asRootReadingInput("cat > /etc/hosts"),
                "no password set yet");
    }

    @Test
    @DisplayName("the command itself is quoted, so a path can never break out of the wrapper")
    void thePathCannotEscapeTheWrapper() {
        Privilege privilege = withPassword(RootAccess.SUDO_PASSWORD, "pw");
        String payload = "cat > '/etc/nginx/a'; rm -rf /; echo '";

        String command = privilege.asRootReadingInput(payload).command();

        // The whole command must reach sh as one quoted argument, never interpolated into the wrapper.
        assertEquals("sudo -k -S -p '' -- /bin/sh -c " + com.kubo.files.ShellQuote.quote(payload), command);
    }

    @Test
    @DisplayName("wiping the password makes the wrapper refuse again, rather than sending an empty line")
    void wipingDisarmsIt() {
        Privilege privilege = withPassword(RootAccess.SUDO_PASSWORD, "pw");
        privilege.wipe();

        assertTrue(privilege.needsPassword());
        assertThrows(IllegalStateException.class, () -> privilege.asRootReadingInput("cat > /etc/hosts"));
    }
}
