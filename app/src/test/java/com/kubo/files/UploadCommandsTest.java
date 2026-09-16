package com.kubo.files;

import com.kubo.command.CommandEntry;
import com.kubo.files.UploadCommands.Conflict;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadCommandsTest {

    @Test
    void namesAndFoldersAreQuoted() {
        String command = UploadCommands.file("/home/xana/it's", "$(reboot).txt", 12, Conflict.REFUSE);

        assertTrue(command.startsWith("F='/home/xana/it'\\''s/'; N='$(reboot).txt'; D=\"$F$N\"; "));
    }

    @Test
    void rootFolderGetsNoDoubleSlash() {
        assertTrue(UploadCommands.file("/", "a.txt", 1, Conflict.REFUSE).startsWith("F='/'; "));
    }

    @Test
    void fileLandsInATemporaryEntryAndMovesOnlyWhenComplete() {
        String command = UploadCommands.file("/srv", "a.txt", 1234, Conflict.REFUSE);

        assertTrue(command.contains("mktemp -p \"$F\" .kubo-upload.XXXXXX"));
        assertTrue(command.contains("!= '1234' ]; then rm -f -- \"$T\"; echo"));
        assertTrue(command.endsWith("mv -T -- \"$T\" \"$D\" && printf '%s' \"$D\""));
        assertTrue(command.indexOf("rm -f") < command.indexOf("Upload incomplete"));
    }

    @Test
    void refuseStopsOnAnExistingName() {
        String command = UploadCommands.file("/srv", "a.txt", 1, Conflict.REFUSE);

        assertTrue(command.contains("[ -e \"$D\" ] || [ -L \"$D\" ]; then"));
        assertTrue(command.contains("exit " + FileCommands.EXISTS_EXIT));
    }

    @Test
    void keepBothLooksForAFreeName() {
        String command = UploadCommands.file("/srv", "notes.md", 1, Conflict.KEEP_BOTH);

        assertTrue(command.contains("B='notes'; E='.md'; I=2; while"));
        assertTrue(command.contains("D=\"$F$B ($I)$E\""));
    }

    @Test
    void replaceNeverReplacesAFolder() {
        assertTrue(UploadCommands.file("/srv", "a", 1, Conflict.REPLACE).contains("[ -d \"$D\" ] && [ ! -L \"$D\" ]"));
        assertThrows(IllegalArgumentException.class, () -> UploadCommands.folder("/srv", "site", 3, Conflict.REPLACE));
    }

    @Test
    void folderIsExtractedAsideAndCountedBeforeMoving() {
        String command = UploadCommands.folder("/srv", "site", 42, Conflict.REFUSE);

        assertTrue(command.contains("mktemp -d -p \"$F\""));
        assertTrue(command.contains("tar -x -f - -C \"$T\" --no-same-owner --no-same-permissions"));
        assertTrue(command.contains("= '42' ]; then mv -T -- \"$T/$N\" \"$D\""));
        assertTrue(command.contains("else rm -rf -- \"$T\"; echo"));
    }

    @Test
    void folderNamesKeepTheirDots() {
        assertArrayEquals(new String[]{"my.site", ""}, UploadCommands.stemAndExtension("my.site", true));
        assertArrayEquals(new String[]{"archive.tar", ".gz"}, UploadCommands.stemAndExtension("archive.tar.gz", false));
        assertArrayEquals(new String[]{".env", ""}, UploadCommands.stemAndExtension(".env", false));
    }

    @Test
    void destinationIsReadFromASuccessfulUpload() {
        CommandEntry done = new CommandEntry(Instant.EPOCH, "x", false, 0, "/srv/notes (2).md", "", Duration.ZERO);
        CommandEntry failed = new CommandEntry(Instant.EPOCH, "x", false, 17, "", "exists", Duration.ZERO);

        assertEquals("/srv/notes (2).md", UploadCommands.parseDestination(done).orElseThrow());
        assertFalse(UploadCommands.parseDestination(failed).isPresent());
    }
}
