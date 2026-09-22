package com.kubo.model.tools;

import java.util.List;
import java.util.Map;

/**
 * A command line tool Kubo can use if the server has it, and offer to install if it does not.
 *
 * <p>Kubo installs nothing on its own. Every one of these is optional: without it the feature it feeds is
 * greyed out with the reason written beside it, and nothing else changes. That is the difference between a
 * tool and a dependency, and it is why the promise on the site still holds.
 *
 * @param binary  what {@code command -v} is asked for
 * @param unlocks what the screen can do once it is there, in one sentence
 * @param caveat  what it still cannot do, empty when there is nothing to warn about
 */
public enum Tool {

    LSOF("lsof", Group.NAMING,
            "Names the program holding a port or a connection, by a different route than ss -p.",
            "Still needs root to see another user's programs.",
            Map.of("apt", "lsof", "dnf", "lsof", "yum", "lsof", "pacman", "lsof", "zypper", "lsof", "apk", "lsof")),

    NETSTAT("netstat", Group.NAMING,
            "The older way of listing sockets, worth having when ss answers strangely.",
            "",
            Map.of("apt", "net-tools", "dnf", "net-tools", "yum", "net-tools", "pacman", "net-tools",
                    "zypper", "net-tools", "apk", "net-tools")),

    ETHTOOL("ethtool", Group.NAMING,
            "The real speed and duplex of a link, instead of only its MTU.",
            "",
            Map.of("apt", "ethtool", "dnf", "ethtool", "yum", "ethtool", "pacman", "ethtool",
                    "zypper", "ethtool", "apk", "ethtool")),

    DIG("dig", Group.REACH,
            "Asks a resolver a question, so the DNS servers on the left can be checked rather than just listed.",
            "",
            Map.of("apt", "bind9-dnsutils", "dnf", "bind-utils", "yum", "bind-utils", "pacman", "bind",
                    "zypper", "bind-utils", "apk", "bind-tools")),

    PING("ping", Group.REACH,
            "Says whether the gateway and the internet answer this server at all.",
            "",
            Map.of("apt", "iputils-ping", "dnf", "iputils", "yum", "iputils", "pacman", "iputils",
                    "zypper", "iputils", "apk", "iputils")),

    MTR("mtr", Group.REACH,
            "Shows every hop between this server and an address, and where packets stop.",
            "",
            Map.of("apt", "mtr-tiny", "dnf", "mtr", "yum", "mtr", "pacman", "mtr", "zypper", "mtr", "apk", "mtr")),

    NETHOGS("nethogs", Group.BANDWIDTH,
            "Bandwidth per program, so the interface rate can be broken down by what is using it.",
            "Runs as root only.",
            Map.of("apt", "nethogs", "dnf", "nethogs", "yum", "nethogs", "pacman", "nethogs",
                    "zypper", "nethogs", "apk", "nethogs")),

    IFTOP("iftop", Group.BANDWIDTH,
            "Bandwidth per connection, live.",
            "Runs as root only.",
            Map.of("apt", "iftop", "dnf", "iftop", "yum", "iftop", "pacman", "iftop", "zypper", "iftop",
                    "apk", "iftop")),

    WHOIS("whois", Group.IDENTIFY,
            "Says who owns an address that is knocking on your SSH port, before you ban it.",
            "Asks a public whois server, so this one reaches outside your network.",
            Map.of("apt", "whois", "dnf", "whois", "yum", "whois", "pacman", "whois", "zypper", "whois",
                    "apk", "whois")),

    CONNTRACK("conntrack", Group.IDENTIFY,
            "The connections the kernel is tracking through NAT, which is where Docker traffic really shows.",
            "Runs as root only.",
            Map.of("apt", "conntrack", "dnf", "conntrack-tools", "yum", "conntrack-tools",
                    "pacman", "conntrack-tools", "zypper", "conntrack-tools", "apk", "conntrack-tools"));

    /** What a tool is for, so the card reads as four answers rather than ten packages. */
    public enum Group {
        NAMING("NAMING WHAT HOLDS A PORT",
                "Kubo can list every connection without these. Naming the program behind one is what it cannot "
                        + "always do, and this is the second opinion when ss will not say."),
        REACH("REACHING OUT FROM THE SERVER",
                "Turns the resolvers and the gateway on the left from things that are listed into things that "
                        + "can be tested."),
        BANDWIDTH("WHERE THE BANDWIDTH GOES",
                "The rate per interface is already there. These break it down by program and by connection."),
        IDENTIFY("WHO IS ON THE OTHER END",
                "For the moment before a ban, and for the Docker traffic that never appears in a plain socket "
                        + "list.");

        private final String title;
        private final String why;

        Group(String title, String why) {
            this.title = title;
            this.why = why;
        }

        public String title() {
            return title;
        }

        public String why() {
            return why;
        }
    }

    private final String binary;
    private final Group group;
    private final String unlocks;
    private final String caveat;
    private final Map<String, String> packages;

    Tool(String binary, Group group, String unlocks, String caveat, Map<String, String> packages) {
        this.binary = binary;
        this.group = group;
        this.unlocks = unlocks;
        this.caveat = caveat;
        this.packages = packages;
    }

    public String binary() {
        return binary;
    }

    public Group group() {
        return group;
    }

    public String unlocks() {
        return unlocks;
    }

    public String caveat() {
        return caveat;
    }

    /**
     * The package to install with this manager, or empty when Kubo does not know one.
     */
    public String packageFor(PackageManager manager) {
        // Keyed by the manager itself, not by its binary: apt's binary is apt-get, and that mismatch is
        // exactly the kind of thing that silently leaves a tool uninstallable on one family.
        return manager.known()
                ? packages.getOrDefault(manager.name().toLowerCase(java.util.Locale.ROOT), "")
                : "";
    }

    public static List<Tool> of(Group group) {
        return java.util.Arrays.stream(values()).filter(tool -> tool.group == group).toList();
    }
}
