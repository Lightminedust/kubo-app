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

    /**
     * Which package manager this machine actually has, when it is not apt.
     *
     * <p>Kubo drives apt and only apt here. Saying "this server does not use apt" and stopping leaves somebody
     * to work out for themselves whether Kubo is confused or their server is unusual, so it names what is
     * there instead. No root: it is six lookups on the PATH.
     */
    public com.kubo.model.tools.PackageManager manager() {
        return com.kubo.tools.ToolCommands.parseManager(
                run(com.kubo.tools.ToolCommands.DETECT, READ_TIMEOUT).output());
    }

    /**
     * What is still running on something that has been replaced.
     *
     * <p>As root: needrestart can only see this user's processes otherwise, which on a server is almost none
     * of them and would answer "nothing to do" to a machine full of stale daemons.
     */
    public com.kubo.model.packages.RestartNeeds restartNeeds() {
        return com.kubo.model.packages.RestartNeeds.parse(runAsRoot(AptCommands.NEEDRESTART).output());
    }

    /** Every place apt is allowed to fetch from. Needs no root: these files are world-readable. */
    public java.util.List<com.kubo.model.packages.AptSource> sources() {
        return com.kubo.model.packages.AptSource.parse(run(AptCommands.SOURCES, READ_TIMEOUT).output());
    }

    /**
     * Every key apt would accept a signature from, with when it stops accepting it.
     *
     * <p>Some keyrings under /etc are root-only, so this is asked as root when it can be. Whatever comes back
     * is what could be read, and the screen says so rather than claiming a machine has no keys.
     */
    public java.util.List<com.kubo.model.packages.SigningKey> keys() {
        return com.kubo.model.packages.SigningKey.parse(runAsRoot(AptCommands.KEYS).output());
    }

    /**
     * Comments a source line out, or takes the comment off.
     *
     * <p>The file and the line come from what Kubo read, and both are checked again before the command is
     * built: apt's own folders only, and a line number that is one.
     */
    public CommandEntry switchSource(String file, int line, boolean enable) {
        return runAsRoot(AptCommands.switchSource(file, line, enable));
    }

    /**
     * Reboots the machine.
     *
     * <p>The only thing that puts a newly installed kernel in charge. It answers by cutting the connection, so
     * a command that never came back is what success looks like here.
     */
    public CommandEntry reboot() {
        return runAsRoot(AptCommands.REBOOT);
    }

    /** How much disk the downloaded index and the package cache are taking, in bytes. */
    public long cacheBytes() {
        try {
            return Long.parseLong(run(AptCommands.CACHE_SIZE, READ_TIMEOUT).output().strip());
        } catch (NumberFormatException unreadable) {
            return 0;
        }
    }

    /**
     * Writes a new source as its own file under sources.list.d.
     *
     * <p>The line goes in through standard input, never into the command. Both halves are checked before
     * either is built, so a newline in an address cannot become a second source.
     */
    public CommandEntry addSource(String name, String uri, String suite, String components, String signedBy) {
        String line = AptCommands.sourceLine(uri, suite, components, signedBy);
        String command = AptCommands.addSource(name);
        if (privilege == null || !privilege.available() || privilege.needsPassword()) {
            CommandEntry entry = connection.exec(command,
                    line.getBytes(java.nio.charset.StandardCharsets.UTF_8), WORK_TIMEOUT);
            commands.add(entry, Origin.PACKAGES);
            return entry;
        }
        Privilege.Command wrapped = privilege.asRootReadingInput(command);
        CommandEntry raw = connection.exec(wrapped.command(),
                join(wrapped.input(), line.getBytes(java.nio.charset.StandardCharsets.UTF_8)), WORK_TIMEOUT);
        CommandEntry entry = new CommandEntry(raw.executedAt(), command, true, raw.exitCode(), raw.output(),
                raw.error(), raw.duration());
        commands.add(entry, Origin.PACKAGES);
        return entry;
    }

    /** The sudo password, then what the command reads. */
    private static byte[] join(byte[] first, byte[] second) {
        if (first == null || first.length == 0) {
            return second;
        }
        byte[] both = new byte[first.length + second.length];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }

    public String simulateInstall(String name) {
        return AptCommands.summarise(run(AptCommands.dryRunInstall(name), READ_TIMEOUT).output());
    }

    public String simulateRemove(String name) {
        return AptCommands.summarise(run(AptCommands.dryRunRemove(name), READ_TIMEOUT).output());
    }

    /**
     * The whole of what apt says it would do, names included.
     *
     * <p>The summary above keeps one line of this. The names are what say whether a removal takes the SSH
     * server with it, which is the question worth asking before rather than after.
     */
    public com.kubo.model.packages.PackagePlan planInstall(String name) {
        return com.kubo.model.packages.PackagePlan.parse(
                run(AptCommands.dryRunInstall(name), READ_TIMEOUT).output());
    }

    public com.kubo.model.packages.PackagePlan planRemove(String name) {
        return com.kubo.model.packages.PackagePlan.parse(
                run(AptCommands.dryRunRemove(name), READ_TIMEOUT).output());
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

    /**
     * The same three, but handing every line apt prints to {@code onLine} as it arrives.
     *
     * <p>An upgrade of forty packages is minutes of downloading and unpacking, and the plain calls above say
     * nothing at all until it is over. Apt says plenty in that time, and this is how the screen gets it while it
     * is still worth showing.
     *
     * @param onLine called on a reader thread, never the UI thread
     */
    public CommandEntry updateIndex(java.util.function.Consumer<String> onLine) {
        return watch(AptCommands.UPDATE_INDEX, onLine);
    }

    public CommandEntry upgrade(java.util.function.Consumer<String> onLine) {
        return watch(AptCommands.UPGRADE, onLine);
    }

    public CommandEntry fullUpgrade(java.util.function.Consumer<String> onLine) {
        return watch(AptCommands.FULL_UPGRADE, onLine);
    }

    public CommandEntry autoremove(java.util.function.Consumer<String> onLine) {
        return watch(AptCommands.AUTOREMOVE, onLine);
    }

    public CommandEntry install(String name, java.util.function.Consumer<String> onLine) {
        return watch(AptCommands.install(name), onLine);
    }

    public CommandEntry remove(String name, java.util.function.Consumer<String> onLine) {
        return watch(AptCommands.remove(name), onLine);
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
     * As {@link #runAsRoot}, watching the output go by.
     *
     * <p>Apt writes its progress to standard error as often as to standard output, and interleaves the two;
     * {@code execWatching} hands over both in the order they arrived, which is the order they make sense in.
     */
    private CommandEntry watch(String command, java.util.function.Consumer<String> onLine) {
        if (privilege == null || !privilege.available() || privilege.needsPassword()) {
            CommandEntry entry = connection.execWatching(command, null, onLine, WORK_TIMEOUT);
            commands.add(entry, Origin.PACKAGES);
            return entry;
        }
        Privilege.Command wrapped = privilege.asRoot(command);
        CommandEntry raw = connection.execWatching(wrapped.command(), wrapped.input(), onLine, WORK_TIMEOUT);
        // The history keeps the plain command and that it ran as root, never the sudo wrapper or the password.
        CommandEntry entry = new CommandEntry(raw.executedAt(), command, true, raw.exitCode(), raw.output(),
                raw.error(), raw.duration());
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
