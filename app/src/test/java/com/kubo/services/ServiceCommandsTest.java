package com.kubo.services;

import com.kubo.model.services.BootState;
import com.kubo.model.services.Dependencies;
import com.kubo.model.services.ServiceUnit;
import com.kubo.model.services.ServicesReport;
import com.kubo.model.services.Standing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading what systemd answers.
 *
 * <p>The screen's whole claim is that it says something true about the next reboot, so the classification is
 * what these guard hardest — in particular against crying wolf over one-shot tasks that have simply finished.
 */
class ServiceCommandsTest {

    private static final String UNITS = """
            apache2.service loaded active running The Apache HTTP Server
            cron.service loaded active running Regular background program processing daemon
            docker.service loaded active running Docker Application Container Engine
            fail2ban.service loaded failed failed Fail2Ban Service
            keyboard-setup.service loaded active exited Set the console keyboard layout
            ssh.service loaded active running OpenBSD Secure Shell server
            unattended-upgrades.service loaded inactive dead Unattended Upgrades Shutdown
            wpa_supplicant.service not-found inactive dead wpa_supplicant.service
            """;

    private static final String UNIT_FILES = """
            apache2.service enabled enabled
            cron.service enabled enabled
            docker.service disabled disabled
            fail2ban.service enabled enabled
            keyboard-setup.service enabled enabled
            ssh.service enabled enabled
            unattended-upgrades.service enabled enabled
            getty@.service enabled enabled
            dbus.service static -
            systemd-fsck@.service masked masked
            """;

    private static ServicesReport report() {
        List<ServiceUnit> units = new ArrayList<>();
        Map<String, BootState> boots = ServiceCommands.parseUnitFiles(UNIT_FILES);
        Map<String, Duration> times = ServiceCommands.parseBlame(BLAME);
        for (ServiceUnit unit : ServiceCommands.parseUnits(UNITS)) {
            units.add(unit.withBoot(boots.getOrDefault(unit.name(), BootState.UNKNOWN))
                    .withStartup(times.get(unit.name())));
        }
        return new ServicesReport(units, Duration.ofSeconds(22), Duration.ofSeconds(18), List.of());
    }

    private static ServiceUnit find(String name) {
        return report().units().stream().filter(unit -> unit.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a listing is read into units, description and all, however many spaces it has in it")
    void unitsAreRead() {
        List<ServiceUnit> units = ServiceCommands.parseUnits(UNITS);

        assertEquals(8, units.size());
        ServiceUnit apache = units.get(0);
        assertEquals("apache2.service", apache.name());
        assertEquals("apache2", apache.shortName());
        assertEquals("The Apache HTTP Server", apache.description());
        assertEquals("running", apache.subState());
        assertTrue(apache.running());
    }

    @Test
    @DisplayName("what each unit does at boot is read, whether or not systemd prints a vendor preset")
    void bootStatesAreRead() {
        Map<String, BootState> states = ServiceCommands.parseUnitFiles(UNIT_FILES);

        assertEquals(BootState.ENABLED, states.get("cron.service"));
        assertEquals(BootState.DISABLED, states.get("docker.service"));
        assertEquals(BootState.STATIC, states.get("dbus.service"), "two columns instead of three");
        assertEquals(BootState.MASKED, states.get("systemd-fsck@.service"));
        assertEquals(BootState.ENABLED, BootState.of("enabled-runtime"), "it still comes up at the next boot");
    }

    @Test
    @DisplayName("only a disabled unit fails to come back: static and indirect ones are pulled in by others")
    void onlyDisabledFailsToReturn() {
        assertTrue(BootState.ENABLED.returnsAfterReboot());
        assertTrue(BootState.STATIC.returnsAfterReboot());
        assertTrue(BootState.INDIRECT.returnsAfterReboot());
        assertFalse(BootState.DISABLED.returnsAfterReboot());
    }

    // The two readings a terminal keeps in separate commands

    @Test
    @DisplayName("a service that is up but disabled is called out: a reboot would take it away for good")
    void runningButDisabledIsCalledOut() {
        assertEquals(Standing.WILL_NOT_RETURN, find("docker.service").standing());
    }

    @Test
    @DisplayName("a service enabled at boot and not running is called out")
    void enabledButStoppedIsCalledOut() {
        assertEquals(Standing.NOT_RUNNING, find("unattended-upgrades.service").standing());
    }

    @Test
    @DisplayName("a one-shot task that has finished is not called out, though it is enabled and idle")
    void finishedOneShotsAreNotAnAlarm() {
        ServiceUnit keyboard = find("keyboard-setup.service");

        assertEquals(BootState.ENABLED, keyboard.boot());
        assertFalse(keyboard.running());
        // It is enabled and not running, which is exactly the shape of a problem — and it is not one.
        assertEquals(Standing.IDLE, keyboard.standing(),
                "a setup task that ran at boot and exited would be a false alarm every single time");
    }

    @Test
    @DisplayName("a failure is a failure, whichever of the two columns systemd puts it in")
    void failuresAreFound() {
        assertEquals(Standing.FAILED, find("fail2ban.service").standing());
        assertEquals(Standing.FAILED, ServiceUnit.of("x.service", "", "loaded", "active", "failed").standing());
    }

    @Test
    @DisplayName("the healthy ones stay out of the way")
    void healthyServicesAreOrdinary() {
        assertEquals(Standing.RUNNING, find("ssh.service").standing());
        assertEquals(Standing.IDLE, find("wpa_supplicant.service").standing(), "not installed, nothing asks for it");
    }

    @Test
    @DisplayName("the report counts and groups by standing, by name within a group")
    void theReportGroups() {
        ServicesReport report = report();

        assertEquals(1, report.count(Standing.FAILED));
        assertEquals(1, report.count(Standing.WILL_NOT_RETURN));
        assertEquals(1, report.count(Standing.NOT_RUNNING));
        assertEquals(3, report.count(Standing.RUNNING));
        assertEquals(List.of("docker"), report.of(Standing.WILL_NOT_RETURN).stream()
                .map(ServiceUnit::shortName).toList());
        // By name within a group, which is how anybody looks for one among a hundred.
        assertEquals(List.of("apache2", "cron", "ssh"), report.of(Standing.RUNNING).stream()
                .map(ServiceUnit::shortName).toList());
    }

    // The last boot

    private static final String BLAME = """
                     8.402s apache2.service
                     2.045s docker.service
                1min 3.210s postgresql.service
                      512ms cron.service
                       45us ssh.service
            """;

    @Test
    @DisplayName("boot times are read in every shape systemd writes them")
    void blameIsRead() {
        Map<String, Duration> times = ServiceCommands.parseBlame(BLAME);

        assertEquals(Duration.ofMillis(8402), times.get("apache2.service"));
        assertEquals(Duration.ofMillis(63210), times.get("postgresql.service"), "one minute and change");
        assertEquals(Duration.ofMillis(512), times.get("cron.service"));
        assertEquals(Duration.ofNanos(45_000), times.get("ssh.service"));
        assertEquals(5, times.size());
    }

    @Test
    @DisplayName("the slowest of the last boot come back longest first")
    void theSlowestComeFirst() {
        List<String> slowest = report().slowest(2).stream().map(ServiceUnit::shortName).toList();

        assertEquals(List.of("apache2", "docker"), slowest);
    }

    @Test
    @DisplayName("the total of the last boot is read, past however many stages the machine went through")
    void bootTotalIsRead() {
        String simple = "Startup finished in 3.456s (kernel) + 18.900s (userspace) = 22.357s";
        assertEquals(Duration.ofMillis(22357), ServiceCommands.parseTotalBoot(simple));
        assertEquals(Duration.ofMillis(18900), ServiceCommands.parseUserspace(simple));

        String withFirmware = "Startup finished in 6.011s (firmware) + 2.5s (loader) + 4.1s (kernel) "
                + "+ 1min 12.300s (userspace) = 1min 24.911s";
        assertEquals(Duration.ofMillis(84911), ServiceCommands.parseTotalBoot(withFirmware));
        assertEquals(Duration.ofMillis(72300), ServiceCommands.parseUserspace(withFirmware));
    }

    @Test
    @DisplayName("a machine that cannot say how it booted returns nothing, rather than a zero that reads as fast")
    void anUnknownBootIsNotZero() {
        assertNull(ServiceCommands.parseTotalBoot(""));
        assertNull(ServiceCommands.parseTotalBoot("Bootup is not yet finished"));
        assertNull(ServiceCommands.parseDuration("what"));
    }

    // One unit in detail

    @Test
    @DisplayName("the detail of a unit is read as the key and value lines it comes in")
    void showIsRead() {
        Map<String, String> properties = ServiceCommands.parseShow("""
                Id=nginx.service
                Description=A high performance web server
                ExecStart={ path=/usr/sbin/nginx ; argv[]=/usr/sbin/nginx -g daemon on; }
                MainPID=812
                MemoryCurrent=12345678
                """);

        assertEquals("nginx.service", properties.get("Id"));
        assertEquals("812", properties.get("MainPID"));
        assertTrue(properties.get("ExecStart").contains("/usr/sbin/nginx"), "the equals signs inside are kept");
    }

    @Test
    @DisplayName("the types of several units come back as one block each")
    void typesAreRead() {
        Map<String, String> types = ServiceCommands.parseTypes("""
                Id=keyboard-setup.service
                Type=oneshot

                Id=nginx.service
                Type=forking
                """);

        assertEquals("oneshot", types.get("keyboard-setup.service"));
        assertEquals("forking", types.get("nginx.service"));
    }

    @Test
    @DisplayName("what a unit needs is read both ways round, and only the services are kept")
    void relationsAreRead() {
        Dependencies links = ServiceCommands.parseRelations("""
                Id=docker.service
                Requires=containerd.service
                Wants=network-online.target
                After=containerd.service basic.target firewalld.service

                Id=nginx.service
                Requires=
                Wants=
                After=network.target remote-fs.target
                """);

        assertEquals(List.of("containerd.service", "firewalld.service"), links.needsOf("docker.service"),
                "the targets are systemd's plumbing, not something a person acts on");
        assertEquals(List.of(), links.needsOf("nginx.service"));
        // The direction systemd never answers, and the one people ask for: stop this and what breaks?
        assertEquals(List.of("docker.service"), links.neededByOf("containerd.service"));
    }

    @Test
    @DisplayName("blocks are told apart by a second Id, for the systemd versions that print no blank line")
    void blocksWithoutBlankLinesAreStillTwoUnits() {
        List<Map<String, String>> blocks = ServiceCommands.parseBlocks("""
                Id=a.service
                Type=oneshot
                Id=b.service
                Type=simple
                """);

        assertEquals(2, blocks.size());
        assertEquals("a.service", blocks.get(0).get("Id"));
        assertEquals("simple", blocks.get(1).get("Type"));
    }

    @Test
    @DisplayName("a unit that names itself in its own ordering is not drawn as needing itself")
    void aUnitNeverNeedsItself() {
        Dependencies links = ServiceCommands.parseRelations("""
                Id=a.service
                After=a.service b.service
                """);

        assertEquals(List.of("b.service"), links.needsOf("a.service"));
    }

    @Test
    @DisplayName("every command asks in a fixed language, so a server set to another one is still read right")
    void everyCommandPinsTheLanguage() {
        for (String command : List.of(ServiceCommands.UNITS, ServiceCommands.UNIT_FILES, ServiceCommands.BLAME,
                ServiceCommands.BOOT_TIME, ServiceCommands.show("nginx.service"),
                ServiceCommands.cat("nginx.service"), ServiceCommands.types(List.of("a.service")))) {
            assertTrue(command.startsWith("LC_ALL=C "), command);
        }
    }

    @Test
    @DisplayName("a unit name reaches the shell quoted, never as something it could be read as")
    void unitNamesAreQuoted() {
        String command = ServiceCommands.show("a'b; rm -rf /");

        assertFalse(command.contains("; rm -rf / "), command);
        assertTrue(command.contains(com.kubo.files.ShellQuote.quote("a'b; rm -rf /")), command);
    }
}
