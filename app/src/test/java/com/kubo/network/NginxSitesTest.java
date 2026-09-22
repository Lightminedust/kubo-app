package com.kubo.network;

import com.kubo.model.network.NginxSites;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Nginx site files, and what switching one on actually runs. */
class NginxSitesTest {

    private static final String OUTPUT = """
            === available
            default
            example.com
            api.example.com
            === enabled
            example.com
            old-site
            """;

    @Test
    @DisplayName("the two listings are told apart, and a site that is off is still listed")
    void readsBothListings() {
        NginxSites sites = NetworkCommands.parseSites(OUTPUT);

        assertEquals(List.of("default", "example.com", "api.example.com"), sites.available());
        assertEquals(List.of("example.com", "old-site"), sites.enabled());
        assertTrue(sites.isEnabled("example.com"));
        assertFalse(sites.isEnabled("api.example.com"));
        assertEquals(List.of("default", "api.example.com"), sites.disabled());
    }

    @Test
    @DisplayName("a link whose file is gone is reported: it stops Nginx from starting at all")
    void findsDanglingLinks() {
        assertEquals(List.of("old-site"), NetworkCommands.parseSites(OUTPUT).dangling());
    }

    @Test
    @DisplayName("a server with no sites-available directory gives nothing rather than a parse of an error")
    void readsNothing() {
        assertTrue(NetworkCommands.parseSites("=== available\n=== enabled\n").empty());
        assertTrue(NetworkCommands.parseSites("ls: cannot access: No such file or directory").empty());
    }

    @Test
    @DisplayName("enabling links first and tests after, and undoes the link when Nginx refuses it")
    void enablingIsUndoneOnFailure() {
        String command = NetworkCommands.enableSite("example.com");

        assertTrue(command.contains("ln -sfn '/etc/nginx/sites-available/example.com'"));
        assertTrue(command.contains("nginx -t"));
        assertTrue(command.contains("rm -f '/etc/nginx/sites-enabled/example.com'"),
                "a site that fails the test must not survive the click that enabled it");
    }

    @Test
    @DisplayName("only a file name ever reaches a path")
    void refusesAnythingElse() {
        for (String bad : List.of("../../etc/passwd", "a; rm -rf /", "", "site name", "$(id)")) {
            assertThrows(IllegalArgumentException.class, () -> NetworkCommands.enableSite(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> NetworkCommands.disableSite(bad), bad);
        }
    }

    @Test
    @DisplayName("a rule number is what a deletion refers to, and it starts at one")
    void deletesByNumber() {
        assertEquals("ufw --force delete 3", NetworkCommands.ufwDeleteRule(3));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.ufwDeleteRule(0));
    }

    @Test
    @DisplayName("opening a port to one source is not the same command as opening it to the world")
    void allowsFromOneSource() {
        assertEquals("ufw allow from '203.0.113.9' to any port 5432 proto tcp",
                NetworkCommands.ufwAllowFrom("203.0.113.9", 5432,
                        com.kubo.model.server.ListeningPort.Protocol.TCP));
        assertThrows(IllegalArgumentException.class,
                () -> NetworkCommands.ufwAllowFrom("evil.example.com", 5432,
                        com.kubo.model.server.ListeningPort.Protocol.TCP));
    }
}
