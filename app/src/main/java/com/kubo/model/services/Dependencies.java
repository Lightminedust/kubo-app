package com.kubo.model.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which service needs which, both ways round.
 *
 * <p>systemd answers only one direction — what a unit requires, wants and starts after. The other direction is
 * the one people actually ask for ("if I stop this, what breaks?"), so it is worked out once here rather than
 * searched for on every click.
 */
public record Dependencies(Map<String, List<String>> needs, Map<String, List<String>> neededBy) {

    public static final Dependencies EMPTY = new Dependencies(Map.of(), Map.of());

    public Dependencies {
        needs = Map.copyOf(needs);
        neededBy = Map.copyOf(neededBy);
    }

    /** Builds both directions from the one systemd gives. */
    public static Dependencies of(Map<String, List<String>> needs) {
        Map<String, List<String>> reverse = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : needs.entrySet()) {
            for (String needed : entry.getValue()) {
                reverse.computeIfAbsent(needed, key -> new ArrayList<>()).add(entry.getKey());
            }
        }
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : reverse.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new Dependencies(needs, frozen);
    }

    public List<String> needsOf(String unit) {
        return needs.getOrDefault(unit, List.of());
    }

    public List<String> neededByOf(String unit) {
        return neededBy.getOrDefault(unit, List.of());
    }

    public boolean isEmpty() {
        return needs.isEmpty();
    }
}
