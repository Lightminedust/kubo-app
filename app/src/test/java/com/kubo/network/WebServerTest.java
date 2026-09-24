package com.kubo.network;

import com.kubo.model.network.WebServer;
import com.kubo.model.network.WebServer.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Naming what serves the web, so that "no Nginx" stops being said as though it meant "no web server". */
class WebServerTest {

    @Test
    @DisplayName("installed and running are two facts, and both are kept")
    void tellsInstalledFromRunning() {
        List<WebServer> found = WebServer.parse("""
                HAVE nginx
                HAVE haproxy
                RUN nginx
                """);

        assertEquals(2, found.size());
        assertEquals(Kind.NGINX, found.get(0).kind());
        assertTrue(found.get(0).running());
        assertEquals(Kind.HAPROXY, found.get(1).kind());
        assertFalse(found.get(1).running(), "installed, and nothing by that name is up");
        assertTrue(found.get(1).sentence().contains("not running"));
    }

    @Test
    @DisplayName("Apache answers to two names on two families of distribution, and is one thing either way")
    void readsApacheUnderBothNames() {
        assertEquals(List.of(new WebServer(Kind.APACHE, true)), WebServer.parse("HAVE apache2\nRUN apache2"));
        assertEquals(List.of(new WebServer(Kind.APACHE, true)), WebServer.parse("HAVE httpd\nRUN httpd"));
        assertEquals(Kind.APACHE, Kind.of("HTTPD").orElseThrow());
    }

    @Test
    @DisplayName("a process reported with its path is still recognised")
    void readsAProcessPath() {
        assertEquals(List.of(new WebServer(Kind.CADDY, true)), WebServer.parse("HAVE caddy\nRUN /usr/bin/caddy"));
    }

    @Test
    @DisplayName("Nginx comes first, then whatever is running, because that is the order somebody reads in")
    void ordersWhatMatters() {
        List<WebServer> found = WebServer.parse("""
                HAVE haproxy
                HAVE caddy
                HAVE nginx
                RUN caddy
                """);

        assertEquals(List.of(Kind.NGINX, Kind.CADDY, Kind.HAPROXY),
                found.stream().map(WebServer::kind).toList());
    }

    @Test
    @DisplayName("something that is not one of these is passed over, never guessed at")
    void ignoresTheRest() {
        assertEquals(List.of(), WebServer.parse("HAVE lighttpd\nRUN postgres\nnonsense\n\n"));
        assertEquals(List.of(), WebServer.parse(""));
        assertEquals(List.of(), WebServer.parse(null));
        assertTrue(Kind.of("lighttpd").isEmpty());
        assertTrue(Kind.of(null).isEmpty());
    }

    @Test
    @DisplayName("with no Nginx, the note names what is actually there instead")
    void saysWhatIsThereInstead() {
        String one = WebServer.noteFor(WebServer.parse("HAVE caddy\nRUN caddy"));

        assertTrue(one.contains("Caddy"), one);
        assertTrue(one.contains("not Nginx"), one);
        assertTrue(one.contains("Nothing here is broken"), one);

        String two = WebServer.noteFor(WebServer.parse("HAVE caddy\nHAVE haproxy"));
        assertTrue(two.contains("Caddy and HAProxy"), two);
    }

    @Test
    @DisplayName("with Nginx there, or with nothing at all, the note says nothing")
    void staysQuietWhenThereIsNothingToAdd() {
        assertEquals("", WebServer.noteFor(WebServer.parse("HAVE nginx\nHAVE caddy")));
        assertEquals("", WebServer.noteFor(List.of()), "an empty screen already says this for itself");
        assertTrue(WebServer.hasNginx(WebServer.parse("HAVE nginx")));
        assertFalse(WebServer.hasNginx(WebServer.parse("HAVE caddy")));
    }

    @Test
    @DisplayName("only Nginx is managed; every other kind still says where to look and what to run")
    void namesWithoutPretendingToManage() {
        assertTrue(Kind.NGINX.managed());
        for (Kind kind : Kind.values()) {
            assertFalse(kind.label().isBlank());
            assertFalse(kind.what().isBlank());
            assertFalse(kind.configHome().isBlank());
            assertFalse(kind.check().isBlank());
            assertFalse(kind.binaries().isEmpty());
            if (kind != Kind.NGINX) {
                assertFalse(kind.managed(), kind + " is named, not managed");
            }
        }
    }

    @Test
    @DisplayName("the command asks for every name, needs no root, and matches names whole")
    void looksForEveryNameWithoutRoot() {
        String command = NetworkCommands.WEB_SERVERS;

        for (String binary : Kind.everyBinary()) {
            assertTrue(command.contains(binary), "the command never looks for " + binary);
        }
        assertFalse(command.contains("sudo"), "this one runs before anybody has been asked for a password");
        assertTrue(command.contains("grep -xE"), "a whole-line match, so nginx never matches nginx-extras");
    }
}
