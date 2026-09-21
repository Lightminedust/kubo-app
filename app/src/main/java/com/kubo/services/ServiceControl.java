package com.kubo.services;

import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.command.CommandService.Origin;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.model.services.Action;
import com.kubo.model.services.Lifeline;
import com.kubo.model.services.Removal;
import com.kubo.model.services.Resource;
import com.kubo.model.services.Signal;
import com.kubo.privilege.Privilege;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Starting, stopping and enabling services. This is the half of the screen that changes the server.
 *
 * <p>Two things are deliberately not here. Nothing takes a command as text: the verb comes from a five-value
 * enum and the unit name is quoted, so there is no shape of input that turns into a second command. And the
 * refusal that protects the connection is checked here as well as in the screen above, because a guard that
 * lives only in a button is one refactor away from being gone.
 *
 * <p>Methods block: call them off the UI thread.
 */
public final class ServiceControl {

    /** Some units take their time stopping; systemd's own default before it gives up is ninety seconds. */
    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    private static final Duration CHECK = Duration.ofSeconds(20);

    private final SshConnection connection;
    private final CommandService commands;
    private final Privilege privilege;

    public ServiceControl(SshConnection connection, CommandService commands, Privilege privilege) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.privilege = privilege;
    }

    /** True when changing anything needs a sudo password Kubo does not have yet. */
    public boolean needsPassword() {
        return privilege != null && privilege.needsPassword();
    }

    /** True when this account can change anything at all. */
    public boolean available() {
        return privilege != null && privilege.available();
    }

    /** Checks a sudo password with a harmless command, without recording it. */
    public CommandEntry verify(Privilege.Command command) {
        return connection.exec(command.command(), command.input(), CHECK);
    }

    /**
     * Runs one of the five verbs on one unit.
     *
     * @throws IllegalStateException when the unit is the one carrying this connection and the verb would cut it
     */
    public CommandEntry run(Action action, String unit) {
        String refusal = Lifeline.refusal(action, unit);
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
        return runAsRoot(ServiceCommands.act(action, unit));
    }

    /**
     * Puts a limit on a running service, without stopping it.
     *
     * @throws IllegalArgumentException when the value is not one systemd would accept
     */
    public CommandEntry setLimit(Resource resource, String value, String unit) {
        return runAsRoot(ServiceCommands.setLimit(resource, value, unit));
    }

    /**
     * Sends one signal to a service.
     *
     * @throws IllegalStateException when the unit is the one carrying this connection and the signal ends it
     */
    public CommandEntry signal(Signal signal, boolean everything, String unit) {
        String refusal = Lifeline.refusal(signal, unit);
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
        return runAsRoot(ServiceCommands.signal(signal, everything, unit));
    }

    /**
     * Saves a service's own settings, as a file of its own beside the one its package shipped.
     *
     * <p>The file the distribution installed is never touched. systemd reads a drop-in after it, so anything
     * written here wins, and taking the file away puts the service back exactly as it was. The content goes
     * in on standard input, never in the command, so it stays out of the process list and out of sudo's log.
     */
    public CommandEntry saveOverride(String unit, String content) {
        return writing(ServiceCommands.writeDropIn(unit), content);
    }

    /**
     * Writes a new service, and will not write over one that already exists.
     *
     * <p>The refusal is in the command itself rather than only in the screen: a screen that looked a moment
     * ago is not the same thing as a check that cannot be raced.
     */
    public CommandEntry createUnit(String unit, String content) {
        return writing(ServiceCommands.createUnit(unit), content);
    }

    /** One command fed a whole file on its standard input, as root when that is what it takes. */
    private CommandEntry writing(String command, String content) {
        byte[] bytes = (content.endsWith("\n") ? content : content + "\n").getBytes(StandardCharsets.UTF_8);
        if (privilege == null || !privilege.available() || privilege.needsPassword()) {
            return record(connection.exec(command, bytes, TIMEOUT), command, false);
        }
        Privilege.Command wrapped = privilege.asRootReadingInput(command);
        return record(connection.exec(wrapped.command(), join(wrapped.input(), bytes), TIMEOUT), command, true);
    }

    /**
     * Takes a service off the machine: only its own file, and only when it is one that was put there by hand.
     *
     * <p>The judgement is the model's and is made again here rather than trusted to the screen, because this
     * is the one verb nothing undoes.
     *
     * @param filePath what systemd says the unit file is, which decides whether this is Kubo's to delete
     * @throws IllegalStateException when this unit may not be deleted
     */
    public CommandEntry removeUnit(String unit, String filePath) {
        String refusal = Removal.refusal(unit, filePath);
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
        return runAsRoot(ServiceCommands.removeUnit(unit));
    }

    /** Clears the failed entry systemd keeps for a unit, which outlives the unit file itself. */
    public CommandEntry resetFailed(String unit) {
        return runAsRoot(ServiceCommands.resetFailed(unit));
    }

    /** Takes those settings away again, which puts the service back the way its package left it. */
    public CommandEntry removeOverride(String unit) {
        return runAsRoot(ServiceCommands.removeDropIn(unit));
    }

    /** systemd does not notice a file changing on its own. */
    public CommandEntry reloadUnits() {
        return runAsRoot(ServiceCommands.DAEMON_RELOAD);
    }

    /** The sudo password first, then what it is feeding. */
    private static byte[] join(byte[] first, byte[] second) {
        if (first == null) {
            return second;
        }
        byte[] both = new byte[first.length + second.length];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }

    /**
     * Runs as root unless the account already is. The history keeps the plain command with its root flag,
     * never the sudo wrapper and never the password.
     */
    private CommandEntry runAsRoot(String command) {
        if (privilege == null || !privilege.available() || privilege.needsPassword()) {
            // Without the password there is nothing to wrap it in: it runs as the connected user and fails
            // the way systemctl fails for one, which the screen reports as it is.
            return record(connection.exec(command, null, TIMEOUT), command, false);
        }
        Privilege.Command wrapped = privilege.asRoot(command);
        return record(connection.exec(wrapped.command(), wrapped.input(), TIMEOUT), command, true);
    }

    private CommandEntry record(CommandEntry raw, String command, boolean asRoot) {
        CommandEntry entry = new CommandEntry(raw.executedAt(), command, asRoot, raw.exitCode(), raw.output(),
                raw.error(), raw.duration());
        commands.add(entry, Origin.SERVICES);
        return entry;
    }
}
