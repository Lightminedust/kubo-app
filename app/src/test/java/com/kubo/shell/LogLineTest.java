package com.kubo.shell;

import com.kubo.model.shell.LogLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Reading a log line for its level and its times")
class LogLineTest {

    @Test
    void theLevelIsTakenFromTheWordsThatCarryIt() {
        assertEquals(LogLine.Level.ERROR, LogLine.of("nginx: [emerg] bind() to 0.0.0.0:80 failed").level());
        assertEquals(LogLine.Level.ERROR, LogLine.of("Connection refused").level());
        assertEquals(LogLine.Level.WARN, LogLine.of("WARN deprecated option ssl_protocols").level());
        assertEquals(LogLine.Level.INFO, LogLine.of("api ready, listening on 3000").level());
        assertEquals(LogLine.Level.PLAIN, LogLine.of("  at Object.<anonymous> (/root/app/x.js:12)").level());
    }

    @Test
    void theWorstWordWins() {
        // "failed to start, retrying" is a failure that happens to mention a retry, not a retry.
        assertEquals(LogLine.Level.ERROR, LogLine.of("failed to start, retrying in 5s").level());
    }

    @Test
    void theLinesPm2ActuallyPrints() {
        // Read off a real pm2 logs page, because a rule that works on invented lines is worth nothing.
        assertEquals(LogLine.Level.ERROR,
                LogLine.of("mailer | 2026-09-25T14:05:16 ERROR connection refused: smtp.example.net:587").level());
        assertEquals(LogLine.Level.WARN,
                LogLine.of("mailer | 2026-09-25T14:05:11 WARN smtp handshake slow, retrying in 5s").level());
        assertEquals(LogLine.Level.INFO,
                LogLine.of("gemmie-api | 2026-09-25T14:05:08 INFO listening on 0.0.0.0:3000").level());
        assertEquals(LogLine.Level.PLAIN,
                LogLine.of("PM2 | 2026-09-25T14:05:16: PM2 log: App [mailer:2] exited with code 1").level());
        assertEquals(LogLine.Level.PLAIN,
                LogLine.of("PM2 | 2026-09-25T14:05:16: PM2 log: App [mailer:2] restarting, 47 restarts").level(),
                "restarting is not starting: the boundary is what keeps them apart");
        assertEquals(LogLine.Level.PLAIN,
                LogLine.of("/root/.pm2/pm2.log last 15 lines:").level());
    }

    @Test
    void theTimesAreFoundWhereverTheyAreInTheLine() {
        // pm2 puts them in the middle, after the process name and a pipe.
        LogLine line = LogLine.of("PM2  | 2026-09-25T14:05:06: PM2 log: Time");

        assertEquals(3, line.spans().size());
        assertEquals("PM2  | ", line.spans().get(0).text());
        assertEquals("2026-09-25T14:05:06", line.spans().get(1).text());
        assertTrue(line.spans().get(1).stamp());
        assertEquals(": PM2 log: Time", line.spans().get(2).text());
    }

    @Test
    void theThreeShapesALinuxServerWritesThemIn() {
        assertTrue(LogLine.of("Sep 25 14:05:06 vmi sshd[1]: x").spans().stream()
                .anyMatch(span -> span.stamp() && span.text().equals("Sep 25 14:05:06")));
        assertTrue(LogLine.of("14:05:06 started").spans().stream()
                .anyMatch(span -> span.stamp() && span.text().equals("14:05:06")));
        assertTrue(LogLine.of("2026-09-25 14:05:06,123 INFO x").spans().stream()
                .anyMatch(span -> span.stamp() && span.text().equals("2026-09-25 14:05:06,123")));
    }

    @Test
    void aLineWithNoTimeIsOnePiece() {
        LogLine line = LogLine.of("plain output");

        assertEquals(1, line.spans().size());
        assertEquals("plain output", line.spans().get(0).text());
        assertEquals(false, line.spans().get(0).stamp());
    }

    @Test
    void thePiecesAlwaysAddBackUpToTheLine() {
        // Whatever the cutting does, nothing is lost and nothing is added: the row shows the line.
        for (String text : new String[]{"", "x", "2026-09-25T14:05:06", "a 14:05:06 b 15:00:00",
                "Sep 25 14:05:06 done"}) {
            StringBuilder back = new StringBuilder();
            LogLine.of(text).spans().forEach(span -> back.append(span.text()));
            assertEquals(text, back.toString());
        }
    }
}
