package com.kubo.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService.Origin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionLogTest {

    private static final Instant STARTED = Instant.parse("2026-09-15T10:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path directory;

    @Test
    void newLogIsValidJsonWithNoCommands() throws IOException {
        Path file = directory.resolve("session.json");
        try (SessionLog ignored = new SessionLog(file, "xana@203.0.113.10:22", STARTED)) {
            JsonNode json = mapper.readTree(file.toFile());

            assertEquals("xana@203.0.113.10:22", json.get("target").asText());
            assertEquals(STARTED.toString(), json.get("startedAt").asText());
            assertTrue(json.get("commands").isArray());
            assertEquals(0, json.get("commands").size());
        }
    }

    @Test
    void everyAppendKeepsTheFileValid() throws IOException {
        Path file = directory.resolve("session.json");
        try (SessionLog log = new SessionLog(file, "xana@host:22", STARTED)) {
            log.append(new CommandEntry(STARTED, "cat /proc/loadavg", false, 0, "0.42 0.30 0.25 3/287 12345\n", "",
                    Duration.ofMillis(38)), Origin.MONITOR);
            assertEquals(1, mapper.readTree(file.toFile()).get("commands").size());

            log.append(new CommandEntry(STARTED.plusSeconds(3), "echo \"quoted\" \\ done", false, 1, "",
                    "permission denied", Duration.ofMillis(52)), Origin.USER);
            JsonNode commands = mapper.readTree(file.toFile()).get("commands");

            assertEquals(2, commands.size());
            JsonNode second = commands.get(1);
            assertEquals("USER", second.get("origin").asText());
            assertEquals("echo \"quoted\" \\ done", second.get("command").asText());
            assertEquals(1, second.get("exitCode").asInt());
            assertEquals(52, second.get("durationMs").asLong());
            assertEquals("permission denied", second.get("error").asText());
            assertEquals(0, second.get("outputBytes").asInt());
            assertEquals(27, commands.get(0).get("outputBytes").asInt());
            assertFalse(commands.get(0).has("output"));
        }
    }

    @Test
    void appendAfterCloseIsIgnored() throws IOException {
        Path file = directory.resolve("session.json");
        SessionLog log = new SessionLog(file, "xana@host:22", STARTED);
        log.close();

        log.append(new CommandEntry(STARTED, "ls", false, 0, "", "", Duration.ZERO), Origin.USER);

        assertEquals(0, mapper.readTree(file.toFile()).get("commands").size());
    }
}
