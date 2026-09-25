package com.kubo.model.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One process as pm2 describes it.
 *
 * <p>Read from {@code pm2 jlist}, never from {@code pm2 list}. The drawn table is made of box characters and
 * columns padded with spaces, and reading it back means counting spaces and hoping the next version of pm2
 * pads them the same way. The JSON is the same information with the guessing taken out, and it carries the
 * process id, which is what every button on a row needs and what no amount of reading the drawing gives you
 * reliably.
 */
public record Pm2Process(int id, String name, String mode, String status, int restarts, double cpu,
                         long memory, Instant since, String namespace) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Pm2Process {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(namespace, "namespace");
    }

    /** True for a process pm2 considers up. */
    public boolean online() {
        return status.equalsIgnoreCase("online");
    }

    /** True for one that stopped on its own rather than being stopped. */
    public boolean broken() {
        return status.equalsIgnoreCase("errored") || status.equalsIgnoreCase("error");
    }

    /** How long it has been up, or empty when pm2 gives no start time. */
    public Duration uptime(Instant now) {
        return since == null || since.isAfter(now) ? Duration.ZERO : Duration.between(since, now);
    }

    /**
     * Everything in a {@code pm2 jlist} answer, or an empty list when it is not JSON at all.
     *
     * <p>Empty rather than an exception: pm2 prints a banner the first time it starts its daemon, and a
     * command that has not been installed prints a shell error. Neither is worth a stack trace.
     */
    public static List<Pm2Process> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        int start = arrayStarts(json);
        if (start < 0) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(json.substring(start));
            if (!root.isArray()) {
                return List.of();
            }
            List<Pm2Process> processes = new ArrayList<>();
            for (JsonNode node : root) {
                processes.add(one(node));
            }
            return List.copyOf(processes);
        } catch (com.fasterxml.jackson.core.JacksonException notJson) {
            return List.of();
        }
    }

    /**
     * Where the array actually begins.
     *
     * <p>Not the first "[" in the text. pm2 announces itself with "[PM2] Spawning PM2 daemon" the first time
     * it runs after a reboot, and that bracket is the first one there is. An array opens on a "[" whose next
     * thing is an object or the end of it, and a banner never looks like that.
     */
    private static int arrayStarts(String json) {
        for (int at = json.indexOf('['); at >= 0; at = json.indexOf('[', at + 1)) {
            for (int i = at + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (Character.isWhitespace(c)) {
                    continue;
                }
                if (c == '{' || c == ']') {
                    return at;
                }
                break;
            }
        }
        return -1;
    }

    private static Pm2Process one(JsonNode node) {
        JsonNode env = node.path("pm2_env");
        JsonNode monit = node.path("monit");
        long started = env.path("pm_uptime").asLong(0);
        String mode = env.path("exec_mode").asText("fork_mode").replace("_mode", "");
        int instances = env.path("instances").asInt(1);
        return new Pm2Process(
                node.path("pm_id").asInt(-1),
                node.path("name").asText("?"),
                instances > 1 ? mode + " ×" + instances : mode,
                env.path("status").asText("unknown"),
                env.path("restart_time").asInt(0),
                monit.path("cpu").asDouble(0),
                monit.path("memory").asLong(0),
                started > 0 ? Instant.ofEpochMilli(started) : null,
                env.path("namespace").asText("default"));
    }
}
