package com.kubo.tools;

import com.kubo.command.AdminPath;
import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.command.CommandService.Origin;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.model.tools.PackageManager;
import com.kubo.model.tools.Tool;
import com.kubo.privilege.Privilege;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Reads which helper tools a server has, and installs one when the operator asks.
 *
 * <p>Installing is the only thing in Kubo that adds something to a server, and it happens on a click, one tool
 * at a time, never on its own. It always goes through root, whoever is connected: no package manager will write
 * to /usr as an ordinary user, and asking as one would only produce a confusing refusal.
 *
 * <p>Methods block: call them off the UI thread.
 */
public final class ToolService {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
    /** Three pings, three rounds of mtr or a whois to the other side of the world all fit inside this. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(40);
    /** Installing fetches over the network and unpacks; a minute is short for a slow mirror. */
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(3);

    private final SshConnection connection;
    private final CommandService commands;
    private final Privilege privilege;

    public ToolService(SshConnection connection, CommandService commands, Privilege privilege) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.privilege = privilege;
    }

    /**
     * Runs one probe and returns what the server printed, whether it worked or not.
     *
     * <p>None of them needs root and all of them are bounded by their own arguments, so a probe cannot outlive
     * the click that started it.
     */
    public CommandEntry probe(String command) {
        CommandEntry raw = connection.exec(AdminPath.with(command), null, PROBE_TIMEOUT);
        CommandEntry entry = new CommandEntry(raw.executedAt(), command, false, raw.exitCode(), raw.output(),
                raw.error(), raw.duration());
        commands.add(entry, Origin.NETWORK);
        return entry;
    }

    /** True when Kubo could install something right now, which needs root. */
    public boolean canInstall() {
        return privilege != null && privilege.available() && !privilege.needsPassword();
    }

    /** What is already there. Reading needs nothing special. */
    public Set<Tool> present() {
        return ToolCommands.parseTools(run(ToolCommands.DETECT).output());
    }

    /**
     * Both answers in one round trip: what is installed, and what could install the rest.
     */
    public Reading read() {
        String output = run(ToolCommands.DETECT).output();
        return new Reading(ToolCommands.parseTools(output), ToolCommands.parseManager(output));
    }

    /**
     * @param manager NONE when this server has no package manager Kubo knows how to drive
     */
    public record Reading(Set<Tool> present, PackageManager manager) {
    }

    /**
     * Installs one tool.
     *
     * @throws IllegalStateException    when root is not available, which is required whoever is connected
     * @throws IllegalArgumentException when Kubo knows no package for this tool on this system
     */
    public CommandEntry install(Tool tool, PackageManager manager) {
        if (!canInstall()) {
            throw new IllegalStateException("Installing writes to the system, so it needs root. Turn on ROOT in "
                    + "the header first.");
        }
        return runAsRoot(ToolCommands.install(tool, manager));
    }

    private CommandEntry run(String command) {
        CommandEntry raw = connection.exec(AdminPath.with(command), null, READ_TIMEOUT);
        CommandEntry entry = new CommandEntry(raw.executedAt(), command, false, raw.exitCode(), raw.output(),
                raw.error(), raw.duration());
        commands.add(entry, Origin.NETWORK);
        return entry;
    }

    private CommandEntry runAsRoot(String command) {
        Privilege.Command wrapped = privilege.asRoot(AdminPath.with(command));
        CommandEntry raw = connection.exec(wrapped.command(), wrapped.input(), INSTALL_TIMEOUT);
        CommandEntry entry = new CommandEntry(raw.executedAt(), command, true, raw.exitCode(), raw.output(),
                raw.error(), raw.duration());
        commands.add(entry, Origin.NETWORK);
        return entry;
    }
}
