package com.kubo.network;

import com.kubo.model.network.Firewall;
import com.kubo.model.network.FirewallRule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading whichever firewall a Linux server happens to run.
 *
 * <p>There is no single answer: Debian and Ubuntu usually front iptables with UFW, Red Hat and its family run
 * firewalld, and many machines have nothing but raw nftables or iptables. Kubo looks for them in that order and
 * reads the first one it finds. Where a ruleset is in a shape it does not understand it returns
 * {@link Firewall.State#UNRECOGNISED} rather than an empty rule list, because an empty rule list reads on screen as
 * "nothing is filtered" and that would be a lie.
 *
 * <p>Every command here only reads.
 */
public final class FirewallCommands {

    /** Which firewall tools exist, most specific first. One line each, so the order of the answer is the order below. */
    public static final String DETECT = "for T in ufw firewall-cmd nft iptables; do "
            + "command -v \"$T\" >/dev/null 2>&1 && printf '%s\\n' \"$T\"; done";

    /** UFW's numbered rules. Needs root; the caller wraps it. */
    public static final String UFW = "ufw status numbered";

    /** firewalld: whether it is running, then the default zone in full. Both need root on most servers. */
    public static final String FIREWALLD = "firewall-cmd --state 2>&1; echo '---'; firewall-cmd --list-all 2>&1";

    /** The whole nftables ruleset. Needs root. */
    public static final String NFTABLES = "nft list ruleset 2>/dev/null";

    /** The iptables filter table as rules. Needs root. */
    public static final String IPTABLES = "iptables -S 2>/dev/null";

    /**
     * Whether a firewall is running, asked in the two ways that work without root.
     *
     * <p>The rules themselves live in root-only files and cannot be had any other way. But whether the thing is
     * switched on can be: systemd answers read-only queries to anyone, and Debian ships {@code /etc/ufw/ufw.conf}
     * world-readable. Knowing "it is running, I just cannot see its rules" is worth a great deal more to someone
     * than "I have no idea".
     */
    public static String liveness(Firewall.Kind kind) {
        String service = switch (kind) {
            case UFW -> "ufw";
            case FIREWALLD -> "firewalld";
            case NFTABLES -> "nftables";
            default -> "";
        };
        if (service.isEmpty()) {
            return "echo UNKNOWN";
        }
        return "if command -v systemctl >/dev/null 2>&1; then "
                + "printf 'SERVICE %s\n' \"$(systemctl is-active " + service + " 2>/dev/null)\"; fi; "
                + "if [ -r /etc/ufw/ufw.conf ]; then grep -i '^ENABLED' /etc/ufw/ufw.conf | head -n1; fi";
    }

    /** What {@link #liveness} could tell. */
    public enum Liveness { RUNNING, STOPPED, UNKNOWN }

    /**
     * Reads {@link #liveness}. Two sources may disagree — the unit can be inactive while rules are still loaded in
     * the kernel — so anything short of both saying "off" is not treated as off.
     */
    public static Liveness parseLiveness(String output) {
        String text = output == null ? "" : output.toLowerCase(Locale.ROOT);
        boolean serviceOn = text.contains("service active");
        boolean serviceOff = text.contains("service inactive") || text.contains("service failed");
        boolean configOn = text.contains("enabled=yes");
        boolean configOff = text.contains("enabled=no");
        if (serviceOn || configOn) {
            return Liveness.RUNNING;
        }
        if (serviceOff && configOff) {
            return Liveness.STOPPED;
        }
        return Liveness.UNKNOWN;
    }

    private static final Pattern UFW_RULE = Pattern.compile("^\\[\\s*(\\d+)]\\s+(.*)$");
    private static final Pattern UFW_ACTION =
            Pattern.compile("\\b(ALLOW|DENY|REJECT|LIMIT)\\b(\\s+(?:IN|OUT|FWD))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PORTS_IN_RULE = Pattern.compile("(\\d{1,5})(?::(\\d{1,5}))?");
    private static final Pattern PORT_SPEC = Pattern.compile("(\\d{1,5})(?:-(\\d{1,5}))?/(tcp|udp)");
    private static final Pattern IPTABLES_DPORT = Pattern.compile("--dport[s]?\\s+(\\d{1,5})(?::(\\d{1,5}))?");
    private static final Pattern IPTABLES_POLICY = Pattern.compile("^-P\\s+INPUT\\s+(\\S+)");
    private static final Pattern NFT_DPORT = Pattern.compile("dport\\s*\\{?\\s*([0-9,\\s-]+)}?");

    /**
     * The service names firewalld uses for the ports that matter on a server. A zone usually names services rather
     * than numbers, and "ssh" tells the user nothing about whether 22 is open.
     */
    private static final Map<String, Integer> SERVICE_PORTS = Map.ofEntries(
            Map.entry("ssh", 22), Map.entry("http", 80), Map.entry("https", 443), Map.entry("dns", 53),
            Map.entry("smtp", 25), Map.entry("smtps", 465), Map.entry("submission", 587),
            Map.entry("imap", 143), Map.entry("imaps", 993), Map.entry("pop3", 110), Map.entry("pop3s", 995),
            Map.entry("ftp", 21), Map.entry("mysql", 3306), Map.entry("postgresql", 5432),
            Map.entry("redis", 6379), Map.entry("mongodb", 27017), Map.entry("memcache", 11211),
            Map.entry("cockpit", 9090), Map.entry("samba", 445), Map.entry("rdp", 3389),
            Map.entry("ntp", 123), Map.entry("http-alt", 8080), Map.entry("proxy-http", 8080),
            Map.entry("elasticsearch", 9200), Map.entry("nfs", 2049), Map.entry("tftp", 69));

    private FirewallCommands() {
    }

    /**
     * The firewall tools the server has, in the order Kubo should try them.
     */
    public static List<Firewall.Kind> parseTools(String output) {
        List<Firewall.Kind> kinds = new ArrayList<>();
        for (String raw : output == null ? List.<String>of() : output.lines().toList()) {
            switch (raw.strip()) {
                case "ufw" -> kinds.add(Firewall.Kind.UFW);
                case "firewall-cmd" -> kinds.add(Firewall.Kind.FIREWALLD);
                case "nft" -> kinds.add(Firewall.Kind.NFTABLES);
                case "iptables" -> kinds.add(Firewall.Kind.IPTABLES);
                default -> {
                }
            }
        }
        return List.copyOf(kinds);
    }

    // UFW

    /**
     * Reads {@code ufw status numbered}.
     */
    public static Firewall parseUfw(String output) {
        String text = output == null ? "" : output;
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.contains("STATUS: INACTIVE")) {
            return Firewall.of(Firewall.Kind.UFW, Firewall.State.INACTIVE);
        }
        if (!upper.contains("STATUS: ACTIVE")) {
            return Firewall.of(Firewall.Kind.UFW, Firewall.State.UNREADABLE);
        }
        List<FirewallRule> rules = new ArrayList<>();
        for (String raw : text.lines().toList()) {
            Matcher matcher = UFW_RULE.matcher(raw.strip());
            if (!matcher.matches()) {
                continue;
            }
            int index = Integer.parseInt(matcher.group(1));
            String rest = matcher.group(2).strip();
            // "22/tcp                     ALLOW IN    Anywhere". Splitting around the verb rather than on column
            // widths keeps this working whatever spacing UFW uses.
            Matcher verb = UFW_ACTION.matcher(rest);
            if (!verb.find()) {
                continue;
            }
            String to = rest.substring(0, verb.start()).strip();
            String from = rest.substring(verb.end()).strip();
            boolean v6 = to.contains("(v6)") || from.contains("(v6)");
            rules.add(new FirewallRule(index, to, FirewallRule.Action.parse(verb.group(1)), from, v6, portsIn(to)));
        }
        return new Firewall(Firewall.Kind.UFW, Firewall.State.ACTIVE, rules);
    }

    // firewalld

    /**
     * Reads {@code firewall-cmd --state} and {@code --list-all} joined by a "---" line.
     *
     * <p>A zone lists both bare ports ("8080/tcp") and service names ("ssh http"); both become rules, so the screen
     * can say 22 is open rather than "ssh is allowed". A service firewalld knows and Kubo does not is kept as a rule
     * naming no port, which is honest: it is allowed, but Kubo will not pretend to know which port it opens.
     */
    public static Firewall parseFirewalld(String output) {
        String text = output == null ? "" : output;
        int split = text.indexOf("---");
        String state = (split < 0 ? text : text.substring(0, split)).strip().toLowerCase(Locale.ROOT);
        String zone = split < 0 ? "" : text.substring(split + 3);

        if (state.contains("not running")) {
            return Firewall.of(Firewall.Kind.FIREWALLD, Firewall.State.INACTIVE);
        }
        if (!state.contains("running")) {
            // "Authorization failed", "Failed to connect to bus" and the like: present, but not readable from here.
            return Firewall.of(Firewall.Kind.FIREWALLD, Firewall.State.UNREADABLE);
        }
        if (zone.isBlank() || zone.toLowerCase(Locale.ROOT).contains("authorization failed")) {
            return Firewall.of(Firewall.Kind.FIREWALLD, Firewall.State.UNREADABLE);
        }

        List<FirewallRule> rules = new ArrayList<>();
        int index = 1;
        for (String raw : zone.lines().toList()) {
            String line = raw.strip();
            if (line.startsWith("ports:")) {
                for (String spec : value(line).split("\\s+")) {
                    List<Integer> ports = portSpec(spec);
                    if (!ports.isEmpty()) {
                        rules.add(new FirewallRule(index++, spec, FirewallRule.Action.ALLOW, "Anywhere", false,
                                ports));
                    }
                }
            } else if (line.startsWith("services:")) {
                for (String name : value(line).split("\\s+")) {
                    if (name.isBlank()) {
                        continue;
                    }
                    Integer port = SERVICE_PORTS.get(name.toLowerCase(Locale.ROOT));
                    rules.add(new FirewallRule(index++, name, FirewallRule.Action.ALLOW, "Anywhere", false,
                            port == null ? List.of() : List.of(port)));
                }
            }
        }
        return new Firewall(Firewall.Kind.FIREWALLD, Firewall.State.ACTIVE, rules);
    }

    // iptables and nftables

    /**
     * Reads {@code iptables -S}. A chain whose policy is ACCEPT and that drops nothing is not filtering, whatever
     * rules it carries; saying so is more useful than listing accept rules that change nothing.
     */
    public static Firewall parseIptables(String output) {
        String text = output == null ? "" : output.strip();
        if (text.isEmpty()) {
            return Firewall.of(Firewall.Kind.IPTABLES, Firewall.State.UNREADABLE);
        }
        String policy = "";
        boolean dropsAnything = false;
        List<FirewallRule> rules = new ArrayList<>();
        int index = 1;
        for (String raw : text.lines().toList()) {
            String line = raw.strip();
            Matcher policyMatch = IPTABLES_POLICY.matcher(line);
            if (policyMatch.find()) {
                policy = policyMatch.group(1).toUpperCase(Locale.ROOT);
                continue;
            }
            if (!line.startsWith("-A INPUT")) {
                continue;
            }
            String target = targetOf(line);
            if (target.equals("DROP") || target.equals("REJECT")) {
                dropsAnything = true;
            }
            Matcher dport = IPTABLES_DPORT.matcher(line);
            if (!dport.find()) {
                continue;
            }
            List<Integer> ports = range(dport.group(1), dport.group(2));
            FirewallRule.Action action = FirewallRule.Action.parse(target.equals("ACCEPT") ? "ALLOW" : target);
            rules.add(new FirewallRule(index++, dport.group(1) + (line.contains("-p udp") ? "/udp" : "/tcp"),
                    action, "Anywhere", false, ports));
        }
        if (policy.equals("DROP") || policy.equals("REJECT")) {
            return new Firewall(Firewall.Kind.IPTABLES, Firewall.State.ACTIVE, rules);
        }
        if (!dropsAnything) {
            return Firewall.of(Firewall.Kind.IPTABLES, Firewall.State.INACTIVE);
        }
        // Accepting by default but dropping some things: real filtering Kubo cannot summarise per port.
        return Firewall.of(Firewall.Kind.IPTABLES, Firewall.State.UNRECOGNISED);
    }

    /**
     * Reads {@code nft list ruleset}. Only the common shape is understood — an input hook whose policy is drop,
     * with accept rules naming destination ports. Anything else is reported as unrecognised.
     */
    public static Firewall parseNftables(String output) {
        String text = output == null ? "" : output.strip();
        if (text.isEmpty()) {
            // No ruleset at all: nftables is installed and filtering nothing.
            return Firewall.of(Firewall.Kind.NFTABLES, Firewall.State.INACTIVE);
        }
        if (!text.contains("hook input")) {
            return Firewall.of(Firewall.Kind.NFTABLES, Firewall.State.INACTIVE);
        }
        boolean dropsByDefault = false;
        List<FirewallRule> rules = new ArrayList<>();
        int index = 1;
        boolean inInput = false;
        for (String raw : text.lines().toList()) {
            String line = raw.strip();
            if (line.contains("hook input")) {
                inInput = true;
                dropsByDefault |= line.contains("policy drop");
                continue;
            }
            if (line.startsWith("chain ") || line.startsWith("table ")) {
                inInput = false;
            }
            if (!inInput || !line.contains("accept")) {
                continue;
            }
            Matcher dport = NFT_DPORT.matcher(line);
            if (!dport.find()) {
                continue;
            }
            for (String part : dport.group(1).split(",")) {
                List<Integer> ports = spread(part.strip());
                if (ports.isEmpty()) {
                    continue;
                }
                rules.add(new FirewallRule(index++, part.strip() + (line.contains("udp ") ? "/udp" : "/tcp"),
                        FirewallRule.Action.ALLOW, "Anywhere", false, ports));
            }
        }
        if (!dropsByDefault) {
            // An input chain that accepts by default filters nothing unless it drops explicitly, and working that
            // out from a ruleset in general is beyond what Kubo reads.
            return Firewall.of(Firewall.Kind.NFTABLES,
                    text.contains("drop") ? Firewall.State.UNRECOGNISED : Firewall.State.INACTIVE);
        }
        return new Firewall(Firewall.Kind.NFTABLES, Firewall.State.ACTIVE, rules);
    }

    // Small shared readings

    private static String value(String line) {
        int colon = line.indexOf(':');
        return colon < 0 ? "" : line.substring(colon + 1).strip();
    }

    private static String targetOf(String line) {
        int jump = line.lastIndexOf("-j ");
        if (jump < 0) {
            return "";
        }
        String rest = line.substring(jump + 3).strip();
        int space = rest.indexOf(' ');
        return (space < 0 ? rest : rest.substring(0, space)).toUpperCase(Locale.ROOT);
    }

    /**
     * "8080/tcp" or "8000-8010/udp".
     */
    private static List<Integer> portSpec(String spec) {
        Matcher matcher = PORT_SPEC.matcher(spec.strip());
        return matcher.matches() ? range(matcher.group(1), matcher.group(2)) : List.of();
    }

    /**
     * "22" or "8000-8010".
     */
    private static List<Integer> spread(String text) {
        int dash = text.indexOf('-');
        return dash < 0 ? range(text, null) : range(text.substring(0, dash), text.substring(dash + 1));
    }

    private static List<Integer> range(String from, String to) {
        try {
            int first = Integer.parseInt(from.strip());
            if (first < 1 || first > 65535) {
                return List.of();
            }
            if (to == null || to.isBlank()) {
                return List.of(first);
            }
            int last = Integer.parseInt(to.strip());
            if (last < first || last > 65535 || last - first > 512) {
                // A range that wide names no port in particular; listing its first port would be a lie about the
                // rest, so the rule keeps only its own text.
                return List.of();
            }
            Set<Integer> ports = new LinkedHashSet<>();
            for (int port = first; port <= last; port++) {
                ports.add(port);
            }
            return List.copyOf(ports);
        } catch (NumberFormatException e) {
            return List.of();
        }
    }

    /**
     * The ports a UFW destination names: "22/tcp", "80,443/tcp", "8000:8010/tcp".
     */
    static List<Integer> portsIn(String destination) {
        Set<Integer> ports = new LinkedHashSet<>();
        for (String token : destination.split("\s+")) {
            // An address is not a list of ports: "192.168.1.5 22/tcp" names one port, not five.
            if (token.indexOf('.') >= 0) {
                continue;
            }
            Matcher matcher = PORTS_IN_RULE.matcher(token);
            while (matcher.find()) {
                ports.addAll(range(matcher.group(1), matcher.group(2)));
            }
        }
        return List.copyOf(ports);
    }
}
