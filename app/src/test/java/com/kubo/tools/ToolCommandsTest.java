package com.kubo.tools;

import com.kubo.model.tools.PackageManager;
import com.kubo.model.tools.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding the helper tools, and the one command that installs one.
 *
 * <p>This is the only place in Kubo that adds something to a server, so what it refuses matters most.
 */
class ToolCommandsTest {

    @Test
    @DisplayName("what is installed and what installs the rest come out of one answer")
    void readsBoth() {
        String output = """
                @@tools
                lsof
                ping
                whois
                @@manager
                apt-get
                """;

        assertEquals(Set.of(Tool.LSOF, Tool.PING, Tool.WHOIS), ToolCommands.parseTools(output));
        assertEquals(PackageManager.APT, ToolCommands.parseManager(output));
    }

    @Test
    @DisplayName("a server with nothing installed says so, and is not mistaken for a broken read")
    void readsAnEmptyServer() {
        String output = "@@tools\n@@manager\ndnf\n";

        assertTrue(ToolCommands.parseTools(output).isEmpty());
        assertEquals(PackageManager.DNF, ToolCommands.parseManager(output));
    }

    @Test
    @DisplayName("a system with no package manager Kubo drives gives NONE rather than a guess")
    void readsNoManager() {
        assertEquals(PackageManager.NONE, ToolCommands.parseManager("@@tools\nlsof\n@@manager\n"));
        assertFalse(PackageManager.NONE.known());
    }

    @Test
    @DisplayName("the detect command never fails on its own last lookup")
    void detectAlwaysAnswers() {
        assertTrue(ToolCommands.DETECT.endsWith("true"),
                "the exit code of the last command -v says nothing about whether the answer is usable");
        assertTrue(ToolCommands.DETECT.contains("command -v lsof"));
        assertTrue(ToolCommands.DETECT.contains("command -v pacman"));
    }

    @Test
    @DisplayName("each family gets its own package name for the same tool")
    void knowsThePackageNames() {
        assertEquals("bind9-dnsutils", Tool.DIG.packageFor(PackageManager.APT));
        assertEquals("bind-utils", Tool.DIG.packageFor(PackageManager.DNF));
        assertEquals("bind", Tool.DIG.packageFor(PackageManager.PACMAN));
        assertEquals("net-tools", Tool.NETSTAT.packageFor(PackageManager.APT));
        assertEquals("mtr-tiny", Tool.MTR.packageFor(PackageManager.APT));
        assertEquals("mtr", Tool.MTR.packageFor(PackageManager.DNF));
    }

    @Test
    @DisplayName("every tool has a package on every manager Kubo claims to drive")
    void nothingIsHalfKnown() {
        for (Tool tool : Tool.values()) {
            for (PackageManager manager : PackageManager.values()) {
                if (manager.known()) {
                    assertFalse(tool.packageFor(manager).isBlank(),
                            tool.binary() + " has no package for " + manager.binary());
                }
            }
        }
    }

    @Test
    @DisplayName("the install command is non-interactive, because nobody can answer a prompt here")
    void installsWithoutAsking() {
        assertEquals("DEBIAN_FRONTEND=noninteractive apt-get install -y lsof"
                        + " || { apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y lsof; }",
                ToolCommands.install(Tool.LSOF, PackageManager.APT));
        assertEquals("dnf install -y conntrack-tools",
                ToolCommands.install(Tool.CONNTRACK, PackageManager.DNF));
        assertEquals("pacman -S --noconfirm --needed iputils",
                ToolCommands.install(Tool.PING, PackageManager.PACMAN));
        assertEquals("apk add --no-cache iftop", ToolCommands.install(Tool.IFTOP, PackageManager.APK));
    }

    @Test
    @DisplayName("with no package manager, nothing is installed and nothing is invented")
    void refusesWithoutAManager() {
        assertThrows(IllegalArgumentException.class, () -> ToolCommands.install(Tool.LSOF, PackageManager.NONE));
        assertThrows(IllegalStateException.class, () -> PackageManager.NONE.install("lsof"));
    }

    @Test
    @DisplayName("every tool says what it unlocks, because a list of package names explains nothing")
    void eachToolIsExplained() {
        for (Tool tool : Tool.values()) {
            assertFalse(tool.unlocks().isBlank(), tool.binary() + " says nothing about what it is for");
            assertTrue(tool.unlocks().endsWith("."), tool.binary() + " should read as a sentence");
        }
        assertEquals(3, Tool.of(Tool.Group.NAMING).size());
        assertEquals(3, Tool.of(Tool.Group.REACH).size());
    }
}
