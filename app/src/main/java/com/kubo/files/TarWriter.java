package com.kubo.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Writes a POSIX tar stream (ustar, with pax records for long or non-ASCII names), enough for the server's
 * GNU tar to recreate a folder. Owners and modes are not carried: the server applies its own.
 */
public final class TarWriter {

    static final int BLOCK = 512;
    static final long END_BYTES = BLOCK * 2L;
    private static final long MAX_USTAR_SIZE = 077777777777L;

    private final OutputStream out;
    private long written;

    public TarWriter(OutputStream out) {
        this.out = Objects.requireNonNull(out, "out");
    }

    public long written() {
        return written;
    }

    /**
     * @param path relative, "/"-separated, ending with "/"
     */
    public void directory(String path, Instant modified) throws IOException {
        header(path, 0, modified, '5', 0755);
    }

    /**
     * Writes a file whose content must be exactly {@code size} bytes.
     */
    public void file(String path, long size, Instant modified, InputStream content) throws IOException {
        header(path, size, modified, '0', 0644);
        byte[] buffer = new byte[64 * 1024];
        long left = size;
        while (left > 0) {
            int read = content.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (read < 0) {
                throw new IOException("File changed while it was sent: " + path);
            }
            write(buffer, 0, read);
            left -= read;
        }
        pad(size);
    }

    /**
     * Ends the archive with its two empty blocks.
     */
    public void finish() throws IOException {
        write(new byte[(int) END_BYTES], 0, (int) END_BYTES);
        out.flush();
    }

    /**
     * Bytes an entry takes in the stream: its pax record if needed, its header and its padded content.
     */
    static long entryBytes(String path, long size) {
        long bytes = BLOCK + padded(size);
        if (needsPax(path, size)) {
            bytes += BLOCK + padded(paxData(path, size).length);
        }
        return bytes;
    }

    private void header(String path, long size, Instant modified, char type, int mode) throws IOException {
        if (needsPax(path, size)) {
            byte[] data = paxData(path, size);
            write(ustar("PaxHeader/" + asciiName(path), data.length, modified, 'x', 0644), 0, BLOCK);
            write(data, 0, data.length);
            pad(data.length);
        }
        write(ustar(asciiName(path), Math.min(size, MAX_USTAR_SIZE), modified, type, mode), 0, BLOCK);
    }

    private static byte[] ustar(String name, long size, Instant modified, char type, int mode) {
        byte[] header = new byte[BLOCK];
        put(header, 0, 100, name.getBytes(StandardCharsets.US_ASCII));
        octal(header, 100, 8, mode);
        octal(header, 108, 8, 0);
        octal(header, 116, 8, 0);
        octal(header, 124, 12, size);
        octal(header, 136, 12, Math.max(0, modified.getEpochSecond()));
        Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = (byte) type;
        put(header, 257, 6, "ustar\0".getBytes(StandardCharsets.US_ASCII));
        put(header, 263, 2, "00".getBytes(StandardCharsets.US_ASCII));
        long checksum = 0;
        for (byte b : header) {
            checksum += b & 0xff;
        }
        octal(header, 148, 7, checksum);
        header[155] = (byte) ' ';
        return header;
    }

    static boolean needsPax(String path, long size) {
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        return bytes.length > 99 || bytes.length != path.length() || size > MAX_USTAR_SIZE;
    }

    /**
     * "len key=value\n" records, where len counts the whole record including its own digits.
     */
    static byte[] paxData(String path, long size) {
        StringBuilder data = new StringBuilder(record("path", path));
        if (size > MAX_USTAR_SIZE) {
            data.append(record("size", String.valueOf(size)));
        }
        return data.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String record(String key, String value) {
        int body = (" " + key + "=" + value + "\n").getBytes(StandardCharsets.UTF_8).length;
        int length = body + String.valueOf(body).length();
        if (String.valueOf(length).length() != String.valueOf(body).length()) {
            length = body + String.valueOf(length).length();
        }
        return length + " " + key + "=" + value + "\n";
    }

    /**
     * A plain-ASCII stand-in for the name field; the real name travels in the pax record.
     */
    private static String asciiName(String path) {
        StringBuilder ascii = new StringBuilder();
        for (char c : path.toCharArray()) {
            ascii.append(c < 128 ? c : '_');
        }
        return ascii.length() > 99 ? ascii.substring(ascii.length() - 99) : ascii.toString();
    }

    private static long padded(long size) {
        return (size + BLOCK - 1) / BLOCK * BLOCK;
    }

    private void pad(long size) throws IOException {
        int padding = (int) (padded(size) - size);
        if (padding > 0) {
            write(new byte[padding], 0, padding);
        }
    }

    private void write(byte[] bytes, int offset, int length) throws IOException {
        out.write(bytes, offset, length);
        written += length;
    }

    private static void put(byte[] header, int offset, int length, byte[] value) {
        System.arraycopy(value, 0, header, offset, Math.min(length, value.length));
    }

    private static void octal(byte[] header, int offset, int length, long value) {
        String digits = Long.toOctalString(value);
        String padded = "0".repeat(Math.max(0, length - 1 - digits.length())) + digits;
        put(header, offset, length - 1, padded.getBytes(StandardCharsets.US_ASCII));
        header[offset + length - 1] = 0;
    }
}
