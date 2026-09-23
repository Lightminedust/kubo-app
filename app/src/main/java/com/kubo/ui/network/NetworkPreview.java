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
                renderReading(output);
                com.kubo.ui.ToolsCard toolsCard = new com.kubo.ui.ToolsCard((tool, report) -> { }, true, "",
                        () -> { });
                toolsCard.show(java.util.Set.of(com.kubo.model.tools.Tool.PING,
                                com.kubo.model.tools.Tool.WHOIS),
                        com.kubo.model.tools.PackageManager.APT);
                // One row open, because the point of the change is that the command is one click away and in
                // full, rather than printed under every row and cut off.
                toolsCard.showCommandForPreview(com.kubo.model.tools.Tool.NETSTAT);
                renderCard(output, "network-tools", toolsCard);
                renderCard(output, "network-installing", installing(false));
                renderCard(output, "network-installed", installing(true));
                renderCard(output, "network-diagnose", diagnostics(null));
                // A trace that stops: a quiet router that is fine, one rationing its replies, then nothing.
                renderCard(output, "network-diagnose-route", diagnostics(String.join(NEWLINE,
                        "HOST: server                      Loss%   Snt   Last   Avg  Best  Wrst StDev",
                        "  1.|-- 203.0.113.1                0.0%     3    0.4   0.5   0.3   0.7   0.1",
                        "  2.|-- ???                       100.0     3    0.0   0.0   0.0   0.0   0.0",
                        "  3.|-- 80.81.192.1               33.3%     3    9.8  10.2   9.8  10.6   0.4",
                        "  4.|-- 62.115.44.9                0.0%     3   18.1  18.4  17.9  19.0   0.4",
                        "  5.|-- ???                       100.0     3    0.0   0.0   0.0   0.0   0.0",
                        "  6.|-- ???                       100.0     3    0.0   0.0   0.0   0.0   0.0")));
                ConnectionsCard.Hands watching = new ConnectionsCard.Hands() {
                    @Override public void ban(String address, java.util.function.Consumer<String> done) { }
                    @Override public void whois(String address,
                            java.util.function.Consumer<String> answer) { }
                };
                // One address looked up, one already banned: the two states the card could not show before.
                ConnectionsCard everything = new ConnectionsCard(exposedServer().conversations(), watching, true,
                        true, java.util.Set.of("193.32.162.8"), () -> { });
                everything.showForPreview("141.98.10.60", String.join("\n",
                        "inetnum:        141.98.10.0 - 141.98.10.255",
                        "netname:        LT-HOSTING",
                        "country:        LT",
                        "org-name:       UAB Host Baltic"), null);
                renderCard(output, "network-connections", everything);
                // The same card with a filter on, which is how a busy server is actually read.
                ConnectionsCard inside = new ConnectionsCard(exposedServer().conversations(), watching, true, true,
                        java.util.Set.of(), () -> { });
                inside.showOnly("INSIDE");
                renderCard(output, "network-connections-inside", inside);
                renderCard(output, "network-firewall", firewallCard(REAL_UFW, null));
                // The one confirmation that asks for a number: the only rule letting SSH in, about to go.
                renderCard(output, "network-firewall-lockout", firewallCard(String.join(NEWLINE,
                        "Default: deny (incoming), allow (outgoing), disabled (routed)",
                        "Status: active",
                        "[ 1] 22/tcp                     ALLOW IN    Anywhere",
                        "[ 2] 80                         ALLOW IN    Anywhere",
                        "[ 3] 443                        ALLOW IN    Anywhere"), "22/tcp"));
                renderNginx(output, "network-nginx", exposedServer().endpoints());
                // The same site with nothing listening on the port it forwards to: the 502 nobody sees coming.
                renderNginx(output, "network-nginx-broken", exposedServer().endpoints().stream()
                        .filter(endpoint -> endpoint.socket().port() != 3000).toList());
                renderCard(output, "network-new-site", newSiteCard());
                renderCard(output, "network-folder-picker", folderPicker());
                renderCard(output, "network-certificate-picker", certificatePicker());
                GuardCard edge = new GuardCard(sampleJails(), java.time.Instant.now(), new GuardCard.Hands() {
                    @Override public void ban(String jail, String address) { }
                    @Override public void unban(String jail, String address) { }
                }, () -> { });
                List<com.kubo.model.network.Certificate> edgeCertificates =
                        new java.util.ArrayList<>(sampleCertificates());
                // The certificate on the user's own server: fifteen years left, and trusted by Cloudflare only.
                edgeCertificates.add(new com.kubo.model.network.Certificate("/etc/ssl/cloudflare/kubo.pem",
                        java.time.Instant.now().plus(java.time.Duration.ofDays(5468)),
                        "O=CloudFlare, Inc., OU=CloudFlare Origin CA, CN=CloudFlare Origin Certificate",
                        "C=US, O=CloudFlare, Inc., OU=CloudFlare Origin SSL Certificate Authority", ""));
                edge.showCertificates(edgeCertificates, java.time.Instant.now());
                renderCard(output, "network-edge", edge);
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

    /** The two cards the rail opens, over the screen they belong to. */
    /**
     * The Nginx screen where it actually lives: inside the window, with the map put away behind it.
     */
    private static void renderNginx(Path output, String name,
                                    List<com.kubo.model.network.Endpoint> listening) throws IOException {
        NetworkScope scope = new NetworkScope();
        Scene scene = new Scene(scope, 1400, 860, Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        scope.applyCss();
        scope.layout();
        scope.showForPreview(exposedServer(), -1);
        scope.showNginxForPreview(nginxScreen(listening));
        write(scene, output, name);
        scope.shutdown();
    }

    /**
     * The Nginx screen with a server that has a redirect, a proxy, a pool, a folder and a certificate: the
     * shape of every small VPS that outgrew one process.
     */
    /**
     * The form that writes a site, filled the way somebody would fill it, with the file it will write beside
     * it. The whole point of the card is that the file is readable before anything is written.
     */
    private static NewSiteCard newSiteCard() {
        NewSiteCard card = new NewSiteCard(new NewSiteCard.Hands() {
            @Override public void exists(String site, java.util.function.Consumer<Boolean> done) { }
            @Override public void create(com.kubo.model.network.SiteDraft draft,
                    java.util.function.Consumer<String> done) { }
            @Override public void delete(String site, java.util.function.Consumer<String> done) { }
            @Override public void pick(com.kubo.model.files.PathKind kind, String start,
                    java.util.function.Consumer<String> chosen) { }
        }, List.of(new com.kubo.model.network.Upstream("backend_nodes", "least_conn", List.of())),
                exposedServer().endpoints(), () -> { }, () -> { });
        card.showForPreview("api.gemmi-e.com www.api.gemmi-e.com",
                com.kubo.model.network.SiteDraft.Preset.NODE, "3000");
        return card;
    }

    private static final String NEWLINE = String.valueOf((char) 10);

    /**
     * @param trace an mtr report to show as already answered, or null for the card as it opens
     */
    private static DiagnosticsCard diagnostics(String trace) {
        DiagnosticsCard card = new DiagnosticsCard(sampleIdentity(),
                java.util.Set.of(com.kubo.model.tools.Tool.PING, com.kubo.model.tools.Tool.DIG,
                        com.kubo.model.tools.Tool.MTR),
                new DiagnosticsCard.Hands() {
                    @Override public void probe(String title, java.util.function.Supplier<String> command,
                            java.util.function.Consumer<String> answer) { }
                    @Override public void openTools() { }
                }, () -> { });
        if (trace != null) {
            card.showForPreview("Trace the route to 45.135.232.17", true, trace);
        }
        return card;
    }

    /** The UFW rules of a real server: two ways SSH gets in, and most rules written twice, once per family. */
    private static final String REAL_UFW = String.join(NEWLINE,
            "Default: deny (incoming), allow (outgoing), disabled (routed)",
            "Status: active",
            "[ 1] OpenSSH                    ALLOW IN    Anywhere",
            "[ 2] 80                         ALLOW IN    Anywhere",
            "[ 3] 443                        ALLOW IN    Anywhere",
            "[ 4] Nginx Full                 ALLOW IN    Anywhere",
            "[ 5] 3080                       DENY IN     Anywhere",
            "[ 6] 22/tcp                     ALLOW IN    Anywhere",
            "[ 7] OpenSSH (v6)               ALLOW IN    Anywhere (v6)",
            "[ 8] 80 (v6)                    ALLOW IN    Anywhere (v6)",
            "[ 9] 443 (v6)                   ALLOW IN    Anywhere (v6)",
            "[10] Nginx Full (v6)            ALLOW IN    Anywhere (v6)",
            "[11] 3080 (v6)                  DENY IN     Anywhere (v6)");

    /**
     * @param deleting the rule whose delete is being asked about, or null for none
     */
    private static FirewallCard firewallCard(String ufw, String deleting) {
        FirewallCard card = new FirewallCard(com.kubo.network.FirewallCommands.parseUfw(ufw),
                new FirewallCard.Hands() {
                    @Override public void allowFrom(String source, int port,
                            com.kubo.model.server.ListeningPort.Protocol protocol) { }
                    @Override public void denyFrom(String source, int port,
                            com.kubo.model.server.ListeningPort.Protocol protocol) { }
                    @Override public void delete(com.kubo.model.network.Firewall.RuleGroup group) { }
                }, 22, () -> { });
        if (deleting != null) {
            card.askForPreview(deleting);
        }
        return card;
    }

    /** A picker opened on a folder, with what is in it, one entry picked. */
    private static com.kubo.ui.PathPicker pathPicker(String title, com.kubo.model.files.PathKind kind,
                                                     String folder, List<com.kubo.model.files.RemoteEntry> entries,
                                                     int pick) {
        com.kubo.ui.PathPicker picker = new com.kubo.ui.PathPicker(title, kind, (path, done) -> { }, folder,
                chosen -> { }, () -> { });
        picker.showForPreview(folder, entries, pick);
        return picker;
    }

    private static com.kubo.model.files.RemoteEntry entry(String folder, String name,
                                                          com.kubo.model.files.RemoteEntry.Type type) {
        return new com.kubo.model.files.RemoteEntry(name, folder + "/" + name, type, 4096, java.time.Instant.now(),
                "rwxr-xr-x", "root", "root", "", type == com.kubo.model.files.RemoteEntry.Type.DIRECTORY ? 3
                : com.kubo.model.files.RemoteEntry.UNKNOWN, true);
    }

    private static com.kubo.ui.PathPicker folderPicker() {
        String folder = "/var/www";
        List<com.kubo.model.files.RemoteEntry> entries = new java.util.ArrayList<>();
        for (String name : List.of("gemmi-e", "html", "kubo", "old-shop", "shared")) {
            entries.add(entry(folder, name, com.kubo.model.files.RemoteEntry.Type.DIRECTORY));
        }
        entries.add(entry(folder, "robots.txt", com.kubo.model.files.RemoteEntry.Type.FILE));
        return pathPicker("Pick the site's folder", com.kubo.model.files.PathKind.FOLDER, folder, entries, 0);
    }

    /** Where Let's Encrypt keeps one certificate: four links and a README, only some of them certificates. */
    private static com.kubo.ui.PathPicker certificatePicker() {
        String folder = "/etc/letsencrypt/live/api.gemmi-e.com";
        List<com.kubo.model.files.RemoteEntry> entries = List.of(
                entry(folder, "cert.pem", com.kubo.model.files.RemoteEntry.Type.LINK),
                entry(folder, "chain.pem", com.kubo.model.files.RemoteEntry.Type.LINK),
                entry(folder, "fullchain.pem", com.kubo.model.files.RemoteEntry.Type.LINK),
                entry(folder, "privkey.pem", com.kubo.model.files.RemoteEntry.Type.LINK),
                entry(folder, "README", com.kubo.model.files.RemoteEntry.Type.FILE));
        com.kubo.ui.PathPicker picker = pathPicker("Pick the certificate", com.kubo.model.files.PathKind.CERTIFICATE,
                folder, entries, 2);
        // What the server said when fullchain.pem was picked: its armour line, and only that.
        picker.knowForPreview(folder + "/fullchain.pem", "-----BEGIN CERTIFICATE-----");
        return picker;
    }

    private static NginxScreen nginxScreen(List<com.kubo.model.network.Endpoint> listening) {
        NginxScreen screen = new NginxScreen(new NginxScreen.Hands() {
            @Override public void enable(String site, java.util.function.Consumer<String> done) { }
            @Override public void disable(String site, java.util.function.Consumer<String> done) { }
            @Override public void test(java.util.function.Consumer<String> done) { }
            @Override public void reload(java.util.function.Consumer<String> done) { }
            @Override public void readFile(String path, java.util.function.Consumer<String> done) { }
            @Override public void readLog(String path, java.util.function.Consumer<String> done) {
                done.accept(String.join("\n",
                        "45.135.232.17 - - [23/Sep/2026:10:04:11 +0000] \"GET / HTTP/1.1\" 200 5312 \"-\" \"-\"",
                        "86.15.2.9 - - [23/Sep/2026:10:04:12 +0000] \"GET /api/v1/health HTTP/1.1\" 200 18 \"-\" \"-\"",
                        "91.0.0.4 - - [23/Sep/2026:10:04:14 +0000] \"GET /wp-login.php HTTP/1.1\" 404 162 \"-\" \"-\"",
                        "20.114.5.33 - - [23/Sep/2026:10:04:19 +0000] \"POST /api/v1/orders HTTP/1.1\" 502 166 \"-\" \"-\"",
                        "8.42.77.190 - - [23/Sep/2026:10:04:21 +0000] \"GET /static/app.css HTTP/1.1\" 200 41233 \"-\" \"-\"",
                        "141.98.10.60 - - [23/Sep/2026:10:04:25 +0000] \"GET / HTTP/1.1\" 301 169 \"-\" \"-\""));
            }
            @Override public void reread() { }
            @Override public void newSite() { }
            @Override public void showPort(int port) { }
            @Override public void browse(String path) { }
            @Override public void back() { }
        });
        String config = """
                # configuration file /etc/nginx/nginx.conf:
                http {
                    upstream backend_nodes {
                        least_conn;
                        server 127.0.0.1:3000 weight=2;
                        server 127.0.0.1:3001;
                        server 127.0.0.1:3002 down;
                    }
                }

                # configuration file /etc/nginx/sites-enabled/gemmie:
                server {
                    listen 80;
                    server_name gemmi-e.com www.gemmi-e.com;
                    return 301 https://$host$request_uri;
                }

                server {
                    listen 443 ssl;
                    server_name gemmi-e.com www.gemmi-e.com;
                    ssl_certificate /etc/letsencrypt/live/gemmi-e.com/fullchain.pem;
                    access_log /var/log/nginx/gemmie.access.log;

                    location / {
                        proxy_pass http://127.0.0.1:3000;
                        proxy_set_header Host $host;
                    }

                    location /api/v1 {
                        proxy_pass http://backend_nodes;
                    }

                    location /ws {
                        proxy_pass http://127.0.0.1:8081;
                        proxy_set_header Upgrade $http_upgrade;
                        proxy_set_header Connection "upgrade";
                    }

                    location /static {
                        alias /var/www/assets;
                    }
                }
                """;
        com.kubo.model.network.NginxSites sites = new com.kubo.model.network.NginxSites(
                List.of("default", "gemmie", "kubo.gemmi-e.com", "old-shop"),
                List.of("gemmie", "kubo.gemmi-e.com"),
                java.util.Map.of("gemmie", List.of("gemmi-e.com", "www.gemmi-e.com"),
                        "kubo.gemmi-e.com", List.of("kubo.gemmi-e.com"),
                        "old-shop", List.of("shop.gemmi-e.com")));
        screen.show(sites,
                com.kubo.network.NetworkCommands.parseNginx(config),
                com.kubo.network.NetworkCommands.parseUpstreams(config),
                List.of(new com.kubo.model.network.Certificate(
                        "/etc/letsencrypt/live/gemmi-e.com/fullchain.pem",
                        java.time.Instant.now().plus(java.time.Duration.ofDays(28)),
                        "CN=gemmi-e.com", "Let's Encrypt", "")),
                listening);
        return screen;
    }

    private static void renderCard(Path output, String name, javafx.scene.layout.Region card) throws IOException {
        NetworkScope scope = new NetworkScope();
        StackPane holder = new StackPane(scope, card);
        Scene scene = new Scene(holder, 1400, 860, Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        holder.applyCss();
        holder.layout();
        scope.showForPreview(exposedServer(), -1);
        scope.flow().fit();
        scope.flow().freeze(2.0);
        write(scene, output, name);
        scope.shutdown();
    }

    /** The map while a reading is in flight, which is a state nobody could see in a still picture before. */
    private static void renderReading(Path output) throws IOException {
        NetworkScope scope = new NetworkScope();
        Scene scene = new Scene(scope, 1400, 860, Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        scope.applyCss();
        scope.layout();
        scope.showForPreview(exposedServer(), -1);
        scope.flow().fit();
        scope.flow().setLoading(true);
        scope.machine().setReading(true);
        scope.layout();
        scope.flow().freeze(1.35);
        write(scene, output, "network-reading");
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
                List.of("Nginx is installed but its configuration could not be read, which needs root."),
                sampleIdentity(), sampleConnections(), sampleRates(), sampleJails(), sampleCertificates(),
                com.kubo.model.network.Naming.of(sampleIdentity(), List.of(
                        new com.kubo.model.docker.DockerNetwork("41317674355ab9", "app-frontend_default",
                                "bridge", "local", List.of()))));
    }

    /**
     * A machine with one public interface, a Docker bridge and a resolver, which is what a small VPS looks like.
     */
    static com.kubo.model.network.Identity sampleIdentity() {
        com.kubo.model.network.Link lo = new com.kubo.model.network.Link("lo", "UNKNOWN", "", 65536,
                List.of(new com.kubo.model.network.Link.Address("127.0.0.1", 8, false)));
        com.kubo.model.network.Link eth0 = new com.kubo.model.network.Link("eth0", "UP", "96:00:02:d1:4f:aa", 1500,
                List.of(new com.kubo.model.network.Link.Address("203.0.113.10", 24, false),
                        new com.kubo.model.network.Link.Address("2001:db8:2044:1::1", 64, true)));
        com.kubo.model.network.Link docker0 = new com.kubo.model.network.Link("docker0", "DOWN",
                "02:42:1b:9c:11:02", 1500,
                List.of(new com.kubo.model.network.Link.Address("172.17.0.1", 16, false)));
        com.kubo.model.network.Link bridge = new com.kubo.model.network.Link("br-41317674355a", "UP",
                "02:42:aa:bb:cc:dd", 1500,
                List.of(new com.kubo.model.network.Link.Address("172.19.0.1", 16, false)));
        com.kubo.model.network.Link veth1 = new com.kubo.model.network.Link("vethfb8a471", "UP",
                "9a:11:02:aa:bb:01", 1500, List.of());
        com.kubo.model.network.Link veth2 = new com.kubo.model.network.Link("veth2c91d03", "UP",
                "9a:11:02:aa:bb:02", 1500, List.of());
        return new com.kubo.model.network.Identity(List.of(lo, eth0, docker0, bridge, veth1, veth2),
                List.of(new com.kubo.model.network.Route("default", "203.0.113.1", "eth0", "", 100),
                        new com.kubo.model.network.Route("172.17.0.0/16", "", "docker0", "172.17.0.1", -1)),
                new com.kubo.model.network.Resolvers(List.of("1.1.1.1", "8.8.8.8"), List.of("example.com"),
                        com.kubo.model.network.Resolvers.RESOLVECTL),
                "203.0.113.10");
    }

    /**
     * A server under ordinary load: a web server answering the internet, an SSH session, a connection pool to
     * Redis and a database. Deliberately crowded, because the flat list was fine at eight rows and unreadable
     * at fifty, and a preview that never shows fifty never shows the problem.
     */
    /**
     * An install halfway through, which is the one state a still picture could not show before.
     */
    private static com.kubo.ui.ToolsCard installing(boolean finished) {
        com.kubo.ui.ToolsCard card = new com.kubo.ui.ToolsCard((tool, report) -> { }, true, "", () -> { });
        card.show(java.util.Set.of(com.kubo.model.tools.Tool.PING, com.kubo.model.tools.Tool.WHOIS),
                com.kubo.model.tools.PackageManager.APT);
        // Lines as long as a real one, because the box used to take its width from its widest line and push
        // the whole card wider with every line that arrived.
        List<String> printed = new java.util.ArrayList<>(List.of(
                "Reading package lists...",
                "Building dependency tree...",
                "0 upgraded, 2 newly installed, 0 to remove and 6 not upgraded.",
                "Need to get 1,264 kB of archives.",
                "After this operation, 454 kB of additional disk space will be used.",
                "Get:1 https://deb.debian.org/debian trixie/main amd64 libtirpc3 amd64 1.3.4+ds-1.1 [80.0 kB]",
                "Get:2 https://deb.debian.org/debian trixie/main amd64 lsof amd64 4.99.3+dfsg-1 [376 kB]"));
        if (finished) {
            printed.add("Fetched 1,264 kB in 1s (892 kB/s)");
            printed.add("Selecting previously unselected package lsof.");
            printed.add("Preparing to unpack .../lsof_4.99.3+dfsg-1_amd64.deb ...");
            printed.add("Unpacking lsof (4.99.3+dfsg-1) ...");
            printed.add("Setting up lsof (4.99.3+dfsg-1) ...");
            printed.add("Processing triggers for man-db (2.13.0-1) ...");
        }
        card.showInstallingForPreview(com.kubo.model.tools.Tool.LSOF, printed, finished);
        return card;
    }

    static List<com.kubo.model.network.Connection> sampleConnections() {
        List<com.kubo.model.network.Connection> connections = new java.util.ArrayList<>();
        // The internet on 443, several callers, some of them holding more than one socket.
        String[] callers = {"45.135.232.17", "86.15.2.9", "193.32.162.8", "51.210.44.7", "8.42.77.190",
                "141.98.10.60", "20.114.5.33"};
        int port = 40000;
        for (int index = 0; index < callers.length; index++) {
            for (int socket = 0; socket <= index % 3; socket++) {
                connections.add(new com.kubo.model.network.Connection(Protocol.TCP, "203.0.113.10", 443,
                        callers[index], ++port, "nginx", 700, true));
            }
        }
        // One person logged in.
        connections.add(new com.kubo.model.network.Connection(Protocol.TCP, "203.0.113.10", 22, "86.15.2.9",
                51234, "sshd", 8213, true));
        // The application's pool to Redis, which is what makes a raw list unreadable.
        for (int socket = 0; socket < 14; socket++) {
            connections.add(new com.kubo.model.network.Connection(Protocol.TCP, "172.19.0.1", 54000 + socket,
                    "172.19.0.2", 6379, "", -1, false));
        }
        connections.add(new com.kubo.model.network.Connection(Protocol.TCP, "127.0.0.1", 44210, "127.0.0.1",
                6379, "node", 1490, false));
        connections.add(new com.kubo.model.network.Connection(Protocol.TCP, "127.0.0.1", 44212, "127.0.0.1",
                6379, "node", 1490, false));
        // And a database on the private network.
        for (int socket = 0; socket < 5; socket++) {
            connections.add(new com.kubo.model.network.Connection(Protocol.TCP, "10.4.4.1", 49000 + socket,
                    "10.4.4.9", 5432, "node", 1490, false));
        }
        return List.copyOf(connections);
    }

    static java.util.Map<String, com.kubo.model.network.Counter.Rate> sampleRates() {
        return java.util.Map.of("eth0", new com.kubo.model.network.Counter.Rate(184_320, 2_411_724));
    }

    static List<com.kubo.model.network.Jail> sampleJails() {
        return List.of(
                new com.kubo.model.network.Jail("sshd", 3, 1892, 57,
                        List.of("45.135.232.17", "193.32.162.8")),
                new com.kubo.model.network.Jail("nginx-http-auth", 0, 0, 0, List.of()));
    }

    static List<com.kubo.model.network.Certificate> sampleCertificates() {
        java.time.Instant now = java.time.Instant.now();
        return List.of(
                new com.kubo.model.network.Certificate("/etc/letsencrypt/live/example.com/fullchain.pem",
                        now.plus(java.time.Duration.ofDays(64)), "CN = example.com", "CN = R11", ""),
                new com.kubo.model.network.Certificate("/etc/letsencrypt/live/api.example.com/fullchain.pem",
                        now.plus(java.time.Duration.ofDays(9)), "CN = api.example.com", "CN = R11", ""),
                com.kubo.model.network.Certificate.unreadable("/etc/ssl/private/old.pem",
                        "Could not open file or uri for loading certificate: Permission denied"));
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
