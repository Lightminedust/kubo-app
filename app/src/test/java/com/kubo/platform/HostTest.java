package com.kubo.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which desktop Kubo is running on, and what it asks that desktop to do.
 *
 * <p>The point of these is that the answer is decided by a pure method: the command line can be checked without
 * a file manager, a display, or the system actually being the one under test.
 */
class HostTest {

    @Test
    @DisplayName("every name Java reports for a supported desktop is recognised")
    void readsTheSystemName() {
        assertEquals(Host.Kind.WINDOWS, Host.of("Windows 11"));
        assertEquals(Host.Kind.WINDOWS, Host.of("Windows Server 2022"));
        assertEquals(Host.Kind.LINUX, Host.of("Linux"));
        assertEquals(Host.Kind.MAC, Host.of("Mac OS X"));
        assertEquals(Host.Kind.MAC, Host.of("Darwin"));
    }

    @Test
    @DisplayName("an unknown or missing system name is not mistaken for one Kubo knows")
    void unknownSystemsFallThrough() {
        assertEquals(Host.Kind.OTHER, Host.of("FreeBSD"));
        assertEquals(Host.Kind.OTHER, Host.of("SunOS"));
        assertEquals(Host.Kind.OTHER, Host.of(""));
        assertEquals(Host.Kind.OTHER, Host.of(null));
    }

    @Test
    @DisplayName("the name is read whatever its case")
    void caseDoesNotMatter() {
        assertEquals(Host.Kind.WINDOWS, Host.of("WINDOWS 10"));
        assertEquals(Host.Kind.LINUX, Host.of("LINUX"));
    }

    @Test
    @DisplayName("showing a file asks each desktop in its own language")
    void revealSpeaksEachDesktop() {
        Path file = Path.of("/home/xana/Downloads/kubo.log");

        assertEquals(List.of("explorer.exe", "/select," + file),
                Host.revealCommand(file, Host.Kind.WINDOWS));
        assertEquals(List.of("open", "-R", file.toString()),
                Host.revealCommand(file, Host.Kind.MAC));
    }

    @Test
    @DisplayName("Linux cannot point at the file, so the folder holding it is opened instead")
    void linuxOpensTheFolder() {
        Path file = Path.of("/home/xana/Downloads/kubo.log");

        List<String> command = Host.revealCommand(file, Host.Kind.LINUX);

        assertEquals("xdg-open", command.get(0));
        // The file's own path would be handed to the default application for .log files, which is not what
        // "show in folder" means.
        assertEquals(file.getParent().toString(), command.get(1));
    }

    @Test
    @DisplayName("a file with no folder above it still produces a usable command")
    void aBareNameDoesNotBreakIt() {
        Path file = Path.of("kubo.log");

        List<String> command = Host.revealCommand(file, Host.Kind.LINUX);

        assertEquals(2, command.size());
        assertEquals("kubo.log", command.get(1));
    }

    @Test
    @DisplayName("opening a folder passes the folder itself, on every desktop")
    void openFolderPassesTheFolder() {
        Path folder = Path.of("/home/xana/.kubo");

        for (Host.Kind kind : Host.Kind.values()) {
            List<String> command = Host.openFolderCommand(folder, kind);
            assertEquals(folder.toString(), command.get(command.size() - 1), kind.toString());
        }
    }

    @Test
    @DisplayName("the running system is one of the four, and the shorthand agrees with it")
    void theShorthandAgreesWithTheKind() {
        Host.Kind kind = Host.kind();

        assertTrue(Host.isWindows() == (kind == Host.Kind.WINDOWS));
        assertTrue(Host.isLinux() == (kind == Host.Kind.LINUX));
        assertTrue(Host.isMac() == (kind == Host.Kind.MAC));
    }
}
