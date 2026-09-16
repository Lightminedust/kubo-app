package com.kubo.ui.connection;

import com.kubo.KuboApp;
import com.kubo.connection.SavedSession;
import com.kubo.connection.SessionStore;
import com.kubo.connection.ssh.SshTarget;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;

/**
 * Renders the connection screen in each state to PNG files, without a server.
 * Run it with: gradlew previewConnection
 */
public final class ConnectionPreview {

    private ConnectionPreview() {
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "build/previews");
        Files.createDirectories(output);

        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                render(output, "connection-idle", ParticleField.State.IDLE, false);
                render(output, "connection-host-key", ParticleField.State.AWAITING_HOST_KEY, true);
                render(output, "connection-connected", ParticleField.State.CONNECTED, true);
                render(output, "connection-failed", ParticleField.State.FAILED, false);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
    }

    /**
     * Sample saved sessions in a temporary file; secrets are only pretended to be protected.
     */
    private static SessionStore sampleStore() throws IOException {
        Path file = Files.createTempDirectory("kubo-preview").resolve("sessions.json");
        SessionStore.Protection pretend = new SessionStore.Protection() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String protect(char[] secret) {
                return "preview";
            }

            @Override
            public char[] unprotect(String protectedSecret) {
                return new char[0];
            }
        };
        long[] now = {Instant.parse("2026-09-13T08:00:00Z").toEpochMilli()};
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                now[0] += 7_200_000;
                return Instant.ofEpochMilli(now[0]);
            }
        };
        SessionStore store = new SessionStore(file, pretend, clock);
        store.remember(SshTarget.parse("deploy@203.0.113.10:2222"), SavedSession.Auth.PASSWORD, null, true,
                "sample".toCharArray());
        store.remember(SshTarget.parse("root@198.51.100.7"), SavedSession.Auth.KEY, "C:\\old\\missing.ppk", false, null);
        store.remember(SshTarget.parse("xana@173.212.254.237"), SavedSession.Auth.KEY,
                Path.of(System.getProperty("user.home")).toString(), false, null);
        return store;
    }

    private static void render(Path output, String name, ParticleField.State state, boolean useKey) throws IOException {
        ConnectionView view = new ConnectionView(sampleStore());
        Scene scene = new Scene(view, KuboApp.WIDTH, KuboApp.HEIGHT, Color.web("#0a0a0f"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        view.showPreview(state, useKey);

        WritableImage image = scene.snapshot(null);
        Path file = output.resolve(name + ".png");
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        view.shutdown();
        System.out.println("Wrote " + file.toAbsolutePath());
    }
}
