package com.kubo.ui.apps;

import com.kubo.KuboApp;
import com.kubo.apps.AppCommands;
import com.kubo.model.apps.App;
import com.kubo.model.apps.AppProcess;
import com.kubo.model.apps.AppsReport;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Renders the apps screen against a sample server to PNG files, without a connection.
 * Run it with: gradlew previewApps
 *
 * <p>The sample goes through the same parsers as a real answer: a {@code pm2 jlist}, a {@code supervisorctl
 * status} and an {@code ss -tlnpH}, written out as the server would print them.
 */
public final class AppsPreview {

    private AppsPreview() {
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "build/previews");
        Files.createDirectories(output);
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                render(output, "apps", report(), "pm2:gemmie-web", false, OUT, null);
                render(output, "apps-errors", report(), "pm2:gemmie-worker", true, ERRORS, null);
                render(output, "apps-exposed", report(), "pm2:gemmie-api", false, OUT, null);
                render(output, "apps-new", report(), "pm2:gemmie-web", false, OUT, "new");
                render(output, "apps-empty", new AppsReport(false, false, List.of(), 4, 4L << 30, 1L << 30, "",
                        List.of()), null, false, "", null);
                render(output, "apps-one", small(), "pm2:Gemmie", false, OUT, null);
                renderMini(output, "apps-mini", report(), "pm2:gemmie-web", null);
                renderMini(output, "apps-mini-log", report(), "pm2:gemmie-worker", ERRORS);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
    }

    /** The card other screens open, alone on the background it is put over. */
    private static void renderMini(Path output, String name, AppsReport report, String chosen, String log)
            throws IOException {
        AppsMini mini = new AppsMini(null, "nell", chosen, key -> { }, () -> { });
        javafx.scene.layout.StackPane back = new javafx.scene.layout.StackPane(mini);
        back.getStyleClass().add("scope-view");
        Scene scene = new Scene(back, 760, 520, Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        mini.apply(report);
        if (log != null) {
            mini.logForPreview(chosen, log, true);
        }
        back.applyCss();
        back.layout();
        WritableImage image = scene.snapshot(null);
        Path file = output.resolve(name + ".png");
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        System.out.println("Wrote " + file.toAbsolutePath());
    }

    /** A small server: four cores, one cluster of two that nobody saved, and a PM2 that will not come back. */
    private static AppsReport small() {
        long started = Instant.now().minusSeconds(10 * 3600 + 20 * 60).toEpochMilli();
        String jlist = "[" + pm2(0, "Gemmie", 2201, "online", "cluster", 0, 7, 196_000_000L, started, 1,
                "/home/nell/gemmie/server.js", 0) + "," + pm2(1, "Gemmie", 2202, "online", "cluster", 1, 4,
                195_000_000L, started, 1, "/home/nell/gemmie/server.js", 0) + "]";
        List<App> apps = AppCommands.group(AppCommands.parsePm2(jlist), AppCommands.parseSockets(
                "LISTEN 0 511 *:3000 *:* users:((\"PM2 v5.4.2: God\",pid=900,fd=21))"), Set.of(), true);
        return new AppsReport(true, false, apps, 4, (long) (7.8 * (1L << 30)), (long) (1.1 * (1L << 30)),
                "disabled", List.of(), new AppsReport.Host("nell", "/home/nell", "/usr/local/bin/pm2",
                "/usr/bin/node"), List.of(0.09, 0.04, 0.12, 0.03), 0.11);
    }

    private static void render(Path output, String name, AppsReport report, String chosen, boolean errors,
                               String log, String card) throws IOException {
        AppsScope scope = new AppsScope(null, "nell");
        Scene scene = new Scene(scope, name.equals("apps-one") ? 1240 : 1400, 860, Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        scope.applyCss();
        scope.layout();
        AppsBoard board = scope.board();
        for (App app : report.apps()) {
            board.historyForPreview(app.key(), cpu(app), memory(app));
        }
        scope.apply(report);
        if (chosen != null) {
            scope.chooseForPreview(chosen);
            scope.logForPreview(log, errors);
        }
        if ("new".equals(card)) {
            scope.newAppForPreview("/srv/gemmie/billing/server.js", "3002", 2);
        }
        board.freezeForPreview(2.4);
        scope.applyCss();
        scope.layout();
        WritableImage image = scene.snapshot(null);
        Path file = output.resolve(name + ".png");
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        System.out.println("Wrote " + file.toAbsolutePath());
        scope.shutdown();
    }

    /** Ninety readings that wander around what the app uses now, the way a real minute and a half does. */
    private static double[] cpu(App app) {
        double[] points = new double[90];
        double base = app.cores() * 100;
        for (int i = 0; i < points.length; i++) {
            double wave = Math.sin(i * 0.21 + app.name().length()) * 0.35 + Math.sin(i * 0.07) * 0.25;
            points[i] = app.online() == 0 ? 0 : Math.max(0, base * (1 + wave));
        }
        return points;
    }

    private static double[] memory(App app) {
        double[] points = new double[90];
        for (int i = 0; i < points.length; i++) {
            points[i] = app.online() == 0 ? 0 : app.memory() * (0.86 + 0.14 * i / 89.0);
        }
        return points;
    }

    private static AppsReport report() {
        long started = Instant.now().minusSeconds(3 * 86400 + 4 * 3600).toEpochMilli();
        long recent = Instant.now().minusSeconds(52 * 60).toEpochMilli();
        List<String> rows = new ArrayList<>();
        int[] cpus = {14, 9, 17, 6};
        for (int i = 0; i < 4; i++) {
            rows.add(pm2(i, "gemmie-web", 1412 + i, "online", "cluster", i, cpus[i], 182_000_000L, started, 2,
                    "/srv/gemmie/web/server.js", 3000));
        }
        rows.add(pm2(4, "gemmie-api", 1490, "online", "fork", 0, 23, 264_000_000L, recent, 0,
                "/srv/gemmie/api/dist/main.js", 4000));
        rows.add(pm2(5, "gemmie-worker", 0, "errored", "fork", 0, 0, 0, 0, 41,
                "/srv/gemmie/worker/index.js", 0));
        rows.add(pm2(6, "docs", 0, "stopped", "fork", 0, 0, 0, 0, 0, "/srv/docs/serve.js", 3100));
        String jlist = "[" + String.join(",", rows) + "]";
        String sockets = String.join("\n",
                "LISTEN 0 511 127.0.0.1:3000 0.0.0.0:* users:((\"PM2 v5.4.2: God\",pid=900,fd=21))",
                "LISTEN 0 511 0.0.0.0:4000 0.0.0.0:* users:((\"node\",pid=1490,fd=19))",
                "LISTEN 0 128 127.0.0.1:8000 0.0.0.0:* users:((\"python3\",pid=2210,fd=5))");
        String supervisor = String.join("\n",
                "queue:queue_00                   RUNNING   pid 2210, uptime 5 days, 2:11:09",
                "mailer                           STOPPED   Not started");
        List<AppProcess> processes = new ArrayList<>(AppCommands.parsePm2(jlist));
        for (AppProcess program : AppCommands.parseSupervisor(supervisor, Instant.now())) {
            processes.add(program.pid() == 2210 ? new AppProcess(program.manager(), program.app(), program.id(),
                    program.instance(), program.status(), program.pid(), 4.2, 96_000_000L, program.since(), 0,
                    program.mode(), "", "", "", "", "", 0, "") : program);
        }
        List<App> apps = AppCommands.group(processes, AppCommands.parseSockets(sockets),
                Set.of("gemmie-web", "gemmie-api", "docs"), true);
        return new AppsReport(true, true, apps, 8, 8L << 30, (long) (5.2 * (1L << 30)), "enabled", List.of(),
                new AppsReport.Host("nell", "/home/nell", "/home/nell/.nvm/versions/node/v20.11.0/bin/pm2",
                        "/home/nell/.nvm/versions/node/v20.11.0/bin/node"),
                List.of(0.34, 0.21, 0.62, 0.11, 0.08, 0.27, 0.05, 0.78), 1.42);
    }

    private static String pm2(int id, String name, int pid, String status, String mode, int instance, double cpu,
                              long memory, long started, int restarts, String script, int port) {
        String folder = script.substring(0, script.lastIndexOf('/'));
        return "{\"pid\":" + pid + ",\"name\":\"" + name + "\",\"pm_id\":" + id
                + ",\"monit\":{\"memory\":" + memory + ",\"cpu\":" + cpu + "},"
                + "\"pm2_env\":{\"status\":\"" + status + "\",\"exec_mode\":\"" + mode + "_mode\","
                + "\"NODE_APP_INSTANCE\":" + instance + ",\"restart_time\":" + restarts
                + ",\"pm_uptime\":" + started + ",\"pm_exec_path\":\"" + script + "\",\"pm_cwd\":\"" + folder
                + "\",\"pm_out_log_path\":\"/home/nell/.pm2/logs/" + name + "-out.log\","
                + "\"pm_err_log_path\":\"/home/nell/.pm2/logs/" + name + "-error.log\","
                + "\"version\":\"1.8.2\",\"env\":{" + (port > 0 ? "\"PORT\":\"" + port + "\"" : "") + "}}}";
    }

    private static final String OUT = String.join("\n",
            "2026-11-12T09:58:01: listening on 127.0.0.1:3000 (worker 0)",
            "2026-11-12T09:58:01: listening on 127.0.0.1:3000 (worker 1)",
            "2026-11-12T09:59:12: GET /api/health 200 3ms",
            "2026-11-12T09:59:40: warn: slow query on orders.find 812ms",
            "2026-11-12T10:00:02: GET / 200 18ms",
            "2026-11-12T10:00:05: POST /api/orders 201 44ms",
            "2026-11-12T10:00:09: cache warmed, 1204 keys");

    private static final String ERRORS = String.join("\n",
            "2026-11-12T09:41:03: Error: connect ECONNREFUSED 127.0.0.1:6380",
            "    at TCPConnectWrap.afterConnect [as oncomplete] (node:net:1595:16)",
            "2026-11-12T09:41:04: Error: connect ECONNREFUSED 127.0.0.1:6380",
            "2026-11-12T09:41:05: [PM2] App [gemmie-worker:5] exited with code [1] via signal [SIGINT]",
            "2026-11-12T09:41:05: Script /srv/gemmie/worker/index.js had too many unstable restarts (16). Stopped. \"errored\"");
}
