package com.kubo.apps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubo.files.ShellQuote;
import com.kubo.model.apps.App;
import com.kubo.model.apps.AppProcess;
import com.kubo.model.apps.Manager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The commands the apps screen sends, and the reading of what comes back.
 *
 * <p>Every command is a fixed template. What the reader types or picks, an application's name, a number of
 * workers, a script's path, is checked against a strict pattern first and then quoted, so no value can become
 * a second command. Nothing here builds a command from free text.
 */
public final class AppCommands {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Where PM2 usually is when a login shell is not there to say: npm's global folder, every Node that nvm
     * installed, Volta. A non-interactive SSH session reads no .bashrc, and that is where nvm puts itself.
     */
    static final String PATHS = "export PATH=\"$PATH:/usr/local/bin:$HOME/.npm-global/bin:$HOME/.local/bin"
            + ":$HOME/.yarn/bin$(for d in \"$HOME\"/.nvm/versions/node/*/bin \"$HOME\"/.volta/bin; do "
            + "[ -d \"$d\" ] && printf ':%s' \"$d\"; done)\"; ";

    /** One round trip: the machine, PM2, its saved list, whether it starts at boot, the ports, supervisor. */
    public static final String READ = String.join(" ",
            "echo '@@cores'; nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null;",
            "echo '@@memory'; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo 2>/dev/null;",
            "echo '@@pm2'; if command -v pm2 >/dev/null 2>&1; then pm2 jlist 2>/dev/null; else echo ABSENT; fi;",
            "echo '@@dump'; cat \"${PM2_HOME:-$HOME/.pm2}/dump.pm2\" 2>/dev/null;",
            "echo '@@boot'; systemctl is-enabled \"pm2-$(id -un)\" 2>/dev/null;",
            "echo '@@sockets'; ss -tlnpH 2>/dev/null;",
            "echo '@@supervisor'; command -v supervisorctl >/dev/null 2>&1 && echo PRESENT || echo ABSENT;",
            "echo '@@who'; id -un; echo \"$HOME\"; command -v pm2 || echo; command -v node || echo;",
            "echo '@@stat'; grep '^cpu[0-9]' /proc/stat 2>/dev/null;",
            "echo '@@load'; cat /proc/loadavg 2>/dev/null;",
            "echo '@@end'");

    /** What supervisord runs. Its socket is usually root's alone. */
    public static final String SUPERVISOR_STATUS = "supervisorctl status";

    /** Writes PM2's list down, so the same applications come back after a reboot. */
    public static final String PM2_SAVE = "pm2 save";

    private static final Pattern ACCOUNT = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,31}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.@-]{0,63}");
    private static final Pattern PROGRAM = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}");
    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9_./@+:-]{0,250}");
    private static final Pattern SUPERVISED = Pattern.compile(
            "^(\\S+)\\s+(RUNNING|STOPPED|STARTING|BACKOFF|STOPPING|EXITED|FATAL|UNKNOWN)\\b\\s*(.*)$");
    private static final Pattern UPTIME = Pattern.compile("uptime (?:(\\d+) days?, )?(\\d+):(\\d{2}):(\\d{2})");
    private static final Pattern PID = Pattern.compile("pid (\\d+)");
    private static final Pattern SOCKET_PID = Pattern.compile("pid=(\\d+)");

    /**
     * The key {@link #parseSockets} files a socket under when PM2's own daemon holds it. A cluster's workers
     * do not hold their port: the daemon listens for all of them and hands the connections out.
     */
    public static final int PM2_DAEMON = -1;

    /** What can be asked of a PM2 application. */
    public enum Pm2Verb {
        START("start"), STOP("stop"), RESTART("restart"), RELOAD("reload"), DELETE("delete");

        final String word;

        Pm2Verb(String word) {
            this.word = word;
        }
    }

    /** What can be asked of a supervisor program. */
    public enum SupervisorVerb {
        START("start"), STOP("stop"), RESTART("restart");

        final String word;

        SupervisorVerb(String word) {
            this.word = word;
        }
    }

    /**
     * A new PM2 application.
     *
     * @param path      the script to run, or for {@code npm start} the folder holding package.json
     * @param npm       true to run {@code npm start} in that folder rather than a script
     * @param instances how many processes; 1 keeps it a plain fork, 0 means one per core
     * @param port      the PORT it is given, or 0 for none
     * @param save      also write PM2's list down, so it comes back after a reboot
     * @param maxMemory restart a process that grows past this many megabytes, or 0 for no limit
     */
    public record NewApp(String path, boolean npm, String name, int instances, int port, boolean save,
                         int maxMemory) {

        public NewApp(String path, boolean npm, String name, int instances, int port, boolean save) {
            this(path, npm, name, instances, port, save, 0);
        }
    }

    private AppCommands() {
    }

    // Checks

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public static boolean validPath(String path) {
        return path != null && PATH.matcher(path).matches() && !path.contains("/../") && !path.endsWith("/..")
                && !path.contains("//");
    }

    private static String name(String name) {
        if (!validName(name)) {
            throw new IllegalArgumentException("Kubo only acts on applications named with letters, digits and . _ - @");
        }
        return ShellQuote.quote(name);
    }

    private static String path(String path) {
        if (!validPath(path)) {
            throw new IllegalArgumentException("That path has characters Kubo does not send to a server");
        }
        return ShellQuote.quote(path);
    }

    /** Why a new application cannot be started as described, or null when it can. */
    public static String problem(NewApp app) {
        if (app == null) {
            return "nothing to start";
        }
        if (!validPath(app.path())) {
            return app.npm() ? "the folder must be a full path, like /srv/api" : "the script must be a full path, like /srv/api/server.js";
        }
        if (!validName(app.name())) {
            return "a name of letters, digits and . _ - @, up to 64";
        }
        if (app.port() != 0 && (app.port() < 1024 || app.port() > 65535)) {
            return "a port between 1024 and 65535, or none";
        }
        if (app.instances() < 0 || app.instances() > 256) {
            return "between 1 and 256 workers";
        }
        if (app.instances() != 1 && (app.npm() || !isNode(app.path()))) {
            return "several workers need cluster mode, which only runs a Node script";
        }
        if (app.maxMemory() != 0 && (app.maxMemory() < 32 || app.maxMemory() > 65536)) {
            return "a memory limit between 32 and 65536 MB, or none";
        }
        return null;
    }

    private static boolean isNode(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".js") || lower.endsWith(".cjs") || lower.endsWith(".mjs");
    }

    // Commands

    public static String pm2(Pm2Verb verb, String app) {
        return "pm2 " + verb.word + " " + name(app);
    }

    /** Changes how many processes a cluster runs. */
    public static String pm2Scale(String app, int workers) {
        if (workers < 1 || workers > 256) {
            throw new IllegalArgumentException("Between 1 and 256 workers");
        }
        return "pm2 scale " + name(app) + " " + workers;
    }

    /** Puts an application's restart counter back to zero. */
    public static String pm2Reset(String app) {
        return "pm2 reset " + name(app);
    }

    /** Empties the two log files of one application. */
    public static String pm2Flush(String app) {
        return "pm2 flush " + name(app);
    }

    /**
     * Makes PM2 start when the machine boots, or stops it doing so. Run as root: it writes and enables the
     * systemd unit {@code pm2-<account>}. This is the command {@code pm2 startup} prints and asks to be run by
     * hand, with the account, its home and the place Node is in read from the server.
     */
    public static String pm2Boot(com.kubo.model.apps.AppsReport.Host host, boolean on) {
        if (host == null || !host.known() || !ACCOUNT.matcher(host.account()).matches()) {
            throw new IllegalArgumentException("Kubo could not read who PM2 runs as on this server");
        }
        List<String> folders = new ArrayList<>();
        for (String program : new String[]{host.pm2(), host.node()}) {
            int slash = program.lastIndexOf('/');
            if (slash > 0) {
                String folder = path(program.substring(0, slash));
                if (!folders.contains(folder)) {
                    folders.add(folder);
                }
            }
        }
        StringBuilder search = new StringBuilder("\"PATH=$PATH\"");
        for (String folder : folders) {
            search.append(":").append(folder);
        }
        return "env " + search + " " + path(host.pm2()) + (on ? " startup" : " unstartup")
                + " systemd -u " + ShellQuote.quote(host.account()) + " --hp " + path(host.home());
    }

    /** Starts a new application from the folder it lives in. */
    public static String pm2Start(NewApp app) {
        String trouble = problem(app);
        if (trouble != null) {
            throw new IllegalArgumentException(trouble);
        }
        String env = app.port() > 0 ? "PORT=" + app.port() + " " : "";
        String command;
        if (app.npm()) {
            command = "cd " + path(app.path()) + " && " + env + "pm2 start npm --name " + name(app.name()) + " -- start";
        } else {
            int slash = app.path().lastIndexOf('/');
            String folder = slash <= 0 ? "/" : app.path().substring(0, slash);
            String workers = app.instances() == 1 ? "" : app.instances() == 0 ? " -i max" : " -i " + app.instances();
            command = "cd " + path(folder) + " && " + env + "pm2 start " + path(app.path()) + " --name "
                    + name(app.name()) + workers;
        }
        if (app.maxMemory() > 0) {
            int cut = command.indexOf(" -- start");
            String limit = " --max-memory-restart " + app.maxMemory() + "M";
            command = cut < 0 ? command + limit : command.substring(0, cut) + limit + command.substring(cut);
        }
        return app.save() ? command + " && pm2 save" : command;
    }

    /** The last lines of one log file PM2 named itself. */
    public static String tail(String file, int lines) {
        if (lines < 1 || lines > 5000) {
            throw new IllegalArgumentException("Between 1 and 5000 lines");
        }
        return "tail -n " + lines + " " + path(file);
    }

    public static String supervisor(SupervisorVerb verb, String program) {
        return "supervisorctl " + verb.word + " " + program(program);
    }

    /** The end of what one program wrote, its output or its errors. */
    public static String supervisorTail(String program, boolean errors) {
        return "supervisorctl tail -16000 " + program(program) + (errors ? " stderr" : "");
    }

    private static String program(String program) {
        if (program == null || !PROGRAM.matcher(program).matches()) {
            throw new IllegalArgumentException("Kubo only acts on programs named with letters, digits and . _ - :");
        }
        return ShellQuote.quote(program);
    }

    /** CPU and memory of supervisor's processes, which supervisorctl does not report. */
    public static String stats(List<Integer> pids) {
        StringBuilder list = new StringBuilder();
        for (int pid : pids) {
            if (pid <= 0) {
                continue;
            }
            list.append(list.isEmpty() ? "" : ",").append(pid);
        }
        return list.isEmpty() ? "" : "ps -o pid=,pcpu=,rss= -p " + list;
    }

    // Reading

    /**
     * A marker at the end of a line. {@code pm2 jlist} and {@code dump.pm2} do not end with a newline, so the
     * next marker lands on their last line, right after the closing bracket.
     */
    private static final Pattern MARKER = Pattern.compile("^(.*?)@@([A-Za-z0-9_-]+)\\s*$");

    public static String section(String output, String marker) {
        String[] lines = output == null ? new String[0] : output.split("\\R");
        StringBuilder found = new StringBuilder();
        boolean inside = false;
        for (String line : lines) {
            Matcher head = MARKER.matcher(line);
            if (head.matches()) {
                if (inside) {
                    if (!head.group(1).isBlank()) {
                        found.append(head.group(1)).append('\n');
                    }
                    break;
                }
                inside = head.group(2).equals(marker);
                continue;
            }
            if (inside) {
                found.append(line).append('\n');
            }
        }
        return found.toString();
    }

    public static int cores(String output) {
        try {
            return Integer.parseInt(section(output, "cores").strip().split("\\s+")[0]);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Who PM2 runs as and where it is: the account, its home, pm2 and node. */
    public static com.kubo.model.apps.AppsReport.Host host(String output) {
        String[] lines = section(output, "who").split("\\R", -1);
        String[] found = new String[4];
        for (int i = 0; i < 4; i++) {
            found[i] = i < lines.length ? lines[i].strip() : "";
        }
        return new com.kubo.model.apps.AppsReport.Host(found[0], validPath(found[1]) ? found[1] : "",
                validPath(found[2]) ? found[2] : "", validPath(found[3]) ? found[3] : "");
    }

    /**
     * The counters of each core from /proc/stat: how long it worked, and how long in all. Two readings apart
     * give how busy each core was between them.
     */
    public static long[][] coreTicks(String output) {
        List<long[]> found = new ArrayList<>();
        for (String line : section(output, "stat").split("\\R")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length < 5 || !parts[0].startsWith("cpu")) {
                continue;
            }
            try {
                long total = 0;
                for (int i = 1; i < parts.length && i <= 8; i++) {
                    total += Long.parseLong(parts[i]);
                }
                long idle = Long.parseLong(parts[4]) + (parts.length > 5 ? Long.parseLong(parts[5]) : 0);
                found.add(new long[]{total - idle, total});
            } catch (NumberFormatException ignored) {
                // Not a row of counters.
            }
        }
        return found.toArray(new long[0][]);
    }

    /** How busy each core was between two readings of its counters, 0 to 1. Empty when they do not match. */
    public static List<Double> coreLoads(long[][] before, long[][] now) {
        if (before == null || now == null || before.length != now.length || now.length == 0) {
            return List.of();
        }
        List<Double> loads = new ArrayList<>();
        for (int i = 0; i < now.length; i++) {
            long total = now[i][1] - before[i][1];
            long busy = now[i][0] - before[i][0];
            loads.add(total <= 0 ? 0.0 : Math.max(0, Math.min(1, busy / (double) total)));
        }
        return loads;
    }

    /** The one minute load average, or -1. */
    public static double load(String output) {
        try {
            return Double.parseDouble(section(output, "load").strip().split("\\s+")[0]);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Total and in use, in bytes, from /proc/meminfo. */
    public static long[] memory(String output) {
        long total = 0;
        long available = 0;
        for (String line : section(output, "memory").split("\\R")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            try {
                long kilobytes = Long.parseLong(parts[1]);
                if (parts[0].equals("MemTotal:")) {
                    total = kilobytes * 1024;
                } else if (parts[0].equals("MemAvailable:")) {
                    available = kilobytes * 1024;
                }
            } catch (NumberFormatException ignored) {
                // A line that is not a figure is not one of the two wanted.
            }
        }
        return new long[]{total, Math.max(0, total - available)};
    }

    /** Every process in a {@code pm2 jlist} answer. Empty when there is none, or when it is not JSON. */
    public static List<AppProcess> parsePm2(String json) {
        JsonNode root = array(json);
        if (root == null) {
            return List.of();
        }
        List<AppProcess> found = new ArrayList<>();
        for (JsonNode node : root) {
            JsonNode env = node.path("pm2_env");
            JsonNode monit = node.path("monit");
            long started = env.path("pm_uptime").asLong(0);
            String mode = env.path("exec_mode").asText("fork_mode").replace("_mode", "");
            int instance = env.path("NODE_APP_INSTANCE").asInt(env.path("pm_id").asInt(0));
            int port = env.path("PORT").asInt(0);
            if (port == 0) {
                port = env.path("env").path("PORT").asInt(0);
            }
            found.add(new AppProcess(Manager.PM2, node.path("name").asText("?"), node.path("pm_id").asInt(-1),
                    mode.equals("cluster") ? instance : 0, env.path("status").asText("unknown"),
                    node.path("pid").asInt(0), monit.path("cpu").asDouble(0), monit.path("memory").asLong(0),
                    started > 0 ? Instant.ofEpochMilli(started) : null, env.path("restart_time").asInt(0), mode,
                    env.path("pm_exec_path").asText(""), env.path("pm_cwd").asText(""),
                    env.path("exec_interpreter").asText(""), env.path("pm_out_log_path").asText(""),
                    env.path("pm_err_log_path").asText(""), port, env.path("version").asText("")));
        }
        return List.copyOf(found);
    }

    /** The names in PM2's saved list, the ones it brings back after a reboot. */
    public static Set<String> savedNames(String dump) {
        JsonNode root = array(dump);
        Set<String> names = new HashSet<>();
        if (root != null) {
            for (JsonNode node : root) {
                String name = node.path("name").asText("");
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * The JSON array in an answer, past whatever PM2 printed before it: the first time it runs after a boot it
     * announces "[PM2] Spawning PM2 daemon", and that bracket is not the array.
     */
    private static JsonNode array(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (int at = text.indexOf('['); at >= 0; at = text.indexOf('[', at + 1)) {
            int next = at + 1;
            while (next < text.length() && Character.isWhitespace(text.charAt(next))) {
                next++;
            }
            if (next < text.length() && (text.charAt(next) == '{' || text.charAt(next) == ']')) {
                try {
                    JsonNode root = MAPPER.readTree(text.substring(at));
                    return root.isArray() ? root : null;
                } catch (com.fasterxml.jackson.core.JacksonException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** What {@code supervisorctl status} lists, without CPU or memory, which it does not know. */
    public static List<AppProcess> parseSupervisor(String output, Instant now) {
        List<AppProcess> found = new ArrayList<>();
        for (String line : (output == null ? "" : output).split("\\R")) {
            Matcher row = SUPERVISED.matcher(line.strip());
            if (!row.matches()) {
                continue;
            }
            String rest = row.group(3);
            Matcher pid = PID.matcher(rest);
            Matcher up = UPTIME.matcher(rest);
            Instant since = null;
            if (up.find()) {
                long days = up.group(1) == null ? 0 : Long.parseLong(up.group(1));
                Duration lasted = Duration.ofDays(days).plusHours(Long.parseLong(up.group(2)))
                        .plusMinutes(Long.parseLong(up.group(3))).plusSeconds(Long.parseLong(up.group(4)));
                since = now.minus(lasted);
            }
            found.add(new AppProcess(Manager.SUPERVISOR, row.group(1), -1, 0, row.group(2),
                    pid.find() ? Integer.parseInt(pid.group(1)) : 0, 0, 0, since, 0, "program", "", "", "",
                    "", "", 0, ""));
        }
        return List.copyOf(found);
    }

    /** CPU and memory for each pid, from {@code ps -o pid=,pcpu=,rss=}: [percent, bytes]. */
    public static Map<Integer, double[]> parseStats(String output) {
        Map<Integer, double[]> found = new LinkedHashMap<>();
        for (String line : (output == null ? "" : output).split("\\R")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length < 3) {
                continue;
            }
            try {
                found.put(Integer.parseInt(parts[0]), new double[]{Double.parseDouble(parts[1]),
                        Long.parseLong(parts[2]) * 1024.0});
            } catch (NumberFormatException ignored) {
                // Not a row of figures.
            }
        }
        return found;
    }

    /**
     * Every listening TCP socket from {@code ss -tlnpH}, by the pids holding it. A socket the account cannot
     * name a process for still counts, under pid 0, so a PORT from an environment can still be found.
     */
    public static Map<Integer, List<App.Listening>> parseSockets(String output) {
        Map<Integer, List<App.Listening>> found = new LinkedHashMap<>();
        for (String line : (output == null ? "" : output).split("\\R")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length < 4) {
                continue;
            }
            String local = parts[3];
            int colon = local.lastIndexOf(':');
            if (colon <= 0) {
                continue;
            }
            int port;
            try {
                port = Integer.parseInt(local.substring(colon + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            String address = local.substring(0, colon).replace("%lo", "");
            if (address.startsWith("[") && address.endsWith("]")) {
                address = address.substring(1, address.length() - 1);
            }
            App.Listening listening = new App.Listening(address, port);
            Matcher pid = SOCKET_PID.matcher(line);
            boolean named = false;
            while (pid.find()) {
                named = true;
                add(found, Integer.parseInt(pid.group(1)), listening);
            }
            if (!named) {
                add(found, 0, listening);
            }
            if (line.contains("(\"PM2 ")) {
                add(found, PM2_DAEMON, listening);
            }
        }
        return found;
    }

    private static void add(Map<Integer, List<App.Listening>> found, int pid, App.Listening listening) {
        List<App.Listening> list = found.computeIfAbsent(pid, key -> new ArrayList<>());
        if (!list.contains(listening)) {
            list.add(listening);
        }
    }

    /**
     * Gathers processes into applications, in the order they were listed, and finds each one's ports: those its
     * own processes hold, and the PORT its environment names when a socket listens there. A PM2 cluster's
     * socket is held by PM2's own daemon rather than by a worker, so the environment is often the only way.
     */
    public static List<App> group(List<AppProcess> processes, Map<Integer, List<App.Listening>> sockets,
                                  Set<String> saved, boolean savedKnown) {
        Map<String, List<AppProcess>> byApp = new LinkedHashMap<>();
        for (AppProcess process : processes) {
            byApp.computeIfAbsent(process.manager().word() + ":" + process.app(), key -> new ArrayList<>())
                    .add(process);
        }
        List<App> apps = new ArrayList<>();
        for (List<AppProcess> members : byApp.values()) {
            members.sort(java.util.Comparator.comparingInt(AppProcess::instance)
                    .thenComparingInt(AppProcess::id));
            AppProcess first = members.get(0);
            List<App.Listening> ports = new ArrayList<>();
            Set<Integer> wanted = new HashSet<>();
            for (AppProcess process : members) {
                for (App.Listening listening : sockets.getOrDefault(process.pid(), List.of())) {
                    if (process.pid() > 0 && !ports.contains(listening)) {
                        ports.add(listening);
                    }
                }
                if (process.envPort() > 0) {
                    wanted.add(process.envPort());
                }
            }
            for (List<App.Listening> held : sockets.values()) {
                for (App.Listening listening : held) {
                    if (wanted.contains(listening.port()) && ports.stream().noneMatch(port ->
                            port.port() == listening.port())) {
                        ports.add(listening);
                    }
                }
            }
            ports.sort(java.util.Comparator.comparingInt(App.Listening::port));
            boolean isSaved = first.manager() != Manager.PM2 || !savedKnown || saved.contains(first.app());
            apps.add(new App(first.manager(), first.app(), members, ports, isSaved));
        }
        giveDaemonSockets(apps, sockets.getOrDefault(PM2_DAEMON, List.of()));
        return List.copyOf(apps);
    }

    /**
     * A cluster that names no PORT still listens somewhere, through PM2's daemon. When the daemon holds
     * sockets no application claimed and exactly one running cluster has no port, they are that cluster's.
     * With two such clusters there is no telling which is whose, and nothing is guessed.
     */
    private static void giveDaemonSockets(List<App> apps, List<App.Listening> daemon) {
        Set<Integer> claimed = new HashSet<>();
        for (App app : apps) {
            app.ports().forEach(port -> claimed.add(port.port()));
        }
        List<App.Listening> free = new ArrayList<>();
        for (App.Listening listening : daemon) {
            if (!claimed.contains(listening.port()) && !free.contains(listening)) {
                free.add(listening);
            }
        }
        int orphan = -1;
        for (int i = 0; i < apps.size(); i++) {
            App app = apps.get(i);
            if (app.manager() == Manager.PM2 && app.cluster() && app.online() > 0 && app.ports().isEmpty()) {
                if (orphan >= 0) {
                    return;
                }
                orphan = i;
            }
        }
        if (orphan < 0 || free.isEmpty()) {
            return;
        }
        free.sort(java.util.Comparator.comparingInt(App.Listening::port));
        App app = apps.get(orphan);
        apps.set(orphan, new App(app.manager(), app.name(), app.processes(), free, app.saved()));
    }
}
