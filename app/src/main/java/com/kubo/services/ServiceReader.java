package com.kubo.services;

import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.command.CommandService.Origin;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.model.services.BootState;
import com.kubo.model.services.Dependencies;
import com.kubo.model.services.ServiceUnit;
import com.kubo.model.services.ServicesReport;
import com.kubo.model.services.Standing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads the services of a server. Nothing here changes anything, and nothing here needs root.
 *
 * <p>Methods block: call them off the UI thread.
 */
public final class ServiceReader {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(45);

    private final SshConnection connection;
    private final CommandService commands;

    public ServiceReader(SshConnection connection, CommandService commands) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /** systemd puts {@code /run/systemd/system} there when, and only when, it is the init system. */
    public boolean present() {
        return run(ServiceCommands.PRESENT).output().trim().equals("YES");
    }

    /**
     * One reading of every service, with what each does at boot and what it cost the last one.
     *
     * <p>Anything that could not be read is named in the report rather than guessed at: a server whose boot
     * times are missing says so, instead of drawing an axis of zeroes that reads as a fast machine.
     */
    public ServicesReport read() {
        List<String> unreadable = new ArrayList<>();
        if (!present()) {
            unreadable.add(ServicesReport.NO_SYSTEMD);
            return new ServicesReport(List.of(), null, null, unreadable);
        }

        List<ServiceUnit> listed = ServiceCommands.parseUnits(run(ServiceCommands.UNITS).output());
        if (listed.isEmpty()) {
            unreadable.add("systemctl listed no service at all, which usually means the command itself failed.");
        }

        Map<String, BootState> boots = ServiceCommands.parseUnitFiles(run(ServiceCommands.UNIT_FILES).output());
        if (boots.isEmpty() && !listed.isEmpty()) {
            unreadable.add("What each service does at the next boot could not be read, so none of them is "
                    + "reported as leaving for good.");
        }

        CommandEntry blame = run(ServiceCommands.BLAME);
        Map<String, Duration> times = ServiceCommands.parseBlame(blame.output());
        CommandEntry boot = run(ServiceCommands.BOOT_TIME);
        Duration total = ServiceCommands.parseTotalBoot(boot.output());
        if (times.isEmpty()) {
            // A container, or a machine still booting: systemd-analyze refuses rather than answering zero.
            unreadable.add("This machine does not say how long its last boot took, so the band below is empty.");
        }

        List<ServiceUnit> units = new ArrayList<>();
        for (ServiceUnit unit : listed) {
            units.add(unit.withBoot(boots.getOrDefault(unit.name(), BootState.UNKNOWN))
                    .withStartup(times.get(unit.name())));
        }
        return withTypes(new ServicesReport(units, total, ServiceCommands.parseUserspace(boot.output()), unreadable));
    }

    /**
     * Asks systemd the type of the units that look like a problem, and only those.
     *
     * <p>A one-shot task is enabled and idle for the whole life of the machine, which is the exact shape of a
     * service that should be up and is not. Most of them say {@code exited} and are recognised without asking,
     * but not all, and crying wolf over a setup task that ran at boot would cost the screen its credit. The
     * question is asked about a handful of units, never about all of them.
     */
    private ServicesReport withTypes(ServicesReport report) {
        List<String> suspects = new ArrayList<>();
        for (ServiceUnit unit : report.units()) {
            if (unit.standing() == Standing.NOT_RUNNING) {
                suspects.add(unit.name());
            }
        }
        if (suspects.isEmpty()) {
            return report;
        }
        Map<String, String> types = ServiceCommands.parseTypes(run(ServiceCommands.types(suspects)).output());
        if (types.isEmpty()) {
            return report;
        }
        List<ServiceUnit> units = new ArrayList<>();
        for (ServiceUnit unit : report.units()) {
            String type = types.get(unit.name());
            units.add(type == null ? unit : unit.withType(type));
        }
        return new ServicesReport(units, report.boot(), report.userspace(), report.unreadable());
    }

    /**
     * Which service needs which. It is a second reading, asked for only once the map is up: it is the longest
     * of the commands and nothing on the screen waits for it.
     */
    public Dependencies relations(List<ServiceUnit> units) {
        if (units.isEmpty()) {
            return Dependencies.EMPTY;
        }
        List<String> names = new ArrayList<>();
        for (ServiceUnit unit : units) {
            names.add(unit.name());
        }
        return ServiceCommands.parseRelations(run(ServiceCommands.relations(names)).output());
    }

    /** Everything systemd knows about one unit. */
    public Map<String, String> detail(String unit) {
        return ServiceCommands.parseShow(run(ServiceCommands.show(unit)).output());
    }

    /** The unit file as it really is on disk, drop-ins included. */
    public String unitFile(String unit) {
        return run(ServiceCommands.cat(unit)).output();
    }

    /**
     * The last lines this unit wrote to the journal. Read as the connected user: where the journal is closed
     * to them, journalctl says so and that refusal is what the screen shows, rather than Kubo quietly asking
     * for root to read something.
     */
    public String journal(String unit, int lines) {
        return run(ServiceCommands.journal(unit, lines)).output();
    }

    /** Whatever settings Kubo has already given this unit, or nothing at all. */
    public String settings(String unit) {
        return run(ServiceCommands.readDropIn(unit)).output();
    }

    /** Whether a unit of that name is already on the machine, so a new one never writes over it. */
    public boolean exists(String unit) {
        return run(ServiceCommands.unitExists(unit)).output().contains("YES");
    }

    private CommandEntry run(String command) {
        CommandEntry entry = connection.exec(command, null, READ_TIMEOUT);
        commands.add(entry, Origin.SERVICES);
        return entry;
    }
}
