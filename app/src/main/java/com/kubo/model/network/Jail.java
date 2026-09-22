package com.kubo.model.network;

import java.util.List;
import java.util.Objects;

/**
 * One fail2ban jail: what it watches, how many attempts it has seen, and who is banned right now.
 *
 * <p>fail2ban is the one thing on a small server that actually stops a brute force, and nothing in Kubo showed
 * whether it was running, let alone who it had caught.
 *
 * @param banned the addresses banned at this moment, which is the list worth acting on
 */
public record Jail(String name, int currentlyFailed, int totalFailed, int totalBanned, List<String> banned) {

    public Jail {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a jail has a name");
        }
        banned = List.copyOf(banned == null ? List.of() : banned);
    }

    public int currentlyBanned() {
        return banned.size();
    }

    public boolean quiet() {
        return banned.isEmpty() && currentlyFailed == 0;
    }
}
