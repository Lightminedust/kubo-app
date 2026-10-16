package com.kubo.ui.media;

import com.kubo.KuboApp;
import com.kubo.model.media.MediaKind;
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
import java.util.concurrent.CountDownLatch;

/**
 * Renders the picture, sound and film viewer in its three states, with no server.
 */
public final class MediaPreview {

    private MediaPreview() {
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "build/previews");
        Files.createDirectories(output);

        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                MediaPanel copying = new MediaPanel(null, null, MediaKind.VIDEO, null);
                copying.showForPreview("standup-2026-09-24.mp4", "Copying standup-2026-09-24.mp4 from the "
                        + "server…", 0.42, "58.1 MB of 138 MB");
                snapshot(copying, output.resolve("media-copying.png"), 1120, 620);

                // A picture that is already on this computer: one of the pictures in the readme.
                Path picture = Path.of("docs/images/network.png");
                if (Files.exists(picture)) {
                    MediaPanel looking = new MediaPanel(null, null, MediaKind.IMAGE, null);
                    looking.showPictureForPreview("exposure-before-the-firewall.png", picture);
                    snapshot(looking, output.resolve("media-picture.png"), 1120, 620);
                }

                MediaPanel stuck = new MediaPanel(null, null, MediaKind.VIDEO, null);
                stuck.showUndecodableForPreview("kubo-services.mp4");
                snapshot(stuck, output.resolve("media-undecodable.png"), 1120, 620);

                MediaPanel listening = new MediaPanel(null, null, MediaKind.AUDIO, null);
                listening.showPlayingForPreview("call-recording-12.m4a", 0.37, "04:11 / 11:16");
                snapshot(listening, output.resolve("media-sound.png"), 620, 300);
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
        // Twice. The surfaces size themselves from the stage, which is only measured by the first pass.
        view.layout();
        view.layout();
        WritableImage image = scene.snapshot(null);
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        System.out.println("Wrote " + file.toAbsolutePath());
    }
}
