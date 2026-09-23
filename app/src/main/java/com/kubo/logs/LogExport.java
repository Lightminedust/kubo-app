package com.kubo.logs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubo.logs.SessionHistory.Command;
import com.kubo.logs.SessionHistory.Session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A session's commands as a structured JSON file: sorted by the screen that sent them, counted, and with the
 * precise time of every run.
 *
 * <p>The session log itself is a list in the order things happened, which answers "what happened at 14:02" and
 * nothing else. This answers the other questions: which screen sends the most, which command runs a thousand
 * times an hour, how many failed, how many went through root, which ones a person typed.
 *
 * <p>Commands only. No output is in it, and no error text: the session file never stored either, and the live
 * session, which does hold error text in memory for the journal to show, does not pass it on here. A command
 * that printed a secret while failing is exactly the thing that must not end up in a file somebody shares.
 */
public final class LogExport {

    /** What each screen is called in the file, for somebody who has never seen Kubo. */
    private static final Map<String, String> LABELS = Map.of(
            "MONITOR", "the main screen: CPU, memory and disk readings",
            "FILES", "FILES.SCOPE: folders, files and transfers",
            "USER", "typed into FILES.SCOPE's command line",
            "DOCKER", "DOCKER.SCOPE: containers, images and networks",
            "REDIS", "REDIS.SCOPE: keys and figures",
            "NETWORK", "NETWORK.SCOPE: ports, firewall, fail2ban and nginx",
            "PACKAGES", "the PACKAGES view: apt and dpkg",
            "SERVICES", "SERVICES.SCOPE: systemd units");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LogExport() {
    }

    /**
     * The whole export, as a structure Jackson writes as it is.
     *
     * @param exportedAt when the file is being written, which is not when the session ran
     */
    public static Map<String, Object> of(Session session, Instant exportedAt) {
        List<Command> commands = session.commands();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("kubo", "session log export");
        root.put("note", "Commands only. No command's output is in this file, and no error text: Kubo never "
                + "stores either. outputBytes is how much a command printed, not what.");
        root.put("target", session.target());
        root.put("startedAt", session.startedAt().toString());
        root.put("endedAt", commands.isEmpty() ? session.startedAt().toString()
                : commands.get(commands.size() - 1).at().toString());
        root.put("exportedAt", exportedAt.toString());
        root.put("totals", totals(commands));

        // One entry per screen, busiest first, each with its commands grouped the same way the journal shows them.
        Map<String, List<CommandGroup>> byService = new LinkedHashMap<>();
        for (CommandGroup group : CommandGroup.of(commands)) {
            byService.computeIfAbsent(group.origin(), any -> new ArrayList<>()).add(group);
        }
        List<Map<String, Object>> services = new ArrayList<>();
        byService.forEach((origin, groups) -> services.add(service(origin, groups)));
        root.put("services", services);

        List<Map<String, Object>> timeline = new ArrayList<>();
        for (Command command : commands) {
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("at", command.at().toString());
            run.put("service", command.origin());
            run.put("typed", command.byHand());
            run.put("asRoot", command.asRoot());
            run.put("command", command.command());
            run.put("exitCode", command.exitCode());
            run.put("durationMs", command.durationMs());
            run.put("outputBytes", command.outputBytes());
            timeline.add(run);
        }
        root.put("timeline", timeline);
        return root;
    }

    /** The export as text, indented to be read by a person as well as a program. */
    public static String json(Session session, Instant exportedAt) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(of(session, exportedAt));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A map of strings and numbers could not be written as JSON", e);
        }
    }

    /** Writes the export to a file. */
    public static void write(Session session, Path file, Instant exportedAt) throws IOException {
        Files.writeString(file, json(session, exportedAt), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> service(String origin, List<CommandGroup> groups) {
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("service", origin);
        service.put("label", LABELS.getOrDefault(origin, origin));
        service.put("totals", totals(groups.stream().flatMap(group -> group.runs().stream()).toList()));

        // The same command run again is counted, not listed again: a reading every twelve seconds is one line
        // with its count and every time it ran, not three hundred lines.
        List<Map<String, Object>> distinct = new ArrayList<>();
        for (CommandGroup group : groups) {
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("command", group.command());
            command.put("count", group.count());
            command.put("failed", group.failed());
            command.put("notRun", group.notRun());
            command.put("asRoot", group.asRoot());
            command.put("typed", group.typed());
            command.put("averageMs", group.averageMs());
            command.put("first", group.first().toString());
            command.put("last", group.last().toString());
            command.put("at", group.runs().stream().map(run -> run.at().toString()).toList());
            distinct.add(command);
        }
        service.put("commands", distinct);
        return service;
    }

    /** How many, split every way the file is asked to split them. */
    private static Map<String, Object> totals(List<Command> commands) {
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("commands", commands.size());
        totals.put("distinct", commands.stream().map(Command::command).distinct().count());
        totals.put("sentByKubo", commands.stream().filter(command -> !command.byHand()).count());
        totals.put("typed", commands.stream().filter(Command::byHand).count());
        totals.put("asRoot", commands.stream().filter(Command::asRoot).count());
        totals.put("succeeded", commands.stream().filter(Command::success).count());
        totals.put("failed", commands.stream().filter(command -> command.ran() && !command.success()).count());
        totals.put("notRun", commands.stream().filter(command -> !command.ran()).count());
        return totals;
    }
}
