package com.kubo.services;

import com.kubo.model.services.Lifeline;
import com.kubo.model.services.Resource;
import com.kubo.model.services.Signal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holding a service back, signalling it, and giving it settings of its own.
 *
 * <p>These three take a value or a file from a person rather than only a unit name, which makes them the
 * widest part of this screen. What they guard is that a typed value is checked against what systemd takes
 * before it is sent, that a file's content never travels in a command line, and that nothing reaches a path
 * without being the shape of a unit name first.
 */
class ServiceLimitTest {

    @Test
    @DisplayName("a limit systemd would take is sent, quoted, with the property from a fixed list")
    void limitsAreSent() {
        assertEquals("LC_ALL=C systemctl set-property 'nginx.service' 'MemoryMax=512M'",
                ServiceCommands.setLimit(Resource.MEMORY_MAX, "512M", "nginx.service"));
        assertEquals("LC_ALL=C systemctl set-property 'nginx.service' 'CPUQuota=50%'",
                ServiceCommands.setLimit(Resource.CPU_QUOTA, "50%", "nginx.service"));
        assertEquals("LC_ALL=C systemctl set-property 'nginx.service' 'MemoryMax=infinity'",
                ServiceCommands.setLimit(Resource.MEMORY_MAX, " infinity ", "nginx.service"),
                "the value is trimmed before it is judged");
    }

    @Test
    @DisplayName("a value systemd would not take is refused here, rather than sent and argued about")
    void nonsenseIsRefused() {
        assertTrue(Resource.MEMORY_MAX.accepts("2G"));
        assertTrue(Resource.MEMORY_MAX.accepts("1.5G"));
        assertFalse(Resource.MEMORY_MAX.accepts("512 megabytes"));
        assertFalse(Resource.CPU_QUOTA.accepts("50"), "systemd wants the per cent sign");
        assertFalse(Resource.CPU_QUOTA.accepts(""));

        assertThrows(IllegalArgumentException.class,
                () -> ServiceCommands.setLimit(Resource.MEMORY_MAX, "512M; rm -rf /", "nginx.service"));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceCommands.setLimit(Resource.CPU_QUOTA, "$(whoami)", "nginx.service"));
    }

    @Test
    @DisplayName("a signal is one of five fixed words, to the one process or to the whole group")
    void signalsAreSent() {
        assertEquals("LC_ALL=C systemctl kill --signal=SIGHUP --kill-who=main 'nginx.service'",
                ServiceCommands.signal(Signal.HUP, false, "nginx.service"));
        assertEquals("LC_ALL=C systemctl kill --signal=SIGKILL --kill-who=all 'nginx.service'",
                ServiceCommands.signal(Signal.KILL, true, "nginx.service"));
    }

    @Test
    @DisplayName("a signal that ends the way in is refused, exactly as stopping it is")
    void theDoorIsGuardedAgainstSignalsToo() {
        assertNotNull(Lifeline.refusal(Signal.KILL, "ssh.service"));
        assertNotNull(Lifeline.refusal(Signal.TERM, "sshd.service"));
        assertNotNull(Lifeline.refusal(Signal.KILL, "systemd-networkd.service"));
        assertNull(Lifeline.refusal(Signal.HUP, "ssh.service"),
                "re-reading its configuration does not end anything");
        assertNull(Lifeline.refusal(Signal.KILL, "nginx.service"));
    }

    @Test
    @DisplayName("a service is given settings of its own beside the file its package shipped, never instead")
    void settingsGoInADropIn() {
        assertEquals("/etc/systemd/system/nginx.service.d/kubo.conf",
                ServiceCommands.dropInFile("nginx.service"));

        String write = ServiceCommands.writeDropIn("nginx.service");
        assertTrue(write.contains("mkdir -p '/etc/systemd/system/nginx.service.d'"), write);
        assertTrue(write.contains("cat > '/etc/systemd/system/nginx.service.d/kubo.conf'"), write);
        // The content is fed in, so nothing of it is ever in a command line or in sudo's log.
        assertFalse(write.contains("echo"), write);
        assertFalse(write.contains("printf"), write);
    }

    @Test
    @DisplayName("taking those settings away leaves the packaged file untouched")
    void removingPutsItBack() {
        String remove = ServiceCommands.removeDropIn("nginx.service");

        assertTrue(remove.contains("rm -f '/etc/systemd/system/nginx.service.d/kubo.conf'"), remove);
        assertFalse(remove.contains("/lib/systemd"), remove);
        assertFalse(remove.contains("-r"), "nothing here removes a tree");
    }

    @Test
    @DisplayName("nothing that is not the shape of a unit name ever reaches a path")
    void namesAreChecked() {
        assertEquals("nginx.service", ServiceCommands.checked("nginx.service"));
        assertEquals("getty@tty1.service", ServiceCommands.checked("getty@tty1.service"));

        for (String hostile : new String[] {"../../etc/passwd", "a b.service", "nginx.service/../x",
            "$(id).service", "nginx.socket", null, ""}) {
            assertThrows(IllegalArgumentException.class, () -> ServiceCommands.dropInFile(hostile),
                    String.valueOf(hostile));
        }
    }

    @Test
    @DisplayName("every one of these asks in a fixed language, like the readings do")
    void theLanguageIsPinnedHere0() {
        assertTrue(ServiceCommands.setLimit(Resource.CPU_QUOTA, "10%", "a.service").startsWith("LC_ALL=C "));
        assertTrue(ServiceCommands.signal(Signal.HUP, false, "a.service").startsWith("LC_ALL=C "));
        assertTrue(ServiceCommands.DAEMON_RELOAD.startsWith("LC_ALL=C "));
    }
}
