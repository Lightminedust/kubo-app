package com.kubo.apps;

import com.kubo.model.apps.App;
import com.kubo.model.apps.AppProcess;
import com.kubo.model.apps.Manager;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the apps screen sends and how it reads PM2, supervisor and the sockets back. */
class AppCommandsTest {

    private static final String JLIST = "[PM2] Spawning PM2 daemon with pm2_home=/home/nell/.pm2\n"
            + "[{\"pid\":1412,\"name\":\"web\",\"pm_id\":0,\"monit\":{\"memory\":178000000,\"cpu\":12.5},"
            + "\"pm2_env\":{\"status\":\"online\",\"exec_mode\":\"cluster_mode\",\"NODE_APP_INSTANCE\":0,"
            + "\"restart_time\":2,\"pm_uptime\":1700000000000,\"pm_exec_path\":\"/srv/web/server.js\","
            + "\"pm_cwd\":\"/srv/web\",\"pm_out_log_path\":\"/home/nell/.pm2/logs/web-out.log\","
            + "\"pm_err_log_path\":\"/home/nell/.pm2/logs/web-error.log\",\"env\":{\"PORT\":\"3000\"}}},"
            + "{\"pid\":1413,\"name\":\"web\",\"pm_id\":1,\"monit\":{\"memory\":170000000,\"cpu\":8},"
            + "\"pm2_env\":{\"status\":\"online\",\"exec_mode\":\"cluster_mode\",\"NODE_APP_INSTANCE\":1,"
            + "\"env\":{\"PORT\":\"3000\"}}},"
            + "{\"pid\":0,\"name\":\"worker\",\"pm_id\":2,\"monit\":{\"memory\":0,\"cpu\":0},"
            + "\"pm2_env\":{\"status\":\"errored\",\"exec_mode\":\"fork_mode\",\"restart_time\":41}}]";

    @Test
    void pm2IsReadPastItsBannerAndEachInstanceKeepsItsPlace() {
        List<AppProcess> processes = AppCommands.parsePm2(JLIST);
        assertEquals(3, processes.size());
        AppProcess first = processes.get(0);
        assertEquals("web", first.app());
        assertEquals("cluster", first.mode());
        assertEquals(3000, first.envPort());
        assertEquals(12.5, first.cpu());
        assertTrue(first.online());
        assertEquals(1, processes.get(1).instance());
        assertTrue(processes.get(2).failed());
        assertEquals(41, processes.get(2).restarts());
    }

    @Test
    void notJsonIsNothingRatherThanAnError() {
        assertTrue(AppCommands.parsePm2("bash: pm2: command not found").isEmpty());
        assertTrue(AppCommands.parsePm2("").isEmpty());
    }

    @Test
    void aClusterIsOneAppAndFindsItsPortThroughItsEnvironment() {
        // The daemon holds a cluster's socket, not a worker: the port is found through PORT.
        Map<Integer, List<App.Listening>> sockets = AppCommands.parseSockets(
                "LISTEN 0 511 0.0.0.0:3000 0.0.0.0:* users:((\"node\",pid=900,fd=20))\n"
                        + "LISTEN 0 128 127.0.0.1:6379 0.0.0.0:* users:((\"redis\",pid=50,fd=6))");
        List<App> apps = AppCommands.group(AppCommands.parsePm2(JLIST), sockets, Set.of("web"), true);
        assertEquals(2, apps.size());
        App web = apps.get(0);
        assertEquals(2, web.processes().size());
        assertEquals(List.of(new App.Listening("0.0.0.0", 3000)), web.ports());
        assertTrue(web.exposed());
        assertTrue(web.saved());
        assertEquals(App.State.UP, web.state());
        assertEquals(0.205, web.cores(), 1e-9);
        App worker = apps.get(1);
        assertFalse(worker.saved());
        assertEquals(App.State.FAILED, worker.state());
    }

    @Test
    void supervisorRowsGiveTheirPidAndHowLongTheyHaveBeenUp() {
        Instant now = Instant.parse("2026-11-12T10:00:00Z");
        List<AppProcess> programs = AppCommands.parseSupervisor(String.join("\n",
                "queue:queue_00   RUNNING   pid 1234, uptime 2 days, 3:04:05",
                "mailer           STOPPED   Not started",
                "importer         FATAL     Exited too quickly (process log may have details)"), now);
        assertEquals(3, programs.size());
        assertEquals(1234, programs.get(0).pid());
        assertEquals(now.minus(Duration.ofDays(2).plusHours(3).plusMinutes(4).plusSeconds(5)),
                programs.get(0).since());
        assertTrue(programs.get(0).online());
        assertFalse(programs.get(1).online());
        assertTrue(programs.get(2).failed());
    }

    @Test
    void theMachineIsReadFromItsSections() {
        String output = "@@cores\n8\n@@memory\nMemTotal:  4000000 kB\nMemAvailable: 1000000 kB\n@@pm2\nABSENT\n";
        assertEquals(8, AppCommands.cores(output));
        long[] memory = AppCommands.memory(output);
        assertEquals(4_096_000_000L, memory[0]);
        assertEquals(3_072_000_000L, memory[1]);
        assertEquals("ABSENT", AppCommands.section(output, "pm2").strip());
    }

    @Test
    void namesAreQuotedAndAnythingElseIsRefused() {
        assertEquals("pm2 restart 'web'", AppCommands.pm2(AppCommands.Pm2Verb.RESTART, "web"));
        assertEquals("pm2 scale 'web' 4", AppCommands.pm2Scale("web", 4));
        assertThrows(IllegalArgumentException.class, () -> AppCommands.pm2(AppCommands.Pm2Verb.STOP, "web; rm -rf /"));
        assertThrows(IllegalArgumentException.class, () -> AppCommands.pm2Scale("web", 0));
        assertEquals("supervisorctl stop 'queue:queue_00'",
                AppCommands.supervisor(AppCommands.SupervisorVerb.STOP, "queue:queue_00"));
        assertThrows(IllegalArgumentException.class, () -> AppCommands.supervisorTail("$(id)", false));
        assertThrows(IllegalArgumentException.class, () -> AppCommands.tail("/var/log/../../etc/shadow", 10));
    }

    @Test
    void aNewAppStartsFromItsFolderWithItsPortAndWorkers() {
        assertEquals("cd '/srv/api' && PORT=3001 pm2 start '/srv/api/server.js' --name 'api' -i 4 && pm2 save",
                AppCommands.pm2Start(new AppCommands.NewApp("/srv/api/server.js", false, "api", 4, 3001, true)));
        assertEquals("cd '/srv/site' && pm2 start npm --name 'site' -- start",
                AppCommands.pm2Start(new AppCommands.NewApp("/srv/site", true, "site", 1, 0, false)));
        assertEquals("cd '/srv/api' && pm2 start '/srv/api/server.js' --name 'api' -i max",
                AppCommands.pm2Start(new AppCommands.NewApp("/srv/api/server.js", false, "api", 0, 0, false)));
    }

    @Test
    void aNewAppThatCannotWorkIsRefusedWithTheReason() {
        assertNotNull(AppCommands.problem(new AppCommands.NewApp("/srv/app.py", false, "py", 4, 0, false)));
        assertNotNull(AppCommands.problem(new AppCommands.NewApp("srv/app.js", false, "app", 1, 0, false)));
        assertNotNull(AppCommands.problem(new AppCommands.NewApp("/srv/app.js", false, "app", 1, 80, false)));
        assertNull(AppCommands.problem(new AppCommands.NewApp("/srv/app.py", false, "py", 1, 0, false)));
    }

    @Test
    void aNewAppCanBeGivenAMemoryLimit() {
        assertEquals("cd '/srv/api' && pm2 start '/srv/api/server.js' --name 'api' -i 2 --max-memory-restart 512M",
                AppCommands.pm2Start(new AppCommands.NewApp("/srv/api/server.js", false, "api", 2, 0, false, 512)));
        assertEquals("cd '/srv/site' && pm2 start npm --name 'site' --max-memory-restart 256M -- start && pm2 save",
                AppCommands.pm2Start(new AppCommands.NewApp("/srv/site", true, "site", 1, 0, true, 256)));
        assertNotNull(AppCommands.problem(new AppCommands.NewApp("/srv/app.js", false, "app", 1, 0, false, 4)));
    }

    @Test
    void startingAtBootIsTheCommandPm2AsksForWithWhatTheServerSaid() {
        com.kubo.model.apps.AppsReport.Host host = new com.kubo.model.apps.AppsReport.Host("nell", "/home/nell",
                "/home/nell/.nvm/versions/node/v20.11.0/bin/pm2", "/home/nell/.nvm/versions/node/v20.11.0/bin/node");
        assertEquals("env \"PATH=$PATH\":'/home/nell/.nvm/versions/node/v20.11.0/bin' "
                        + "'/home/nell/.nvm/versions/node/v20.11.0/bin/pm2' startup systemd -u 'nell' --hp '/home/nell'",
                AppCommands.pm2Boot(host, true));
        assertTrue(AppCommands.pm2Boot(host, false).contains("/pm2' unstartup systemd -u 'nell'"));
        com.kubo.model.apps.AppsReport.Host apart = new com.kubo.model.apps.AppsReport.Host("deploy", "/home/deploy",
                "/home/deploy/.npm-global/bin/pm2", "/usr/bin/node");
        assertTrue(AppCommands.pm2Boot(apart, true)
                .startsWith("env \"PATH=$PATH\":'/home/deploy/.npm-global/bin':'/usr/bin' "));
        assertThrows(IllegalArgumentException.class, () -> AppCommands.pm2Boot(
                new com.kubo.model.apps.AppsReport.Host("nell; rm -rf /", "/home/nell", "/usr/bin/pm2", ""), true));
        assertThrows(IllegalArgumentException.class, () -> AppCommands.pm2Boot(
                com.kubo.model.apps.AppsReport.Host.UNKNOWN, true));
    }

    @Test
    void aClusterWithoutAPortTakesTheOneTheDaemonHoldsForIt() {
        String jlist = "[{\"pid\":2201,\"name\":\"Gemmie\",\"pm_id\":0,\"pm2_env\":{\"status\":\"online\","
                + "\"exec_mode\":\"cluster_mode\",\"NODE_APP_INSTANCE\":0}},"
                + "{\"pid\":2202,\"name\":\"Gemmie\",\"pm_id\":1,\"pm2_env\":{\"status\":\"online\","
                + "\"exec_mode\":\"cluster_mode\",\"NODE_APP_INSTANCE\":1}}]";
        String sockets = "LISTEN 0 511 *:3000 *:* users:((\"PM2 v5.4.2: God\",pid=900,fd=21))\n"
                + "LISTEN 0 128 0.0.0.0:22 0.0.0.0:* users:((\"sshd\",pid=700,fd=3))\n";
        List<App> apps = AppCommands.group(AppCommands.parsePm2(jlist), AppCommands.parseSockets(sockets),
                Set.of(), true);
        assertEquals(1, apps.size());
        assertEquals(List.of(new App.Listening("*", 3000)), apps.get(0).ports());
        assertTrue(apps.get(0).exposed());

        String two = jlist.substring(0, jlist.length() - 1) + ",{\"pid\":2301,\"name\":\"other\",\"pm_id\":2,"
                + "\"pm2_env\":{\"status\":\"online\",\"exec_mode\":\"cluster_mode\",\"NODE_APP_INSTANCE\":0}}]";
        List<App> unsure = AppCommands.group(AppCommands.parsePm2(two), AppCommands.parseSockets(sockets),
                Set.of(), true);
        assertTrue(unsure.get(0).ports().isEmpty());
        assertTrue(unsure.get(1).ports().isEmpty());
    }

    @Test
    void aMarkerGluedToJsonWithoutANewlineStillEndsTheSection() {
        String output = "@@pm2\n[{\"pid\":1,\"name\":\"Gemmie\",\"pm_id\":0,\"pm2_env\":{\"status\":\"online\"}}]"
                + "@@dump\n[\n  {\n    \"name\": \"Gemmie\"\n  }\n]@@boot\nenabled\n@@sockets\n";
        assertEquals(Set.of("Gemmie"), AppCommands.savedNames(AppCommands.section(output, "dump")));
        assertEquals("enabled", AppCommands.section(output, "boot").strip());
        assertEquals(1, AppCommands.parsePm2(AppCommands.section(output, "pm2")).size());
        assertTrue(AppCommands.section(output, "pm2").strip().endsWith("]"));
    }

    @Test
    void resetAndFlushNameOneApp() {
        assertEquals("pm2 reset 'web'", AppCommands.pm2Reset("web"));
        assertEquals("pm2 flush 'web'", AppCommands.pm2Flush("web"));
        assertThrows(IllegalArgumentException.class, () -> AppCommands.pm2Flush("web && id"));
    }

    @Test
    void whoRunsPm2AndHowBusyEachCoreIsAreRead() {
        String before = "@@who\nnell\n/home/nell\n/usr/local/bin/pm2\n/usr/bin/node\n"
                + "@@stat\ncpu0 100 0 100 800 0 0 0 0 0 0\ncpu1 50 0 50 900 0 0 0 0 0 0\n"
                + "@@load\n0.42 0.30 0.20 1/123 456\n@@end\n";
        String after = "@@stat\ncpu0 150 0 150 900 0 0 0 0 0 0\ncpu1 50 0 50 1100 0 0 0 0 0 0\n@@end\n";
        com.kubo.model.apps.AppsReport.Host host = AppCommands.host(before);
        assertEquals("nell", host.account());
        assertEquals("/usr/local/bin/pm2", host.pm2());
        assertTrue(host.known());
        assertFalse(AppCommands.host("@@who\nnell\n/home/nell\n\n\n@@end\n").known());
        assertEquals(0.42, AppCommands.load(before));
        List<Double> loads = AppCommands.coreLoads(AppCommands.coreTicks(before), AppCommands.coreTicks(after));
        assertEquals(List.of(0.5, 0.0), loads);
        assertTrue(AppCommands.coreLoads(null, AppCommands.coreTicks(after)).isEmpty());
    }

    @Test
    void supervisorStatsComeFromPs() {
        assertEquals("ps -o pid=,pcpu=,rss= -p 12,34", AppCommands.stats(List.of(12, 0, 34)));
        assertEquals("", AppCommands.stats(List.of(0)));
        Map<Integer, double[]> stats = AppCommands.parseStats(" 12  3.5 2048\n 34 0.0 100\n");
        assertEquals(3.5, stats.get(12)[0]);
        assertEquals(2048 * 1024.0, stats.get(12)[1]);
        assertEquals(Manager.SUPERVISOR.word(), "supervisor");
    }
}
