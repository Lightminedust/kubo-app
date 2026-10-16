package com.kubo.ui.redis;

import com.kubo.KuboApp;
import com.kubo.command.CommandService;
import com.kubo.model.redis.RedisInfo;
import com.kubo.model.redis.RedisKey;
import com.kubo.model.redis.RedisStatus;
import com.kubo.model.redis.RedisStatus.Availability;
import com.kubo.model.redis.RedisStatus.Location;
import com.kubo.model.redis.RedisType;
import com.kubo.model.redis.RedisValue;
import com.kubo.redis.RedisService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

/**
 * Renders the Redis screen with a sample keyspace to PNG files, without a server.
 * Run it with: gradlew previewRedis
 */
public final class RedisPreview {

    private RedisPreview() {
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "build/previews");
        Files.createDirectories(output);

        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                renderString(output);
                renderHash(output);
                renderMulti(output);
                renderCrowded(output);
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

    private static void renderString(Path output) throws IOException {
        RedisScope scope = scope();
        Scene scene = scene(scope);
        RedisKey session = new RedisKey("session:9f2a", RedisType.STRING, 1740);
        scope.showForPreview(info(), keys(), session, RedisValue.string(
                "{\"user\":420,\"role\":\"admin\",\"csrf\":\"a91f...\",\"ip\":\"203.0.113.42\"}", false));
        scope.stack().freeze(2.4);
        write(scene, output, "redis");
        scope.shutdown();
    }

    private static void renderHash(Path output) throws IOException {
        RedisScope scope = scope();
        Scene scene = scene(scope);
        RedisKey profile = new RedisKey("user:420", RedisType.HASH, RedisKey.PERSISTENT);
        scope.showForPreview(info(), keys(), profile, RedisValue.collection(RedisType.HASH, List.of(
                new RedisValue.Entry("name", "Gemmie"),
                new RedisValue.Entry("email", "gemmie@kubo.dev"),
                new RedisValue.Entry("role", "admin"),
                new RedisValue.Entry("created", "2026-09-12"),
                new RedisValue.Entry("logins", "1284")), false));
        scope.stack().freeze(2.4);
        write(scene, output, "redis-hash");
        scope.shutdown();
    }

    private static void renderHelp(Path output) throws IOException {
        RedisScope scope = scope();
        StackPane holder = new StackPane(scope, new RedisHelp(() -> {
        }));
        Scene scene = new Scene(holder, 1400, 860, Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);
        scope.showForPreview(info(), keys(), null, null);
        scope.stack().freeze(2.0);
        write(scene, output, "redis-help");
        scope.shutdown();
    }

    private static void renderMulti(Path output) throws IOException {
        RedisStatus first = new RedisStatus(Availability.USABLE, Location.DOCKER, "gemmie-cache", "7.2.4");
        RedisStatus second = new RedisStatus(Availability.USABLE, Location.DOCKER, "jobs-redis", "7.4.7");
        RedisScope scope = new RedisScope(List.of(
                new RedisService.Detected(null, first), new RedisService.Detected(null, second)),
                null, new CommandService());
        Scene scene = scene(scope);
        scope.showForPreview(info(), moduleKeys(), null, null);
        scope.stack().fit();
        scope.stack().freeze(2.7);
        write(scene, output, "redis-multi");
        scope.shutdown();
    }

    private static RedisScope scope() {
        return new RedisScope(new RedisStatus(Availability.USABLE, Location.DOCKER, "gemmie-cache", "7.2.4"));
    }

    private static Scene scene(RedisScope scope) {
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

    private static RedisInfo info() {
        return new RedisInfo("7.2.4", 259200, "32.7M", "48.1M", 12, 1450,
                List.of(new RedisInfo.Db(0, 1420, 340), new RedisInfo.Db(1, 12, 3)));
    }

    /**
     * A keyspace big enough to be the real problem: thousands of keys on a handful of plates.
     */
    private static void renderCrowded(Path output) throws IOException {
        RedisScope scope = scope();
        Scene scene = scene(scope);
        scope.showForPreview(info(), crowdedKeys(), null, null);
        scope.stack().freeze(3.1);
        write(scene, output, "redis-crowded");
        scope.shutdown();
    }

    private static List<RedisKey> crowdedKeys() {
        String[] prefixes = {"session:", "user:", "cart:", "cache:", "queue:", "lock:", "rate:", "feed:"};
        List<RedisKey> keys = new ArrayList<>();
        Random random = new Random(19);
        for (int i = 0; i < 2400; i++) {
            RedisType type = switch (i % 5) {
                case 0, 1, 2 -> RedisType.STRING;
                case 3 -> RedisType.HASH;
                default -> RedisType.ZSET;
            };
            long ttl = i % 4 == 0 ? RedisKey.PERSISTENT : random.nextInt(9000);
            keys.add(new RedisKey(prefixes[i % prefixes.length] + Integer.toString(100000 + i * 31, 36), type, ttl));
        }
        return keys;
    }

    private static List<RedisKey> keys() {
        RedisType[] types = RedisType.values();
        String[] prefixes = {"session:", "user:", "cart:", "cache:", "queue:", "lock:", "rate:", "feed:", "job:", "tag:"};
        List<RedisKey> keys = new ArrayList<>();
        Random random = new Random(7);
        for (int i = 0; i < 48; i++) {
            RedisType type = switch (i % 6) {
                case 0 -> RedisType.STRING;
                case 1 -> RedisType.HASH;
                case 2 -> RedisType.LIST;
                case 3 -> RedisType.SET;
                case 4 -> RedisType.ZSET;
                default -> RedisType.STREAM;
            };
            long ttl = i % 3 == 0 ? RedisKey.PERSISTENT : random.nextInt(6000);
            keys.add(new RedisKey(prefixes[i % prefixes.length] + Integer.toString(1000 + i * 37, 36), type, ttl));
        }
        // The inspected examples must also exist in the navigable stack, exactly as they do in the live screen.
        keys.set(0, new RedisKey("session:9f2a", RedisType.STRING, 1740));
        keys.set(1, new RedisKey("user:420", RedisType.HASH, RedisKey.PERSISTENT));
        return keys;
    }

    /**
     * A keyspace that also holds values a Redis module owns, which the screen names rather than calling "other".
     */
    private static List<RedisKey> moduleKeys() {
        List<RedisKey> keys = new ArrayList<>(keys());
        for (int i = 0; i < 9; i++) {
            keys.add(new RedisKey("prod:69e896b3999d96df1f12e6d" + i, RedisType.OTHER,
                    i % 2 == 0 ? RedisKey.PERSISTENT : 2_400_000 + i, "ReJSON-RL"));
        }
        return keys;
    }
}
