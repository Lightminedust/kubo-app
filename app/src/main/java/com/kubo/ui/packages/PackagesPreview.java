package com.kubo.ui.packages;

import com.kubo.KuboApp;
import com.kubo.model.packages.AptStatus;
import com.kubo.model.packages.PackageEntry;
import com.kubo.model.packages.PackageInventory;
import com.kubo.model.packages.PackageRole;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

/**
 * Renders the packages screen against a sample server to PNG files, without a connection.
 * Run it with: gradlew previewPackages
 */
public final class PackagesPreview {

    private PackagesPreview() {
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "build/previews");
        Files.createDirectories(output);
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                render(output, "packages", sample(true), null, null);
                render(output, "packages-clean", sample(false), null, null);
                render(output, "packages-open", sample(true), PackageRole.SERVICES, "web");
                render(output, "packages-crowded", sample(true), PackageRole.APPLICATIONS, "utils");
                render(output, "packages-close", sample(true), PackageRole.SERVICES, "web");
                render(output, "packages-legend", sample(true), PackageRole.SERVICES, "web");
                // The upgrade card: what is waiting, and the same card partway through the download.
                render(output, "packages-upgrade", sample(true), null, null);
                render(output, "packages-upgrading", sample(true), null, null);
                render(output, "packages-index", sample(true), null, null);
                render(output, "packages-restart", sample(true), null, null);
                render(output, "packages-sheet", sample(true), null, null);
                render(output, "packages-sources", sample(true), null, null);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
    }

    /** A line break, written this way so a patch to this file cannot turn it into a real one. */
    private static final String NEWLINE = String.valueOf((char) 10);

    private static void render(Path output, String name, PackageInventory inventory, PackageRole opened,
                               String section) throws IOException {
        PackagesView view = new PackagesView();
        Scene scene = new Scene(view, 1400, 860, Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        view.applyCss();
        view.layout();
        view.showForPreview(inventory);
        if (opened != null) {
            view.tree().openForPreview(opened, section);
        }
        if (name.endsWith("-close")) {
            view.tree().zoomForPreview(5);
        }
        if (name.endsWith("-legend")) {
            view.showLegendForPreview();
        }
        if (name.endsWith("-upgrade")) {
            view.showUpgradeForPreview(inventory.waiting(), false, null);
        }
        if (name.endsWith("-sources")) {
            view.showSourcesForPreview(
                    com.kubo.model.packages.AptSource.parse(String.join(NEWLINE,
                            "@@file /etc/apt/sources.list",
                            "deb http://deb.debian.org/debian bookworm main contrib non-free-firmware",
                            "deb http://security.debian.org/debian-security bookworm-security main",
                            "deb-src http://deb.debian.org/debian bookworm main",
                            "# deb http://deb.debian.org/debian bookworm-backports main",
                            "@@file /etc/apt/sources.list.d/docker.list",
                            "deb [signed-by=/usr/share/keyrings/docker.gpg] "
                                    + "https://download.docker.com/linux/debian bookworm stable",
                            "@@file /etc/apt/sources.list.d/nodesource.list",
                            "deb [signed-by=/usr/share/keyrings/nodesource.gpg] "
                                    + "https://deb.nodesource.com/node_20.x nodistro main",
                            "@@file /etc/apt/sources.list.d/postgres.sources",
                            "Types: deb",
                            "URIs: https://apt.postgresql.org/pub/repos/apt",
                            "Suites: bookworm-pgdg",
                            "Components: main",
                            "Signed-By: /usr/share/keyrings/postgres.gpg")),
                    java.util.List.of(
                            new com.kubo.model.packages.SigningKey("/usr/share/keyrings/nodesource.gpg",
                                    "NodeSource <gpg@nodesource.com>", "1655A0AB68576280",
                                    java.time.LocalDate.of(2026, 10, 12)),
                            new com.kubo.model.packages.SigningKey("/usr/share/keyrings/docker.gpg",
                                    "Docker Release (CE deb) <docker@docker.com>", "8D81803C0EBFCD88",
                                    java.time.LocalDate.of(2027, 2, 10))),
                    java.time.LocalDate.of(2026, 9, 24));
        }
        if (name.endsWith("-restart")) {
            view.showRestartForPreview(com.kubo.model.packages.RestartNeeds.parse(String.join(NEWLINE,
                    "NEEDRESTART-VER: 3.6",
                    "NEEDRESTART-KCUR: 6.1.0-17-amd64",
                    "NEEDRESTART-KEXP: 6.1.0-18-amd64",
                    "NEEDRESTART-KSTA: 3",
                    "NEEDRESTART-SVC: ssh.service",
                    "NEEDRESTART-SVC: dbus.service",
                    "NEEDRESTART-SVC: nginx.service",
                    "NEEDRESTART-SVC: postfix@-.service",
                    "NEEDRESTART-SVC: cron.service",
                    "NEEDRESTART-SVC: fail2ban.service",
                    "NEEDRESTART-SVC: pm2-xana.service",
                    "NEEDRESTART-SVC: docker.service",
                    "NEEDRESTART-SVC: redis-server.service",
                    "NEEDRESTART-SVC: containerd.service",
                    "NEEDRESTART-SESS: root @ session-1.scope")), "cron.service");
        }
        if (name.endsWith("-sheet")) {
            view.showSheetForPreview(com.kubo.model.packages.PackageSheet.parse(String.join(NEWLINE,
                    "Package: nginx",
                    "Version: 1.22.1-9",
                    "Installed-Size: 520",
                    "Maintainer: Debian Nginx Maintainers <pkg-nginx@lists.debian.net>",
                    "Architecture: amd64",
                    "Depends: libc6 (>= 2.34), libssl3 (>= 3.0.0), nginx-common (= 1.22.1-9), libpcre2-8-0",
                    "Recommends: nginx-doc",
                    "Size: 521268",
                    "Section: httpd",
                    "Priority: optional",
                    "Homepage: https://nginx.org",
                    "Description: small, powerful, scalable web/proxy server",
                    " Nginx is a web and proxy server with a strong focus on high concurrency,",
                    " performance and low memory use.",
                    " .",
                    " This package installs the full version.",
                    "SHA256: 6d1f0f2c9f1c2c5e0e1b6b1b2c3d4e5f60718293a4b5c6d7e8f9012345678901")));
        }
        if (name.endsWith("-index")) {
            view.showIndexForPreview(java.util.List.of(
                    "Hit:1 http://deb.debian.org/debian bookworm InRelease",
                    "Get:2 http://security.debian.org/debian-security bookworm-security InRelease [48.0 kB]",
                    "Get:3 http://deb.debian.org/debian bookworm-updates InRelease [55.4 kB]",
                    "Get:4 http://security.debian.org/debian-security bookworm-security/main amd64 "
                            + "Packages [241 kB]",
                    "Fetched 344 kB in 1s (312 kB/s)",
                    "Reading package lists..."), false, false);
        }
        if (name.endsWith("-upgrading")) {
            // Real apt output, in the order apt prints it, so the readout is read the way it will be read.
            view.showUpgradeForPreview(inventory.waiting(), false, java.util.List.of(
                    "Reading package lists...",
                    "Building dependency tree...",
                    "The following packages will be upgraded:",
                    "  libssl3 nginx nginx-common openssh-server",
                    "4 upgraded, 0 newly installed, 0 to remove and 0 not upgraded.",
                    "Need to get 12.4 MB of archives.",
                    "Get:1 http://deb.debian.org/debian bookworm/main amd64 libssl3 amd64 3.0.13-1 [2,024 kB]",
                    "Get:2 http://deb.debian.org/debian bookworm/main amd64 nginx-common all 1.22.1-9 [523 kB]",
                    "Get:3 http://deb.debian.org/debian bookworm/main amd64 nginx amd64 1.22.1-9 [521 kB]",
                    "Fetched 3,068 kB in 2s (1,534 kB/s)",
                    "(Reading database ... 41203 files and directories currently installed.)",
                    "Preparing to unpack .../libssl3_3.0.13-1_amd64.deb ...",
                    "Unpacking libssl3:amd64 (3.0.13-1) over (3.0.11-1) ..."));
        }
        view.tree().freeze(2.4);
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        WritableImage image = scene.snapshot(null);
        Path file = output.resolve(name + ".png");
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        System.out.println("Wrote " + file.toAbsolutePath());
        view.shutdown();
    }

    /**
     * A Debian server with a realistic spread of families, and either updates waiting or none.
     */
    private static PackageInventory sample(boolean updates) {
        String[][] families = {
                {"libs", "340"}, {"utils", "120"}, {"python", "96"}, {"net", "64"}, {"devel", "58"},
                {"admin", "44"}, {"perl", "38"}, {"web", "22"}, {"doc", "18"}, {"text", "16"},
                {"shells", "9"}, {"database", "7"}, {"graphics", "31"}, {"editors", "14"}, {"sound", "12"},
                {"science", "11"}, {"x11", "26"}, {"math", "8"}, {"video", "6"}, {"electronics", "5"},
                {"games", "4"}, {"otherosfs", "3"}};
        Random random = new Random(11);
        List<PackageEntry> packages = new ArrayList<>();
        for (String[] family : families) {
            int count = Integer.parseInt(family[1]);
            for (int i = 0; i < count; i++) {
                long size = (long) Math.max(12, Math.abs(random.nextGaussian()) * 1800 + 40);
                boolean waiting = updates && random.nextDouble() < 0.06;
                boolean security = waiting && random.nextDouble() < 0.35;
                packages.add(new PackageEntry(family[0] + "-pkg-" + i, "1." + (i % 9) + ".2", size, family[0],
                        i == 0 ? "required" : "optional", i == 0 && family[0].equals("libs"),
                        waiting ? "1." + (i % 9) + ".3" : "", security,
                        !waiting && random.nextDouble() < 0.03,
                        random.nextDouble() < 0.18));
            }
        }
        AptStatus status = new AptStatus(AptStatus.Availability.USABLE, "2.6.1", updates);
        return new PackageInventory(status, packages,
                updates ? List.of("An update has asked for a restart: the running kernel or libraries are not the "
                        + "installed ones any more.") : List.of());
    }
}
