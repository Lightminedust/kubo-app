package com.kubo.logs;

import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService.Origin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionHistoryTest {

    @TempDir
    Path temp;

    @Test
    void readsBackWhatTheSessionLogWrote() throws IOException {
        Path file = temp.resolve("2026-09-15_10-00-00_xana.json");
        Instant started = Instant.parse("2026-09-15T08:00:00Z");
        try (SessionLog log = new SessionLog(file, "xana@173.212.254.237:22", started)) {
            log.append(new CommandEntry(started.plusSeconds(1), "uptime", false, 0, "up 3 days", "",
                    Duration.ofMillis(40)), Origin.MONITOR);
            log.append(new CommandEntry(started.plusSeconds(2), "rm x", false, 1, "", "rm: denied\n",
                    Duration.ofMillis(12)), Origin.USER);
        }

        SessionHistory.Session session = SessionHistory.read(file);

        assertEquals("xana@173.212.254.237:22", session.target());
        assertEquals(started, session.startedAt());
        assertEquals(2, session.commands().size());
        SessionHistory.Command failed = session.commands().get(1);
        assertEquals("USER", failed.origin());
        assertEquals("rm x", failed.command());
        assertEquals(1, failed.exitCode());
        // The failure is still visible from the exit code; the message itself was never written to the file.
        assertEquals("", failed.error(), "the log keeps the size of standard error, never its text");
        assertFalse(failed.success());
        assertEquals(9, session.commands().get(0).outputBytes());
    }

    @Test
    void emptySessionHasNoCommands() throws IOException {
        Path file = temp.resolve("empty.json");
        new SessionLog(file, "t", Instant.EPOCH).close();

        assertTrue(SessionHistory.read(file).commands().isEmpty());
    }

    @Test
    void filesAreListedNewestFirst() throws IOException {
        Files.writeString(temp.resolve("2026-09-14_09-00-00_a.json"), "{}");
        Files.writeString(temp.resolve("2026-09-15_09-00-00_a.json"), "{}");
        Files.writeString(temp.resolve("notes.txt"), "x");

        List<Path> files = SessionHistory.files(temp);

        assertEquals(2, files.size());
        assertTrue(files.get(0).getFileName().toString().startsWith("2026-09-15"));
        assertTrue(SessionHistory.files(temp.resolve("missing")).isEmpty());
    }

    @Test
    void liveCommandsLookLikeLoggedOnes() {
        CommandEntry entry = new CommandEntry(Instant.EPOCH, "ls", false, CommandEntry.NOT_RUN, "", " lost ",
                Duration.ofMillis(5));

        SessionHistory.Command command = SessionHistory.Command.of(entry, Origin.FILES);

        assertEquals("FILES", command.origin());
        assertEquals("lost", command.error());
        assertFalse(command.ran());
    }

    @Test
    void somethingElseIsRefused() throws IOException {
        Path file = Files.writeString(temp.resolve("bad.json"), "[1, 2]");

        assertThrows(IOException.class, () -> SessionHistory.read(file));
    }
}
