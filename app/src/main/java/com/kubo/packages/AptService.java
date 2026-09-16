package com.kubo.packages;

import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.command.CommandService.Origin;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.model.packages.AptStatus;
import com.kubo.model.packages.PackageEntry;
import com.kubo.model.packages.PackageInventory;
import com.kubo.privilege.Privilege;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads what is installed on the server and, when asked, changes it.
 *
 * <p>Reading needs nothing special; installing, removing and upgrading need root, so those go through
 * {@link Privilege} and are always shown to the user first as a simulation. Methods block: call them off the UI
 * thread.
 */
public final class AptService {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(45);
    /** Fetching and unpacking packages is slow; this is generous on purpose. */
    private static final Duration WORK_TIMEOUT = Duration.ofMinutes(15);

    private final SshConnection connection;
    private final CommandService commands;
    private final Privilege privilege;

    public AptService(SshConnection connection, CommandService commands, Privilege privilege) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.privilege = privilege;
    }

    /**
     * True when changing anything needs a sudo password Kubo does not have yet.
     */
    public boolean needsPassword() {
        return privilege != null && privilege.needsPassword();
    }

    /**
     * Checks a sudo password with a harmless command, without recording it.
     */
    public CommandEntry verify(Privilege.Command command) {
        return connection.exec(command.command(), command.input(), READ_TIMEOUT);
    }

    public AptStatus detect() {
        String status = run(AptCommands.STATUS).output();
        String reboot = run(AptCommands.REBOOT_REQUIRED).output();
        return AptCommands.parseStatus(status, reboot);
    }

    /**
     * Everything installed, with what is waiting and what is no longer needed folded in.
     */
    public PackageInventory read() {
        List<String> notes = new ArrayList<>();
        AptStatus status = detect();
        if (!status.usable()) {
            notes.add("This server does not use apt, so Kubo cannot manage its packages.");
            return new PackageInventory(status, List.of(), notes);
        }

        List<PackageEntry> installed = AptCommands.parseInstalled(run(AptCommands.INSTALLED).output());
        if (installed.isEmpty()) {
            notes.add("dpkg listed nothing, which usually means the query itself failed.");
        }

        CommandEntry upgradable = run(AptCommands.UPGRADABLE_LIST);
        Map<String, String[]> waiting = AptCommands.parseUpgradable(upgradable.output());

        // Simulating an autoremove reads the package database only; it changes nothing.
        Set<String> autoRemovable = AptCommands.parseAutoRemovable(run(AptCommands.AUTOREMOVE_PREVIEW).output());
        Set<String> manual = AptCommands.parseNames(run(AptCommands.MANUAL).output());

        if (status.rebootRequired()) {
            notes.add("An update has asked for a restart: the running kernel or libraries are not the installed "
                    + "ones any more.");
        }
        return new PackageInventory(status, AptCommands.merge(installed, waiting, autoRemovable, manual), notes);
    }

    // What apt would do, shown before anything is done.

    public String simulateInstall(String name) {
        return AptCommands.summarise(run(AptCommands.dryRunInstall(name), READ_TIMEOUT).output());
    }

    public String simulateRemove(String name) {
        return AptCommands.summarise(run(AptCommands.dryRunRemove(name), READ_TIMEOUT).output());
    }

    public CommandEntry describe(String name) {
        return run(AptCommands.describe(name), READ_TIMEOUT);
    }

    // Actions, all as root.

    public CommandEntry updateIndex() {
        return runAsRoot(AptCommands.UPDATE_INDEX);
    }

    public CommandEntry upgrade() {
        return runAsRoot(AptCommands.UPGRADE);
    }

    public CommandEntry fullUpgrade() {
        return runAsRoot(AptCommands.FULL_UPGRADE);
    }

    public CommandEntry autoremove() {
        return runAsRoot(AptCommands.AUTOREMOVE);
    }

    public CommandEntry clean() {
        return runAsRoot(AptCommands.CLEAN);
    }

    public CommandEntry install(String name) {
        return runAsRoot(AptCommands.install(name));
    }

    public CommandEntry remove(String name) {
        return runAsRoot(AptCommands.remove(name));
    }

    public CommandEntry purge(String name) {
        return runAsRoot(AptCommands.purge(name));
    }

    // Running

    private CommandEntry run(String command) {
        return run(command, READ_TIMEOUT);
    }

    private CommandEntry run(String command, Duration timeout) {
        CommandEntry entry = connection.exec(command, null, timeout);
        commands.add(entry, Origin.PACKAGES);
        return entry;
    }

    /**
     * Runs as root when the account is not already root. The history keeps the plain command with its root flag,
     * never the sudo wrapper and never the password.
     */
    private CommandEntry runAsRoot(String command) {
        // Without the sudo password there is nothing to wrap the command in; it runs as the connected user and
        // fails the way apt fails for a normal user, which the caller reports.
        if (privilege == null || !privilege.available() || privilege.needsPassword()) {
            return run(command, WORK_TIMEOUT);
        }
        Privilege.Command wrapped = privilege.asRoot(command);
        CommandEntry raw = connection.exec(wrapped.command(), wrapped.input(), WORK_TIMEOUT);
        CommandEntry entry = new CommandEntry(raw.executedAt(), command, true, raw.exitCode(), raw.output(),
                raw.error(), raw.duration());
        commands.add(entry, Origin.PACKAGES);
        return entry;
    }
}
