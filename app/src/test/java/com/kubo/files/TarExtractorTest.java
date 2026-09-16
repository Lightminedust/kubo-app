package com.kubo.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TarExtractorTest {

    private static final Instant MODIFIED = Instant.parse("2026-09-01T12:00:00Z");

    @TempDir
    Path temp;

    @Test
    void rebuildsWhatTheWriterWrote() throws IOException {
        byte[] archive = archive(tar -> {
            tar.directory("site/", MODIFIED);
            tar.directory("site/assets/", MODIFIED);
            file(tar, "site/index.html", "<html></html>");
            file(tar, "site/assets/app.js", "console.log(1)");
            file(tar, "site/empty.txt", "");
        });

        TarExtractor extractor = extract(archive, archive.length);

        assertTrue(extractor.finished());
        assertEquals("site", extractor.rootName());
        assertEquals(3, extractor.files());
        assertEquals(2, extractor.directories());
        assertEquals("<html></html>", Files.readString(temp.resolve("site/index.html")));
        assertEquals("console.log(1)", Files.readString(temp.resolve("site/assets/app.js")));
        assertEquals(0, Files.size(temp.resolve("site/empty.txt")));
        assertEquals(MODIFIED.toEpochMilli(), Files.getLastModifiedTime(temp.resolve("site/index.html")).toMillis());
    }

    @Test
    void worksWhateverTheChunksTheStreamArrivesIn() throws IOException {
        byte[] archive = archive(tar -> {
            tar.directory("a/", MODIFIED);
            file(tar, "a/" + "long-name-".repeat(15) + "été.txt", "x".repeat(1500));
        });

        TarExtractor extractor = extract(archive, 7);

        assertTrue(extractor.finished());
        assertEquals(1500, Files.size(temp.resolve("a/" + "long-name-".repeat(15) + "été.txt")));
    }

    @Test
    void incompleteStreamIsNotFinished() throws IOException {
        byte[] archive = archive(tar -> {
            tar.directory("a/", MODIFIED);
            file(tar, "a/b.txt", "hello");
        });

        TarExtractor extractor = extract(Arrays.copyOf(archive, archive.length - 700), archive.length);

        assertFalse(extractor.finished());
    }

    @Test
    void namesClimbingOutOrLeavingTheRootAreSkipped() throws IOException {
        byte[] archive = archive(tar -> {
            tar.directory("a/", MODIFIED);
            file(tar, "a/../../evil.txt", "no");
            file(tar, "other/evil.txt", "no");
        });

        TarExtractor extractor = extract(archive, archive.length);

        assertEquals(2, extractor.skipped());
        assertFalse(Files.exists(temp.getParent().resolve("evil.txt")));
        assertFalse(Files.exists(temp.resolve("other")));
    }

    @Test
    void namesWindowsRefusesAreMadeSafe() throws IOException {
        byte[] archive = archive(tar -> {
            tar.directory("a/", MODIFIED);
            file(tar, "a/what?.txt", "1");
            file(tar, "a/con.log", "2");
        });

        TarExtractor extractor = extract(archive, archive.length);

        assertEquals(2, extractor.renamed());
        assertTrue(Files.exists(temp.resolve("a/what_.txt")));
        assertTrue(Files.exists(temp.resolve("a/_con.log")));
    }

    @Test
    void damagedHeaderIsRefused() throws IOException {
        byte[] archive = archive(tar -> tar.directory("a/", MODIFIED));
        archive[10] ^= 0x55;

        assertThrows(IOException.class, () -> extract(archive, archive.length));
    }

    private TarExtractor extract(byte[] archive, int chunk) throws IOException {
        TarExtractor extractor = new TarExtractor(temp);
        try (extractor) {
            for (int offset = 0; offset < archive.length; offset += chunk) {
                extractor.write(archive, offset, Math.min(chunk, archive.length - offset));
            }
        }
        return extractor;
    }

    private interface Entries {
        void write(TarWriter tar) throws IOException;
    }

    private static byte[] archive(Entries entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TarWriter tar = new TarWriter(out);
        entries.write(tar);
        tar.finish();
        return out.toByteArray();
    }

    private static void file(TarWriter tar, String path, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        tar.file(path, bytes.length, MODIFIED, new ByteArrayInputStream(bytes));
    }
}
