package com.kubo.model.network;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The firewall standing in front of this server: which tool runs it, whether it is enforcing, and the rules it
 * holds.
 *
 * <p>Linux servers do not agree on a firewall. Ubuntu and Debian usually run UFW, Red Hat and its family run
 * firewalld, and plenty of machines have nothing but raw nftables or iptables. Kubo reads whichever it finds, and
 * where it cannot read one it says so rather than guessing — claiming "nothing is filtered" on a server that is in
 * fact filtered is the one mistake this screen must not make.
 */
public record Firewall(Kind kind, State state, List<FirewallRule> rules) {

    /** Which tool is in charge of the filtering. */
    public enum Kind {
        UFW("UFW"),
        FIREWALLD("FIREWALLD"),
        NFTABLES("NFTABLES"),
        IPTABLES("IPTABLES"),
        /** No firewall tool was found at all. */
        NONE("FIREWALL");

        private final String title;

        Kind(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    public enum State {
        /** Running and enforcing rules Kubo could read. */
        ACTIVE,
        /** Installed but switched off, or its default policy accepts everything: nothing is filtered. */
        INACTIVE,
        /** No firewall tool is installed on this server. */
        ABSENT,
        /** A firewall is there but its status could not be read, which usually needs root. */
        UNREADABLE,
        /** A firewall is running with rules Kubo does not know how to read. */
        UNRECOGNISED
    }

    public Firewall {
        kind = kind == null ? Kind.NONE : kind;
        rules = List.copyOf(rules == null ? List.of() : rules);
    }

    public static Firewall absent() {
        return new Firewall(Kind.NONE, State.ABSENT, List.of());
    }

    public static Firewall of(Kind kind, State state) {
        return new Firewall(kind, state, List.of());
    }

    public boolean isEnforcing() {
        return state == State.ACTIVE;
    }

    /**
     * True when Kubo can actually answer the question "is this port filtered?". When it cannot — the status needed
     * root, or the rules are in a form it does not read — every answer below is a guess, and the screen says so
     * instead of showing one.
     */
    public boolean isKnown() {
        return state == State.ACTIVE || state == State.INACTIVE || state == State.ABSENT;
    }

    /** The tool's name, for anything shown to the user. */
    public String title() {
        return kind.title();
    }

    /**
     * The rule that decides this port's fate, if any: the first one naming it.
     */
    public Optional<FirewallRule> ruleFor(int port) {
        return rules.stream().filter(rule -> !rule.ports().isEmpty() && rule.ports().contains(port)).findFirst();
    }

    /**
     * True when the firewall certainly stops the internet reaching this port. A firewall that is off or absent stops
     * nothing; one Kubo could not read is not counted as protection either, but {@link #isKnown()} says which of
     * those two it is so the screen can word it honestly.
     */
    public boolean blocks(int port) {
        if (!isEnforcing()) {
            return false;
        }
        Optional<FirewallRule> rule = ruleFor(port);
        // With the firewall enforcing and no rule naming the port, the default incoming policy denies it.
        return rule.map(found -> !found.opensToTheWorld(port)).orElse(true);
    }

    /**
     * How the state reads in one word, for a readout.
     */
    public String shortState() {
        return switch (state) {
            case ACTIVE -> "ON";
            case INACTIVE -> "OFF";
            case ABSENT -> "NONE";
            case UNREADABLE, UNRECOGNISED -> "?";
        };
    }

    /**
     * Why the firewall cannot be counted on, worded for the user, or empty when it can.
     */
    public String problem() {
        return switch (state) {
            case ACTIVE -> "";
            case INACTIVE -> title() + " is installed but not filtering: every listening port is reachable.";
            case ABSENT -> "No firewall was found on this server, so nothing here is filtered by the machine "
                    + "itself. Your provider may still filter in front of it.";
            case UNREADABLE -> title() + " is installed but only root may read its rules. Turn on ROOT in the "
                    + "header and refresh, and this screen will show what it allows. Until then nothing below "
                    + "counts it as protection.";
            case UNRECOGNISED -> title() + " is running, but Kubo cannot read which ports it allows: either its "
                    + "rules need root — turn on ROOT in the header and refresh — or they are in a form Kubo does "
                    + "not read. Your server may well be protected; nothing below assumes it is.";
        };
    }
}
