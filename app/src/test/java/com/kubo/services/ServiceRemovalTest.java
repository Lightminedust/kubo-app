package com.kubo.services;

import com.kubo.model.services.Action;
import com.kubo.model.services.BootState;
import com.kubo.model.services.Removal;
import com.kubo.model.services.ServiceUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deleting a service: the one verb nothing undoes.
 *
 * <p>These pin what Kubo will not delete, which matters more than what it will. A unit file that came from a
 * package is that package's, and a unit that carries this connection is the way back into the machine.
 */
class ServiceRemovalTest {

    private static ServiceUnit unit(String name, String active, String sub, BootState boot) {
        return new ServiceUnit(name, "", "loaded", active, sub, boot, "simple", null);
    }

    private static final String MINE = "/etc/systemd/system/kubo-test.service";

    @Test
    @DisplayName("a unit written by hand in /etc/systemd/system can go")
    void theLocalOnesCanGo() {
        assertNull(Removal.refusal("kubo-test.service", MINE));
    }

    @Test
    @DisplayName("a unit a package installed is refused, wherever the package put it")
    void packagesKeepTheirOwn() {
        for (String path : List.of("/usr/lib/systemd/system/nginx.service",
                "/lib/systemd/system/nginx.service",
                "/run/systemd/system/nginx.service",
                "/usr/local/lib/systemd/system/nginx.service")) {
            String refusal = Removal.refusal("nginx.service", path);
            assertNotNull(refusal, path + " should not be Kubo's to delete");
            assertTrue(refusal.contains(path), "the refusal should say where the file really is");
        }
    }

    @Test
    @DisplayName("a path that only looks like the local one is still refused")
    void theDirectoryIsExact() {
        // A drop-in, a template's own file, and a name that merely starts the same way.
        assertNotNull(Removal.refusal("nginx.service", "/etc/systemd/system/nginx.service.d/kubo.conf"));
        assertNotNull(Removal.refusal("nginx.service", "/etc/systemd/system/other/nginx.service"));
        assertNotNull(Removal.refusal("nginx.service", "/etc/systemd/systemd/nginx.service"));
    }

    @Test
    @DisplayName("a unit with no file at all is refused rather than half deleted")
    void nothingToDelete() {
        assertNotNull(Removal.refusal("ghost.service", ""));
        assertNotNull(Removal.refusal("ghost.service", null));
    }

    @Test
    @DisplayName("the door in and the network under it are refused even when they are local")
    void theWayBackIn() {
        for (String name : List.of("ssh.service", "sshd.service", "NetworkManager.service",
                "systemd-networkd.service")) {
            assertNotNull(Removal.refusal(name, "/etc/systemd/system/" + name),
                    name + " carries this connection");
        }
    }

    @Test
    @DisplayName("a running service is stopped and disabled before its file goes")
    void stopItFirst() {
        assertEquals(List.of(Action.STOP, Action.DISABLE),
                Removal.steps(unit("kubo-test.service", "active", "running", BootState.ENABLED)));
    }

    @Test
    @DisplayName("a service that is already down and already off needs no step at all")
    void nothingToDoFirst() {
        assertEquals(List.of(),
                Removal.steps(unit("kubo-test.service", "inactive", "dead", BootState.DISABLED)));
    }

    @Test
    @DisplayName("a failed service is stopped first too, which is what clears it")
    void aFailedOneIsStopped() {
        assertEquals(List.of(Action.STOP),
                Removal.steps(unit("kubo-test.service", "failed", "failed", BootState.DISABLED)));
    }

    @Test
    @DisplayName("an enabled service that is down is disabled, not stopped")
    void onlyWhatIsNeeded() {
        assertEquals(List.of(Action.DISABLE),
                Removal.steps(unit("kubo-test.service", "inactive", "dead", BootState.ENABLED)));
    }

    @Test
    @DisplayName("the command can only ever reach one file, built from the name systemd gave")
    void theCommandIsAPathAndNothingElse() {
        // Quoted, as every path Kubo sends is: the shell sees one argument whatever is in it.
        assertEquals("rm -f '/etc/systemd/system/kubo-test.service'",
                ServiceCommands.removeUnit("kubo-test.service"));
    }

    @Test
    @DisplayName("a name that is not a unit name never becomes a path")
    void namesAreChecked() {
        for (String bad : List.of("../../etc/passwd", "a b.service", "nginx.service; rm -rf /",
                "nginx", "$(id).service", "")) {
            assertThrows(IllegalArgumentException.class, () -> ServiceCommands.removeUnit(bad), bad);
        }
    }

    @Test
    @DisplayName("clearing a failed entry is allowed to find nothing to clear")
    void resetFailedNeverFails() {
        String command = ServiceCommands.resetFailed("kubo-test.service");
        assertTrue(command.contains("reset-failed"));
        assertTrue(command.endsWith("; true"), "a unit that never failed is not an error");
    }
}
