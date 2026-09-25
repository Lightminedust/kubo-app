package com.kubo.ui.shell;

import com.kubo.KuboApp;
import com.kubo.model.shell.Pm2Process;
import com.kubo.model.shell.ProcessReading;
import com.kubo.ui.logs.StreamView;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Renders the two windows the shell opens for itself, with sample data and no server.
 */
public final class ShellPreview {

    private static final Instant NOW = Instant.now();

    private ShellPreview() {
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "build/previews");
        Files.createDirectories(output);

        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                ProcessView table = new ProcessView(null, null);
                table.showForPreview(processes());
                snapshot(table, output.resolve("shell-pm2.png"), 1240, 620);
                table.shutdown();

                TopView top = new TopView(null, null, null);
                top.showForPreview(ProcessReading.parse(PS));
                snapshot(top, output.resolve("shell-top.png"), 1320, 620);
                top.shutdown();

                StreamView stream = new StreamView(null, "pm2 logs");
                stream.showForPreview(lines());
                snapshot(stream, output.resolve("shell-stream.png"), 1080, 620);
                stream.shutdown();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
    }

    private static void snapshot(Parent view, Path file, int width, int height) throws IOException {
        Scene scene = new Scene(view, width, height, Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        view.applyCss();
        view.layout();
        WritableImage image = scene.snapshot(null);
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        System.out.println("Wrote " + file.toAbsolutePath());
    }

    /** A page of ps as a real server answers it, command last so its spaces stay in it. */
    private static final String PS = """
              1     0 root      0.0  0.1  12480 Ss     191400 /sbin/init splash
           2691 33051 xana     14.3  6.2 508240 Sl      191400 node /home/xana/GemmieShop/app.js
           3350062 33051 xana    2.4  2.5 216064 Rl      191400 node /home/xana/GemmieShop/app.js
           3350  2691 mongod    3.5  2.0 275968 Ssl     191400 mongod --replSet rs0 --bind_ip_all
            999     1 redis     0.7  0.4  31744 Ssl     191400 /opt/redis-stack/bin/redis-server *:6379
           1187     1 root      0.2  0.6 121856 Ss      191400 /usr/bin/python3 /usr/bin/fail2ban-server
           3404  2691 xana      1.3  1.1  52160 Sl        4320 node /home/xana/app/worker.js
             79     2 root      0.0  0.0      0 I<     191400 [kworker/1:1H-kblockd]
           3592  3350 root      0.0  0.3  48128 Ss      120600 sshd: xana [priv]
           4102     1 xana      0.0  0.0      0 Z            7 [node] <defunct>
           2778     1 root     52.4  1.8 194560 R        86400 /usr/bin/containerd
            @@load
            1.94 1.62 1.38 3/382 41233
            @@mem
            MemTotal:        8039420 kB
            MemAvailable:    6584312 kB
            @@cpus
            4
            """;

    private static List<Pm2Process> processes() {
        return List.of(
                new Pm2Process(0, "gemmie-api", "cluster ×2", "online", 3, 1.4, 58_720_256L,
                        NOW.minusSeconds(191_400), "default"),
                new Pm2Process(1, "gemmie-web", "fork", "online", 0, 0.2, 31_457_280L,
                        NOW.minusSeconds(191_400), "default"),
                new Pm2Process(2, "mailer", "fork", "errored", 47, 0, 0, null, "jobs"),
                new Pm2Process(3, "nightly-import", "fork", "stopped", 0, 0, 0, null, "jobs"),
                new Pm2Process(4, "socket-gateway", "cluster ×4", "online", 1, 6.8, 141_557_760L,
                        NOW.minusSeconds(4_320), "default"));
    }

    private static List<String> lines() {
        return List.of(
                "[TAILING] Tailing last 15 lines for [all] processes",
                "/root/.pm2/pm2.log last 15 lines:",
                "PM2       | 2026-09-25T14:05:06: PM2 log: PM2 version          : 6.0.14",
                "PM2       | 2026-09-25T14:05:06: PM2 log: Node.js version      : 20.19.2",
                "PM2       | 2026-09-25T14:05:07: PM2 log: App [gemmie-api:0] starting in cluster mode",
                "gemmie-api  | 2026-09-25T14:05:08 INFO  listening on 0.0.0.0:3000",
                "gemmie-api  | 2026-09-25T14:05:08 INFO  connected to redis at 127.0.0.1:6379",
                "mailer      | 2026-09-25T14:05:11 WARN  smtp handshake slow, retrying in 5s",
                "mailer      | 2026-09-25T14:05:16 ERROR connection refused: smtp.example.net:587",
                "mailer      |     at Socket.<anonymous> (/root/app/mailer/send.js:112:19)",
                "mailer      |     at process.processTicksAndRejections (node:internal/process/task_queues:95:5)",
                "PM2       | 2026-09-25T14:05:16: PM2 log: App [mailer:2] exited with code 1",
                "PM2       | 2026-09-25T14:05:16: PM2 log: App [mailer:2] restarting, 47 restarts",
                "gemmie-web  | 2026-09-25T14:05:19 INFO  GET /  200  14ms",
                "socket-gateway | 2026-09-25T14:05:21 INFO  12 clients online");
    }
}
