package com.kubo.logs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService.Origin;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Every command of one session, written to a JSON file as it happens. The file is a complete JSON document
 * after each write, so it stays readable even if Kubo stops abruptly.
 *
 * <pre>
 * { "target": "...", "startedAt": "...", "commands": [ { "at": ..., "origin": ..., "command": ... }, ... ] }
 * </pre>
 * Command output is not stored — neither standard output nor standard error — only its size. Either can be large,
 * and either can carry file contents, paths or a secret a program printed while failing. What happened is still
 * recorded: the command, whether it ran as root, its exit code and how long it took.
 */
public final class SessionLog implements AutoCloseable {

    public static final Path DIRECTORY = Path.of(System.getProperty("user.home"), ".kubo", "logs");

    private static final String TAIL = "\n  ]\n}\n";
    private static final int TAIL_BYTES = TAIL.getBytes(StandardCharsets.UTF_8).length;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
            .withZone(ZoneId.systemDefault());

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path path;
    private final RandomAccessFile file;
    private boolean empty = true;
    private boolean closed;

    SessionLog(Path path, String target, Instant startedAt) throws IOException {
        this.path = Objects.requireNonNull(path, "path");
        this.file = new RandomAccessFile(path.toFile(), "rw");
        file.setLength(0);
        String head = "{\n  \"target\": " + mapper.writeValueAsString(target)
                + ",\n  \"startedAt\": " + mapper.writeValueAsString(startedAt.toString())
                + ",\n  \"commands\": [";
        file.write((head + TAIL).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Starts a new log file in {@link #DIRECTORY}, named after the start time and the target.
     */
    public static SessionLog start(String target) throws IOException {
        Files.createDirectories(DIRECTORY);
        Instant now = Instant.now();
        String name = FILE_TIME.format(now) + "_" + target.replaceAll("[^A-Za-z0-9@._-]", "_") + ".json";
        return new SessionLog(DIRECTORY.resolve(name), target, now);
    }

    public Path path() {
        return path;
    }

    /**
     * Adds a command. Failures to write are ignored: logging must never interrupt the session.
     */
    public void append(CommandEntry entry, Origin origin) {
        append(new com.kubo.command.CommandService.Logged(entry, origin, false));
    }

    /**
     * Adds a command, saying whether a person typed it. Failures to write are ignored: logging must never
     * interrupt the session.
     */
    public synchronized void append(com.kubo.command.CommandService.Logged logged) {
        if (closed) {
            return;
        }
        try {
            String line = (empty ? "" : ",") + "\n    "
                    + mapper.writeValueAsString(toJson(logged.entry(), logged.origin(), logged.typed()));
            // Overwrite the closing brackets, then write them back after the new entry.
            file.seek(file.length() - TAIL_BYTES);
            file.write((line + TAIL).getBytes(StandardCharsets.UTF_8));
            empty = false;
        } catch (IOException ignored) {
            // The session goes on without this entry.
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            file.close();
        } catch (IOException ignored) {
            // Nothing left to release.
        }
    }

    private static Map<String, Object> toJson(CommandEntry entry, Origin origin, boolean typed)
            throws JsonProcessingException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("at", entry.executedAt().toString());
        json.put("origin", origin.name());
        json.put("typed", typed);
        json.put("command", entry.command());
        json.put("asRoot", entry.asRoot());
        json.put("exitCode", entry.exitCode());
        json.put("durationMs", entry.duration().toMillis());
        json.put("outputBytes", entry.output().getBytes(StandardCharsets.UTF_8).length);
        json.put("errorBytes", entry.error().getBytes(StandardCharsets.UTF_8).length);
        return json;
    }
}
