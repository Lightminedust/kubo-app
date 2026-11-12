package com.kubo.model.apps;

import java.util.List;
import java.util.Objects;

/**
 * One application and every process of it: one for a fork, several for a PM2 cluster.
 *
 * @param ports   the ports it listens on, each with the address it is bound to
 * @param saved   for PM2, whether it is in the saved list that comes back after a reboot; true for supervisor,
 *                whose programs are files and always come back
 */
public record App(Manager manager, String name, List<AppProcess> processes, List<Listening> ports, boolean saved) {

    /** A port, and the address it listens on. */
    public record Listening(String address, int port) {

        /** Bound to every address, so reachable from outside without going through Nginx. */
        public boolean everywhere() {
            return address.equals("0.0.0.0") || address.equals("*") || address.equals("::") || address.equals("[::]");
        }

        @Override
        public String toString() {
            return (address.contains(":") && !address.startsWith("[") ? "[" + address + "]" : address) + ":" + port;
        }
    }

    /** How the application holds, all its processes taken together. */
    public enum State {
        /** Every process up. */
        UP,
        /** Some up, some not. */
        PARTLY,
        /** Something crashed or was given up on. */
        FAILED,
        /** Stopped by someone. */
        STOPPED,
        /** Starting or stopping. */
        MOVING
    }

    public App {
        Objects.requireNonNull(manager, "manager");
        name = name == null ? "" : name;
        processes = List.copyOf(processes);
        ports = List.copyOf(ports);
    }

    /** What actions address it by: its manager and its name. */
    public String key() {
        return manager.word() + ":" + name;
    }

    public int online() {
        return (int) processes.stream().filter(AppProcess::online).count();
    }

    public State state() {
        int up = online();
        if (processes.stream().anyMatch(AppProcess::failed)) {
            return up > 0 ? State.PARTLY : State.FAILED;
        }
        if (processes.stream().anyMatch(AppProcess::moving)) {
            return State.MOVING;
        }
        if (up == processes.size() && up > 0) {
            return State.UP;
        }
        return up == 0 ? State.STOPPED : State.PARTLY;
    }

    /** Cores in use, all processes together: 1.0 is one whole core. */
    public double cores() {
        return processes.stream().mapToDouble(AppProcess::cpu).sum() / 100.0;
    }

    public long memory() {
        return processes.stream().mapToLong(AppProcess::memory).sum();
    }

    public int restarts() {
        return processes.stream().mapToInt(AppProcess::restarts).sum();
    }

    public boolean cluster() {
        return processes.stream().anyMatch(process -> process.mode().equals("cluster"));
    }

    /** The first process, which carries what all of them share: the script, the folder, the logs. */
    public AppProcess first() {
        return processes.isEmpty() ? null : processes.get(0);
    }

    /** Listening on every address: anyone can reach it without Nginx in front. */
    public boolean exposed() {
        return ports.stream().anyMatch(Listening::everywhere);
    }
}
