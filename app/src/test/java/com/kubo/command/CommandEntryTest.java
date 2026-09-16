package com.kubo.command;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandEntryTest {

    private static CommandEntry withExitCode(int exitCode) {
        return new CommandEntry(Instant.now(), "docker ps", false, exitCode, "", "", Duration.ofMillis(40));
    }

    @Test
    void exitCodeZeroIsASuccess() {
        CommandEntry entry = withExitCode(0);

        assertTrue(entry.success());
        assertTrue(entry.ran());
    }

    @Test
    void nonZeroExitCodeRanButFailed() {
        CommandEntry entry = withExitCode(126);

        assertFalse(entry.success());
        assertTrue(entry.ran());
    }

    @Test
    void notRunIsNeitherRunNorSuccessful() {
        CommandEntry entry = withExitCode(CommandEntry.NOT_RUN);

        assertFalse(entry.success());
        assertFalse(entry.ran());
    }

    @Test
    void rejectsMissingValues() {
        Instant now = Instant.now();
        Duration duration = Duration.ofMillis(40);

        assertThrows(NullPointerException.class, () -> new CommandEntry(null, "ls", false, 0, "", "", duration));
        assertThrows(NullPointerException.class, () -> new CommandEntry(now, null, false, 0, "", "", duration));
        assertThrows(NullPointerException.class, () -> new CommandEntry(now, "ls", false, 0, null, "", duration));
        assertThrows(NullPointerException.class, () -> new CommandEntry(now, "ls", false, 0, "", null, duration));
        assertThrows(NullPointerException.class, () -> new CommandEntry(now, "ls", false, 0, "", "", null));
        assertThrows(IllegalArgumentException.class,
                () -> new CommandEntry(now, "ls", false, 0, "", "", Duration.ofMillis(-1)));
    }
}
