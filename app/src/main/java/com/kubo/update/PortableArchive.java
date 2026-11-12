package com.kubo.update;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;

/** Extracts only regular files beneath Kubo/. Links, devices and escaping paths are rejected. */
public final class PortableArchive {
    private static final long MAX_UNPACKED = 2L * 1024 * 1024 * 1024;
    private final Path root;
    private int entries;
    private long unpacked;

    private PortableArchive(Path root) { this.root = root.toAbsolutePath().normalize(); }

    public static Path extract(Path archive, PackageKind kind, Path destination) throws IOException {
        PortableArchive reader = new PortableArchive(destination);
        if (kind == PackageKind.ZIP) reader.zip(archive);
        else if (kind == PackageKind.TAR) reader.tar(archive);
        else throw new IOException("This is not a portable package");
        Path app = destination.resolve("Kubo");
        Path launcher = app.resolve(kind == PackageKind.ZIP ? "Kubo.exe" : "bin/Kubo");
        Path config = app.resolve(kind == PackageKind.ZIP ? "app/Kubo.cfg" : "lib/app/Kubo.cfg");
        Path runtime = app.resolve(kind == PackageKind.ZIP ? "runtime" : "lib/runtime");
        if (!Files.isRegularFile(launcher) || !Files.isRegularFile(config) || !Files.isDirectory(runtime))
            throw new IOException("The archive does not contain a complete Kubo application");
        return app;
    }

    private Path target(String name) throws IOException {
        ReleaseClient.interrupted();
        if (++entries > 20_000 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.chars().anyMatch(c -> c < 32)
                || !(name.equals("Kubo") || name.startsWith("Kubo/"))) throw new IOException("Invalid archive entry");
        for (String part : name.split("/")) if (part.equals("..") || part.equals(".")) throw new IOException("Unsafe archive path");
        Path file = root.resolve(name).normalize();
        if (!file.startsWith(root) || file.equals(root)) throw new IOException("Archive path escapes its destination");
        return file;
    }

    private void zip(Path archive) throws IOException {
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            var enumeration = zip.getEntries();
            while (enumeration.hasMoreElements()) {
                ZipArchiveEntry entry = enumeration.nextElement();
                if (entry.isUnixSymlink() || !zip.canReadEntryData(entry)) throw new IOException("Unsupported ZIP entry");
                int type = entry.getUnixMode() & 0170000;
                if (type != 0 && type != 0100000 && type != 0040000) throw new IOException("ZIP contains a special file");
                Path file = target(entry.getName());
                if (entry.isDirectory()) Files.createDirectories(file);
                else try (InputStream input = zip.getInputStream(entry)) {
                    CRC32 checksum = copy(input, file, entry.getSize());
                    if (checksum.getValue() != entry.getCrc()) throw new IOException("Corrupt ZIP entry");
                }
            }
        }
    }

    private void tar(Path archive) throws IOException {
        try (var tar = new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(archive)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.isSymbolicLink() || entry.isLink() || entry.isSparse() || (!entry.isDirectory() && !entry.isFile()))
                    throw new IOException("TAR contains a link or special file");
                Path file = target(entry.getName());
                if (entry.isDirectory()) Files.createDirectories(file);
                else {
                    copy(tar, file, entry.getSize());
                    if (Files.getFileStore(file).supportsFileAttributeView("posix"))
                        Files.setPosixFilePermissions(file, permissions(entry.getMode()));
                }
            }
        }
    }

    private CRC32 copy(InputStream input, Path file, long expected) throws IOException {
        if (expected < 0 || expected > MAX_UNPACKED - unpacked) throw new IOException("Archive exceeds the extraction limit");
        Files.createDirectories(file.getParent());
        CRC32 checksum = new CRC32();
        long count = 0;
        try (var output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                ReleaseClient.interrupted();
                count += read;
                unpacked += read;
                if (count > expected || unpacked > MAX_UNPACKED) throw new IOException("Archive exceeds the extraction limit");
                checksum.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (count != expected) throw new IOException("Truncated archive entry");
        return checksum;
    }

    private static EnumSet<PosixFilePermission> permissions(int mode) {
        var result = EnumSet.noneOf(PosixFilePermission.class);
        PosixFilePermission[] values = PosixFilePermission.values();
        for (int i = 0; i < 9; i++) if ((mode & (1 << (8 - i))) != 0) result.add(values[i]);
        return result;
    }
}
