package com.kubo.model.services;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Everything one reading of the services says: the units, how the last boot went, and what could not be read.
 *
 * <p>As everywhere in Kubo, a thing that could not be read is named, never guessed at.
 */
public record ServicesReport(List<ServiceUnit> units, Duration boot, Duration userspace, List<String> unreadable) {

    public static final ServicesReport EMPTY = new ServicesReport(List.of(), null, null, List.of());

    public ServicesReport {
        units = List.copyOf(units);
        unreadable = List.copyOf(unreadable);
    }

    /** True when the server does not run systemd at all, so this screen has nothing to say about it. */
    public boolean absent() {
        return units.isEmpty() && unreadable.contains(NO_SYSTEMD);
    }

    public static final String NO_SYSTEMD = "This server does not run systemd";

    /**
     * The units of one standing, the ones that need looking at first and then by name.
     */
    public List<ServiceUnit> of(Standing standing) {
        List<ServiceUnit> found = new ArrayList<>();
        for (ServiceUnit unit : units) {
            if (unit.standing() == standing) {
                found.add(unit);
            }
        }
        // By name, and nothing else. A group holds a hundred units and what anybody does with a hundred
        // of anything is look for one of them by name; an order they have to read through first is an order
        // that hides it. The slow ones are not lost by this: each carries its own time beside its name, and
        // the total for the machine is in the header.
        found.sort(Comparator.comparing(ServiceUnit::shortName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ServiceUnit::shortName));
        return found;
    }

    public int count(Standing standing) {
        int total = 0;
        for (ServiceUnit unit : units) {
            if (unit.standing() == standing) {
                total++;
            }
        }
        return total;
    }

    /** The slowest units of the last boot, longest first. */
    public List<ServiceUnit> slowest(int limit) {
        List<ServiceUnit> timed = new ArrayList<>();
        for (ServiceUnit unit : units) {
            if (unit.startup() != null && !unit.startup().isZero()) {
                timed.add(unit);
            }
        }
        timed.sort(Comparator.comparing(ServiceUnit::startup).reversed());
        return timed.subList(0, Math.min(limit, timed.size()));
    }
}
