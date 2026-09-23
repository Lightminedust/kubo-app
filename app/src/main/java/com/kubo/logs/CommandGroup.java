package com.kubo.logs;

import com.kubo.logs.SessionHistory.Command;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every run of one command sent by one screen: a reading taken every two seconds is one group of nine hundred
 * runs, not nine hundred lines.
 *
 * @param runs in the order they ran, never empty
 */
public record CommandGroup(String origin, String command, List<Command> runs) {

    public CommandGroup {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(command, "command");
        runs = List.copyOf(runs);
        if (runs.isEmpty()) {
            throw new IllegalArgumentException("A group has at least one run");
        }
    }

    public int count() {
        return runs.size();
    }

    public Instant first() {
        return runs.get(0).at();
    }

    public Instant last() {
        return runs.get(runs.size() - 1).at();
    }

    public long failed() {
        return runs.stream().filter(run -> run.ran() && !run.success()).count();
    }

    public long notRun() {
        return runs.stream().filter(run -> !run.ran()).count();
    }

    public long asRoot() {
        return runs.stream().filter(Command::asRoot).count();
    }

    public long typed() {
        return runs.stream().filter(Command::byHand).count();
    }

    public long averageMs() {
        return Math.round(runs.stream().mapToLong(Command::durationMs).average().orElse(0));
    }

    /**
     * The commands of a session, one group per screen and command: the busiest screen first, and in each screen
     * the command run most often first. Equal counts keep the order they first appeared in.
     */
    public static List<CommandGroup> of(List<Command> commands) {
        Map<String, Map<String, List<Command>>> byOrigin = new LinkedHashMap<>();
        for (Command command : commands) {
            byOrigin.computeIfAbsent(command.origin(), any -> new LinkedHashMap<>())
                    .computeIfAbsent(command.command(), any -> new ArrayList<>()).add(command);
        }
        List<CommandGroup> groups = new ArrayList<>();
        byOrigin.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Map<String, List<Command>>> entry) ->
                        entry.getValue().values().stream().mapToLong(List::size).sum()).reversed())
                .forEach(origin -> origin.getValue().entrySet().stream()
                        .sorted(Comparator.comparingInt((Map.Entry<String, List<Command>> entry) ->
                                entry.getValue().size()).reversed())
                        .forEach(entry -> groups.add(new CommandGroup(origin.getKey(), entry.getKey(),
                                entry.getValue()))));
        return groups;
    }
}
