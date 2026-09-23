package com.kubo.logs;

import com.kubo.command.CommandEntry;
import com.kubo.logs.SessionHistory.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The BY SERVICE view of the journal and of the export: each command once, with how often and when. */
class CommandGroupTest {

    private static final Instant T = Instant.parse("2026-09-23T10:00:00Z");

    private static Command run(int second, String origin, String command, int exit, long ms) {
        return new Command(T.plusSeconds(second), origin, command, exit, ms, 0, "", false, false);
    }

    @Test
    @DisplayName("the busiest screen first, and in it the command run most often first")
    void groupsByScreenThenCommand() {
        List<CommandGroup> groups = CommandGroup.of(List.of(
                run(0, "NETWORK", "ufw status", 0, 5),
                run(1, "MONITOR", "uptime", 0, 10),
                run(2, "MONITOR", "df -h", 0, 10),
                run(3, "MONITOR", "uptime", 1, 20),
                run(4, "NETWORK", "ss -tn", CommandEntry.NOT_RUN, 0)));

        assertEquals(List.of("MONITOR uptime", "MONITOR df -h", "NETWORK ufw status", "NETWORK ss -tn"),
                groups.stream().map(group -> group.origin() + " " + group.command()).toList());
        CommandGroup uptime = groups.get(0);
        assertEquals(2, uptime.count());
        assertEquals(T.plusSeconds(1), uptime.first());
        assertEquals(T.plusSeconds(3), uptime.last());
        assertEquals(1, uptime.failed());
        assertEquals(15, uptime.averageMs());
        assertEquals(1, groups.get(3).notRun());
        assertEquals(0, groups.get(3).failed(), "a command that never ran did not fail, it was never answered");
    }

    @Test
    @DisplayName("what was typed and what ran as root is counted in each group")
    void countsTypedAndRoot() {
        CommandGroup group = CommandGroup.of(List.of(
                new Command(T, "NETWORK", "ufw status", 0, 1, 0, "", true, true),
                new Command(T, "NETWORK", "ufw status", 0, 1, 0, "", true, false))).get(0);

        assertEquals(2, group.asRoot());
        assertEquals(1, group.typed());
    }

    @Test
    @DisplayName("nothing sent is no group, and a group is never empty")
    void emptyIsNothing() {
        assertEquals(List.of(), CommandGroup.of(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CommandGroup("X", "ls", List.of()));
    }
}
