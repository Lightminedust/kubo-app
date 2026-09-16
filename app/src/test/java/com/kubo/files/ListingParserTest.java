package com.kubo.files;

import com.kubo.model.files.DirectoryListing;
import com.kubo.model.files.RemoteEntry;
import com.kubo.model.files.RemoteEntry.Type;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListingParserTest {

    private static String record(String type, long size, String modified, String mode, String name, String link) {
        return String.join("\0", type, String.valueOf(size), modified, mode, "xana", "xana", link, name) + "\0";
    }

    private static String output(String entries, String... grandchildren) {
        StringBuilder counts = new StringBuilder();
        for (String grandchild : grandchildren) {
            counts.append(grandchild).append('\0');
        }
        return entries + ListingParser.COUNTS_MARKER + counts;
    }

    @Test
    void readsEntriesWithTheirMetadata() {
        String entries = record("f", 2048, "1726390000.5000000000", "-rw-r--r--", "notes.md", "")
                + record("d", 4096, "1726390100.0000000000", "drwxr-xr-x", "projects", "")
                + record("l", 11, "1726390200.0000000000", "lrwxrwxrwx", "logs", "/var/log/app");

        DirectoryListing listing = ListingParser.parse("/home/xana",
                output(entries, "projects/kubo", "projects/site", "projects/.git"), "");

        assertFalse(listing.failed());
        assertFalse(listing.truncated());
        assertEquals(List.of("projects", "logs", "notes.md"), listing.entries().stream().map(RemoteEntry::name).toList());

        RemoteEntry projects = listing.entries().get(0);
        assertEquals("/home/xana/projects", projects.path());
        assertEquals(Type.DIRECTORY, projects.type());
        assertEquals(3, projects.childCount());
        assertTrue(projects.readable());

        RemoteEntry link = listing.entries().get(1);
        assertEquals(Type.LINK, link.type());
        assertEquals("/var/log/app", link.linkTarget());
        assertEquals(RemoteEntry.UNKNOWN, link.childCount());

        RemoteEntry notes = listing.entries().get(2);
        assertEquals(2048, notes.sizeBytes());
        assertEquals(Instant.ofEpochMilli(1726390000500L), notes.modified());
        assertEquals("-rw-r--r--", notes.permissions());
        assertEquals("xana", notes.owner());
    }

    @Test
    void namesWithSpacesQuotesAndLineBreaksAreKept() {
        String entries = record("f", 1, "1.0", "-rw-r--r--", "it's a \"test\"\nfile.txt", "")
                + record("d", 1, "1.0", "drwxr-xr-x", "my folder", "");

        DirectoryListing listing = ListingParser.parse("/home/xana", output(entries, "my folder/a b"), "");

        assertEquals("my folder", listing.entries().get(0).name());
        assertEquals(1, listing.entries().get(0).childCount());
        assertEquals("it's a \"test\"\nfile.txt", listing.entries().get(1).name());
    }

    @Test
    void foldersTheUserCannotOpenAreMarked() {
        String entries = record("d", 4096, "1.0", "drwx------", "private", "")
                + record("d", 4096, "1.0", "drwxr-xr-x", "public", "");
        String error = "find: ‘/home/xana/private’: Permission denied\n";

        DirectoryListing listing = ListingParser.parse("/home/xana", output(entries), error);

        RemoteEntry locked = listing.entries().get(0);
        assertFalse(locked.readable());
        assertEquals(RemoteEntry.UNKNOWN, locked.childCount());
        assertTrue(listing.entries().get(1).readable());
        assertEquals(0, listing.entries().get(1).childCount());
    }

    @Test
    void deniedPathsAcceptStraightQuotesAndDoubleSlashes() {
        assertEquals(java.util.Set.of("/srv/data"),
                ListingParser.deniedPaths("find: '/srv//data': Permission denied\nfind: '/srv/ok': No such file"));
    }

    @Test
    void unreadableFolderFails() {
        DirectoryListing listing = ListingParser.parse("/root", ListingParser.DENIED_MARKER, "");

        assertTrue(listing.failed());
        assertTrue(listing.entries().isEmpty());
    }

    @Test
    void emptyFolderAndIncompleteRecordsAreHandled() {
        assertTrue(ListingParser.parse("/tmp/empty", output(""), "").entries().isEmpty());

        String cutOff = record("f", 1, "1.0", "-rw-r--r--", "complete.txt", "") + "f\0" + "12\0";
        assertEquals(1, ListingParser.parse("/tmp", output(cutOff), "").entries().size());
    }

    @Test
    void recordsWithBadNumbersAreSkipped() {
        String entries = record("f", 1, "not-a-time", "-rw-r--r--", "broken", "")
                + record("f", 1, "1.0", "-rw-r--r--", "fine", "");

        assertEquals(List.of("fine"),
                ListingParser.parse("/tmp", output(entries), "").entries().stream().map(RemoteEntry::name).toList());
    }

    @Test
    void rootFolderPathsHaveNoDoubleSlash() {
        DirectoryListing listing = ListingParser.parse("/", output(record("d", 1, "1.0", "drwxr-xr-x", "etc", "")), "");

        assertEquals("/etc", listing.entries().get(0).path());
    }

    @Test
    void commandQuotesThePath() {
        String command = ListingParser.command("/home/xana/it's here");

        assertTrue(command.startsWith("P='/home/xana/it'\\''s here'; "));
    }
}
