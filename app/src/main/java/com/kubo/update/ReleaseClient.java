package com.kubo.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.function.DoubleConsumer;

/** Anonymous release checks and bounded, verified downloads. No SSH or session data enters this class. */
public final class ReleaseClient {
    public static final URI LATEST = URI.create("https://api.github.com/repos/Lightminedust/Kubo/releases/latest");
    public static final long MAX_DOWNLOAD = 512L * 1024 * 1024;
    private static final Set<String> HOSTS = Set.of("api.github.com", "github.com", "release-assets.githubusercontent.com",
            "objects.githubusercontent.com", "github-releases.githubusercontent.com");
    @FunctionalInterface public interface Transport { InputStream open(URI uri) throws IOException; }
    private final Transport transport;

    public ReleaseClient() { this(ReleaseClient::open); }
    public ReleaseClient(Transport transport) { this.transport = transport; }

    public Optional<Release> latest() throws IOException {
        try (InputStream input = transport.open(LATEST)) {
            byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
            if (bytes.length > 2 * 1024 * 1024) throw new IOException("The release response is too large");
            return parse(bytes);
        }
    }

    public static Optional<Release> parse(byte[] bytes) throws IOException {
        JsonNode root = new ObjectMapper().readTree(bytes);
        if (root == null || !root.isObject()) throw new IOException("Invalid GitHub release response");
        if (root.path("draft").asBoolean(true) || root.path("prerelease").asBoolean(true)) return Optional.empty();
        String tag = root.path("tag_name").asText();
        var version = Version.parse(tag);
        if (version.isEmpty()) return Optional.empty();
        var assets = new ArrayList<Release.Asset>();
        for (JsonNode entry : root.path("assets")) {
            String name = entry.path("name").asText();
            String digest = entry.path("digest").asText();
            long size = entry.path("size").asLong(-1);
            if (!name.matches("[A-Za-z0-9._-]+") || !digest.matches("sha256:[a-fA-F0-9]{64}")
                    || size <= 0 || size > MAX_DOWNLOAD || !entry.path("state").asText("uploaded").equals("uploaded")) continue;
            URI expected = URI.create(Release.REPOSITORY + "/releases/download/" + tag + "/" + name);
            if (!expected.toString().equals(entry.path("browser_download_url").asText())) continue;
            assets.add(new Release.Asset(name, expected, size, digest.substring(7).toLowerCase(java.util.Locale.ROOT)));
        }
        return Optional.of(new Release(version.get(), tag, java.util.List.copyOf(assets)));
    }

    public Path download(Release.Asset asset, Path directory, DoubleConsumer progress) throws IOException {
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "download-", ".part");
        Path destination = directory.resolve(asset.name());
        MessageDigest digest = sha256();
        long count = 0, lastReport = 0;
        try {
            try (InputStream input = transport.open(asset.url()); var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    interrupted();
                    count += read;
                    if (count > asset.size() || count > MAX_DOWNLOAD) throw new IOException("Download size does not match the release");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                    if (System.nanoTime() - lastReport > 100_000_000L) {
                        progress.accept((double) count / asset.size());
                        lastReport = System.nanoTime();
                    }
                }
            }
            if (count != asset.size() || !HexFormat.of().formatHex(digest.digest()).equals(asset.sha256()))
                throw new IOException("The downloaded file failed its SHA-256 verification. Nothing was installed.");
            Files.move(temporary, destination);
            progress.accept(1);
            return destination;
        } finally { Files.deleteIfExists(temporary); }
    }

    public static void verify(Path file, Release.Asset asset) throws IOException {
        if (Files.size(file) != asset.size()) throw new IOException("The downloaded package has changed");
        MessageDigest digest = sha256();
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) { interrupted(); digest.update(buffer, 0, read); }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(asset.sha256())) throw new IOException("Package checksum mismatch");
    }

    static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Update cancelled");
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    public static boolean allowed(URI uri) {
        return uri != null && "https".equals(uri.getScheme()) && uri.getHost() != null && HOSTS.contains(uri.getHost())
                && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private static InputStream open(URI address) throws IOException {
        URI uri = address;
        long deadline = System.nanoTime() + (address.equals(LATEST) ? 30_000_000_000L : 600_000_000_000L);
        for (int redirects = 0; redirects < 6; redirects++) {
            interrupted();
            if (System.nanoTime() > deadline) throw new IOException("Update request timed out");
            if (!allowed(uri)) throw new IOException("Untrusted update download address");
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Kubo-Updater");
            connection.setRequestProperty("Accept", uri.getHost().equals("api.github.com") ? "application/vnd.github+json" : "application/octet-stream");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing download redirect");
                    uri = uri.resolve(location);
                    connection.disconnect();
                    continue;
                }
                if (status != 200) throw new IOException(status == 403 || status == 429
                        ? "GitHub is limiting requests. Try again later." : "GitHub returned HTTP " + status);
                return new FilterInputStream(connection.getInputStream()) {
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                        interrupted();
                        if (System.nanoTime() > deadline) throw new IOException("Update request timed out");
                        return super.read(bytes, offset, length);
                    }
                    @Override public void close() throws IOException { try { super.close(); } finally { connection.disconnect(); } }
                };
            } catch (IOException | RuntimeException failure) { connection.disconnect(); throw failure; }
        }
        throw new IOException("Too many download redirects");
    }
}
