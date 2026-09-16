package com.kubo.command;

import com.kubo.command.CommandService.Origin;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandServiceTest {

    private static CommandEntry entry(String command) {
        return new CommandEntry(Instant.now(), command, false, 0, "", "", Duration.ofMillis(40));
    }

    @Test
    void historyIsEmptyAtStart() {
        CommandService service = new CommandService();

        assertTrue(service.getHistory().isEmpty());
    }

    @Test
    void historyKeepsEntriesInOrder() {
        CommandService service = new CommandService();
        CommandEntry first = entry("docker ps");
        CommandEntry second = entry("ss -tulpnH");

        service.add(first, Origin.MONITOR);
        service.add(second, Origin.USER);

        assertEquals(List.of(first, second), service.getHistory());
    }

    @Test
    void historyDropsOldestEntriesPastTheLimit() {
        CommandService service = new CommandService();
        for (int i = 0; i < CommandService.MAX_HISTORY + 5; i++) {
            service.add(entry("command " + i), Origin.MONITOR);
        }

        List<CommandEntry> history = service.getHistory();

        assertEquals(CommandService.MAX_HISTORY, history.size());
        assertEquals("command 5", history.get(0).command());
        assertEquals("command " + (CommandService.MAX_HISTORY + 4), history.get(history.size() - 1).command());
    }

    @Test
    void addRejectsNull() {
        CommandService service = new CommandService();

        assertThrows(NullPointerException.class, () -> service.add(null, Origin.USER));
        assertThrows(NullPointerException.class, () -> service.add(entry("ls"), null));
        assertTrue(service.getHistory().isEmpty());
    }

    @Test
    void returnedHistoryCannotBeModified() {
        CommandService service = new CommandService();
        service.add(entry("docker ps"), Origin.MONITOR);

        List<CommandEntry> history = service.getHistory();

        assertThrows(UnsupportedOperationException.class, history::clear);
        assertEquals(1, service.getHistory().size());
    }

    @Test
    void returnedHistoryIsASnapshot() {
        CommandService service = new CommandService();
        service.add(entry("docker ps"), Origin.MONITOR);
        List<CommandEntry> before = service.getHistory();

        service.add(entry("ss -tulpnH"), Origin.MONITOR);

        assertEquals(1, before.size());
        assertEquals(2, service.getHistory().size());
    }

    @Test
    void listenersReceiveEntriesWithTheirOriginUntilRemoved() {
        CommandService service = new CommandService();
        List<String> received = new ArrayList<>();
        Runnable remove = service.addListener((entry, origin) -> received.add(origin + " " + entry.command()));

        service.add(entry("ls"), Origin.USER);
        service.add(entry("find"), Origin.FILES);
        remove.run();
        service.add(entry("uptime"), Origin.MONITOR);

        assertEquals(List.of("USER ls", "FILES find"), received);
    }
}
