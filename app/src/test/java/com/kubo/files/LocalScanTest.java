package com.kubo.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalScanTest {

    @TempDir
    Path temp;

    @Test
    void folderListsItselfFirstThenItsContent() throws IOException {
        Path site = Files.createDirectories(temp.resolve("site"));
        Files.writeString(site.resolve("index.html"), "<html>");
        Path assets = Files.createDirectories(site.resolve("assets"));
        Files.write(assets.resolve("logo.png"), new byte[100]);

        LocalScan scan = LocalScan.of(site);
        List<String> relative = scan.items().stream().map(LocalScan.Item::relative).toList();

        assertTrue(scan.isDirectory());
        assertEquals("site", scan.name());
        assertEquals("site", relative.get(0));
        assertEquals(4, relative.size());
        assertTrue(relative.contains("site/assets/logo.png"));
        assertTrue(relative.indexOf("site/assets") < relative.indexOf("site/assets/logo.png"));
        assertEquals(106, scan.totalBytes());
        assertEquals(0, scan.skipped());
    }

    @Test
    void singleFileIsItsOwnScan() throws IOException {
        Path notes = Files.writeString(temp.resolve("notes.md"), "# notes");

        LocalScan scan = LocalScan.of(notes);

        assertFalse(scan.isDirectory());
        assertEquals(1, scan.items().size());
        assertEquals("notes.md", scan.items().get(0).relative());
        assertEquals(7, scan.totalBytes());
    }

    @Test
    void tarSizeMatchesWhatTheWriterProduces() throws IOException {
        Path site = Files.createDirectories(temp.resolve("site"));
        Files.write(site.resolve("a.bin"), new byte[700]);
        Files.createDirectories(site.resolve("empty"));

        LocalScan scan = LocalScan.of(site);
        var out = new java.io.ByteArrayOutputStream();
        TarWriter tar = new TarWriter(out);
        for (LocalScan.Item item : scan.items()) {
            if (item.directory()) {
                tar.directory(LocalScan.tarPath(item), item.modified());
            } else {
                tar.file(LocalScan.tarPath(item), item.size(), item.modified(), Files.newInputStream(item.path()));
            }
        }
        tar.finish();

        assertEquals(scan.tarBytes(), out.size());
    }

    @Test
    void missingPathCannotBeScanned() {
        assertThrows(IOException.class, () -> LocalScan.of(temp.resolve("missing")));
    }
}
