package com.kubo.command;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * History of the commands sent during the session. Safe to use from several threads.
 */
public class CommandService {

    /**
     * Who sent a command.
     */
    public enum Origin {
        /** Periodic readings of the server's state. */
        MONITOR,
        /** The file manager reading folders and sizes. */
        FILES,
        /** Typed by the user. */
        USER,
        /** Docker: listing, figures, actions and commands run inside containers. */
        DOCKER,
        /** Redis: dashboard figures, key scans and value reads or edits. */
        REDIS,
        /** Network: firewall, web routing, listening ports and the processes behind them. */
        NETWORK,
        /** Packages: what apt and dpkg report, and the installs, removals and upgrades asked for. */
        PACKAGES,
        /** Services: what systemd reports about its units and the last boot. */
        SERVICES
    }

    /**
     * Oldest entries are dropped past this size: periodic probes add about 30 entries a minute.
     */
    public static final int MAX_HISTORY = 1_000;

    private final Deque<CommandEntry> history = new ArrayDeque<>();
    private final List<BiConsumer<CommandEntry, Origin>> listeners = new CopyOnWriteArrayList<>();

    public void add(CommandEntry entry, Origin origin) {
        Objects.requireNonNull(entry, "entry cannot be null");
        Objects.requireNonNull(origin, "origin cannot be null");
        synchronized (this) {
            history.addLast(entry);
            while (history.size() > MAX_HISTORY) {
                history.removeFirst();
            }
        }
        for (BiConsumer<CommandEntry, Origin> listener : listeners) {
            listener.accept(entry, origin);
        }
    }

    public synchronized List<CommandEntry> getHistory() {
        return List.copyOf(history);
    }

    /**
     * Receives every new entry, on the thread that added it.
     *
     * @return removes the listener
     */
    public Runnable addListener(BiConsumer<CommandEntry, Origin> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
