package com.kubo.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService.Origin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Reads back the session logs written by {@link SessionLog}.
 */
public final class SessionHistory {

    /**
     * One command as the log keeps it: no output, only its size.
     */
    public record Command(Instant at, String origin, String command, int exitCode, long durationMs, long outputBytes,
                          String error, boolean asRoot, boolean typed) {

        public Command {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(error, "error");
        }

        /** A command sent by Kubo, not as root: the shape of every log written before either was recorded. */
        public Command(Instant at, String origin, String command, int exitCode, long durationMs, long outputBytes,
                       String error) {
            this(at, origin, command, exitCode, durationMs, outputBytes, error, false, false);
        }

        public static Command of(CommandEntry entry, Origin origin) {
            return of(entry, origin, false);
        }

        public static Command of(CommandEntry entry, Origin origin, boolean typed) {
            return new Command(entry.executedAt(), origin.name(), entry.command(), entry.exitCode(),
                    entry.duration().toMillis(), entry.output().getBytes(StandardCharsets.UTF_8).length,
                    entry.error().strip(), entry.asRoot(), typed);
        }

        /**
         * Typed by a person: the flag, or the USER origin every log before the flag used for it.
         */
        public boolean byHand() {
            return typed || origin.equals("USER");
        }

        public boolean success() {
            return exitCode == 0;
        }

        public boolean ran() {
            return exitCode != CommandEntry.NOT_RUN;
        }
    }

    public record Session(Path path, String target, Instant startedAt, List<Command> commands) {

        public Session {
            commands = List.copyOf(commands);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionHistory() {
    }

    /**
     * Log files of a folder, newest first.
     */
    public static List<Path> files(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
        }
    }

    public static Session read(Path path) throws IOException {
        JsonNode root = MAPPER.readTree(path.toFile());
        if (root == null || !root.isObject()) {
            throw new IOException("Not a Kubo session log: " + path.getFileName());
        }
        List<Command> commands = new ArrayList<>();
        for (JsonNode node : root.path("commands")) {
            Instant at = instant(node.path("at").asText(""));
            if (at == null) {
                continue;
            }
            commands.add(new Command(at, node.path("origin").asText("?"), node.path("command").asText(""),
                    node.path("exitCode").asInt(CommandEntry.NOT_RUN), node.path("durationMs").asLong(0),
                    node.path("outputBytes").asLong(0), node.path("error").asText(""),
                    node.path("asRoot").asBoolean(false), node.path("typed").asBoolean(false)));
        }
        Instant started = instant(root.path("startedAt").asText(""));
        return new Session(path, root.path("target").asText("?"), started == null ? Instant.EPOCH : started, commands);
    }

    private static Instant instant(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
