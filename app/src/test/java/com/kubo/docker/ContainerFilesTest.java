package com.kubo.docker;

import com.kubo.model.files.RemoteEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Looking inside a container has to work on whatever image someone built, and it must never write.
 */
class ContainerFilesTest {

    private static final String LISTING = """
            total 32
            drwxr-xr-x    1 root     root          4096 Sep 16 09:12 .
            drwxr-xr-x    1 root     root          4096 Sep 16 09:12 ..
            -rw-r--r--    1 root     root           612 Sep 16 09:12 nginx.conf
            drwxr-xr-x    2 root     root          4096 Sep 16 09:12 conf.d
            lrwxrwxrwx    1 root     root            11 Sep 16 09:12 access.log -> /dev/stdout
            -rw-r--r--    1 www-data www-data     20480 Sep 16 09:12 my report.txt
            crw-rw-rw-    1 root     root      1,   3 Sep 16 09:12 null
            """;

    @Test
    @DisplayName("a BusyBox or GNU listing gives the same entries, folders first")
    void listingIsRead() {
        ContainerFiles.Listing listing = ContainerFiles.parse("/etc/nginx", LISTING);

        assertFalse(listing.failed());
        assertEquals(5, listing.entries().size(), "the . and .. rows are dropped");
        assertEquals("conf.d", listing.entries().get(0).name(), "folders come first");
        assertTrue(listing.entries().stream().anyMatch(entry -> entry.name().equals("nginx.conf")
                && entry.sizeBytes() == 612 && entry.type() == RemoteEntry.Type.FILE));
    }

    @Test
    @DisplayName("a name with spaces in it stays whole")
    void namesWithSpacesSurvive() {
        ContainerFiles.Listing listing = ContainerFiles.parse("/etc/nginx", LISTING);
        RemoteEntry report = listing.entries().stream()
                .filter(entry -> entry.name().equals("my report.txt")).findFirst().orElseThrow();

        assertEquals(20480, report.sizeBytes());
        assertEquals("/etc/nginx/my report.txt", report.path());
        assertEquals("www-data", report.owner());
    }

    @Test
    @DisplayName("a link keeps its target, and a device file does not break the reading")
    void linksAndDevices() {
        ContainerFiles.Listing listing = ContainerFiles.parse("/etc/nginx", LISTING);

        RemoteEntry link = listing.entries().stream()
                .filter(entry -> entry.name().equals("access.log")).findFirst().orElseThrow();
        assertEquals(RemoteEntry.Type.LINK, link.type());
        assertEquals("/dev/stdout", link.linkTarget());

        RemoteEntry device = listing.entries().stream()
                .filter(entry -> entry.name().equals("null")).findFirst().orElseThrow();
        assertEquals(RemoteEntry.Type.OTHER, device.type());
        assertEquals(0, device.sizeBytes(), "a device prints major, minor where a size would be");
    }

    @Test
    @DisplayName("a container with no shell says so instead of showing an empty folder")
    void aContainerWithoutAShell() {
        ContainerFiles.Listing listing = ContainerFiles.parse("/", ContainerFiles.NO_SHELL);

        assertTrue(listing.failed());
        assertTrue(listing.problem().toLowerCase().contains("no shell"), listing.problem());
        assertTrue(listing.entries().isEmpty());
    }

    @Test
    @DisplayName("a folder that cannot be opened says so instead of showing an empty folder")
    void aFolderThatCannotBeOpened() {
        ContainerFiles.Listing listing = ContainerFiles.parse("/root", ContainerFiles.DENIED);
        assertTrue(listing.failed());
        assertTrue(listing.entries().isEmpty());
    }

    @Test
    @DisplayName("the path is built from the folder that was listed, root included")
    void pathsAreJoinedProperly() {
        assertEquals("/etc/nginx.conf", ContainerFiles.join("/etc", "nginx.conf"));
        assertEquals("/nginx.conf", ContainerFiles.join("/", "nginx.conf"));
        assertEquals("/a/b/c", ContainerFiles.join("/a/b/", "c"));
    }

    @Test
    @DisplayName("every command sent into a container only reads")
    void nothingEverWrites() {
        String list = ContainerFiles.list("abc", "/etc");
        String read = ContainerFiles.read("abc", "/etc/hosts");

        for (String command : new String[]{list, read}) {
            assertTrue(command.startsWith("docker exec "), command);
            for (String writing : new String[]{" rm ", " mv ", " cp ", " chmod ", " chown ", " tee ", " dd "}) {
                assertFalse(command.contains(writing), command + " must not contain " + writing);
            }
            // The only redirection allowed throws an error message away; nothing is ever redirected into a file.
            assertEquals(command.split(">", -1).length - 1, command.split(">/dev/null", -1).length - 1,
                    "the only redirect may be to /dev/null: " + command);
        }
        assertTrue(list.contains("ls -la"), list);
        assertTrue(read.contains("head -c"), "a file read has to be capped");
    }

    @Test
    @DisplayName("a path a user clicked cannot become part of the command")
    void pathsAreQuoted() {
        String command = ContainerFiles.list("abc", "/etc'; rm -rf /; echo '");

        // The text is still in there, but only inside quotes: what matters is that it cannot close them. Correct
        // single-quoting always leaves an even number of quote characters behind.
        assertEquals(0, command.chars().filter(c -> c == '\'').count() % 2,
                "the quoting is unbalanced, so the path could escape it: " + command);
        assertTrue(command.endsWith("printf '@@NOSHELL@@'"), command);
    }

    @Test
    @DisplayName("only small text-looking files are offered for viewing")
    void whatIsWorthOpening() {
        assertTrue(ContainerFiles.viewable(file("nginx.conf", 600)));
        assertTrue(ContainerFiles.viewable(file("Dockerfile", 200)));
        assertFalse(ContainerFiles.viewable(file("libc.so", 900)), "a binary is not text");
        assertFalse(ContainerFiles.viewable(file("huge.log", ContainerFiles.MAX_VIEW_BYTES + 1)));
        assertFalse(ContainerFiles.viewable(new RemoteEntry("conf.d", "/etc/conf.d", RemoteEntry.Type.DIRECTORY,
                4096, java.time.Instant.EPOCH, "drwxr-xr-x", "root", "root", "", RemoteEntry.UNKNOWN, true)));
    }

    private static RemoteEntry file(String name, long size) {
        return new RemoteEntry(name, "/etc/" + name, RemoteEntry.Type.FILE, size, java.time.Instant.EPOCH,
                "-rw-r--r--", "root", "root", "", RemoteEntry.UNKNOWN, true);
    }
}
