package com.kubo.apps;

import com.kubo.command.AdminPath;
import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.model.apps.App;
import com.kubo.model.apps.AppProcess;
import com.kubo.model.apps.AppsReport;
import com.kubo.model.apps.Manager;
import com.kubo.privilege.Privilege;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads and drives what PM2 and supervisor keep running on the server.
 *
 * <p>PM2 is read as the connected account, because PM2 is per account: root's PM2 is another list. Supervisor
 * is read as root when root is on, because its socket is usually root's alone.
 *
 * <p>The history keeps the plain command, never the PATH prepended to find PM2 and never the sudo wrapper.
 * Methods block: call them off the UI thread.
 */
public final class AppService {

    private static final Duration READ = Duration.ofSeconds(25);
    /** A reload waits for every worker of a cluster to come back, one after the other. */
    private static final Duration ACT = Duration.ofMinutes(2);
    private static final Duration CHECK = Duration.ofSeconds(20);

    private final SshConnection connection;
    private final CommandService commands;
    private final Privilege privilege;
    /** The cores' counters at the reading before, to tell how busy each was since. */
    private long[][] ticks;

    public AppService(SshConnection connection, CommandService commands, Privilege privilege) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.privilege = privilege;
    }

    /** Checks a sudo password with a harmless command, without recording it. */
    public CommandEntry verify(Privilege.Command command) {
        return connection.exec(command.command(), command.input(), CHECK);
    }

    /** True when root is possible here but the sudo password has not been given yet. */
    public boolean needsPassword() {
        return privilege != null && privilege.available() && privilege.needsPassword();
    }

    /** True when this account can become root at all. */
    public boolean rootPossible() {
        return privilege != null && privilege.available();
    }

    public AppsReport read() {
        List<String> notes = new ArrayList<>();
        CommandEntry entry = run(AppCommands.READ, READ);
        String output = entry.output();
        if (output.isBlank()) {
            notes.add(entry.error().isBlank() ? "the server did not answer" : firstLine(entry.error()));
        }
        int cores = AppCommands.cores(output);
        long[] memory = AppCommands.memory(output);
        String pm2Section = AppCommands.section(output, "pm2");
        boolean pm2 = !pm2Section.isBlank() && !pm2Section.strip().equals("ABSENT");
        List<AppProcess> processes = new ArrayList<>(pm2 ? AppCommands.parsePm2(pm2Section) : List.of());
        if (pm2 && processes.isEmpty() && !pm2Section.contains("[")) {
            notes.add("pm2 is installed but did not list anything it runs");
        }
        Set<String> saved = AppCommands.savedNames(AppCommands.section(output, "dump"));
        String boot = AppCommands.section(output, "boot").strip();
        Map<Integer, List<App.Listening>> sockets = AppCommands.parseSockets(AppCommands.section(output, "sockets"));
        boolean supervisor = AppCommands.section(output, "supervisor").strip().equals("PRESENT");

        if (supervisor) {
            CommandEntry status = runAsRoot(AppCommands.SUPERVISOR_STATUS, READ);
            List<AppProcess> programs = AppCommands.parseSupervisor(status.output(), Instant.now());
            if (programs.isEmpty() && !status.success()) {
                String said = status.error().isBlank() ? status.output() : status.error();
                notes.add(said.toLowerCase(java.util.Locale.ROOT).contains("permission")
                        ? "supervisor is installed, and reading it needs root"
                        : "supervisor did not answer" + (said.isBlank() ? "" : ": " + firstLine(said)));
            }
            processes.addAll(withStats(programs));
        }
        List<App> apps = AppCommands.group(processes, sockets, saved, pm2);
        long[][] counters = AppCommands.coreTicks(output);
        List<Double> loads = AppCommands.coreLoads(ticks, counters);
        if (counters.length > 0) {
            ticks = counters;
        }
        return new AppsReport(pm2, supervisor, apps, cores, memory[0], memory[1], boot, notes,
                AppCommands.host(output), loads, AppCommands.load(output));
    }

    /** Supervisor's programs with the CPU and memory ps gives for their pids. */
    private List<AppProcess> withStats(List<AppProcess> programs) {
        String command = AppCommands.stats(programs.stream().map(AppProcess::pid).toList());
        if (command.isEmpty()) {
            return programs;
        }
        Map<Integer, double[]> stats = AppCommands.parseStats(run(command, READ).output());
        List<AppProcess> filled = new ArrayList<>();
        for (AppProcess p : programs) {
            double[] figures = stats.get(p.pid());
            filled.add(figures == null ? p : new AppProcess(p.manager(), p.app(), p.id(), p.instance(), p.status(),
                    p.pid(), figures[0], (long) figures[1], p.since(), p.restarts(), p.mode(), p.script(), p.cwd(),
                    p.interpreter(), p.outLog(), p.errLog(), p.envPort(), p.version()));
        }
        return filled;
    }

    public CommandEntry pm2(AppCommands.Pm2Verb verb, String app) {
        return run(AppCommands.pm2(verb, app), ACT);
    }

    public CommandEntry scale(String app, int workers) {
        return run(AppCommands.pm2Scale(app, workers), ACT);
    }

    public CommandEntry save() {
        return run(AppCommands.PM2_SAVE, ACT);
    }

    public CommandEntry reset(String app) {
        return run(AppCommands.pm2Reset(app), ACT);
    }

    public CommandEntry flush(String app) {
        return run(AppCommands.pm2Flush(app), ACT);
    }

    /**
     * Makes PM2 start at boot, or not. As root, because it writes a systemd unit. Turning it on also saves
     * the list: a PM2 that comes back with nothing to bring back is the trap this is here to avoid.
     */
    public CommandEntry boot(AppsReport.Host host, boolean on) {
        CommandEntry entry = runAsRoot(AppCommands.pm2Boot(host, on), ACT);
        if (!entry.success() || !on) {
            return entry;
        }
        return save();
    }

    /** One folder on the server, for choosing a script by walking to it. As root when root is on. */
    public com.kubo.model.files.DirectoryListing list(String path) {
        CommandEntry entry = runAsRoot(com.kubo.files.ListingParser.command(path), READ);
        if (!entry.ran()) {
            return com.kubo.model.files.DirectoryListing.failed(path, entry.error());
        }
        return com.kubo.files.ListingParser.parse(path, entry.output(), entry.error());
    }

    public CommandEntry start(AppCommands.NewApp app) {
        return run(AppCommands.pm2Start(app), ACT);
    }

    public CommandEntry supervisor(AppCommands.SupervisorVerb verb, String program) {
        return runAsRoot(AppCommands.supervisor(verb, program), ACT);
    }

    /**
     * The end of what an application wrote: its output, or its errors.
     *
     * @return the lines, or what went wrong prefixed with "!" so the screen can say it in red
     */
    public String log(App app, boolean errors, int lines) {
        AppProcess first = app.first();
        if (first == null) {
            return "";
        }
        CommandEntry entry;
        if (app.manager() == Manager.SUPERVISOR) {
            entry = runAsRoot(AppCommands.supervisorTail(app.name(), errors), READ);
        } else {
            String file = errors ? first.errLog() : first.outLog();
            if (file.isEmpty() || file.equals("/dev/null")) {
                return "!pm2 sends these lines nowhere";
            }
            if (!AppCommands.validPath(file)) {
                return "!the log file's name has characters Kubo does not send to a server";
            }
            entry = run(AppCommands.tail(file, lines), READ);
        }
        if (!entry.success() && entry.output().isBlank()) {
            return "!" + (entry.error().isBlank() ? "the log could not be read" : firstLine(entry.error()));
        }
        return entry.output();
    }

    private CommandEntry run(String command, Duration timeout) {
        CommandEntry raw = connection.exec(AppCommands.PATHS + AdminPath.with(command), null, timeout);
        CommandEntry entry = new CommandEntry(raw.executedAt(), command, false, raw.exitCode(), raw.output(),
                raw.error(), raw.duration());
        commands.add(entry, CommandService.Origin.APPS);
        return entry;
    }

    /**
     * As root when root is on; as the account otherwise, which fails with the server's own reason, and that
     * reason is what the screen reports.
     */
    private CommandEntry runAsRoot(String command, Duration timeout) {
        if (privilege == null || !privilege.available() || privilege.needsPassword()) {
            return run(command, timeout);
        }
        Privilege.Command wrapped = privilege.asRoot(AdminPath.with(command));
        CommandEntry raw = connection.exec(wrapped.command(), wrapped.input(), timeout);
        CommandEntry entry = new CommandEntry(raw.executedAt(), command, true, raw.exitCode(), raw.output(),
                raw.error(), raw.duration());
        commands.add(entry, CommandService.Origin.APPS);
        return entry;
    }

    private static String firstLine(String text) {
        String stripped = text.strip();
        int end = stripped.indexOf('\n');
        return end < 0 ? stripped : stripped.substring(0, end);
    }
}
