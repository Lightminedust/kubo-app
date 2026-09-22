package com.kubo.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubo.files.ShellQuote;
import com.kubo.model.network.Firewall;
import com.kubo.model.network.FirewallRule;
import com.kubo.model.network.ListeningSocket;
import com.kubo.model.network.PmApp;
import com.kubo.model.network.Vhost;
import com.kubo.model.server.ListeningPort;
import com.kubo.model.server.ListeningPort.Protocol;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The commands NETWORK.SCOPE runs and the reading of their answers: the sockets that listen, the PM2 applications,
 * the UFW rules and the active Nginx configuration.
 *
 * <p>Everything that changes the server is a fixed template taking a validated port, PID or quoted name, never a
 * line the user typed.
 */
public final class NetworkCommands {

    /** Sockets waiting for connections, with the process holding each. */
    public static final String SOCKETS = "ss -tulpnH";

    /** PM2's own JSON report; prints nothing usable when PM2 is not installed. */
    public static final String PM2 = "command -v pm2 >/dev/null 2>&1 && pm2 jlist 2>/dev/null || echo '[]'";

    /** UFW's numbered rules. Needs root; the caller wraps it. */
    public static final String UFW = "ufw status numbered";

    /** Whether UFW exists at all, answered without root. */
    public static final String UFW_PRESENT = "command -v ufw >/dev/null 2>&1 && echo YES || echo NO";

    /** The whole active Nginx configuration. Needs root on most servers. */
    public static final String NGINX = "nginx -T 2>/dev/null";

    /**
     * The Nginx configuration read straight from its files, for when {@code nginx -T} could not run as root. The
     * usual places, and only the ones this user may actually read.
     */
    public static final String NGINX_FILES = "for F in /etc/nginx/nginx.conf /etc/nginx/conf.d/*.conf "
            + "/etc/nginx/sites-enabled/*; do [ -r \"$F\" ] && cat \"$F\"; done 2>/dev/null";

    public static final String NGINX_PRESENT = "command -v nginx >/dev/null 2>&1 && echo YES || echo NO";

    /** Checks the Nginx configuration and reloads it only if it is valid. */
    public static final String NGINX_RELOAD = "nginx -t && systemctl reload nginx";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PROCESS = Pattern.compile("\\(\\(\"([^\"]+)\",pid=(\\d+)");
    private static final Pattern RULE = Pattern.compile("^\\[\\s*(\\d+)]\\s+(.*)$");
    private static final Pattern ACTION =
            Pattern.compile("\\b(ALLOW|DENY|REJECT|LIMIT)\\b(\\s+(?:IN|OUT|FWD))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PORTS_IN_RULE = Pattern.compile("(\\d{1,5})(?::(\\d{1,5}))?");

    private NetworkCommands() {
    }

    // Actions

    /**
     * Opens a port to the world.
     */
    public static String ufwAllow(int port, Protocol protocol) {
        return "ufw allow " + requirePort(port) + "/" + suffix(protocol);
    }

    /**
     * Removes the rule that opened a port. UFW asks for confirmation unless it is forced.
     */
    public static String ufwDeleteAllow(int port, Protocol protocol) {
        return "ufw --force delete allow " + requirePort(port) + "/" + suffix(protocol);
    }

    /**
     * Refuses traffic to a port outright.
     */
    public static String ufwDeny(int port, Protocol protocol) {
        return "ufw deny " + requirePort(port) + "/" + suffix(protocol);
    }

    /**
     * Opens a port to one source only.
     *
     * <p>The rule the firewall screen could not write. Opening 5432 to the world and opening it to one office
     * address are the same click today, and they are not the same decision.
     *
     * @param source an address or a network in CIDR form
     */
    public static String ufwAllowFrom(String source, int port, Protocol protocol) {
        return "ufw allow from " + ShellQuote.quote(GuardCommands.checkedAddress(source))
                + " to any port " + requirePort(port) + " proto " + suffix(protocol);
    }

    /**
     * Removes a rule by the number UFW printed beside it.
     *
     * <p>Numbers shift as soon as one is removed, so the screen re-reads the list after every delete rather than
     * letting somebody work down a stale list.
     */
    public static String ufwDeleteRule(int number) {
        if (number < 1) {
            throw new IllegalArgumentException("A rule number starts at 1");
        }
        return "ufw --force delete " + number;
    }

    /** Whether the configuration is valid. Nothing is reloaded, nothing is changed. */
    public static final String NGINX_TEST = "nginx -t 2>&1";

    /** The sites available and the sites enabled, so the screen can tell one from the other. */
    public static final String NGINX_SITES =
            "for D in available enabled; do echo \"=== $D\"; "
            + "ls -1 /etc/nginx/sites-$D 2>/dev/null; done";

    /** A site file name. Anything else never reaches a path. */
    private static final Pattern SITE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    public static String checkedSite(String site) {
        if (site == null || !SITE.matcher(site.strip()).matches()) {
            throw new IllegalArgumentException("Not a site name: " + site);
        }
        return site.strip();
    }

    /**
     * Turns a site on, and refuses to reload if the result would not parse.
     *
     * <p>The link is made first and tested after, because nginx can only judge a configuration it can see. If the
     * test fails the link is removed again, so a bad site never survives the click that enabled it.
     */
    public static String enableSite(String site) {
        String name = checkedSite(site);
        String available = ShellQuote.quote("/etc/nginx/sites-available/" + name);
        String enabled = ShellQuote.quote("/etc/nginx/sites-enabled/" + name);
        return "test -e " + available + " && ln -sfn " + available + " " + enabled
                + " && { nginx -t 2>&1 && systemctl reload nginx; } || { rm -f " + enabled
                + "; echo 'The site was not enabled: nginx refused the configuration.'; false; }";
    }

    public static String disableSite(String site) {
        String enabled = ShellQuote.quote("/etc/nginx/sites-enabled/" + checkedSite(site));
        return "rm -f " + enabled + " && nginx -t 2>&1 && systemctl reload nginx";
    }

    public static String pm2Restart(String app) {
        return "pm2 restart " + ShellQuote.quote(requireApp(app));
    }

    public static String pm2Stop(String app) {
        return "pm2 stop " + ShellQuote.quote(requireApp(app));
    }

    public static String pm2Logs(String app, int lines) {
        return "pm2 logs " + ShellQuote.quote(requireApp(app)) + " --lines "
                + Math.max(1, Math.min(1000, lines)) + " --nostream";
    }

    /**
     * Ends a process outright. Kubo refuses the PIDs that would take the machine down with them.
     */
    public static String kill(int pid) {
        if (pid <= 1) {
            throw new IllegalArgumentException("Refusing to kill PID " + pid);
        }
        return "kill -9 " + pid;
    }

    private static int requirePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("A port is between 1 and 65535");
        }
        return port;
    }

    private static String requireApp(String app) {
        if (app == null || app.isBlank()) {
            throw new IllegalArgumentException("A PM2 application name is required");
        }
        return app.strip();
    }

    private static String suffix(Protocol protocol) {
        return protocol == Protocol.UDP ? "udp" : "tcp";
    }

    // Sockets

    /**
     * Reads {@code ss -tulpnH}. Lines that cannot be read are skipped rather than failing the whole list.
     */
    public static List<ListeningSocket> parseSockets(String output) {
        List<ListeningSocket> sockets = new ArrayList<>();
        for (String line : output.lines().toList()) {
            parseSocket(line).ifPresent(sockets::add);
        }
        return sockets;
    }

    /**
     * "tcp LISTEN 0 4096 0.0.0.0:22 0.0.0.0:* users:(("sshd",pid=812,fd=3))"
     */
    public static Optional<ListeningSocket> parseSocket(String line) {
        String[] fields = line.strip().split("\\s+");
        if (fields.length < 5) {
            return Optional.empty();
        }
        Protocol protocol;
        if (fields[0].startsWith("tcp")) {
            protocol = Protocol.TCP;
        } else if (fields[0].startsWith("udp")) {
            protocol = Protocol.UDP;
        } else {
            return Optional.empty();
        }
        String local = fields[4];
        int colon = local.lastIndexOf(':');
        if (colon <= 0) {
            return Optional.empty();
        }
        int port;
        try {
            port = Integer.parseInt(local.substring(colon + 1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (port <= 0 || port > 65535) {
            return Optional.empty();
        }
        String name = "";
        int pid = ListeningSocket.UNKNOWN_PID;
        if (fields.length > 6) {
            Matcher matcher = PROCESS.matcher(String.join(" ", java.util.Arrays.copyOfRange(fields, 6, fields.length)));
            if (matcher.find()) {
                name = matcher.group(1);
                try {
                    pid = Integer.parseInt(matcher.group(2));
                } catch (NumberFormatException e) {
                    pid = ListeningSocket.UNKNOWN_PID;
                }
            }
        }
        return Optional.of(new ListeningSocket(
                new ListeningPort(protocol, local.substring(0, colon), port, name), pid));
    }

    // PM2

    public static List<PmApp> parsePm2(String output) {
        List<PmApp> apps = new ArrayList<>();
        String json = output.strip();
        int start = json.indexOf('[');
        if (start < 0) {
            return apps;
        }
        try {
            JsonNode root = MAPPER.readTree(json.substring(start));
            if (!root.isArray()) {
                return apps;
            }
            for (JsonNode node : root) {
                JsonNode env = node.path("pm2_env");
                JsonNode monit = node.path("monit");
                apps.add(new PmApp(
                        node.path("name").asText(""),
                        node.path("pm_id").asInt(-1),
                        node.path("pid").asInt(0),
                        env.path("status").asText(""),
                        monit.path("cpu").asDouble(0),
                        monit.path("memory").asLong(0),
                        env.path("pm_uptime").asLong(0),
                        env.path("restart_time").asInt(0)));
            }
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return List.of();
        }
        return apps;
    }

    // UFW

    /**
     * Reads {@code ufw status numbered}, including the case where it says the firewall is off.
     */
    /**
     * Reads {@code ufw status numbered}.
     *
     * @deprecated the firewall reading moved to {@link FirewallCommands}, which reads firewalld, nftables and
     *         iptables too. Kept because UFW is still the one Kubo can change.
     */
    @Deprecated
    public static Firewall parseFirewall(String output) {
        return FirewallCommands.parseUfw(output);
    }

    static List<Integer> portsIn(String to) {
        return FirewallCommands.portsIn(to);
    }

    // Nginx

    /**
     * Reads {@code nginx -T} and keeps what matters: which names are served, on which ports, with which certificate,
     * and where each block forwards.
     */
    public static List<Vhost> parseNginx(String output) {
        String config = stripComments(output);
        List<Vhost> vhosts = new ArrayList<>();
        int at = 0;
        while (true) {
            int start = findServerBlock(config, at);
            if (start < 0) {
                break;
            }
            int open = config.indexOf('{', start);
            int close = matchingBrace(config, open);
            if (close < 0) {
                break;
            }
            vhosts.add(readServer(config.substring(open + 1, close)));
            at = close + 1;
        }
        return vhosts;
    }

    /**
     * The index of the next {@code server} keyword that opens a block, skipping the {@code server} directive that
     * lists members of an upstream.
     */
    private static int findServerBlock(String config, int from) {
        int at = from;
        while (true) {
            int found = config.indexOf("server", at);
            if (found < 0) {
                return -1;
            }
            boolean startsWord = found == 0 || !Character.isLetterOrDigit(config.charAt(found - 1));
            int after = found + "server".length();
            int cursor = after;
            while (cursor < config.length() && Character.isWhitespace(config.charAt(cursor))) {
                cursor++;
            }
            if (startsWord && cursor < config.length() && config.charAt(cursor) == '{') {
                return found;
            }
            at = found + 1;
        }
    }

    private static int matchingBrace(String text, int open) {
        if (open < 0) {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static Vhost readServer(String block) {
        List<String> names = new ArrayList<>();
        List<Integer> ports = new ArrayList<>();
        List<Vhost.ProxyTarget> targets = new ArrayList<>();
        boolean ssl = false;
        String certificate = "";
        String root = "";
        for (String statement : block.split(";")) {
            String line = statement.strip();
            if (line.isEmpty()) {
                continue;
            }
            // Keep only the directive itself, not whatever nested block preceded it.
            int brace = Math.max(line.lastIndexOf('{'), line.lastIndexOf('}'));
            if (brace >= 0) {
                line = line.substring(brace + 1).strip();
            }
            String[] words = line.split("\\s+");
            if (words.length < 2) {
                continue;
            }
            switch (words[0]) {
                case "server_name" -> {
                    for (int i = 1; i < words.length; i++) {
                        names.add(words[i]);
                    }
                }
                case "listen" -> {
                    Integer port = portOfListen(words[1]);
                    if (port != null && !ports.contains(port)) {
                        ports.add(port);
                    }
                    if (line.contains(" ssl") || line.endsWith(" ssl")) {
                        ssl = true;
                    }
                }
                case "ssl_certificate" -> {
                    certificate = words[1];
                    ssl = true;
                }
                case "proxy_pass" -> targets.add(target(words[1]));
                case "root" -> root = words[1];
                default -> {
                    // Every other directive is of no interest to the map.
                }
            }
        }
        return new Vhost(names, ports, ssl, targets, certificate, root);
    }

    /**
     * "443 ssl", "[::]:443", "127.0.0.1:8080", "80" — the port is what follows the last colon, or the whole word.
     */
    static Integer portOfListen(String value) {
        String first = value.strip();
        int colon = first.lastIndexOf(':');
        String number = colon >= 0 ? first.substring(colon + 1) : first;
        try {
            int port = Integer.parseInt(number.replaceAll("[^0-9]", ""));
            return port >= 1 && port <= 65535 ? port : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * "http://127.0.0.1:3000/" becomes the host and port Kubo can match against a listening socket.
     */
    public static Vhost.ProxyTarget target(String raw) {
        String value = raw.strip();
        String withoutScheme = value.replaceFirst("^[a-zA-Z]+://", "");
        int slash = withoutScheme.indexOf('/');
        String authority = slash >= 0 ? withoutScheme.substring(0, slash) : withoutScheme;
        int colon = authority.lastIndexOf(':');
        if (colon < 0) {
            boolean secure = value.startsWith("https://");
            return new Vhost.ProxyTarget(value, authority, authority.isEmpty() ? -1 : (secure ? 443 : 80));
        }
        try {
            return new Vhost.ProxyTarget(value, authority.substring(0, colon),
                    Integer.parseInt(authority.substring(colon + 1)));
        } catch (NumberFormatException e) {
            return new Vhost.ProxyTarget(value, authority, -1);
        }
    }

    /**
     * Removes {@code #} comments, which {@code nginx -T} adds in quantity, so they cannot upset brace matching.
     */
    private static String stripComments(String config) {
        StringBuilder clean = new StringBuilder(config.length());
        for (String line : config.split("\n", -1)) {
            int hash = line.indexOf('#');
            clean.append(hash >= 0 ? line.substring(0, hash) : line).append('\n');
        }
        return clean.toString();
    }
}
