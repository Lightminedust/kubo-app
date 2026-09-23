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
            === names
            --- default
             server_name _;
            --- example.com
              server_name gemmi-e.com www.gemmi-e.com;
            --- api.example.com
              server_name api.gemmi-e.com;
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
    @DisplayName("a file is shown by the domains it answers to, not only by its name")
    void readsServerNames() {
        NginxSites sites = NetworkCommands.parseSites(OUTPUT);

        assertEquals(List.of("gemmi-e.com", "www.gemmi-e.com"), sites.namesOf("example.com"));
        assertEquals(List.of("api.gemmi-e.com"), sites.namesOf("api.example.com"));
        // "_" is nginx saying "anything", which is not a name anybody types.
        assertEquals(List.of(), sites.namesOf("default"));
        assertEquals(List.of(), sites.namesOf("old-site"));
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
    @Test
    @DisplayName("a path with anything the shell would read is refused here, not handled there")
    void refusesPathsItWillNotRead() {
        assertEquals("/etc/nginx/sites-available/gemmie",
                NetworkCommands.checkedPath("/etc/nginx/sites-available/gemmie"));
        assertEquals("/var/log/nginx/gemmi-e.com.access.log",
                NetworkCommands.checkedPath("/var/log/nginx/gemmi-e.com.access.log"));

        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.checkedPath("etc/nginx/nginx.conf"));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.checkedPath("/etc/nginx/a b"));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.checkedPath("/etc/nginx/x; rm -rf /"));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.checkedPath("/etc/$(whoami)"));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.checkedPath("/etc/`id`"));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.checkedPath(null));
    }

    @Test
    @DisplayName("a log read is bounded, whatever number it is handed")
    void boundsTheLogRead() {
        assertTrue(NetworkCommands.readLog("/var/log/nginx/access.log", 300).contains("tail -n 300 "));
        assertTrue(NetworkCommands.readLog("/var/log/nginx/access.log", 0).contains("tail -n 1 "));
        assertTrue(NetworkCommands.readLog("/var/log/nginx/access.log", 999999).contains("tail -n 2000 "));
        assertTrue(NetworkCommands.readLog("/var/log/nginx/access.log", 300)
                .contains("'/var/log/nginx/access.log'"), "quoted, as well as checked");
    }
    @Test
    @DisplayName("writing a site writes beside the file and moves over it, so a dropped connection breaks nothing")
    void writesThroughATemporaryFile() {
        String command = NetworkCommands.writeSite("api.gemmi-e.com");

        assertTrue(command.startsWith("cat > '/etc/nginx/sites-available/api.gemmi-e.com.kubo-new'"));
        assertTrue(command.contains("chmod 0644 '/etc/nginx/sites-available/api.gemmi-e.com.kubo-new'"));
        assertTrue(command.endsWith("mv -f '/etc/nginx/sites-available/api.gemmi-e.com.kubo-new' "
                + "'/etc/nginx/sites-available/api.gemmi-e.com'"));
    }

    @Test
    @DisplayName("nothing of the file's content is anywhere in the command that writes it")
    void theContentIsNeverInTheCommand() {
        String command = NetworkCommands.writeSite("api.gemmi-e.com");

        assertFalse(command.contains("server_name"));
        assertFalse(command.contains("proxy_pass"));
        assertFalse(command.contains("<<"), "no heredoc: the content arrives on standard input");
        assertFalse(command.contains("echo"));
    }

    @Test
    @DisplayName("a site name that is not a site name never reaches a path")
    void refusesBadSiteNames() {
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.writeSite("../../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.writeSite("a b"));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.writeSite("x; rm -rf /"));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.deleteSite("/etc/nginx/nginx.conf"));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.siteExists("$(id)"));
    }

    @Test
    @DisplayName("the rate limit file has one fixed path and no name of anybody's in its command")
    void writesTheZonesAtAFixedPath() {
        String command = NetworkCommands.writeRateLimits();

        assertTrue(command.contains("'/etc/nginx/conf.d/kubo-rate-limits.conf.kubo-new'"));
        assertTrue(command.contains("mkdir -p /etc/nginx/conf.d"));
    }

    @Test
    @DisplayName("deleting a site takes the link and the file, and nothing else")
    void deletesBoth() {
        String command = NetworkCommands.deleteSite("api.gemmi-e.com");

        assertEquals("rm -f '/etc/nginx/sites-enabled/api.gemmi-e.com' "
                + "'/etc/nginx/sites-available/api.gemmi-e.com'", command);
    }
    @Test
    @DisplayName("reading a certificate or a key asks for its armour line and cannot get anything else")
    void readsOnlyTheArmour() {
        String command = NetworkCommands.pemArmour("/etc/letsencrypt/live/api.gemmi-e.com/privkey.pem");

        assertEquals("grep -a -m1 -o -e '-----BEGIN [A-Z ]*-----' -- "
                + "'/etc/letsencrypt/live/api.gemmi-e.com/privkey.pem'", command);
        assertTrue(command.contains(" -o "), "the match only, never the line around it");
        assertFalse(command.contains("cat") || command.contains("head") || command.contains("tail"));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.pemArmour("/etc/ssl/a b.pem"));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.pemArmour("relative.pem"));
    }
}
