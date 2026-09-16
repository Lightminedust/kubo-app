package com.kubo.monitor.parse;

import com.kubo.model.server.ServerIdentity;
import com.kubo.model.server.ServerIdentity.UserIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityParserTest {

    private static final String DEBIAN = """
            @@hostname
            vmi2412345
            @@os
            PRETTY_NAME="Debian GNU/Linux 13 (trixie)"
            NAME="Debian GNU/Linux"
            VERSION_ID="13"
            ID=debian
            @@kernel
            6.12.38+deb13-amd64
            @@cpu
            model name\t: AMD EPYC 7282 16-Core Processor
            @@cores
            4
            @@memory
            MemTotal:        8130548 kB
            @@user
            uid=1000(xana) gid=1000(xana) groups=1000(xana),24(cdrom),27(sudo),100(users),988(docker)
            """;

    @Test
    void parsesADebianServer() {
        ServerIdentity identity = IdentityParser.parse(DEBIAN);

        assertEquals("vmi2412345", identity.hostname());
        assertEquals("Debian GNU/Linux 13 (trixie)", identity.osRelease());
        assertEquals("6.12.38+deb13-amd64", identity.kernelVersion());
        assertEquals("AMD EPYC 7282 16-Core Processor", identity.cpuModel());
        assertEquals(4, identity.cores());
        assertEquals(8130548L * 1024, identity.totalMemoryBytes());
        assertEquals(new UserIdentity("xana", false, true, true), identity.user());
    }

    @Test
    void cpuWithoutModelNameIsUnknown() {
        ServerIdentity identity = IdentityParser.parse(DEBIAN.replace("model name\t: AMD EPYC 7282 16-Core Processor\n", ""));

        assertEquals("Unknown CPU", identity.cpuModel());
    }

    @Test
    void osWithoutPrettyNameFallsBackToName() {
        ServerIdentity identity = IdentityParser.parse(DEBIAN.replace("PRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\"\n", ""));

        assertEquals("Debian GNU/Linux", identity.osRelease());
    }

    @Test
    void rootUserIsDetected() {
        UserIdentity user = IdentityParser.user("uid=0(root) gid=0(root) groups=0(root)");

        assertTrue(user.isRoot());
        assertFalse(user.inSudoGroup());
        assertFalse(user.inDockerGroup());
    }

    @Test
    void wheelCountsAsSudo() {
        assertTrue(IdentityParser.user("uid=1000(ops) gid=1000(ops) groups=1000(ops),10(wheel)").inSudoGroup());
    }

    @Test
    void groupNamesAreMatchedExactly() {
        UserIdentity user = IdentityParser.user("uid=1001(dev) gid=1001(dev) groups=1001(dev),999(dockerusers)");

        assertFalse(user.inDockerGroup());
    }

    @Test
    void failsWhenEssentialSectionsAreMissing() {
        assertThrows(IllegalArgumentException.class, () -> IdentityParser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> IdentityParser.parse(DEBIAN.replace("@@cores\n4\n", "@@cores\n")));
        assertThrows(IllegalArgumentException.class, () -> IdentityParser.parse(DEBIAN.replace("uid=1000", "user=1000")));
        assertThrows(IllegalArgumentException.class, () -> IdentityParser.parse(DEBIAN.replace("@@cores\n4", "@@cores\nfour")));
    }
}
