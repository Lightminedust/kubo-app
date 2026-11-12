package com.kubo.update;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class PortableArchiveTest {
    @TempDir Path temporary;
    static Map<String,String> files(boolean windows) {
        Map<String,String> files = new LinkedHashMap<>();
        files.put(windows ? "Kubo/Kubo.exe" : "Kubo/bin/Kubo", "new executable");
        files.put(windows ? "Kubo/app/Kubo.cfg" : "Kubo/lib/app/Kubo.cfg", "package config");
        files.put(windows ? "Kubo/runtime/release" : "Kubo/lib/runtime/release", "runtime metadata");
        return files;
    }
    static Path zip(Path output, Map<String,String> files) throws IOException {
        try (var zip = new ZipArchiveOutputStream(output)) {
            for(var item : files.entrySet()) {
                ZipArchiveEntry entry = new ZipArchiveEntry(item.getKey());
                zip.putArchiveEntry(entry); zip.write(item.getValue().getBytes()); zip.closeArchiveEntry();
            }
        }
        return output;
    }
    static Path tar(Path output, Map<String,String> files) throws IOException {
        try (var tar = new TarArchiveOutputStream(new GZIPOutputStream(Files.newOutputStream(output)))) {
            for(var item : files.entrySet()) {
                byte[] bytes = item.getValue().getBytes();
                TarArchiveEntry entry = new TarArchiveEntry(item.getKey());
                entry.setSize(bytes.length); entry.setMode(item.getKey().equals("Kubo/bin/Kubo") ? 0755 : 0644);
                tar.putArchiveEntry(entry); tar.write(bytes); tar.closeArchiveEntry();
            }
        }
        return output;
    }
    @Test void extractsBothPortableFormatsAndExecutablePermissions() throws Exception {
        Path zip = zip(temporary.resolve("package.zip"),files(true));
        Path windows = PortableArchive.extract(zip,PackageKind.ZIP,temporary.resolve("windows"));
        assertEquals("new executable",Files.readString(windows.resolve("Kubo.exe")));
        Path tar = tar(temporary.resolve("package.tar.gz"),files(false));
        Path linux = PortableArchive.extract(tar,PackageKind.TAR,temporary.resolve("linux"));
        assertEquals("new executable",Files.readString(linux.resolve("bin/Kubo")));
        if(Files.getFileStore(linux).supportsFileAttributeView("posix"))
            assertTrue(Files.getPosixFilePermissions(linux.resolve("bin/Kubo")).contains(PosixFilePermission.OWNER_EXECUTE));
    }
    @Test void rejectsTraversalAndUnexpectedRootsInBothFormats() throws Exception {
        int i=0;
        for(String name : new String[]{"Kubo/../../escape", "/absolute", "other/Kubo.exe", "Kubo/C:escape", "Kubo/..\\escape"}) {
            Path zip = zip(temporary.resolve("bad" + i + ".zip"),Map.of(name,"bad"));
            Path target = temporary.resolve("out" + i);
            assertThrows(IOException.class, () -> PortableArchive.extract(zip,PackageKind.ZIP,target));
            Path tar = tar(temporary.resolve("bad" + i + ".tar.gz"),Map.of(name,"bad"));
            assertThrows(IOException.class, () -> PortableArchive.extract(tar,PackageKind.TAR,target));
            i++;
        }
        assertFalse(Files.exists(temporary.resolve("escape")));
    }
    @Test void rejectsLinksFromZipCentralDirectoryAndTarHeaders() throws Exception {
        Path zipFile = temporary.resolve("link.zip");
        try(var zip = new ZipArchiveOutputStream(zipFile)) {
            ZipArchiveEntry link = new ZipArchiveEntry("Kubo/link"); link.setUnixMode(0120777);
            zip.putArchiveEntry(link); zip.write("../../escape".getBytes()); zip.closeArchiveEntry();
        }
        assertThrows(IOException.class, () -> PortableArchive.extract(zipFile,PackageKind.ZIP,temporary.resolve("zip")));
        for(byte type : new byte[]{'1','2','3','6'}) {
            Path tarFile = temporary.resolve("link" + type + ".tar.gz");
            try(var tar = new TarArchiveOutputStream(new GZIPOutputStream(Files.newOutputStream(tarFile)))) {
                TarArchiveEntry link = new TarArchiveEntry("Kubo/link",type); link.setLinkName("../../escape");
                tar.putArchiveEntry(link); tar.closeArchiveEntry();
            }
            assertThrows(IOException.class, () -> PortableArchive.extract(tarFile,PackageKind.TAR,temporary.resolve("tar" + type)));
        }
    }
    @Test void rejectsIncompleteAppAndNeverOverwritesAnExistingFile() throws Exception {
        Path archive = zip(temporary.resolve("incomplete.zip"),Map.of("Kubo/Kubo.exe","partial"));
        assertThrows(IOException.class, () -> PortableArchive.extract(archive,PackageKind.ZIP,temporary.resolve("incomplete")));
        Path output = temporary.resolve("existing"); Files.createDirectories(output.resolve("Kubo"));
        Files.writeString(output.resolve("Kubo/Kubo.exe"),"existing");
        Path complete = zip(temporary.resolve("complete.zip"),files(true));
        assertThrows(IOException.class, () -> PortableArchive.extract(complete,PackageKind.ZIP,output));
        assertEquals("existing",Files.readString(output.resolve("Kubo/Kubo.exe")));
    }
}
