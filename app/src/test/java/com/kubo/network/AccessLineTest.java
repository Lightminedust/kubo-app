package com.kubo.network;

import com.kubo.model.network.AccessLine;
import com.kubo.model.network.AccessLine.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessLineTest {

    @Test
    @DisplayName("a combined log line gives up who asked, for what, and what they got")
    void readsTheCombinedFormat() {
        AccessLine line = AccessLine.of(
                "45.135.232.17 - - [23/Sep/2026:10:04:11 +0000] \"GET /api/v1/orders HTTP/1.1\" 200 5312 "
                        + "\"https://gemmi-e.com/\" \"Mozilla/5.0\"");

        assertTrue(line.read());
        assertEquals("45.135.232.17", line.address());
        assertEquals("GET", line.method());
        assertEquals("/api/v1/orders", line.path());
        assertEquals(200, line.status());
        assertEquals("5312", line.size());
        assertEquals(Level.OK, line.level());
    }

    @Test
    @DisplayName("each class of answer reads differently, which is the whole point of showing the log")
    void sortsByWhatHappened() {
        assertEquals(Level.OK, statusOf(200));
        assertEquals(Level.OK, statusOf(204));
        assertEquals(Level.MOVED, statusOf(301));
        assertEquals(Level.REFUSED, statusOf(404));
        assertEquals(Level.REFUSED, statusOf(403));
        assertEquals(Level.BROKEN, statusOf(500));
        assertEquals(Level.BROKEN, statusOf(502));
    }

    private static Level statusOf(int status) {
        return AccessLine.of("1.2.3.4 - - [23/Sep/2026:10:04:11 +0000] \"GET / HTTP/1.1\" " + status + " 1")
                .level();
    }

    @Test
    @DisplayName("a line in a shape this does not read is kept whole rather than dropped")
    void keepsWhatItCannotRead() {
        String raw = "2026/09/23 10:04:11 [error] 1487#1487: *3 connect() failed";

        AccessLine line = AccessLine.of(raw);

        assertFalse(line.read());
        assertEquals(Level.UNKNOWN, line.level());
        assertEquals(raw, line.raw());
        assertEquals(raw, line.sentence(), "an error log line is still worth reading");
    }

    @Test
    @DisplayName("the sentence puts the status first, because that is what the eye is looking for")
    void writesTheStatusFirst() {
        AccessLine line = AccessLine.of(
                "8.42.77.190 - - [23/Sep/2026:10:04:21 +0000] \"GET /static/app.css HTTP/1.1\" 404 162");

        assertTrue(line.sentence().startsWith("404"));
        assertTrue(line.sentence().contains("/static/app.css"));
        assertTrue(line.sentence().contains("8.42.77.190"));
    }

    @Test
    void survivesNothingAtAll() {
        assertFalse(AccessLine.of(null).read());
        assertFalse(AccessLine.of("").read());
        assertEquals("", AccessLine.of("").raw());
    }
}
