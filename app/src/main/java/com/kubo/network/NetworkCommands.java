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
     * Refuses a port to one source, or to everyone when the source is empty.
     *
     * @param source an address or a network in CIDR form, or empty for everyone
     */
    public static String ufwDenyFrom(String source, int port, Protocol protocol) {
        if (source == null || source.isBlank()) {
            return ufwDeny(port, protocol);
        }
        return "ufw deny from " + ShellQuote.quote(GuardCommands.checkedAddress(source))
                + " to any port " + requirePort(port) + " proto " + suffix(protocol);
    }

    /**
     * Removes several rules by number, highest first.
     *
     * <p>UFW renumbers everything below a rule the moment it goes. Deleting 5 and then 11 would delete the rule
     * that used to be 12. Highest first, each number still means what it meant when the list was read.
     */
    public static String ufwDeleteRules(java.util.List<Integer> numbers) {
        if (numbers == null || numbers.isEmpty()) {
            throw new IllegalArgumentException("Nothing to delete");
        }
        java.util.List<Integer> ordered = new java.util.ArrayList<>(new java.util.TreeSet<>(numbers));
        java.util.Collections.reverse(ordered);
        java.util.List<String> steps = new java.util.ArrayList<>();
        for (int number : ordered) {
            steps.add(ufwDeleteRule(number));
        }
        return String.join(" && ", steps);
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
            + "ls -1 /etc/nginx/sites-$D 2>/dev/null; done; "
            // What each file answers to. The file name is an administrator's filing choice; the server_name is
            // the thing a visitor types.
            + "echo '=== names'; for F in /etc/nginx/sites-available/*; do "
            // ${F##*/} rather than basename: no nested quoting, so nothing to get wrong.
            + "[ -f \"$F\" ] && { echo \"--- ${F##*/}\"; "
            + "grep -hE '^[[:space:]]*server_name' \"$F\" 2>/dev/null; }; done";

    /** A site file name. Anything else never reaches a path. */
    private static final Pattern SITE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    /**
     * An absolute path Kubo is willing to read.
     *
     * <p>These paths come out of the server's own configuration rather than out of a field, which makes them
     * likelier to be sane and no more trustworthy for it. They are checked against this and then quoted, so a
     * path with a space, a quote or a semicolon in it is refused here rather than handled by the shell.
     */
    private static final Pattern READABLE_PATH = Pattern.compile("^/[A-Za-z0-9._/@+-]{1,255}$");

    public static String checkedPath(String path) {
        if (path == null || !READABLE_PATH.matcher(path.strip()).matches()) {
            throw new IllegalArgumentException("Not a path Kubo will read: " + path);
        }
        return path.strip();
    }

    /**
     * One configuration file, as it is written on the server.
     *
     * <p>{@code nginx -T} already prints every file, but it prints them with everything it included and with the
     * comments taken out by the reader here. What somebody wants to check before changing a site is the file as
     * they would open it.
     */
    public static String readFile(String path) {
        return "cat " + ShellQuote.quote(checkedPath(path));
    }

    /**
     * The last lines of one log file.
     *
     * <p>Bounded on purpose. A log is the one file on a server with no size, and a screen that asked for all of
     * it would hang on the first busy site it met. {@code tail -f} would answer that, and it would also hold the
     * connection open for as long as the screen stayed open, which is the one thing every other reading here
     * has been changed to stop doing.
     */
    public static String readLog(String path, int lines) {
        int count = Math.max(1, Math.min(2000, lines));
        return "tail -n " + count + " " + ShellQuote.quote(checkedPath(path));
    }

    /** Where the rate limit zones live. One fixed path, one fixed content, nothing typed in either. */
    public static final String RATE_LIMIT_FILE = "/etc/nginx/conf.d/kubo-rate-limits.conf";

    /**
     * Whether a site file is already there, so the screen can ask before writing over somebody's work.
     */
    public static String siteExists(String site) {
        return "test -e " + ShellQuote.quote("/etc/nginx/sites-available/" + checkedSite(site))
                + " && echo YES || echo NO";
    }

    /**
     * Writes one site file from standard input.
     *
     * <p>The file's content is not in this command and never will be. It arrives on standard input, so nothing
     * anybody typed is ever read by a shell. The only thing here that comes from outside is the site name, and
     * that is matched against {@link #SITE} and then quoted.
     *
     * <p>Written beside the real file and moved over it, so a connection that drops halfway leaves the old file
     * whole rather than half a new one. Mode 0644 because that is what every other file in sites-available is
     * and because Nginx reads it as root.
     */
    public static String writeSite(String site) {
        String name = checkedSite(site);
        String target = "/etc/nginx/sites-available/" + name;
        return writeThrough(target);
    }

    /**
     * Writes the rate limit zones, at a path with nothing of anyone's in it.
     */
    public static String writeRateLimits() {
        return "mkdir -p /etc/nginx/conf.d && " + writeThrough(RATE_LIMIT_FILE);
    }

    /** Write beside, then move over: a dropped connection leaves the old file whole. */
    private static String writeThrough(String target) {
        String temp = ShellQuote.quote(target + ".kubo-new");
        return "cat > " + temp + " && chmod 0644 " + temp + " && mv -f " + temp + " "
                + ShellQuote.quote(target);
    }

    /**
     * Removes a site file and its link, for undoing a site that was just created and refused.
     */
    public static String deleteSite(String site) {
        String name = checkedSite(site);
        return "rm -f " + ShellQuote.quote("/etc/nginx/sites-enabled/" + name)
                + " " + ShellQuote.quote("/etc/nginx/sites-available/" + name);
    }

    /**
     * The PEM armour of a file: the {@code -----BEGIN CERTIFICATE-----} or {@code -----BEGIN PRIVATE KEY-----}
     * line, and only that.
     *
     * <p>{@code -o} prints the match and not the line around it, and the pattern stops at the closing dashes, so
     * not one character of what follows the armour can come back, from a certificate or from a key. {@code -a}
     * reads a binary file as text instead of refusing it, and {@code -m1} stops at the first armour, so a chain of
     * three certificates is judged by its first.
     */
    public static String pemArmour(String path) {
        return "grep -a -m1 -o -e '-----BEGIN [A-Z ]*-----' -- " + ShellQuote.quote(checkedPath(path));
    }

    /** Where Nginx writes by default, for a block whose own file says nothing. */
    public static final String DEFAULT_ACCESS_LOG = "/var/log/nginx/access.log";
    public static final String DEFAULT_ERROR_LOG = "/var/log/nginx/error.log";

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

    /**
     * The two listings from {@link #NGINX_SITES}, told apart by the marker line before each.
     */
    public static com.kubo.model.network.NginxSites parseSites(String output) {
        java.util.List<String> available = new java.util.ArrayList<>();
        java.util.List<String> enabled = new java.util.ArrayList<>();
        java.util.Map<String, java.util.List<String>> names = new java.util.LinkedHashMap<>();
        java.util.List<String> into = null;
        String file = null;
        for (String line : output.split("\\R")) {
            String text = line.strip();
            if (text.equals("=== available")) {
                into = available;
                file = null;
            } else if (text.equals("=== enabled")) {
                into = enabled;
                file = null;
            } else if (text.equals("=== names")) {
                into = null;
            } else if (text.startsWith("--- ")) {
                file = text.substring(4).strip();
            } else if (into != null && !text.isEmpty() && SITE.matcher(text).matches()) {
                into.add(text);
            } else if (file != null && text.startsWith("server_name")) {
                for (String name : text.substring("server_name".length()).replace(";", "").strip().split("\\s+")) {
                    // "_" is nginx's way of saying "anything", which is not a name anybody types.
                    if (!name.isBlank() && !name.equals("_")) {
                        names.computeIfAbsent(file, key -> new java.util.ArrayList<>()).add(name);
                    }
                }
            }
        }
        return new com.kubo.model.network.NginxSites(available, enabled, names);
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
        String config = stripComments(keepFileMarkers(output));
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
            vhosts.add(readServer(config.substring(open + 1, close), fileBefore(config, start)));
            at = close + 1;
        }
        return vhosts;
    }

    /**
     * Every pool of servers the configuration names, so a site forwarding to a name can say what is in it.
     */
    public static List<com.kubo.model.network.Upstream> parseUpstreams(String output) {
        String config = stripComments(keepFileMarkers(output));
        List<com.kubo.model.network.Upstream> upstreams = new ArrayList<>();
        int at = 0;
        while (true) {
            int start = config.indexOf("upstream", at);
            if (start < 0) {
                break;
            }
            boolean startsWord = start == 0 || !Character.isLetterOrDigit(config.charAt(start - 1));
            int open = config.indexOf('{', start);
            if (!startsWord || open < 0) {
                at = start + 1;
                continue;
            }
            String name = config.substring(start + "upstream".length(), open).strip();
            int close = matchingBrace(config, open);
            if (close < 0) {
                break;
            }
            if (!name.isEmpty() && !name.contains("{") && !name.contains(";")) {
                upstreams.add(readUpstream(name, config.substring(open + 1, close)));
            }
            at = close + 1;
        }
        return upstreams;
    }

    private static com.kubo.model.network.Upstream readUpstream(String name, String block) {
        List<com.kubo.model.network.Upstream.Member> members = new ArrayList<>();
        String method = "";
        for (String statement : block.split(";")) {
            String[] words = statement.strip().split("\\s+");
            if (words.length == 0 || words[0].isEmpty()) {
                continue;
            }
            switch (words[0]) {
                case "server" -> {
                    if (words.length < 2) {
                        continue;
                    }
                    int weight = 1;
                    boolean down = false;
                    boolean backup = false;
                    for (int i = 2; i < words.length; i++) {
                        if (words[i].startsWith("weight=")) {
                            try {
                                weight = Integer.parseInt(words[i].substring("weight=".length()));
                            } catch (NumberFormatException keepTheDefault) {
                                weight = 1;
                            }
                        } else if (words[i].equals("down")) {
                            down = true;
                        } else if (words[i].equals("backup")) {
                            backup = true;
                        }
                    }
                    members.add(new com.kubo.model.network.Upstream.Member(words[1], weight, down, backup));
                }
                case "least_conn", "ip_hash", "random" -> method = words[0];
                default -> {
                    // hash, keepalive and the rest say nothing about which server a request goes to.
                }
            }
        }
        return new com.kubo.model.network.Upstream(name, method, members);
    }

    /** What a kept {@code # configuration file …:} line becomes, so the comment stripper leaves it alone. */
    private static final String FILE_MARKER = "@@kubo_file ";

    private static final Pattern CONFIGURATION_FILE =
            Pattern.compile("(?m)^[ \\t]*#[ \\t]*configuration file[ \\t]+(\\S+?):[ \\t]*$");

    /**
     * Turns {@code nginx -T}'s file headings into something that survives having the comments taken out.
     *
     * <p>{@code nginx -T} prints every file it read, one after another, each under a line naming it. That line
     * is the only thing in the whole answer that says which file a server block lives in, and it is the first
     * thing somebody wants when a site misbehaves. It is also a comment, so stripping comments threw it away.
     *
     * <p>It becomes a directive nothing here reads, sitting between blocks where no block will see it.
     */
    private static String keepFileMarkers(String output) {
        return CONFIGURATION_FILE.matcher(output).replaceAll(FILE_MARKER + "$1;");
    }

    /** The file the block at this offset came out of: the last marker before it. */
    private static String fileBefore(String config, int at) {
        int marker = config.lastIndexOf(FILE_MARKER, at);
        if (marker < 0) {
            return "";
        }
        int end = config.indexOf(';', marker);
        return end < 0 ? "" : config.substring(marker + FILE_MARKER.length(), end).strip();
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

    private static Vhost readServer(String block, String file) {
        List<String> names = new ArrayList<>();
        List<Integer> ports = new ArrayList<>();
        List<Vhost.ProxyTarget> targets = new ArrayList<>();
        boolean ssl = false;
        String certificate = "";
        String root = "";
        String accessLog = "";
        String errorLog = "";
        List<com.kubo.model.network.Rule> rules = readRules(block);
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
                case "access_log" -> accessLog = words[1].startsWith("/") ? words[1] : accessLog;
                case "error_log" -> errorLog = words[1].startsWith("/") ? words[1] : errorLog;
                default -> {
                    // Every other directive is of no interest to the map.
                }
            }
        }
        // A block whose only job is to send visitors to the secure address. Worth saying in one line, because
        // it is why a domain appears twice and why one of the two has no rules in it at all.
        boolean forcesHttps = rules.isEmpty() && targets.isEmpty() && root.isEmpty()
                && redirectsToHttps(block);
        // A location that names no destination of its own is not a location that goes nowhere: Nginx serves
        // it from the server's root. Root at the server level is the most common way these files are written,
        // and reading it any other way showed "no destination" on sites that were serving files perfectly well.
        // Only a root written at the server's own level is inherited. The loop above also sees the ones inside
        // locations, and one location's root is nothing to do with its neighbours.
        String serverRoot = directiveAtTopLevel(block, "root");
        if (!serverRoot.isEmpty()) {
            List<com.kubo.model.network.Rule> inherited = new ArrayList<>();
            for (com.kubo.model.network.Rule rule : rules) {
                inherited.add(rule.kind() == com.kubo.model.network.Rule.Kind.UNSET
                        ? new com.kubo.model.network.Rule(rule.path(), com.kubo.model.network.Rule.Kind.FILES,
                                serverRoot, rule.websocket())
                        : rule);
            }
            rules = inherited;
        }
        return new Vhost(names, ports, ssl, targets, certificate, root, rules, file, forcesHttps,
                accessLog, errorLog);
    }

    /**
     * The value of a directive written directly in this block, not inside any block nested in it. Empty when
     * there is none.
     */
    static String directiveAtTopLevel(String block, String name) {
        StringBuilder top = new StringBuilder(block.length());
        int depth = 0;
        for (int i = 0; i < block.length(); i++) {
            char character = block.charAt(i);
            if (character == '{') {
                depth++;
                // A nested block ends the statement before it, or its opening words would run into the next.
                top.append(';');
            } else if (character == '}') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0) {
                top.append(character);
            }
        }
        for (String statement : top.toString().split(";")) {
            String[] words = statement.strip().split("\s+");
            if (words.length >= 2 && words[0].equals(name)) {
                return words[1];
            }
        }
        return "";
    }

    private static boolean redirectsToHttps(String block) {
        String lower = block.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("https://") && (lower.contains("return 301") || lower.contains("return 302")
                || lower.contains("rewrite "));
    }

    /**
     * The {@code location} rules inside a server block, in the order the file writes them.
     *
     * <p>Nginx does not answer a request with the first rule that matches, it answers with the most specific
     * one, so this order is the file's and not the server's. It is still the order to show: it is the one the
     * reader will find when they open the file.
     */
    static List<com.kubo.model.network.Rule> readRules(String block) {
        List<com.kubo.model.network.Rule> rules = new ArrayList<>();
        int at = 0;
        while (true) {
            int found = block.indexOf("location", at);
            if (found < 0) {
                return rules;
            }
            boolean startsWord = found == 0 || !Character.isLetterOrDigit(block.charAt(found - 1));
            int open = block.indexOf('{', found);
            if (!startsWord || open < 0) {
                at = found + 1;
                continue;
            }
            String path = block.substring(found + "location".length(), open).strip();
            int close = matchingBrace(block, open);
            if (close < 0) {
                return rules;
            }
            String inside = block.substring(open + 1, close);
            if (!path.isEmpty() && !path.contains(";")) {
                rules.add(readRule(path, inside));
            }
            at = close + 1;
        }
    }

    private static com.kubo.model.network.Rule readRule(String path, String inside) {
        com.kubo.model.network.Rule.Kind kind = com.kubo.model.network.Rule.Kind.UNSET;
        String target = "";
        boolean upgrade = false;
        boolean connection = false;
        for (String statement : inside.split(";")) {
            String line = statement.strip();
            int brace = Math.max(line.lastIndexOf('{'), line.lastIndexOf('}'));
            if (brace >= 0) {
                line = line.substring(brace + 1).strip();
            }
            String[] words = line.split("\\s+");
            if (words.length < 2) {
                continue;
            }
            switch (words[0]) {
                case "proxy_pass" -> {
                    kind = com.kubo.model.network.Rule.Kind.PROXY;
                    target = words[1];
                }
                case "root", "alias" -> {
                    if (kind != com.kubo.model.network.Rule.Kind.PROXY) {
                        kind = com.kubo.model.network.Rule.Kind.FILES;
                        target = words[1];
                    }
                }
                case "return" -> {
                    if (kind == com.kubo.model.network.Rule.Kind.UNSET) {
                        boolean moved = words.length > 2 && words[1].startsWith("3");
                        kind = moved
                                ? com.kubo.model.network.Rule.Kind.REDIRECT
                                : com.kubo.model.network.Rule.Kind.ANSWER;
                        target = words.length > 2 ? words[2] : words[1];
                    }
                }
                case "proxy_set_header" -> {
                    if (words.length > 1 && words[1].equalsIgnoreCase("Upgrade")) {
                        upgrade = true;
                    }
                    if (words.length > 1 && words[1].equalsIgnoreCase("Connection")) {
                        connection = true;
                    }
                }
                default -> {
                    // Everything else changes how the answer is made, not who makes it.
                }
            }
        }
        return new com.kubo.model.network.Rule(path, kind, target, upgrade && connection);
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
