package com.kubo.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The local console Kubo offers beside the server's.
 *
 * <p>The thing worth guarding here is that a typed entry stays one argument. If it were ever pasted into a
 * longer command line, a line beginning with a dash would be read as an option to the shell rather than as
 * something to run.
 */
class LocalShellTest {

    @Test
    @DisplayName("what the user types is passed as a single argument, never spliced into the command line")
    void theEntryIsOneArgument() {
        String entry = "-NoExit; Remove-Item C:\\ -Recurse";

        List<String> windows = LocalShell.command(entry, Host.Kind.WINDOWS);
        List<String> linux = LocalShell.command(entry, Host.Kind.LINUX);

        // On Windows the encoding prelude is prepended, so the entry is the tail of the last argument.
        assertTrue(windows.get(windows.size() - 1).endsWith(entry), windows.toString());
        assertEquals(entry, linux.get(linux.size() - 1));
        assertEquals(3, linux.size(), "sh, -c, and the entry — nothing else");
    }

    @Test
    @DisplayName("Windows gets PowerShell with no profile, and output forced to UTF-8")
    void windowsRunsPowerShell() {
        List<String> command = LocalShell.command("Get-Date", Host.Kind.WINDOWS);

        assertEquals("powershell.exe", command.get(0));
        assertTrue(command.contains("-NoProfile"), command.toString());
        assertTrue(command.contains("-NonInteractive"), command.toString());
        // Without this the console uses the machine's code page and mangles anything outside it.
        assertTrue(command.get(command.size() - 1).startsWith("[Console]::OutputEncoding="), command.toString());
    }

    @Test
    @DisplayName("every other system gets /bin/sh, which they all have and which behaves the same on all of them")
    void othersRunBinSh() {
        for (Host.Kind kind : List.of(Host.Kind.LINUX, Host.Kind.MAC, Host.Kind.OTHER)) {
            List<String> command = LocalShell.command("uname -a", kind);
            assertEquals(List.of("/bin/sh", "-c", "uname -a"), command, kind.toString());
        }
    }

    @Test
    @DisplayName("the console is never called PowerShell on a system that has none")
    void theNameTellsTheTruth() {
        assertEquals("POWERSHELL", LocalShell.name(Host.Kind.WINDOWS));
        assertEquals("PowerShell", LocalShell.title(Host.Kind.WINDOWS));

        for (Host.Kind kind : List.of(Host.Kind.LINUX, Host.Kind.MAC, Host.Kind.OTHER)) {
            assertEquals("SHELL", LocalShell.name(kind), kind.toString());
            assertEquals("Shell", LocalShell.title(kind), kind.toString());
            assertFalse(LocalShell.prompt(kind).contains("PowerShell"), kind.toString());
            assertFalse(LocalShell.prompt(kind).contains("Windows"), kind.toString());
        }
    }

    @Test
    @DisplayName("the prompt sign is short enough to sit in front of the input line")
    void theSignIsShort() {
        for (Host.Kind kind : Host.Kind.values()) {
            assertTrue(LocalShell.sign(kind).length() <= 3, kind + " -> " + LocalShell.sign(kind));
        }
    }

    @Test
    @DisplayName("the no-argument methods answer for the system actually running the tests")
    void theShorthandMatchesThisSystem() {
        assertEquals(LocalShell.name(Host.kind()), LocalShell.name());
        assertEquals(LocalShell.title(Host.kind()), LocalShell.title());
        assertEquals(LocalShell.sign(Host.kind()), LocalShell.sign());
        assertEquals(LocalShell.prompt(Host.kind()), LocalShell.prompt());
        assertEquals(LocalShell.command("id", Host.kind()), LocalShell.command("id"));
    }
}
