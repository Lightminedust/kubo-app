package com.kubo.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadCommandsTest {

    @TempDir
    Path temp;

    @Test
    void fileAnnouncesItselfOnStderrAndSendsOnlyItsContent() {
        String command = DownloadCommands.file("/home/xana/it's.txt");

        assertTrue(command.startsWith("P='/home/xana/it'\\''s.txt'; "));
        assertTrue(command.contains("' >&2; exit 3; fi; printf '@@FILE %s\\n'"));
        assertTrue(command.endsWith("cat -- \"$P\""));
    }

    @Test
    void folderIsTarredFromItsParentUnderItsOwnName() {
        String command = DownloadCommands.folder("/home/xana/site");

        assertTrue(command.endsWith("cd -- \"$(dirname -- \"$P\")\" && tar --format=posix -c -f - -- \"$(basename -- \"$P\")\""));
        assertThrows(IllegalArgumentException.class, () -> DownloadCommands.folder("/"));
    }

    @Test
    void announcementIsReadAndLeftOutOfTheProblems() {
        String error = "@@FILE 1234 1726400000\ncat: read error\n";

        assertEquals(new DownloadCommands.Announced(1234, 1726400000), DownloadCommands.announced(error).orElseThrow());
        assertEquals("cat: read error", DownloadCommands.problems(error));
        assertTrue(DownloadCommands.announced("nothing").isEmpty());
    }

    @Test
    void windowsNamesAreMadeSafe() {
        assertEquals("notes.md", LocalNames.sanitize("notes.md"));
        assertEquals("a_b_c_.txt", LocalNames.sanitize("a:b|c?.txt"));
        assertEquals("trailing_", LocalNames.sanitize("trailing."));
        assertEquals("_aux.txt", LocalNames.sanitize("aux.txt"));
        assertEquals("_NUL", LocalNames.sanitize("NUL"));
        assertEquals("console.log", LocalNames.sanitize("console.log"));
        assertEquals("_", LocalNames.sanitize(""));
    }

    @Test
    void freeNameAvoidsWhatIsAlreadyThere() throws IOException {
        Files.writeString(temp.resolve("notes.md"), "1");
        Files.writeString(temp.resolve("notes (2).md"), "2");
        Files.createDirectories(temp.resolve("site"));

        assertEquals("notes (3).md", LocalNames.freeName(temp, "notes.md", false));
        assertEquals("site (2)", LocalNames.freeName(temp, "site", true));
        assertEquals("other.md", LocalNames.freeName(temp, "other.md", false));
    }
}
