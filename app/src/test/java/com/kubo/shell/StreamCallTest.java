package com.kubo.shell;

import com.kubo.model.shell.StreamCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("A command that follows a log instead of ending")
class StreamCallTest {

    @Test
    void theOnesThatFollowByDefault() {
        assertTrue(StreamCall.of("pm2 logs").isPresent());
        assertTrue(StreamCall.of("pm2 logs api --lines 200").isPresent());
        assertTrue(StreamCall.of("sudo pm2 monit").isPresent());
    }

    @Test
    void theOnesThatFollowOnlyWhenAsked() {
        assertTrue(StreamCall.of("tail -f /var/log/nginx/error.log").isPresent());
        assertTrue(StreamCall.of("tail -fn 200 /var/log/syslog").isPresent(), "bundled short options count");
        assertTrue(StreamCall.of("journalctl -u nginx -f").isPresent());
        assertTrue(StreamCall.of("journalctl --follow --unit ssh").isPresent());
        assertTrue(StreamCall.of("sudo dmesg -w").isPresent());
        assertTrue(StreamCall.of("docker logs -f kubo").isPresent());
        assertTrue(StreamCall.of("docker compose logs -f web").isPresent());
        assertTrue(StreamCall.of("kubectl logs -f pod/api").isPresent());
    }

    @Test
    void theSameToolsWithoutTheFollowAreOrdinaryCommands() {
        assertEquals(Optional.empty(), StreamCall.of("tail -n 200 /var/log/syslog"));
        assertEquals(Optional.empty(), StreamCall.of("journalctl -u nginx"));
        assertEquals(Optional.empty(), StreamCall.of("docker logs kubo"));
    }

    @Test
    void aFilterFlagIsNotAFollowFlag() {
        // "docker ps -f status=running" ends by itself. Reading the -f as a follow would open a window on a
        // command that is already over.
        assertEquals(Optional.empty(), StreamCall.of("docker ps -f status=running"));
        assertEquals(Optional.empty(), StreamCall.of("pm2 list"));
        assertEquals(Optional.empty(), StreamCall.of("ls -f"));
        assertEquals(Optional.empty(), StreamCall.of(""));
        assertEquals(Optional.empty(), StreamCall.of(null));
    }

    @Test
    void theTitleIsTheCommandAndStaysShortEnoughForABar() {
        assertEquals("tail -f /var/log/syslog", StreamCall.of("tail -f /var/log/syslog").orElseThrow().title());

        String long_ = "tail -f " + "/very/long/path".repeat(12);
        String title = StreamCall.of(long_).orElseThrow().title();
        assertEquals(62, title.length());
        assertTrue(title.endsWith("…"));
    }
}
