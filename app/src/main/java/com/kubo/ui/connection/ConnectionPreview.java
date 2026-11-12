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
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Platform.startup(() -> {
            try {
                render(output, "connection-idle", ConnectionLink.Phase.IDLE, false);
                render(output, "connection-connecting", ConnectionLink.Phase.CONNECTING, false);
                render(output, "connection-host-key", ConnectionLink.Phase.HOST_KEY, true);
                render(output, "connection-connected", ConnectionLink.Phase.CONNECTED, true);
                render(output, "connection-failed", ConnectionLink.Phase.FAILED, false);
                render(output, "connection-small", ConnectionLink.Phase.IDLE, false, 1100, 640);
                render(output, "connection-small-key", ConnectionLink.Phase.IDLE, true, 1100, 640);
                SessionStore one = sampleStore();
                one.list().stream().skip(1).forEach(session -> {
                    try { one.delete(session.id()); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                });
                render(output, "connection-one-session", ConnectionLink.Phase.IDLE, false, 1280, 800, one, false);
                render(output, "connection-laptop", ConnectionLink.Phase.IDLE, false, 1366, 730, one, false);
                render(output, "connection-wide", ConnectionLink.Phase.IDLE, false, 1920, 1080, one, false);
                for (SavedSession session : one.list()) one.delete(session.id());
                render(output, "connection-empty", ConnectionLink.Phase.IDLE, false, 1100, 640, one, false);
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
        if (failure.get() != null) throw new IllegalStateException("Connection preview failed", failure.get());
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
        Path exampleKey = file.resolveSibling("sshKey.ppk");
        Files.writeString(exampleKey, "Preview placeholder, not an SSH key.");
        store.remember(SshTarget.parse("xana@203.0.113.42"), SavedSession.Auth.KEY,
                exampleKey.toString(), true, "sample".toCharArray());
        return store;
    }

    private static void render(Path output, String name, ConnectionLink.Phase state, boolean useKey) throws IOException {
        render(output, name, state, useKey, KuboApp.WIDTH, KuboApp.HEIGHT);
    }

    private static void render(Path output, String name, ConnectionLink.Phase state, boolean useKey,
                               int width, int height) throws IOException {
        render(output, name, state, useKey, width, height, sampleStore());
    }

    private static void render(Path output, String name, ConnectionLink.Phase state, boolean useKey,
                               int width, int height, SessionStore store) throws IOException {
        render(output, name, state, useKey, width, height, store, true);
    }

    private static void render(Path output, String name, ConnectionLink.Phase state, boolean useKey,
                               int width, int height, SessionStore store, boolean fillForm) throws IOException {
        ConnectionView view = new ConnectionView(store);
        Scene scene = new Scene(view, width, height, Color.web("#0a0a0f"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        if (fillForm) view.showPreview(state, useKey);

        WritableImage image = scene.snapshot(null);
        Path file = output.resolve(name + ".png");
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        view.shutdown();
        System.out.println("Wrote " + file.toAbsolutePath());
    }
}
