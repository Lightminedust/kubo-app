package com.kubo.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class DownloadTest {
    @TempDir Path temporary;
    static Release.Asset asset(byte[] data, String name) throws Exception {
        return new Release.Asset(name, URI.create(Release.REPOSITORY + "/releases/download/v1.10.0/" + name), data.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
    }
    @Test void verifiesBeforePublishingTheDownloadAndClosesStreams() throws Exception {
        byte[] content = "verified download".getBytes();
        AtomicBoolean closed = new AtomicBoolean();
        ReleaseClient client = new ReleaseClient(uri -> new ByteArrayInputStream(content) {
            @Override public void close() throws IOException { closed.set(true); super.close(); }
        });
        double[] progress = {0};
        Path file = client.download(asset(content,"Kubo-Setup.exe"), temporary,v -> progress[0] = v);
        assertArrayEquals(content,Files.readAllBytes(file));
        assertTrue(closed.get()); assertEquals(1,progress[0]);
        ReleaseClient.verify(file,asset(content,"Kubo-Setup.exe"));
    }
    @Test void removesPartialFilesForShortLongOrWrongDownloads() throws Exception {
        byte[] expected = "expected".getBytes();
        for(String data : new String[]{"short", "way too long", "tampered"}) {
            ReleaseClient client = new ReleaseClient(uri -> new ByteArrayInputStream(data.getBytes()));
            assertThrows(IOException.class, () -> client.download(asset(expected,"Kubo-Setup.exe"), temporary, v -> {}));
            try(var files = Files.list(temporary)) { assertEquals(0, files.count()); }
        }
    }
    @Test void rejectsAFileModifiedAfterDownload() throws Exception {
        byte[] bytes = "original".getBytes();
        Path file = Files.writeString(temporary.resolve("download.exe"), "modified");
        assertThrows(IOException.class, () -> ReleaseClient.verify(file,asset(bytes,"Kubo-Setup.exe")));
    }
    @Test void cancellingLeavesNoInstallableFile() throws Exception {
        byte[] bytes = new byte[200_000];
        ReleaseClient client = new ReleaseClient(uri -> new ByteArrayInputStream(bytes));
        try {
            assertThrows(IOException.class, () -> client.download(asset(bytes,"Kubo-Setup.exe"), temporary,
                    progress -> Thread.currentThread().interrupt()));
        } finally { Thread.interrupted(); }
        try(var files = Files.list(temporary)) { assertEquals(0,files.count()); }
    }
    @Test void metadataIsBoundedAndNetworkFailurePropagates() {
        ReleaseClient huge = new ReleaseClient(uri -> new ByteArrayInputStream(new byte[2 * 1024 * 1024 + 1]));
        assertThrows(IOException.class, huge::latest);
        assertThrows(IOException.class, () -> new ReleaseClient(uri -> { throw new IOException("offline"); }).latest());
    }
    @Test void invalidAssetNamesCannotEscapeTheCache() {
        assertThrows(IllegalArgumentException.class, () -> new Release.Asset("../escape.exe", URI.create("https://github.com/file"), 4,"a".repeat(64)));
    }
    @Test void updatePreferenceSurvivesRestart() throws Exception {
        UpdateSettings settings = new UpdateSettings(temporary.resolve("settings.properties"));
        assertTrue(settings.automatic());
        settings.automatic(false); assertFalse(new UpdateSettings(temporary.resolve("settings.properties")).automatic());
        settings.automatic(true); assertTrue(settings.automatic());
    }
}
