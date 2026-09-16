package com.kubo.command;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One command sent to the server and what came back.
 *
 * @param exitCode the command's exit code, or {@link #NOT_RUN} when it could not run at all
 * @param output   what the command printed on stdout
 * @param error    what the command printed on stderr, or why it could not run
 * @param duration round trip from sending the command to receiving its end
 */
public record CommandEntry(
        Instant executedAt,
        String command,
        boolean asRoot,
        int exitCode,
        String output,
        String error,
        Duration duration
) {

    /**
     * Exit code for a command that never ran (connection lost, timeout). Real exit codes are 0 to 255.
     */
    public static final int NOT_RUN = -1;

    public CommandEntry {
        Objects.requireNonNull(executedAt, "executedAt cannot be null");
        Objects.requireNonNull(command, "command cannot be null");
        Objects.requireNonNull(output, "output cannot be null");
        Objects.requireNonNull(error, "error cannot be null");
        Objects.requireNonNull(duration, "duration cannot be null");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration cannot be negative");
        }
    }

    /**
     * The command ran and exited with 0.
     */
    public boolean success() {
        return exitCode == 0;
    }

    /**
     * The command reached the server and finished, whatever its exit code.
     */
    public boolean ran() {
        return exitCode != NOT_RUN;
    }
}
