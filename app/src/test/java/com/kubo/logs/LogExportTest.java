package com.kubo.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubo.command.CommandEntry;
import com.kubo.logs.SessionHistory.Command;
import com.kubo.logs.SessionHistory.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JSON download: by service, by type, counted, with every time, and never an output. */
class LogExportTest {

    private static final Instant T = Instant.parse("2026-09-23T10:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Command run(int second, String origin, String command, int exit, boolean root, boolean typed) {
        return new Command(T.plusSeconds(second), origin, command, exit, 10 + second, 42,
                "secret printed on failure", root, typed);
    }

    private static Session session() {
        return new Session(Path.of("s.json"), "xana@host:22", T, List.of(
                run(0, "MONITOR", "cat /proc/loadavg", 0, false, false),
                run(1, "NETWORK", "ss -tn", 0, false, false),
                run(2, "MONITOR", "cat /proc/loadavg", 0, false, false),
                run(3, "NETWORK", "ufw status", 1, true, false),
                run(4, "MONITOR", "cat /proc/loadavg", 0, false, false),
                run(5, "NETWORK", "ip -br a", CommandEntry.NOT_RUN, false, true),
                run(6, "USER", "ls", 0, false, false)));
    }

    private static JsonNode export() throws IOException {
        return MAPPER.readTree(LogExport.json(session(), T.plusSeconds(60)));
    }

    @Test
    @DisplayName("the whole session is counted every way: sent, typed, root, succeeded, failed, not run")
    void countsTheSession() throws IOException {
        JsonNode json = export();

        assertEquals("xana@host:22", json.get("target").asText());
        assertEquals(T.toString(), json.get("startedAt").asText());
        assertEquals(T.plusSeconds(6).toString(), json.get("endedAt").asText());
        assertEquals(T.plusSeconds(60).toString(), json.get("exportedAt").asText());
        JsonNode totals = json.get("totals");
        assertEquals(7, totals.get("commands").asInt());
        assertEquals(5, totals.get("distinct").asInt());
        assertEquals(5, totals.get("sentByKubo").asInt());
        assertEquals(2, totals.get("typed").asInt(), "the flag, and the USER origin of older logs");
        assertEquals(1, totals.get("asRoot").asInt());
        assertEquals(5, totals.get("succeeded").asInt());
        assertEquals(1, totals.get("failed").asInt());
        assertEquals(1, totals.get("notRun").asInt());
    }

    @Test
    @DisplayName("each service apart, busiest first, the same command counted once with every time it ran")
    void sortsByService() throws IOException {
        JsonNode services = export().get("services");

        assertEquals(3, services.size());
        JsonNode monitor = services.get(0);
        assertEquals("MONITOR", monitor.get("service").asText());
        assertTrue(monitor.get("label").asText().contains("main screen"));
        assertEquals(1, monitor.get("commands").size());
        JsonNode reading = monitor.get("commands").get(0);
        assertEquals("cat /proc/loadavg", reading.get("command").asText());
        assertEquals(3, reading.get("count").asInt());
        assertEquals(T.toString(), reading.get("first").asText());
        assertEquals(T.plusSeconds(4).toString(), reading.get("last").asText());
        assertEquals(3, reading.get("at").size());
        assertEquals(T.plusSeconds(2).toString(), reading.get("at").get(1).asText());
        assertEquals(12, reading.get("averageMs").asInt());

        JsonNode network = services.get(1);
        assertEquals("NETWORK", network.get("service").asText());
        assertEquals(1, network.get("totals").get("asRoot").asInt());
        assertEquals(1, network.get("totals").get("failed").asInt());
        assertEquals(1, network.get("totals").get("typed").asInt());
    }

    @Test
    @DisplayName("a timeline in the order things happened, with the service and the type of each")
    void keepsTheTimeline() throws IOException {
        JsonNode timeline = export().get("timeline");

        assertEquals(7, timeline.size());
        JsonNode typed = timeline.get(5);
        assertEquals("NETWORK", typed.get("service").asText());
        assertTrue(typed.get("typed").asBoolean());
        assertEquals(CommandEntry.NOT_RUN, typed.get("exitCode").asInt());
        assertTrue(timeline.get(3).get("asRoot").asBoolean());
        assertEquals(42, timeline.get(0).get("outputBytes").asInt());
    }

    @Test
    @DisplayName("never an output, never an error text, even when the live session holds one")
    void neverCarriesOutput() throws IOException {
        String json = LogExport.json(session(), T);

        assertFalse(json.contains("secret printed on failure"), json);
        assertFalse(export().get("timeline").get(0).has("error"));
        assertFalse(export().get("timeline").get(0).has("output"));
    }

    @Test
    @DisplayName("an empty session is still a complete file, and it lands on disk as written")
    void writesAFile(@TempDir Path folder) throws IOException {
        Session empty = new Session(Path.of("e.json"), "t", T, List.of());
        Path file = folder.resolve("export.json");

        LogExport.write(empty, file, T);
        JsonNode json = MAPPER.readTree(file.toFile());

        assertEquals(0, json.get("totals").get("commands").asInt());
        assertEquals(T.toString(), json.get("endedAt").asText());
        assertEquals(0, json.get("services").size());
        assertEquals(0, json.get("timeline").size());
    }
}
