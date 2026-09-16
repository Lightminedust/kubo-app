package com.kubo.files;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything to send for one local file or folder, read before the upload starts so its exact size is known.
 * Links are not followed and unreadable items are left out; both are counted as skipped.
 *
 * @param items   the root first, each folder before what it holds, paths relative to the root's parent with "/"
 * @param skipped links and unreadable items left out
 */
public record LocalScan(Path root, List<Item> items, long totalBytes, int skipped) {

    public record Item(Path path, String relative, boolean directory, long size, Instant modified) {
    }

    public LocalScan {
        Objects.requireNonNull(root, "root");
        items = List.copyOf(items);
    }

    public boolean isDirectory() {
        return !items.isEmpty() && items.get(0).directory();
    }

    public String name() {
        Path name = root.getFileName();
        return name == null ? root.toString() : name.toString();
    }

    /**
     * Exact number of bytes of the tar stream describing these items.
     */
    public long tarBytes() {
        long bytes = TarWriter.END_BYTES;
        for (Item item : items) {
            bytes += TarWriter.entryBytes(tarPath(item), item.directory() ? 0 : item.size());
        }
        return bytes;
    }

    static String tarPath(Item item) {
        return item.directory() ? item.relative() + "/" : item.relative();
    }

    public static LocalScan of(Path root) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) {
            throw new IOException("Links are not sent: " + absolute);
        }
        String rootName = absolute.getFileName() == null ? "" : absolute.getFileName().toString();
        List<Item> items = new ArrayList<>();
        long[] total = {0};
        int[] skipped = {0};
        Files.walkFileTree(absolute, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    skipped[0]++;
                    return FileVisitResult.SKIP_SUBTREE;
                }
                items.add(new Item(directory, relative(absolute, rootName, directory), true, 0,
                        attributes.lastModifiedTime().toInstant()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!attributes.isRegularFile() || !Files.isReadable(file)) {
                    skipped[0]++;
                    return FileVisitResult.CONTINUE;
                }
                items.add(new Item(file, relative(absolute, rootName, file), false, attributes.size(),
                        attributes.lastModifiedTime().toInstant()));
                total[0] += attributes.size();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                skipped[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        if (items.isEmpty() || !items.get(0).path().equals(absolute)) {
            throw new IOException("Cannot read " + absolute);
        }
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS) && items.size() != 1) {
            throw new IOException("Cannot read " + absolute);
        }
        return new LocalScan(absolute, items, total[0], skipped[0]);
    }

    private static String relative(Path root, String rootName, Path path) {
        String inside = root.relativize(path).toString().replace('\\', '/');
        return inside.isEmpty() ? rootName : rootName + "/" + inside;
    }
}
