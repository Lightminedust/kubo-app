package com.kubo.files;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TarWriterTest {

    private static final Instant MODIFIED = Instant.parse("2026-09-15T10:00:00Z");

    @Test
    void fileHeaderFollowsUstar() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TarWriter tar = new TarWriter(out);
        byte[] content = "hello\n".getBytes(StandardCharsets.UTF_8);

        tar.file("docs/hello.txt", content.length, MODIFIED, new ByteArrayInputStream(content));
        byte[] bytes = out.toByteArray();

        assertEquals(1024, bytes.length);
        assertEquals("docs/hello.txt", text(bytes, 0, 100));
        assertEquals("0000644", text(bytes, 100, 8));
        assertEquals("00000000006", text(bytes, 124, 12));
        assertEquals(Long.toOctalString(MODIFIED.getEpochSecond()), text(bytes, 136, 12).replaceFirst("^0+", ""));
        assertEquals('0', bytes[156]);
        assertEquals("ustar", text(bytes, 257, 6));
        assertEquals("hello\n", text(bytes, 512, 512));
    }

    @Test
    void checksumCountsTheHeaderWithItsFieldAsSpaces() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new TarWriter(out).directory("docs/", MODIFIED);
        byte[] header = Arrays.copyOf(out.toByteArray(), 512);

        long stored = Long.parseLong(text(header, 148, 7), 8);
        Arrays.fill(header, 148, 156, (byte) ' ');
        long sum = 0;
        for (byte b : header) {
            sum += b & 0xff;
        }

        assertEquals(sum, stored);
        assertEquals('5', header[156]);
    }

    @Test
    void longOrAccentedNamesTravelInAPaxRecord() throws IOException {
        String path = "projets/été/" + "x".repeat(120) + ".txt";
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        new TarWriter(out).file(path, 0, MODIFIED, new ByteArrayInputStream(new byte[0]));
        byte[] bytes = out.toByteArray();
        String record = text(bytes, 512, 512);

        assertEquals('x', bytes[156]);
        assertTrue(record.endsWith(" path=" + path + "\n"));
        int length = Integer.parseInt(record.substring(0, record.indexOf(' ')));
        assertEquals(record.getBytes(StandardCharsets.UTF_8).length, length);
        assertEquals('0', bytes[1024 + 156]);
        assertEquals(TarWriter.entryBytes(path, 0), bytes.length);
    }

    @Test
    void sizesArePredictedExactly() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TarWriter tar = new TarWriter(out);
        byte[] content = new byte[1300];

        tar.directory("a/", MODIFIED);
        tar.file("a/b.bin", content.length, MODIFIED, new ByteArrayInputStream(content));
        tar.finish();

        long expected = TarWriter.entryBytes("a/", 0) + TarWriter.entryBytes("a/b.bin", 1300) + TarWriter.END_BYTES;
        assertEquals(expected, out.size());
        assertEquals(expected, tar.written());
        assertEquals(0, out.size() % 512);
    }

    @Test
    void plainShortNamesNeedNoPax() {
        assertFalse(TarWriter.needsPax("a/b.txt", 10));
        assertTrue(TarWriter.needsPax("é.txt", 10));
        assertTrue(TarWriter.needsPax("a".repeat(100), 10));
    }

    @Test
    void fileShorterThanAnnouncedIsAnError() {
        TarWriter tar = new TarWriter(new ByteArrayOutputStream());

        assertThrows(IOException.class, () -> tar.file("a.txt", 10, MODIFIED, new ByteArrayInputStream(new byte[3])));
    }

    private static String text(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }
}
