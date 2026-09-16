package com.kubo.ui.docker;

import com.kubo.KuboApp;
import com.kubo.model.docker.ContainerDetails;
import com.kubo.model.docker.ContainerState;
import com.kubo.model.docker.ContainerStats;
import com.kubo.model.docker.DockerContainer;
import com.kubo.model.docker.DockerImage;
import com.kubo.model.docker.DockerNetwork;
import com.kubo.model.docker.DockerStatus;
import com.kubo.model.docker.DockerStatus.Availability;
import com.kubo.model.docker.EnvVar;
import com.kubo.model.docker.Health;
import com.kubo.model.docker.Mount;
import com.kubo.model.docker.PortMapping;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Renders the Docker screen with sample containers, images and networks to PNG files, without a server.
 * Run it with: gradlew previewDocker
 */
public final class DockerPreview {

    private DockerPreview() {
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "build/previews");
        Files.createDirectories(output);

        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                renderContainers(output);
                renderImages(output);
                renderNetworks(output);
                renderRun(output);
                renderFiles(output);
                renderHelp(output);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
    }

    private static void renderContainers(Path output) throws IOException {
        DockerScope scope = scope();
        Scene scene = scene(scope);
        scope.selectForPreview(containers().get(0), apiDetails());
        scope.showLogsForPreview("""
                2026-09-15T10:41:02.113Z api listening on :8080
                2026-09-15T10:41:02.204Z connected to postgres://db:5432
                2026-09-15T10:42:10.552Z GET /health 200 3ms
                2026-09-15T10:42:41.019Z GET /api/projects 200 41ms
                2026-09-15T10:43:02.884Z POST /api/login 200 88ms""");
        scope.cluster().freeze(3.2);
        write(scene, output, "docker");
        scope.shutdown();
    }

    private static void renderFiles(Path output) throws IOException {
        DockerScope scope = scope();
        Scene scene = scene(scope);
        scope.selectForPreview(containers().get(0), apiDetails());
        scope.showFilesForPreview("/etc/nginx", com.kubo.docker.ContainerFiles.parse("/etc/nginx", """
                total 32
                drwxr-xr-x    1 root     root          4096 Sep 16 09:12 .
                drwxr-xr-x    1 root     root          4096 Sep 16 09:12 ..
                drwxr-xr-x    2 root     root          4096 Sep 16 09:12 conf.d
                drwxr-xr-x    2 root     root          4096 Sep 16 09:12 modules
                -rw-r--r--    1 root     root           648 Sep 16 09:12 nginx.conf
                -rw-r--r--    1 root     root          3957 Sep 16 09:12 mime.types
                -rw-r--r--    1 root     root          5290 Sep 16 09:12 fastcgi_params
                lrwxrwxrwx    1 root     root            11 Sep 16 09:12 access.log -> /dev/stdout
                -rw-r--r--    1 root     root       2097152 Sep 16 09:12 cache.bin
                """));
        scope.cluster().freeze(3.2);
        write(scene, output, "docker-files");
        scope.shutdown();
    }

    private static void renderImages(Path output) throws IOException {
        DockerScope scope = scope();
        Scene scene = scene(scope);
        scope.showImagesForPreview(List.of(
                new DockerImage("sha256:aaa1", "gemmie/api", "1.4", 512L * 1024 * 1024, "2 days ago", false),
                new DockerImage("sha256:bbb2", "postgres", "16", 438L * 1024 * 1024, "3 weeks ago", false),
                new DockerImage("sha256:ccc3", "nginx", "latest", 187L * 1024 * 1024, "1 month ago", false),
                new DockerImage("sha256:ddd4", "redis", "7-alpine", 41L * 1024 * 1024, "1 month ago", false),
                new DockerImage("sha256:eee5", "<none>", "<none>", 1_240L * 1024 * 1024, "5 days ago", true),
                new DockerImage("sha256:fff6", "<none>", "<none>", 96L * 1024 * 1024, "6 days ago", true)));
        scope.cluster().freeze(2.0);
        write(scene, output, "docker-images");
        scope.shutdown();
    }

    private static void renderNetworks(Path output) throws IOException {
        DockerScope scope = scope();
        Scene scene = scene(scope);
        scope.showNetworksForPreview(List.of(
                new DockerNetwork("net1", "bridge", "bridge", "local", List.of()),
                new DockerNetwork("net2", "gemmie_default", "bridge", "local",
                        List.of("gemmie-api", "gemmie-db", "gemmie-cache")),
                new DockerNetwork("net3", "proxy", "bridge", "local", List.of("gemmie-api", "traefik")),
                new DockerNetwork("net4", "host", "host", "local", List.of())));
        scope.cluster().freeze(2.0);
        write(scene, output, "docker-networks");
        scope.shutdown();
    }

    private static void renderRun(Path output) throws IOException {
        DockerScope scope = scope();
        RunDialog dialog = new RunDialog((request, done) -> {
        }, () -> {
        });
        StackPane holder = new StackPane(scope, dialog);
        Scene scene = new Scene(holder, 1400, 860, Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        scope.cluster().freeze(2.0);
        holder.applyCss();
        holder.layout();
        dialog.previewBusy(2.3, "nginx:alpine");
        write(scene, output, "docker-run");
        dialog.stopAnimation();
        scope.shutdown();
    }

    private static void renderHelp(Path output) throws IOException {
        DockerScope scope = scope();
        StackPane holder = new StackPane(scope, new DockerHelp(() -> {
        }));
        Scene scene = new Scene(holder, 1400, 860, Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        scope.cluster().freeze(2.0);
        write(scene, output, "docker-help");
        scope.shutdown();
    }

    private static DockerScope scope() {
        DockerScope scope = new DockerScope(new DockerStatus(Availability.USABLE, "24.0.7", true), containers());
        scope.cluster().setVersion("24.0.7");
        return scope;
    }

    private static Scene scene(DockerScope scope) {
        Scene scene = new Scene(scope, 1400, 860, Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        scope.applyCss();
        scope.layout();
        return scene;
    }

    private static void write(Scene scene, Path output, String name) throws IOException {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        WritableImage image = scene.snapshot(null);
        Path file = output.resolve(name + ".png");
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        System.out.println("Wrote " + file.toAbsolutePath());
    }

    private static List<DockerContainer> containers() {
        return List.of(
                new DockerContainer("abc123def456aaaa", "gemmie-api", "gemmie/api:1.4", ContainerState.RUNNING,
                        Health.HEALTHY, "Up 3 days (healthy)", "gemmie", "api",
                        List.of(PortMapping.published("0.0.0.0", 8080, 8080, "tcp")),
                        new ContainerStats(12.4, 168L * 1024 * 1024, 1945L * 1024 * 1024, 8.6)),
                new DockerContainer("def789aaaabbbb11", "gemmie-db", "postgres:16", ContainerState.RUNNING,
                        Health.HEALTHY, "Up 3 days (healthy)", "gemmie", "db",
                        List.of(PortMapping.exposedOnly(5432, "tcp")),
                        new ContainerStats(2.1, 512L * 1024 * 1024, 1945L * 1024 * 1024, 26.3)),
                new DockerContainer("ghi012cccc2222dd", "gemmie-cache", "redis:7-alpine", ContainerState.RUNNING,
                        Health.NONE, "Up 3 days", "gemmie", "cache",
                        List.of(PortMapping.published("127.0.0.1", 6379, 6379, "tcp")),
                        new ContainerStats(0.4, 24L * 1024 * 1024, 1945L * 1024 * 1024, 1.2)),
                new DockerContainer("jkl345eeee3333ff", "gemmie-worker", "gemmie/worker:1.4", ContainerState.RESTARTING,
                        Health.STARTING, "Restarting (1) 5 seconds ago", "gemmie", "worker", List.of(), null),
                new DockerContainer("mno678gggg4444hh", "mbaliscope-web", "nginx:latest", ContainerState.RUNNING,
                        Health.NONE, "Up 6 hours", "mbaliscope", "web",
                        List.of(PortMapping.published("0.0.0.0", 443, 443, "tcp")),
                        new ContainerStats(0.1, 12L * 1024 * 1024, 1945L * 1024 * 1024, 0.6)),
                new DockerContainer("pqr901iiii5555jj", "sandbox", "alpine:3.19", ContainerState.EXITED,
                        Health.NONE, "Exited (0) 2 hours ago", "", "",
                        List.of(), null),
                new DockerContainer("stu234kkkk6666ll", "old-backup", "busybox:latest", ContainerState.EXITED,
                        Health.NONE, "Exited (137) 6 days ago", "", "", List.of(), null));
    }

    private static ContainerDetails apiDetails() {
        return new ContainerDetails("abc123def456aaaa", "gemmie-api", "gemmie/api:1.4", ContainerState.RUNNING,
                "node server.js --port 8080", "unless-stopped", "2026-09-12T08:00:00Z",
                List.of(PortMapping.published("0.0.0.0", 8080, 8080, "tcp")),
                List.of(new Mount("bind", "/srv/gemmie/config", "/app/config", false),
                        new Mount("volume", "gemmie-uploads", "/app/uploads", true)),
                List.of(EnvVar.parse("NODE_ENV=production"), EnvVar.parse("PORT=8080"),
                        EnvVar.parse("DATABASE_URL=postgres://db:5432/gemmie"),
                        EnvVar.parse("JWT_SECRET=s3cr3t-signing-key-9f2a"),
                        EnvVar.parse("REDIS_URL=redis://cache:6379")));
    }
}
