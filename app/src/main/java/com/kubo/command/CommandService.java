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

    /**
     * One command as the session keeps it: what ran, which screen sent it, and whether a person typed it.
     *
     * @param typed true when somebody typed it into a console, false when Kubo sent it on its own
     */
    public record Logged(CommandEntry entry, Origin origin, boolean typed) {

        public Logged {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(origin, "origin");
        }
    }

    private final Deque<CommandEntry> history = new ArrayDeque<>();
    private final List<BiConsumer<CommandEntry, Origin>> listeners = new CopyOnWriteArrayList<>();
    private final List<java.util.function.Consumer<Logged>> loggedListeners = new CopyOnWriteArrayList<>();

    /** A command Kubo sent on its own. */
    public void add(CommandEntry entry, Origin origin) {
        add(entry, origin, false);
    }

    /**
     * @param typed true when somebody typed it into a console of this screen
     */
    public void add(CommandEntry entry, Origin origin, boolean typed) {
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
        Logged logged = new Logged(entry, origin, typed);
        for (java.util.function.Consumer<Logged> listener : loggedListeners) {
            listener.accept(logged);
        }
    }

    /**
     * Receives every new entry with whether it was typed, on the thread that added it.
     *
     * @return removes the listener
     */
    public Runnable addLoggedListener(java.util.function.Consumer<Logged> listener) {
        Objects.requireNonNull(listener, "listener");
        loggedListeners.add(listener);
        return () -> loggedListeners.remove(listener);
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
