package com.kubo.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubo.model.network.Link;
import com.kubo.model.network.Resolvers;
import com.kubo.model.network.Route;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reading where the server sits on the network: its interfaces, its routes and its resolvers.
 *
 * <p>Every command here only reads, and none of them needs root.
 *
 * <p>{@code ip} has printed JSON since iproute2 4.13, which covers every distribution released since 2018, but
 * not the older RHEL and CentOS machines that are still in service. So each reading is asked for as JSON and
 * parsed from the plain output when that comes back empty. The plain form carries less: the addresses are all
 * there, the link state and the MAC are not.
 */
public final class LinkCommands {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Interfaces and their addresses, as JSON. */
    public static final String LINKS_JSON = "ip -j addr show 2>/dev/null";

    /** The same, for an iproute2 too old to know -j. One line per address. */
    public static final String LINKS_TEXT = "ip -o addr show 2>/dev/null; ip -o link show 2>/dev/null";

    /** The routing table, as JSON. */
    public static final String ROUTES_JSON = "ip -j route show 2>/dev/null";

    public static final String ROUTES_TEXT = "ip route show 2>/dev/null";

    /**
     * The resolvers, asked of systemd-resolved first and of the file second.
     *
     * <p>Both are read in one round trip rather than one after the other, because the answer to "is resolved
     * running" is the presence of its output, and asking for it separately would cost a second command.
     */
    public static final String RESOLVERS = "resolvectl status 2>/dev/null; echo '---'; cat /etc/resolv.conf 2>/dev/null";

    /**
     * Everything about the machine in one command.
     *
     * <p>Six separate reads meant six round trips on a connection that runs one command at a time, and the
     * screen polls every twelve seconds. Anything else asked for while that was in flight waited behind it,
     * which is what made opening a card feel broken. Each answer is announced by a marker so the same parsers
     * read it.
     */
    public static final String MACHINE =
            "echo '@@links'; ip -j addr show 2>/dev/null; "
            + "echo '@@routes'; ip -j route show 2>/dev/null; "
            + "echo '@@resolvectl'; resolvectl status 2>/dev/null; "
            + "echo '@@resolvconf'; cat /etc/resolv.conf 2>/dev/null; "
            + "echo '@@sockets'; ss -tulpnH 2>/dev/null; "
            + "echo '@@connections'; ss -tunpH state established 2>/dev/null; "
            + "echo '@@counters'; cat /proc/net/dev 2>/dev/null";

    private LinkCommands() {
    }

    /**
     * One announced block of {@link #MACHINE}, or an empty string when it is not there.
     */
    public static String section(String output, String marker) {
        String head = "@@" + marker;
        int start = -1;
        String[] lines = output == null ? new String[0] : output.split("\\R");
        StringBuilder found = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.equals(head)) {
                start = i;
                continue;
            }
            if (start >= 0) {
                if (line.startsWith("@@")) {
                    break;
                }
                found.append(lines[i]).append('\n');
            }
        }
        return found.toString();
    }

    /** The two halves {@link #parseResolvers(String)} expects, taken out of one answer. */
    public static String resolverSections(String output) {
        return section(output, "resolvectl") + "\n---\n" + section(output, "resolvconf");
    }

    // Interfaces

    /**
     * Interfaces from {@code ip -j addr}, or an empty list when the output is not the JSON that was asked for.
     */
    public static List<Link> parseLinksJson(String output) {
        List<Link> links = new ArrayList<>();
        JsonNode root = readJson(output);
        if (root == null || !root.isArray()) {
            return links;
        }
        for (JsonNode node : root) {
            String name = node.path("ifname").asText("");
            if (name.isBlank()) {
                continue;
            }
            List<Link.Address> addresses = new ArrayList<>();
            for (JsonNode info : node.path("addr_info")) {
                String local = info.path("local").asText("");
                int prefix = info.path("prefixlen").asInt(-1);
                if (local.isBlank() || prefix < 0) {
                    continue;
                }
                addresses.add(new Link.Address(local, prefix, info.path("family").asText("").equals("inet6")));
            }
            links.add(new Link(name, node.path("operstate").asText("UNKNOWN"),
                    node.path("address").asText(""), node.path("mtu").asInt(Link.UNKNOWN_MTU), addresses));
        }
        return links;
    }

    /**
     * Interfaces from {@code ip -o addr} followed by {@code ip -o link}.
     *
     * <p>A link with no address at all still has to appear: an interface that is up and unconfigured is exactly
     * the kind of thing somebody opens this screen to find.
     */
    public static List<Link> parseLinksText(String output) {
        java.util.Map<String, List<Link.Address>> addresses = new java.util.LinkedHashMap<>();
        java.util.Map<String, String[]> details = new java.util.LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            String[] parts = line.strip().split("\\s+");
            // "2: eth0    inet 10.0.0.5/24 brd ... scope global eth0"
            if (parts.length >= 4 && (parts[2].equals("inet") || parts[2].equals("inet6"))) {
                String name = parts[1].replace(":", "");
                String[] cidr = parts[3].split("/");
                if (cidr.length == 2) {
                    try {
                        addresses.computeIfAbsent(name, key -> new ArrayList<>())
                                .add(new Link.Address(cidr[0], Integer.parseInt(cidr[1]), parts[2].equals("inet6")));
                    } catch (IllegalArgumentException ignored) {
                        // A line ip printed in a shape this does not read. Skipping one address beats
                        // dropping the interface it belongs to.
                    }
                }
                addresses.computeIfAbsent(name, key -> new ArrayList<>());
            } else if (parts.length >= 3 && parts[1].endsWith(":") && line.contains("mtu")) {
                // "2: eth0: <BROADCAST,MULTICAST,UP> mtu 1500 ... state UP ... link/ether 00:11:22:33:44:55 ..."
                String name = parts[1].replace(":", "");
                details.put(name, new String[]{value(parts, "state"), value(parts, "link/ether"),
                        value(parts, "mtu")});
                addresses.computeIfAbsent(name, key -> new ArrayList<>());
            }
        }
        List<Link> links = new ArrayList<>();
        for (var entry : addresses.entrySet()) {
            String[] detail = details.getOrDefault(entry.getKey(), new String[]{"", "", ""});
            int mtu = Link.UNKNOWN_MTU;
            try {
                mtu = detail[2].isBlank() ? Link.UNKNOWN_MTU : Integer.parseInt(detail[2]);
            } catch (NumberFormatException ignored) {
                // Left unknown rather than guessed.
            }
            links.add(new Link(entry.getKey(), detail[0], detail[1], mtu, entry.getValue()));
        }
        return links;
    }

    // Routes

    public static List<Route> parseRoutesJson(String output) {
        List<Route> routes = new ArrayList<>();
        JsonNode root = readJson(output);
        if (root == null || !root.isArray()) {
            return routes;
        }
        for (JsonNode node : root) {
            String destination = node.path("dst").asText("");
            if (destination.isBlank()) {
                continue;
            }
            routes.add(new Route(destination, node.path("gateway").asText(""), node.path("dev").asText(""),
                    node.path("prefsrc").asText(""), node.path("metric").asInt(Route.NO_METRIC)));
        }
        return routes;
    }

    /** Routes from plain {@code ip route}: "default via 10.0.0.1 dev eth0 proto static metric 100". */
    public static List<Route> parseRoutesText(String output) {
        List<Route> routes = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length < 1 || parts[0].isBlank()) {
                continue;
            }
            int metric = Route.NO_METRIC;
            try {
                String printed = value(parts, "metric");
                metric = printed.isBlank() ? Route.NO_METRIC : Integer.parseInt(printed);
            } catch (NumberFormatException ignored) {
                // Left unknown.
            }
            routes.add(new Route(parts[0], value(parts, "via"), value(parts, "dev"), value(parts, "src"), metric));
        }
        return routes;
    }

    // Resolvers

    /**
     * The resolvers, preferring what resolved reports over what the file says.
     *
     * <p>On a machine running systemd-resolved the file holds 127.0.0.53, its own stub. Reporting that as the
     * resolver would be true of the file and useless to the reader, who wants to know which server actually
     * answers.
     */
    public static Resolvers parseResolvers(String output) {
        String[] halves = output.split("(?m)^---$", 2);
        Resolvers resolved = fromResolvectl(halves[0]);
        if (resolved.known()) {
            return resolved;
        }
        return fromResolvConf(halves.length > 1 ? halves[1] : "");
    }

    private static Resolvers fromResolvectl(String output) {
        Set<String> servers = new LinkedHashSet<>();
        Set<String> domains = new LinkedHashSet<>();
        String reading = "";
        for (String line : output.split("\\R")) {
            String text = line.strip();
            if (text.startsWith("DNS Servers:")) {
                reading = "servers";
                addWords(servers, text.substring("DNS Servers:".length()));
            } else if (text.startsWith("DNS Server:")) {
                reading = "servers";
                addWords(servers, text.substring("DNS Server:".length()));
            } else if (text.startsWith("DNS Domain:")) {
                reading = "domains";
                addWords(domains, text.substring("DNS Domain:".length()));
            } else if (text.isEmpty() || text.contains(":")) {
                // Any other labelled line ends the block a wrapped list belongs to.
                reading = "";
            } else if (reading.equals("servers")) {
                addWords(servers, text);
            } else if (reading.equals("domains")) {
                addWords(domains, text);
            }
        }
        // The stub is what resolved listens on, not what it asks. Reporting it would answer the wrong question.
        servers.remove("127.0.0.53");
        return servers.isEmpty() ? Resolvers.none()
                : new Resolvers(List.copyOf(servers), List.copyOf(domains), Resolvers.RESOLVECTL);
    }

    private static Resolvers fromResolvConf(String output) {
        Set<String> servers = new LinkedHashSet<>();
        Set<String> domains = new LinkedHashSet<>();
        for (String line : output.split("\\R")) {
            String text = line.strip();
            if (text.startsWith("#") || text.startsWith(";")) {
                continue;
            }
            if (text.startsWith("nameserver ")) {
                addWords(servers, text.substring("nameserver ".length()));
            } else if (text.startsWith("search ") || text.startsWith("domain ")) {
                addWords(domains, text.substring(text.indexOf(' ')));
            }
        }
        return servers.isEmpty() ? Resolvers.none()
                : new Resolvers(List.copyOf(servers), List.copyOf(domains), Resolvers.RESOLV_CONF);
    }

    // Shared

    private static void addWords(Set<String> into, String text) {
        for (String word : text.strip().split("\\s+")) {
            if (!word.isBlank()) {
                into.add(word);
            }
        }
    }

    /** The word after a keyword on an {@code ip} line, or empty when the keyword is not there. */
    private static String value(String[] parts, String keyword) {
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals(keyword)) {
                return parts[i + 1];
            }
        }
        return "";
    }

    private static JsonNode readJson(String output) {
        String text = output == null ? "" : output.strip();
        if (!text.startsWith("[")) {
            return null;
        }
        try {
            return MAPPER.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }
}
