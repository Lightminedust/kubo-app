package com.kubo.ui.services;

import com.kubo.model.services.BootState;
import com.kubo.model.services.Dependencies;
import com.kubo.model.services.ServiceUnit;
import com.kubo.model.services.ServicesReport;
import com.kubo.model.services.Standing;
import com.kubo.services.ServiceCommands;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * Renders the services screen against a sample server to PNG files, without a connection.
 * Run it with: gradlew previewServices
 *
 * <p>The sample is fed through the same parsers the real screen uses, so what the picture shows is what the
 * code reads, not what a hand-built object says it reads.
 */
public final class ServicesPreview {

    private ServicesPreview() {
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "build/previews");
        Files.createDirectories(output);
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                scope(output, "services", 1400, 860, report(), "nginx.service", SHOW, "", scope -> { });
                scope(output, "services-failed", 1400, 860, report(), "redis-server.service", FAILED_SHOW, "",
                        scope -> scope.linesForPreview(FAILED_JOURNAL, false));
                scope(output, "services-file", 1400, 860, report(), "nginx.service", SHOW, DROP_IN,
                        scope -> scope.linesForPreview(UNIT_FILE, true));
                scope(output, "services-find", 1400, 860, report(), "postgresql@16-main.service", POSTGRES_SHOW, "",
                        scope -> scope.filterForPreview("postgres"));
                scope(output, "services-large", 1400, 860, large(), "nginx.service", SHOW, "", scope -> { });
                scope(output, "services-large-idle", 1400, 860, large(), "nginx.service", SHOW, "",
                        scope -> scope.openForPreview(Standing.IDLE));
                scope(output, "services-tree", 1400, 860, report(), "nginx.service", SHOW, "",
                        scope -> scope.board().followForPreview("php8.2-fpm.service"));
                scope(output, "services-new", 1400, 860, report(), "nginx.service", SHOW, "",
                        scope -> scope.newServiceForPreview("/opt/backup/run.sh", "/opt/backup"));
                scope(output, "services-new-empty", 1400, 860, report(), "nginx.service", SHOW, "",
                        scope -> scope.newServiceForPreview("", ""));
                scope(output, "services-narrow", 1100, 760, report(), "docker.service", DOCKER_SHOW, "",
                        scope -> { });
                scope(output, "services-limits", 1400, 860, report(), "nginx.service", SHOW, "",
                        ServicesScope::limitsForPreview);
                scope(output, "services-settings", 1400, 860, report(), "nginx.service", SHOW, DROP_IN,
                        ServicesScope::settingsForPreview);
                scope(output, "services-confirm", 1400, 860, report(), "nginx.service", SHOW, "",
                        ServicesScope::confirmForPreview);
                scope(output, "services-help", 1400, 860, report(), "nginx.service", SHOW, "",
                        scope -> scope.showForPreview(new ServicesHelp(() -> { })));
                scope(output, "services-journal-closed", 1400, 860, report(), "nginx.service", SHOW, "",
                        scope -> scope.linesForPreview(CLOSED_JOURNAL, false));
                reading(output);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
    }

    /** The whole screen, filled from the sample, one service picked and its journal read. */
    private static void scope(Path output, String name, int width, int height, ServicesReport report, String pick,
                              String show, String dropIn, Consumer<ServicesScope> then) throws IOException {
        ServicesScope scope = new ServicesScope(null, null, null, null, null);
        StackPane holder = new StackPane(scope);
        Scene scene = new Scene(holder, width, height, Color.web("#050508"));
        scene.getStylesheets().add(com.kubo.KuboApp.STYLESHEET);
        holder.applyCss();
        holder.layout();
        scope.preview(report, links());
        scope.pickForPreview(pick, ServiceCommands.parseShow(show), dropIn);
        scope.linesForPreview(JOURNAL, false);
        then.accept(scope);
        holder.applyCss();
        holder.layout();
        scope.board().freezeForPreview(2.4);
        holder.applyCss();
        holder.layout();
        scope.board().freezeForPreview(2.4);
        write(scene, output, name);
        scope.shutdown();
    }

    /** The screen as it opens: the journal of what systemd is being asked, over the board it will fill. */
    private static void reading(Path output) throws IOException {
        ServicesScope scope = new ServicesScope(null, null, null, null, null);
        StackPane holder = new StackPane(scope);
        Scene scene = new Scene(holder, 1400, 860, Color.web("#050508"));
        scene.getStylesheets().add(com.kubo.KuboApp.STYLESHEET);
        holder.applyCss();
        holder.layout();
        scope.readingForPreview();
        holder.applyCss();
        holder.layout();
        write(scene, output, "services-reading");
        scope.shutdown();
    }

    private static void write(Scene scene, Path output, String name) throws IOException {
        WritableImage image = scene.snapshot(null);
        Path file = output.resolve(name + ".png");
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        System.out.println("Wrote " + file.toAbsolutePath());
    }

    private static final String CLOSED_JOURNAL = """
            Hint: You are currently not seeing messages from other users and the system.
                  Users in groups 'adm', 'systemd-journal' can see all messages.
                  Pass -q to turn off this notice.
            -- No entries --
            """;

    private static final String DROP_IN = """
            [Service]
            Restart=always
            MemoryMax=512M
            """;

    private static final String FAILED_SHOW = """
            Id=redis-server.service
            Description=Advanced key-value store
            Type=notify
            ExecStart={ path=/usr/bin/redis-server ; argv[]=/usr/bin/redis-server /etc/redis/redis.conf ; ignore_errors=no }
            FragmentPath=/lib/systemd/system/redis-server.service
            MainPID=0
            NRestarts=5
            Restart=always
            Result=exit-code
            """;

    private static final String FAILED_JOURNAL = """
            2026-11-12T08:41:02+0000 vps systemd[1]: Starting redis-server.service - Advanced key-value store...
            2026-11-12T08:41:02+0000 vps redis-server[30211]: *** FATAL CONFIG FILE ERROR (Redis 7.0.15) ***
            2026-11-12T08:41:02+0000 vps redis-server[30211]: >>> 'maxmemory 2gb hard'
            2026-11-12T08:41:02+0000 vps redis-server[30211]: wrong number of arguments
            2026-11-12T08:41:02+0000 vps systemd[1]: redis-server.service: Main process exited, code=exited, status=1/FAILURE
            2026-11-12T08:41:02+0000 vps systemd[1]: redis-server.service: Failed with result 'exit-code'.
            2026-11-12T08:41:02+0000 vps systemd[1]: Failed to start redis-server.service - Advanced key-value store.
            2026-11-12T08:41:03+0000 vps systemd[1]: redis-server.service: Scheduled restart job, restart counter is at 5.
            2026-11-12T08:41:03+0000 vps systemd[1]: redis-server.service: Start request repeated too quickly.
            """;

    private static final String POSTGRES_SHOW = """
            Id=postgresql@16-main.service
            Description=PostgreSQL Cluster 16-main
            Type=forking
            ExecStart={ path=/usr/bin/pg_ctlcluster ; argv[]=/usr/bin/pg_ctlcluster --skip-systemctl-redirect 16-main start ; ignore_errors=no }
            FragmentPath=/lib/systemd/system/postgresql@.service
            MainPID=1043
            MemoryCurrent=91226112
            TasksCurrent=8
            ActiveEnterTimestamp=Fri 2026-09-18 04:12:16 UTC
            NRestarts=0
            Restart=on-failure
            Result=success
            """;

    private static final String DOCKER_SHOW = """
            Id=docker.service
            Description=Docker Application Container Engine
            Type=notify
            ExecStart={ path=/usr/bin/dockerd ; argv[]=/usr/bin/dockerd -H fd:// --containerd=/run/containerd/containerd.sock ; ignore_errors=no }
            FragmentPath=/lib/systemd/system/docker.service
            MainPID=977
            MemoryCurrent=78118912
            TasksCurrent=14
            ActiveEnterTimestamp=Fri 2026-09-18 04:12:11 UTC
            NRestarts=0
            Restart=always
            Result=success
            """;

    static Dependencies links() {
        return ServiceCommands.parseRelations(RELATIONS + SMALL_RELATIONS);
    }

    /**
     * A server the size of the one this screen was built for: nineteen services up and a hundred idle units
     * around them. The listings are written out and read back through the same parsers as a real answer, so
     * what the picture shows is what the code reads.
     */
    static ServicesReport large() {
        StringBuilder units = new StringBuilder();
        StringBuilder files = new StringBuilder();
        StringBuilder blame = new StringBuilder();
        for (int i = 0; i < RUNNING.length; i++) {
            String name = RUNNING[i] + ".service";
            units.append(name).append(" loaded active running ").append(RUNNING[i]).append(" service\n");
            files.append(name).append(" enabled enabled\n");
            blame.append(String.format(java.util.Locale.ROOT, "%12.3fs %s%n", 13.7 / (i + 1), name));
        }
        for (String idle : IDLE) {
            String name = idle + ".service";
            units.append(name).append(" loaded inactive dead ").append(idle).append(" task\n");
            files.append(name).append(" static -\n");
        }
        Map<String, BootState> boots = ServiceCommands.parseUnitFiles(files.toString());
        Map<String, Duration> times = ServiceCommands.parseBlame(blame.toString());
        List<ServiceUnit> all = new ArrayList<>();
        for (ServiceUnit unit : ServiceCommands.parseUnits(units.toString())) {
            all.add(unit.withBoot(boots.getOrDefault(unit.name(), BootState.UNKNOWN))
                    .withStartup(times.get(unit.name())));
        }
        return new ServicesReport(all, Duration.ofMillis(32_100), Duration.ofMillis(28_000), List.of());
    }

    private static final String[] RUNNING = {
        "nginx", "postgresql@16-main", "php8.2-fpm", "docker", "containerd", "redis-server", "ssh", "cron",
        "rsyslog", "systemd-journald", "systemd-logind", "systemd-networkd", "systemd-resolved", "systemd-udevd",
        "dbus", "polkit", "chrony", "fail2ban", "pm2-xana",
    };

    /** The static one-shot units a Debian install carries: the long tail nobody ever reads in a terminal. */
    private static final String[] IDLE = buildIdle();

    private static String[] buildIdle() {
        String[] roots = {"apparmor", "auditd", "blk-availability", "certbot", "console-setup", "cryptdisks",
            "dpkg-db-backup", "e2scrub_all", "e2scrub_reap", "emergency", "finalrd", "fstrim", "getty@tty1",
            "getty@tty2", "getty@tty3", "grub-common", "hwclock", "ifupdown-pre", "initrd-cleanup",
            "initrd-parse-etc", "initrd-switch-root", "initrd-udevadm-cleanup-db", "keyboard-setup",
            "kmod-static-nodes", "logrotate", "man-db", "modprobe@configfs", "modprobe@dm_mod",
            "modprobe@drm", "modprobe@efi_pstore", "modprobe@fuse", "modprobe@loop", "networking",
            "packagekit", "plymouth-quit", "plymouth-read-write", "plymouth-start", "postgresql",
            "procps", "quotaon", "rc-local", "rescue", "rsync", "setvtrgb", "ssh-agent", "sudo",
            "systemd-ask-password-console", "systemd-ask-password-wall", "systemd-binfmt",
            "systemd-boot-check-no-failures", "systemd-fsck-root", "systemd-fsckd", "systemd-halt",
            "systemd-hibernate", "systemd-hostnamed", "systemd-hwdb-update", "systemd-initctl",
            "systemd-journal-catalog-update", "systemd-journal-flush", "systemd-kexec", "systemd-localed",
            "systemd-machine-id-commit", "systemd-modules-load", "systemd-network-generator",
            "systemd-poweroff", "systemd-quotacheck", "systemd-random-seed", "systemd-reboot",
            "systemd-remount-fs", "systemd-suspend", "systemd-sysctl", "systemd-sysusers",
            "systemd-timedated", "systemd-timesyncd", "systemd-tmpfiles-clean", "systemd-tmpfiles-setup",
            "systemd-tmpfiles-setup-dev", "systemd-update-utmp", "systemd-user-sessions",
            "systemd-vconsole-setup", "ufw", "unattended-upgrades", "user-runtime-dir@1000", "user@1000",
            "uuidd", "apt-daily", "apt-daily-upgrade", "dbus-org.freedesktop.hostname1",
            "dbus-org.freedesktop.locale1", "dbus-org.freedesktop.login1", "dbus-org.freedesktop.timedate1",
            "docker-cleanup", "getty-static", "ifup@eth0", "kmod", "lvm2-monitor", "lvm2-pvscan",
            "openvpn", "qemu-guest-agent", "serial-getty@ttyS0", "snapd", "sysstat", "udisks2", "wpa_supplicant",
        };
        return roots;
    }

    /** A Debian server with a web stack, a database, Docker, and two real problems on it. */
    static ServicesReport report() {
        Map<String, BootState> boots = ServiceCommands.parseUnitFiles(UNIT_FILES);
        Map<String, Duration> blame = ServiceCommands.parseBlame(BLAME);
        List<ServiceUnit> units = new ArrayList<>();
        for (ServiceUnit unit : ServiceCommands.parseUnits(UNITS)) {
            units.add(unit.withBoot(boots.getOrDefault(unit.name(), BootState.UNKNOWN))
                    .withStartup(blame.get(unit.name())));
        }
        return new ServicesReport(units, ServiceCommands.parseTotalBoot(BOOT_TIME),
                ServiceCommands.parseUserspace(BOOT_TIME), List.of());
    }

    private static final String UNITS = """
            accounts-daemon.service loaded active running Accounts Service
            apparmor.service loaded active exited Load AppArmor profiles
            atd.service loaded active running Deferred execution scheduler
            certbot.service loaded inactive dead Certbot
            chrony.service loaded active running chrony, an NTP client/server
            containerd.service loaded active running containerd container runtime
            cron.service loaded active running Regular background program processing daemon
            dbus.service loaded active running D-Bus System Message Bus
            docker.service loaded active running Docker Application Container Engine
            e2scrub_reap.service loaded active exited Remove Stale Online ext4 Metadata Check Snapshots
            fail2ban.service loaded failed failed Fail2Ban Service
            getty@tty1.service loaded active running Getty on tty1
            keyboard-setup.service loaded active exited Set the console keyboard layout
            kmod-static-nodes.service loaded active exited Create List of Static Device Nodes
            logrotate.service loaded inactive dead Rotate log files
            man-db.service loaded inactive dead Daily man-db regeneration
            networkd-dispatcher.service loaded active running Dispatcher daemon for systemd-networkd
            nginx.service loaded active running A high performance web server and a reverse proxy server
            packagekit.service loaded inactive dead PackageKit Daemon
            php8.2-fpm.service loaded active running The PHP 8.2 FastCGI Process Manager
            polkit.service loaded active running Authorization Manager
            postgresql@16-main.service loaded active running PostgreSQL Cluster 16-main
            postgresql.service loaded active exited PostgreSQL RDBMS
            redis-server.service loaded failed failed Advanced key-value store
            rsyslog.service loaded active running System Logging Service
            ssh.service loaded active running OpenBSD Secure Shell server
            systemd-journald.service loaded active running Journal Service
            systemd-logind.service loaded active running User Login Management
            systemd-modules-load.service loaded active exited Load Kernel Modules
            systemd-networkd.service loaded active running Network Configuration
            systemd-random-seed.service loaded active exited Load/Save Random Seed
            systemd-resolved.service loaded active running Network Name Resolution
            systemd-sysctl.service loaded active exited Apply Kernel Variables
            systemd-tmpfiles-setup.service loaded active exited Create Volatile Files and Directories
            systemd-udevd.service loaded active running Rule-based Manager for Device Events
            systemd-user-sessions.service loaded active exited Permit User Sessions
            ufw.service loaded active exited Uncomplicated firewall
            unattended-upgrades.service loaded inactive dead Unattended Upgrades Shutdown
            user@1000.service loaded active running User Manager for UID 1000
            uuidd.service loaded inactive dead Daemon for generating UUIDs
            """;

    private static final String UNIT_FILES = """
            accounts-daemon.service enabled enabled
            apparmor.service enabled enabled
            atd.service enabled enabled
            certbot.service static -
            chrony.service enabled enabled
            containerd.service enabled enabled
            cron.service enabled enabled
            dbus.service static -
            docker.service disabled disabled
            e2scrub_reap.service enabled enabled
            fail2ban.service enabled enabled
            getty@tty1.service enabled enabled
            keyboard-setup.service enabled enabled
            kmod-static-nodes.service static -
            logrotate.service static -
            man-db.service static -
            networkd-dispatcher.service enabled enabled
            nginx.service enabled enabled
            packagekit.service static -
            php8.2-fpm.service enabled enabled
            polkit.service static -
            postgresql@16-main.service indirect -
            postgresql.service enabled enabled
            redis-server.service enabled enabled
            rsyslog.service enabled enabled
            ssh.service enabled enabled
            systemd-journald.service static -
            systemd-logind.service static -
            systemd-modules-load.service static -
            systemd-networkd.service enabled enabled
            systemd-random-seed.service static -
            systemd-resolved.service enabled enabled
            systemd-sysctl.service static -
            systemd-tmpfiles-setup.service static -
            systemd-udevd.service static -
            systemd-user-sessions.service static -
            ufw.service enabled enabled
            unattended-upgrades.service enabled enabled
            user@1000.service static -
            uuidd.service indirect -
            """;

    private static final String BLAME = """
                     9.812s postgresql@16-main.service
                     4.203s docker.service
                     3.117s php8.2-fpm.service
                     2.006s containerd.service
                     1.442s networkd-dispatcher.service
                      988ms nginx.service
                      642ms systemd-udevd.service
                      511ms ssh.service
                      402ms systemd-resolved.service
                      318ms rsyslog.service
                      205ms chrony.service
                      144ms cron.service
                       92ms polkit.service
                       61ms ufw.service
                       44ms dbus.service
            """;

    private static final String RELATIONS = """
            Id=nginx.service
            Requires=
            Wants=redis-server.service
            After=postgresql@16-main.service php8.2-fpm.service network.target

            Id=pm2-xana.service
            Requires=
            Wants=
            After=nginx.service redis-server.service

            Id=fail2ban.service
            Requires=
            Wants=
            After=nginx.service rsyslog.service
            """;

    private static final String SMALL_RELATIONS = """
            Id=docker.service
            Requires=containerd.service
            Wants=network-online.target
            After=containerd.service basic.target firewalld.service systemd-journald.socket

            Id=nginx.service
            Requires=
            Wants=
            After=network.target remote-fs.target postgresql.service

            Id=php8.2-fpm.service
            Requires=
            Wants=
            After=nginx.service

            Id=fail2ban.service
            Requires=
            Wants=
            After=rsyslog.service nginx.service
            """;

    private static final String SHOW = """
            Id=nginx.service
            Description=A high performance web server and a reverse proxy server
            Type=forking
            ExecStart={ path=/usr/sbin/nginx ; argv[]=/usr/sbin/nginx -g daemon on; ; ignore_errors=no }
            FragmentPath=/lib/systemd/system/nginx.service
            MainPID=812
            MemoryCurrent=12648448
            TasksCurrent=5
            ActiveEnterTimestamp=Fri 2026-09-18 04:12:07 UTC
            NRestarts=0
            Restart=on-failure
            Result=success
            """;

    private static final String UNIT_FILE = """
            # /lib/systemd/system/nginx.service
            [Unit]
            Description=A high performance web server and a reverse proxy server
            After=network.target nss-lookup.target

            [Service]
            Type=forking
            PIDFile=/run/nginx.pid
            ExecStartPre=/usr/sbin/nginx -t -q -g 'daemon on; master_process on;'
            ExecStart=/usr/sbin/nginx -g 'daemon on; master_process on;'
            ExecReload=/usr/sbin/nginx -g 'daemon on; master_process on;' -s reload
            TimeoutStopSec=5
            KillMode=mixed

            [Install]
            WantedBy=multi-user.target
            """;

    private static final String JOURNAL = """
            2026-09-18T04:12:07+0000 vps systemd[1]: Starting nginx.service - A high performance web server...
            2026-09-18T04:12:07+0000 vps systemd[1]: Started nginx.service.
            2026-09-19T06:25:01+0000 vps systemd[1]: Reloading nginx.service.
            2026-09-19T06:25:01+0000 vps systemd[1]: Reloaded nginx.service.
            2026-09-20T03:00:14+0000 vps systemd[1]: Reloading nginx.service.
            2026-09-20T03:00:14+0000 vps systemd[1]: Reloaded nginx.service.
            """;

    private static final String BOOT_TIME =
            "Startup finished in 4.181s (kernel) + 21.507s (userspace) = 25.689s";
}
