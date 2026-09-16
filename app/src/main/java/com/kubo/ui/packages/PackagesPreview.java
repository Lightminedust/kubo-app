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
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
    }

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
