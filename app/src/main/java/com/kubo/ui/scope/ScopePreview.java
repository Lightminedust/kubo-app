package com.kubo.ui.scope;

import com.kubo.KuboApp;
import com.kubo.command.CommandEntry;
import com.kubo.model.server.DiskPartition;
import com.kubo.model.server.ListeningPort;
import com.kubo.model.server.ListeningPort.Protocol;
import com.kubo.model.server.Server;
import com.kubo.model.server.ServerIdentity;
import com.kubo.model.server.ServerMetrics;
import com.kubo.monitor.parse.DisksParser;
import com.kubo.monitor.parse.IdentityParser;
import com.kubo.monitor.parse.MetricsParser;
import com.kubo.monitor.parse.PortsParser;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Renders the server scope with sample data to PNG files, without a server.
 * Run it with: gradlew previewScope
 */
public final class ScopePreview {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");

    private ScopePreview() {
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "build/previews");
        Files.createDirectories(output);

        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                render(output, "scope-calm", 9, 0.4, 38, 0.3, 40_000, 12_000, false);
                render(output, "scope-busy", 88, 7.5, 91, 3.6, 2_400_000, 900_000, false);
                render(output, "scope-lost", 30, 1, 55, 0.8, 200_000, 50_000, true);
                render(output, "scope-console-folded", 9, 0.4, 38, 0.3, 40_000, 12_000, false, true);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
    }

    private static void render(Path output, String name, double cpu, double steal, double memory, double load,
                               long download, long upload, boolean lost) throws IOException {
        render(output, name, cpu, steal, memory, load, download, upload, lost, false);
    }

    private static void render(Path output, String name, double cpu, double steal, double memory, double load,
                               long download, long upload, boolean lost, boolean collapsed) throws IOException {
        ServerView sample = sample(cpu, steal, memory, load, download, upload);

        ScopeView view = new ScopeView();
        Scene scene = new Scene(view, KuboApp.WIDTH, KuboApp.HEIGHT, Color.web("#0a0a0f"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        view.showPreview(sample.server(), sample.history(), sample.log(), "xana@203.0.113.10:22", lost,
                collapsed);

        WritableImage image = scene.snapshot(null);
        Path file = output.resolve(name + ".png");
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        view.shutdown();
        System.out.println("Wrote " + file.toAbsolutePath());
    }

    private record ServerView(Server server, List<ServerMetrics> history, List<CommandEntry> log) {
    }

    private static ServerView sample(double cpu, double steal, double memory, double load, long download, long upload) {
        long totalMemory = 8L * 1024 * 1024 * 1024;
        ServerIdentity identity = new ServerIdentity("vmi2412345", "Debian GNU/Linux 13 (trixie)",
                "6.12.38+deb13-amd64", "AMD EPYC 7282 16-Core Processor", 4, totalMemory,
                new ServerIdentity.UserIdentity("xana", false, true, true));

        List<ServerMetrics> history = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            double wave = Math.sin(i / 6.0) * 0.5 + Math.sin(i / 2.3) * 0.25;
            double cpuAt = clamp(cpu + wave * Math.max(4, cpu * 0.25));
            double memoryAt = clamp(memory + Math.sin(i / 17.0) * 3);
            long rate = (long) Math.max(0, download * (1 + wave * 0.6));
            history.add(metrics(NOW.plusSeconds(2L * i), cpuAt, steal, memoryAt, load, rate, (long) (upload * (1 + wave * 0.4)),
                    totalMemory));
        }
        ServerMetrics latest = metrics(NOW.plusSeconds(180), cpu, steal, memory, load, download, upload, totalMemory);
        history.add(latest);

        List<ListeningPort> ports = List.of(
                new ListeningPort(Protocol.TCP, "0.0.0.0", 22, "sshd"),
                new ListeningPort(Protocol.TCP, "[::]", 22, "sshd"),
                new ListeningPort(Protocol.TCP, "*", 80, "docker-proxy"),
                new ListeningPort(Protocol.TCP, "*", 443, "docker-proxy"),
                new ListeningPort(Protocol.TCP, "203.0.113.10", 8080, ""),
                new ListeningPort(Protocol.TCP, "172.17.0.1", 9000, ""),
                new ListeningPort(Protocol.TCP, "10.0.0.4", 9100, ""),
                new ListeningPort(Protocol.TCP, "127.0.0.1", 5432, ""),
                new ListeningPort(Protocol.TCP, "127.0.0.1", 6379, ""),
                new ListeningPort(Protocol.UDP, "127.0.0.53%lo", 53, ""),
                new ListeningPort(Protocol.TCP, "127.0.0.1", 3000, "node"));
        List<DiskPartition> disks = List.of(
                new DiskPartition("/", 193_000_000_000L, (long) (193_000_000_000L * memory / 110), 60_000_000_000L),
                new DiskPartition("/boot/efi", 124_000_000L, 6_000_000L, 118_000_000L));

        List<CommandEntry> log = new ArrayList<>();
        log.add(entry(NOW, IdentityParser.COMMAND, 0, 61, ""));
        log.add(entry(NOW.plusMillis(80), DisksParser.COMMAND, 0, 44, ""));
        log.add(entry(NOW.plusMillis(130), DisksParser.COMMAND, 0, 39, ""));
        for (int i = 0; i < 12; i++) {
            log.add(entry(NOW.plusSeconds(2L * i + 1), MetricsParser.COMMAND, 0, 36 + (i * 7) % 11, ""));
        }
        log.add(entry(NOW.plusSeconds(26), "docker ps --format '{{json .}}'", 1, 52,
                "permission denied while trying to connect to the Docker daemon socket"));

        return new ServerView(new Server(identity, latest, ports, disks), history, log);
    }

    private static ServerMetrics metrics(Instant at, double cpu, double steal, double memory, double load,
                                         long download, long upload, long totalMemory) {
        long available = (long) (totalMemory * (1 - memory / 100));
        long swapTotal = 2L * 1024 * 1024 * 1024;
        return new ServerMetrics(at, clamp(cpu), Math.min(steal, 100 - clamp(cpu)),
                new ServerMetrics.LoadAverage(load, load * 0.9, load * 0.8),
                new ServerMetrics.MemoryMetrics(totalMemory, available, swapTotal, (long) (swapTotal * (1 - memory / 400))),
                new ServerMetrics.NetworkMetrics(download, upload), 1_054_800, 287);
    }

    private static CommandEntry entry(Instant at, String command, int exitCode, long millis, String error) {
        return new CommandEntry(at, command, false, exitCode, "", error, Duration.ofMillis(millis));
    }

    private static double clamp(double percent) {
        return Math.max(0, Math.min(100, percent));
    }
}
