package com.kubo.model.network;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One numbered UFW rule, as {@code ufw status numbered} prints it.
 *
 * @param index  the number UFW gave it, which is what a deletion refers to
 * @param to     the destination it covers, e.g. "22/tcp", "80,443/tcp", "Anywhere"
 * @param from   where it applies from, e.g. "Anywhere", "192.168.1.0/24"
 * @param v6     true for the IPv6 half of a rule
 * @param ports  the ports the rule names, empty when it names none (such as an "Anywhere" rule)
 */
public record FirewallRule(int index, String to, Action action, String from, boolean v6, List<Integer> ports) {

    /** What UFW does with matching traffic. */
    public enum Action {
        ALLOW, DENY, REJECT, LIMIT, OTHER;

        public static Action parse(String text) {
            String upper = text == null ? "" : text.strip().toUpperCase(Locale.ROOT);
            if (upper.startsWith("ALLOW")) {
                return ALLOW;
            }
            if (upper.startsWith("DENY")) {
                return DENY;
            }
            if (upper.startsWith("REJECT")) {
                return REJECT;
            }
            if (upper.startsWith("LIMIT")) {
                return LIMIT;
            }
            return OTHER;
        }

        /** True when traffic is let through rather than stopped. */
        public boolean letsThrough() {
            return this == ALLOW || this == LIMIT;
        }
    }

    public FirewallRule {
        to = to == null ? "" : to;
        from = from == null ? "" : from;
        action = action == null ? Action.OTHER : action;
        ports = List.copyOf(ports == null ? List.of() : ports);
    }

    /**
     * True when the rule covers this port. A rule naming no port covers everything.
     */
    public boolean covers(int port) {
        return ports.isEmpty() || ports.contains(port);
    }

    /**
     * True when the rule opens this port to anywhere, which is what makes a port reachable from the internet.
     */
    public boolean opensToTheWorld(int port) {
        return action.letsThrough() && !ports.isEmpty() && ports.contains(port)
                && from.toLowerCase(Locale.ROOT).startsWith("anywhere");
    }
}
