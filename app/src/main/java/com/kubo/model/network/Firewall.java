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
public record Firewall(Kind kind, State state, List<FirewallRule> rules, Defaults defaults) {

    /**
     * What happens to traffic no rule names. Half the answer to "is this port open", and the half the rule list
     * does not show.
     *
     * @param incoming "deny", "reject" or "allow", empty when it was not read
     */
    public record Defaults(String incoming, String outgoing, String routed) {

        public Defaults {
            incoming = incoming == null ? "" : incoming.strip().toLowerCase(Locale.ROOT);
            outgoing = outgoing == null ? "" : outgoing.strip().toLowerCase(Locale.ROOT);
            routed = routed == null ? "" : routed.strip().toLowerCase(Locale.ROOT);
        }

        public static Defaults unknown() {
            return new Defaults("", "", "");
        }

        public boolean known() {
            return !incoming.isEmpty();
        }

        /** True when anything no rule names is let in, which turns every rule list into a list of exceptions. */
        public boolean letsEverythingIn() {
            return incoming.equals("allow");
        }
    }

    /**
     * One rule as a person thinks of it: UFW writes most rules twice, once for IPv4 and once for IPv6, and a list
     * of eleven lines is really six decisions.
     *
     * @param v4 the IPv4 half, or null for a rule that exists for IPv6 only
     * @param v6 the IPv6 half, or null
     */
    public record RuleGroup(FirewallRule v4, FirewallRule v6) {

        /** Whichever half there is, for what both halves share. */
        public FirewallRule rule() {
            return v4 != null ? v4 : v6;
        }

        /** "v4 + v6", "v4 only", "v6 only". */
        public String families() {
            if (v4 != null && v6 != null) {
                return "v4 + v6";
            }
            return v4 != null ? "v4 only" : "v6 only";
        }

        /** The numbers to delete for this group, highest first: UFW renumbers after each one goes. */
        public List<Integer> numbers() {
            List<Integer> numbers = new java.util.ArrayList<>();
            if (v4 != null) {
                numbers.add(v4.index());
            }
            if (v6 != null) {
                numbers.add(v6.index());
            }
            numbers.sort(java.util.Comparator.reverseOrder());
            return List.copyOf(numbers);
        }

        List<FirewallRule> halves() {
            List<FirewallRule> halves = new java.util.ArrayList<>();
            if (v4 != null) {
                halves.add(v4);
            }
            if (v6 != null) {
                halves.add(v6);
            }
            return halves;
        }
    }

    /**
     * What deleting some rules would do to the SSH port Kubo itself came in on.
     *
     * @param risky  true when, once they are gone, nothing lets that port through any more
     * @param reason what to tell somebody before they do it, empty when there is nothing to say
     */
    public record Lockout(boolean risky, String reason) {
    }

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
        UNRECOGNISED,
        /**
         * The look for a firewall did not come back.
         *
         * <p>Separate from {@link #ABSENT} on purpose. A command that failed and a command that answered
         * "nothing installed" look the same from here, and reading the first as the second is how a screen
         * ends up telling somebody their server is unprotected when it is not.
         */
        UNCHECKED
    }

    public Firewall {
        kind = kind == null ? Kind.NONE : kind;
        rules = List.copyOf(rules == null ? List.of() : rules);
        defaults = defaults == null ? Defaults.unknown() : defaults;
    }

    public Firewall(Kind kind, State state, List<FirewallRule> rules) {
        this(kind, state, rules, Defaults.unknown());
    }

    /**
     * The rules paired into their IPv4 and IPv6 halves, in the order of the first half of each.
     *
     * <p>Two rules are halves of one when they say the same thing apart from the family: the same destination,
     * the same action, the same source.
     */
    public List<RuleGroup> grouped() {
        java.util.Map<String, FirewallRule[]> byKey = new java.util.LinkedHashMap<>();
        for (FirewallRule rule : rules) {
            String key = bare(rule.to()) + "|" + rule.action() + "|" + bare(rule.from());
            FirewallRule[] pair = byKey.computeIfAbsent(key, any -> new FirewallRule[2]);
            if (rule.v6() && pair[1] == null) {
                pair[1] = rule;
            } else if (!rule.v6() && pair[0] == null) {
                pair[0] = rule;
            } else {
                // A second rule identical to one already paired: kept on its own line rather than lost.
                byKey.put(key + "|" + rule.index(), rule.v6() ? new FirewallRule[]{null, rule}
                        : new FirewallRule[]{rule, null});
            }
        }
        List<RuleGroup> groups = new java.util.ArrayList<>();
        for (FirewallRule[] pair : byKey.values()) {
            groups.add(new RuleGroup(pair[0], pair[1]));
        }
        return List.copyOf(groups);
    }

    private static String bare(String text) {
        return text.replace("(v6)", "").strip().replaceAll("\\s+", " ");
    }

    /**
     * Whether deleting these rules would close the port Kubo is connected through.
     *
     * <p>UFW keeps an established connection open when a rule goes, so the session doing the deleting survives.
     * The next one does not: the reconnect after a network blip, tomorrow's login, Kubo's next start. That is
     * how a server ends up reachable only from its provider's console.
     *
     * <p>Safe when the firewall is off, when anything not named is let in anyway, or when another rule still lets
     * the port through for every family being deleted. Two rules for SSH is common: OpenSSH and 22/tcp both.
     */
    public Lockout ifDeleted(RuleGroup doomed, int sshPort) {
        List<FirewallRule> going = doomed.halves();
        boolean opensIt = going.stream().anyMatch(rule -> rule.action().letsThrough()
                && !rule.ports().isEmpty() && rule.ports().contains(sshPort));
        if (!opensIt || !isEnforcing()) {
            return new Lockout(false, "");
        }
        if (defaults.letsEverythingIn()) {
            return new Lockout(false, "Anything not listed is let in anyway, so " + sshPort + " stays open.");
        }
        for (FirewallRule half : going) {
            java.util.Optional<FirewallRule> other = rules.stream()
                    .filter(rule -> !going.contains(rule) && rule.v6() == half.v6() && rule.action().letsThrough()
                            && !rule.ports().isEmpty() && rule.ports().contains(sshPort)
                            && rule.from().toLowerCase(Locale.ROOT).startsWith("anywhere"))
                    .findFirst();
            if (other.isEmpty() && !half.v6()) {
                return new Lockout(true, "Kubo is connected through port " + sshPort + ", and nothing else in "
                        + "this firewall lets " + sshPort + " through. The session open now stays open. The next "
                        + "SSH connection, Kubo's included, will be refused, and the way back in is your "
                        + "provider's console.");
            }
            if (other.isEmpty()) {
                return new Lockout(true, "Nothing else lets " + sshPort + " through over IPv6. Kubo is not "
                        + "affected, but anyone reaching this server by its IPv6 address will be refused SSH.");
            }
        }
        FirewallRule other = rules.stream()
                .filter(rule -> !going.contains(rule) && rule.action().letsThrough()
                        && !rule.ports().isEmpty() && rule.ports().contains(sshPort))
                .findFirst().orElseThrow();
        return new Lockout(false, bare(other.to()) + " still lets " + sshPort + " through, so SSH stays open.");
    }

    /** The look did not come back. Not the same thing as nothing being there. */
    public static Firewall unchecked() {
        return new Firewall(Kind.NONE, State.UNCHECKED, List.of());
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
     * Whether Kubo can switch this firewall on and off at all.
     *
     * <p>UFW and firewalld have one switch each and Kubo knows where it is. Raw nftables and iptables do not:
     * there is no "off" for them, only a ruleset that may or may not be loaded by something else at boot, and
     * guessing which something is how a server comes back from a reboot with no filtering at all.
     */
    public boolean canBeSwitched() {
        return kind == Kind.UFW || kind == Kind.FIREWALLD;
    }

    /**
     * What switching it off would mean.
     *
     * <p>Always worth a confirmation while it is enforcing. Turning a firewall off does not open one port, it
     * opens every port anything on the machine is listening on, to the whole internet, in one command. There is
     * no partial version of this to be careless about.
     */
    public Lockout ifSwitchedOff() {
        if (!isEnforcing()) {
            return new Lockout(false, "");
        }
        String counted = rules.isEmpty() ? "" : " Its " + rules.size()
                + (rules.size() == 1 ? " rule stays written down" : " rules stay written down")
                + " and will apply again when it is switched back on.";
        return new Lockout(true, "Switching " + title() + " off stops it filtering anything at all. Every port "
                + "a program on this machine is listening on becomes reachable from anywhere, at once." + counted);
    }

    /**
     * What switching it on would mean, which is the more dangerous direction.
     *
     * <p>A firewall that comes up denying incoming traffic, with nothing in it that lets SSH through, refuses
     * the next connection. The session open now survives, because it is already established, so nothing appears
     * to go wrong until the moment somebody needs to get back in.
     *
     * <p>UFW asks about this itself and Kubo passes {@code --force} to get past its prompt, which a
     * non-interactive session cannot answer. That makes asking here not a nicety but the replacement for a
     * question that was being skipped.
     *
     * @param sshPort the port this session came in on
     */
    public Lockout ifSwitchedOn(int sshPort) {
        if (isEnforcing()) {
            return new Lockout(false, "");
        }
        if (rules.isEmpty()) {
            // Switched off, UFW prints no rules, and there is no honest way to promise anything about them.
            return new Lockout(true, "Kubo cannot read the rules of a firewall that is switched off, so it "
                    + "cannot tell you whether port " + sshPort + " will still be let through. If nothing in it "
                    + "allows " + sshPort + ", this session stays up and the next one is refused.");
        }
        if (defaults.known() && defaults.letsEverythingIn()) {
            return new Lockout(false, "Its default is to let incoming traffic in, so " + sshPort
                    + " stays open.");
        }
        boolean allowed = rules.stream().anyMatch(rule -> rule.action().letsThrough()
                && !rule.ports().isEmpty() && rule.ports().contains(sshPort));
        if (allowed) {
            return new Lockout(false, "");
        }
        return new Lockout(true, "Nothing in this firewall lets port " + sshPort + " through, and Kubo is "
                + "connected on it. The session open now stays open. The next SSH connection will be refused, "
                + "and the way back in is your provider's console.");
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
        // With the firewall enforcing and no rule naming the port, the default incoming policy decides. It is
        // deny on every install Kubo has seen, and read when it can be; a server set to let everything in is not
        // blocking anything its rules do not name.
        return rule.map(found -> !found.opensToTheWorld(port)).orElse(!defaults.letsEverythingIn());
    }

    /**
     * How the state reads in one word, for a readout.
     */
    public String shortState() {
        return switch (state) {
            case ACTIVE -> "ON";
            case INACTIVE -> "OFF";
            case ABSENT -> "NONE";
            case UNREADABLE, UNRECOGNISED, UNCHECKED -> "?";
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
            case UNCHECKED -> "Kubo could not find out whether this server has a firewall: the command that "
                    + "looks for ufw, firewalld, nft and iptables did not answer. This is not a statement that "
                    + "there is no firewall. Turn on ROOT and re-read, and check it yourself in the shell with "
                    + "command -v ufw nft iptables.";
        };
    }
}
