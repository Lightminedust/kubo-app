package com.kubo.ui.network;

import com.kubo.KuboApp;
import com.kubo.model.docker.ContainerState;
import com.kubo.model.docker.DockerContainer;
import com.kubo.model.docker.Health;
import com.kubo.model.docker.PortMapping;
import com.kubo.model.network.Endpoint;
import com.kubo.model.network.Firewall;
import com.kubo.model.network.ListeningSocket;
import com.kubo.model.network.NetworkMap;
import com.kubo.model.network.PmApp;
import com.kubo.model.network.Risk;
import com.kubo.model.network.Vhost;
import com.kubo.model.server.ListeningPort;
import com.kubo.model.server.ListeningPort.Protocol;
import com.kubo.network.FirewallCommands;
import com.kubo.network.NetworkCommands;
import com.kubo.network.NetworkService;
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
 * Renders the network screen against a sample server to PNG files, without a connection.
 * Run it with: gradlew previewNetwork
 */
public final class NetworkPreview {

    private NetworkPreview() {
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "build/previews");
        Files.createDirectories(output);
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                render(output, "network", exposedServer(), -1);
                render(output, "network-focus", exposedServer(), 0);
                render(output, "network-safe", tidyServer(), -1);
                render(output, "network-crowded", crowdedServer(), -1);
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

    private static void render(Path output, String name, NetworkMap map, int select) throws IOException {
        NetworkScope scope = new NetworkScope();
        Scene scene = new Scene(scope, 1400, 860, Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        scope.applyCss();
        scope.layout();
        scope.showForPreview(map, select);
        scope.flow().fit();
        scope.flow().freeze(2.4);
        write(scene, output, name);
        scope.shutdown();
    }

    private static void renderHelp(Path output) throws IOException {
        NetworkScope scope = new NetworkScope();
        StackPane holder = new StackPane(scope, new NetworkHelp(() -> {
        }));
        Scene scene = new Scene(holder, 1400, 860, Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        holder.applyCss();
        holder.layout();
        scope.showForPreview(exposedServer(), -1);
        scope.flow().fit();
        scope.flow().freeze(2.0);
        write(scene, output, "network-help");
        scope.shutdown();
    }

    private static void write(Scene scene, Path output, String name) throws IOException {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        WritableImage image = scene.snapshot(null);
        Path file = output.resolve(name + ".png");
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        System.out.println("Wrote " + file.toAbsolutePath());
    }

    private static ListeningSocket socket(String address, int port, String process, int pid) {
        return new ListeningSocket(new ListeningPort(Protocol.TCP, address, port, process), pid);
    }

    /**
     * A server with a real mistake on it: MongoDB bound to every interface with the firewall switched off.
     */
    private static NetworkMap exposedServer() {
        Firewall firewall = NetworkCommands.parseFirewall("""
                Status: active
                [ 1] 22/tcp                     ALLOW IN    Anywhere
                [ 2] 80,443/tcp                 ALLOW IN    Anywhere
                [ 3] 27017/tcp                  ALLOW IN    Anywhere
                """);
        List<Vhost> vhosts = List.of(
                new Vhost(List.of("gemmie.rw", "www.gemmie.rw"), List.of(443), true,
                        List.of(NetworkCommands.target("http://127.0.0.1:3000")),
                        "/etc/letsencrypt/live/gemmie.rw/fullchain.pem", ""),
                new Vhost(List.of("api.gemmie.rw"), List.of(443), true,
                        List.of(NetworkCommands.target("http://127.0.0.1:4000")),
                        "/etc/letsencrypt/live/api.gemmie.rw/fullchain.pem", ""),
                new Vhost(List.of("mbaliscope.rw"), List.of(80), false,
                        List.of(NetworkCommands.target("http://127.0.0.1:8080")), "", ""));
        List<PmApp> pm2 = List.of(
                new PmApp("gemmie-web", 0, 1412, "online", 1.4, 178_000_000L, 0, 2),
                new PmApp("gemmie-api", 1, 1490, "online", 6.2, 264_000_000L, 0, 0),
                new PmApp("gemmie-worker", 2, 0, "errored", 0, 0, 0, 41));
        List<DockerContainer> containers = List.of(
                new DockerContainer("c1a2b3", "gemmie-redis", "redis:7-alpine", ContainerState.RUNNING,
                        Health.NONE, "Up", "gemmie", "cache",
                        List.of(PortMapping.published("127.0.0.1", 6379, 6379, "tcp")), null),
                new DockerContainer("d4e5f6", "mongodb_gemmie", "mongo:7", ContainerState.RUNNING,
                        Health.HEALTHY, "Up", "gemmie", "db",
                        List.of(PortMapping.published("0.0.0.0", 27017, 27017, "tcp")), null));
        List<ListeningSocket> sockets = List.of(
                socket("0.0.0.0", 22, "sshd", 812),
                socket("0.0.0.0", 80, "nginx", 999),
                socket("0.0.0.0", 443, "nginx", 999),
                socket("127.0.0.1", 3000, "node", 1412),
                socket("127.0.0.1", 4000, "node", 1490),
                socket("127.0.0.1", 8080, "node", 1533),
                socket("127.0.0.1", 6379, "docker-proxy", 2100),
                socket("0.0.0.0", 27017, "docker-proxy", 2140),
                socket("127.0.0.53", 53, "systemd-resolve", 500),
                // Bound to the world but not allowed through: the case the firewall pane exists to show.
                socket("0.0.0.0", 9090, "cockpit", 1700));
        List<Endpoint> endpoints = NetworkService.correlate(sockets, pm2, containers, vhosts, firewall);
        List<Risk> risks = endpoints.stream().map(Risk::of).flatMap(java.util.Optional::stream).toList();
        return new NetworkMap(firewall, vhosts, endpoints, pm2, risks,
                List.of("Nginx is installed but its configuration could not be read, which needs root."));
    }

    /**
     * A real-world server with no firewall and a crowd of unnamed sockets: the case that has to stay legible.
     */
    private static NetworkMap crowdedServer() {
        Firewall firewall = FirewallCommands.parseUfw("ERROR: You need to be root to run this script");
        List<Vhost> vhosts = List.of(new Vhost(List.of("gemmie.rw"), List.of(443), true,
                List.of(NetworkCommands.target("http://127.0.0.1:3080")), "/etc/ssl/fullchain.pem", ""));
        List<PmApp> pm2 = List.of(new PmApp("gemmie-api", 0, 3301, "online", 3.1, 210_000_000L, 0, 1));
        List<ListeningSocket> sockets = new java.util.ArrayList<>(List.of(
                socket("0.0.0.0", 22, "", ListeningSocket.UNKNOWN_PID),
                socket("[::]", 22, "", ListeningSocket.UNKNOWN_PID),
                socket("0.0.0.0", 80, "", ListeningSocket.UNKNOWN_PID),
                socket("127.0.0.1", 3080, "node", 3301),
                socket("0.0.0.0", 5355, "", ListeningSocket.UNKNOWN_PID),
                socket("[::]", 5355, "", ListeningSocket.UNKNOWN_PID),
                socket("0.0.0.0", 25, "", ListeningSocket.UNKNOWN_PID),
                socket("[::1]", 25, "", ListeningSocket.UNKNOWN_PID),
                socket("127.0.0.53", 53, "", ListeningSocket.UNKNOWN_PID),
                socket("127.0.0.54", 53, "", ListeningSocket.UNKNOWN_PID),
                socket("127.0.0.1", 6379, "docker-proxy", 2100),
                socket("127.0.0.1", 8001, "docker-proxy", 2101),
                socket("127.0.0.1", 27017, "docker-proxy", 2102)));
        List<DockerContainer> containers = List.of(
                new DockerContainer("c1", "gemmie-redis", "redis:7", ContainerState.RUNNING, Health.NONE, "Up",
                        "", "", List.of(PortMapping.published("127.0.0.1", 6379, 6379, "tcp")), null),
                new DockerContainer("c2", "gemmie-api", "gemmie/api", ContainerState.RUNNING, Health.NONE, "Up",
                        "", "", List.of(PortMapping.published("127.0.0.1", 8001, 8001, "tcp")), null),
                new DockerContainer("c3", "mongodb_gemmie", "mongo:7", ContainerState.RUNNING, Health.NONE, "Up",
                        "", "", List.of(PortMapping.published("127.0.0.1", 27017, 27017, "tcp")), null));
        List<Endpoint> endpoints = NetworkService.correlate(sockets, pm2, containers, vhosts, firewall);
        List<Risk> risks = endpoints.stream().map(Risk::of).flatMap(java.util.Optional::stream).toList();
        return new NetworkMap(firewall, vhosts, endpoints, pm2, risks,
                // Taken from the model, so the preview says exactly what the real screen would say.
                List.of(firewall.problem()));
    }

    /**
     * The same server once the database has been put back behind loopback.
     */
    private static NetworkMap tidyServer() {
        Firewall firewall = NetworkCommands.parseFirewall("""
                Status: active
                [ 1] 22/tcp                     ALLOW IN    Anywhere
                [ 2] 80,443/tcp                 ALLOW IN    Anywhere
                """);
        List<Vhost> vhosts = List.of(new Vhost(List.of("gemmie.rw"), List.of(443), true,
                List.of(NetworkCommands.target("http://127.0.0.1:3000")), "/etc/ssl/fullchain.pem", ""));
        List<PmApp> pm2 = List.of(new PmApp("gemmie-web", 0, 1412, "online", 1.4, 178_000_000L, 0, 2));
        List<ListeningSocket> sockets = List.of(
                socket("0.0.0.0", 22, "sshd", 812),
                socket("0.0.0.0", 443, "nginx", 999),
                socket("127.0.0.1", 3000, "node", 1412),
                socket("127.0.0.1", 27017, "mongod", 2140));
        List<Endpoint> endpoints = NetworkService.correlate(sockets, pm2, List.of(), vhosts, firewall);
        List<Risk> risks = endpoints.stream().map(Risk::of).flatMap(java.util.Optional::stream).toList();
        return new NetworkMap(firewall, vhosts, endpoints, pm2, risks, List.of());
    }
}
