package com.kubo.files;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Rebuilds folders and files from a tar stream as it arrives, written to it like to any output stream.
 * Only folders and regular files are created, always inside the destination: links, devices and names trying
 * to climb out ("..") are left out. Names Windows refuses are made safe.
 */
public final class TarExtractor extends OutputStream {

    private static final int BLOCK = 512;

    private enum State { HEADER, FILE, PAX, LONG_NAME, SKIP, PADDING, END }

    private final Path destination;
    private final byte[] header = new byte[BLOCK];
    private int headerFill;
    private State state = State.HEADER;
    private long remaining;
    private long padding;
    private int zeroBlocks;

    private OutputStream file;
    private Path filePath;
    private long fileModified;
    private final ByteArrayOutputStream collected = new ByteArrayOutputStream();

    private String pendingPath;
    private Long pendingSize;
    private Long pendingModified;

    private String rootName;
    private int files;
    private int directories;
    private int skipped;
    private int renamed;
    private long bytes;

    public TarExtractor(Path destination) {
        this.destination = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
    }

    /**
     * True once the archive's closing blocks arrived: the stream was complete.
     */
    public boolean finished() {
        return state == State.END;
    }

    /**
     * The first name of the archive, as created on disk; null before any entry.
     */
    public String rootName() {
        return rootName;
    }

    public int files() {
        return files;
    }

    public int directories() {
        return directories;
    }

    /**
     * Links, special files and unsafe names that were not created.
     */
    public int skipped() {
        return skipped;
    }

    /**
     * Names changed because Windows refuses them.
     */
    public int renamed() {
        return renamed;
    }

    public long bytes() {
        return bytes;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
        int position = offset;
        int end = offset + length;
        while (position < end) {
            switch (state) {
                case END -> {
                    return;
                }
                case HEADER -> {
                    int take = Math.min(BLOCK - headerFill, end - position);
                    System.arraycopy(data, position, header, headerFill, take);
                    headerFill += take;
                    position += take;
                    if (headerFill == BLOCK) {
                        headerFill = 0;
                        readHeader();
                    }
                }
                case FILE, PAX, LONG_NAME, SKIP -> {
                    int take = (int) Math.min(remaining, end - position);
                    if (state == State.FILE) {
                        file.write(data, position, take);
                        bytes += take;
                    } else if (state != State.SKIP) {
                        collected.write(data, position, take);
                    }
                    remaining -= take;
                    position += take;
                    if (remaining == 0) {
                        endData();
                    }
                }
                case PADDING -> {
                    int take = (int) Math.min(padding, end - position);
                    padding -= take;
                    position += take;
                    if (padding == 0) {
                        state = State.HEADER;
                    }
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (file != null) {
            file.close();
            file = null;
        }
    }

    private void readHeader() throws IOException {
        if (isZero(header)) {
            zeroBlocks++;
            if (zeroBlocks >= 2) {
                state = State.END;
            }
            return;
        }
        zeroBlocks = 0;
        if (!checksumMatches()) {
            throw new IOException("The archive is damaged");
        }
        char type = (char) header[156];
        long size = pendingSize != null && (type == '0' || type == '\0' || type == '7') ? pendingSize : number(124, 12);
        String name = text(0, 100);
        if (isUstar()) {
            String prefix = text(345, 155);
            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }
        }
        switch (type) {
            case 'x' -> begin(State.PAX, size);
            case 'L' -> begin(State.LONG_NAME, size);
            case 'g' -> begin(State.SKIP, size);
            default -> {
                String path = pendingPath != null ? pendingPath : name;
                long modified = pendingModified != null ? pendingModified : number(136, 12);
                pendingPath = null;
                pendingSize = null;
                pendingModified = null;
                entry(type, path, size, modified);
            }
        }
    }

    private void entry(char type, String path, long size, long modified) throws IOException {
        Path target = resolve(path);
        boolean regular = type == '0' || type == '\0' || type == '7';
        if (target == null || !(regular || type == '5')) {
            skipped++;
            begin(State.SKIP, regular || type == '5' ? size : Math.max(0, size));
            return;
        }
        if (type == '5') {
            Files.createDirectories(target);
            directories++;
            setModified(target, modified);
            begin(State.SKIP, 0);
            return;
        }
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            // Two names that differ only by case on the server meet on Windows: keep both.
            target = target.resolveSibling(LocalNames.freeName(target.getParent(), target.getFileName().toString(), false));
            renamed++;
        }
        file = new BufferedOutputStream(Files.newOutputStream(target));
        filePath = target;
        fileModified = modified;
        files++;
        begin(State.FILE, size);
    }

    private void begin(State next, long size) throws IOException {
        remaining = size;
        padding = (BLOCK - size % BLOCK) % BLOCK;
        collected.reset();
        state = next;
        if (size == 0) {
            endData();
        }
    }

    private void endData() throws IOException {
        switch (state) {
            case FILE -> {
                file.close();
                file = null;
                setModified(filePath, fileModified);
            }
            case PAX -> readPax(collected.toString(StandardCharsets.UTF_8));
            case LONG_NAME -> pendingPath = collected.toString(StandardCharsets.UTF_8).replace("\0", "");
            default -> {
            }
        }
        state = padding > 0 ? State.PADDING : State.HEADER;
    }

    /**
     * "len key=value\n" records; only path, size and mtime matter here.
     */
    private void readPax(String records) {
        int position = 0;
        while (position < records.length()) {
            int space = records.indexOf(' ', position);
            int newline = records.indexOf('\n', space + 1);
            if (space < 0 || newline < 0) {
                return;
            }
            String record = records.substring(space + 1, newline);
            int equals = record.indexOf('=');
            if (equals > 0) {
                String key = record.substring(0, equals);
                String value = record.substring(equals + 1);
                try {
                    switch (key) {
                        case "path" -> pendingPath = value;
                        case "size" -> pendingSize = Long.parseLong(value);
                        case "mtime" -> pendingModified = (long) Double.parseDouble(value);
                        default -> {
                        }
                    }
                } catch (NumberFormatException ignored) {
                    // A malformed value falls back to the plain header.
                }
            }
            position = newline + 1;
        }
    }

    /**
     * Where an archive name lands, or null when it must not be created.
     */
    private Path resolve(String name) {
        List<String> segments = new ArrayList<>();
        for (String segment : name.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                return null;
            }
            String safe = LocalNames.sanitize(segment);
            if (!safe.equals(segment)) {
                renamed++;
            }
            segments.add(safe);
        }
        if (segments.isEmpty()) {
            return null;
        }
        if (rootName == null) {
            rootName = segments.get(0);
        } else if (!segments.get(0).equals(rootName)) {
            // Everything must stay inside the one folder that was asked for.
            return null;
        }
        Path target = destination;
        for (String segment : segments) {
            target = target.resolve(segment);
        }
        target = target.normalize();
        return target.startsWith(destination) && !target.equals(destination) ? target : null;
    }

    private static void setModified(Path path, long seconds) {
        if (seconds <= 0) {
            return;
        }
        try {
            Files.setLastModifiedTime(path, FileTime.fromMillis(seconds * 1000));
        } catch (IOException ignored) {
            // The content is what matters.
        }
    }

    private boolean checksumMatches() {
        long stored = number(148, 8);
        long unsigned = 0;
        long signed = 0;
        for (int i = 0; i < BLOCK; i++) {
            byte b = i >= 148 && i < 156 ? (byte) ' ' : header[i];
            unsigned += b & 0xff;
            signed += b;
        }
        return stored == unsigned || stored == signed;
    }

    private boolean isUstar() {
        return header[257] == 'u' && header[258] == 's' && header[259] == 't' && header[260] == 'a' && header[261] == 'r';
    }

    private long number(int offset, int length) {
        if ((header[offset] & 0x80) != 0) {
            // Base-256, used for values too large for octal digits.
            long value = header[offset] & 0x7f;
            for (int i = offset + 1; i < offset + length; i++) {
                value = (value << 8) | (header[i] & 0xff);
            }
            return value;
        }
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            byte b = header[i];
            if (b == 0 || b == ' ') {
                if (value > 0) {
                    break;
                }
                continue;
            }
            if (b < '0' || b > '7') {
                break;
            }
            value = value * 8 + (b - '0');
        }
        return value;
    }

    private String text(int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
