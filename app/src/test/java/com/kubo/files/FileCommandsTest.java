package com.kubo.files;

import com.kubo.command.CommandEntry;
import com.kubo.model.files.Account;
import com.kubo.model.files.FileText;
import com.kubo.model.files.PermissionMode;
import com.kubo.model.files.RemoteEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileCommandsTest {

    @ParameterizedTest
    @ValueSource(strings = {"notes.md", ".env", "my file", "été.txt", "a'b", "$(x)"})
    void acceptsOrdinaryNames(String name) {
        assertTrue(FileCommands.nameProblem(name).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " lead", "trail ", ".", "..", "a/b", "line\nbreak", "tab\there"})
    void refusesNamesTheServerWouldMisread(String name) {
        assertTrue(FileCommands.nameProblem(name).isPresent());
    }

    @Test
    void refusesNamesLongerThan255Bytes() {
        assertTrue(FileCommands.nameProblem("é".repeat(128)).isPresent());
        assertTrue(FileCommands.nameProblem("a".repeat(255)).isEmpty());
    }

    @Test
    void aFolderCannotGoInsideItself() {
        assertTrue(FileCommands.destinationProblem("/home/xana/app", "/home/xana/app", false).isPresent());
        assertTrue(FileCommands.destinationProblem("/home/xana/app", "/home/xana/app/src", true).isPresent());
        assertTrue(FileCommands.destinationProblem("/home/xana/app", "/home/xana/apple", true).isEmpty());
    }

    @Test
    void movingIntoTheSameFolderIsPointlessButCopyingIsNot() {
        assertTrue(FileCommands.destinationProblem("/home/xana/notes.md", "/home/xana", true).isPresent());
        assertTrue(FileCommands.destinationProblem("/home/xana/notes.md", "/home/xana", false).isEmpty());
    }

    @Test
    void copyInItsOwnFolderGetsANewName() {
        assertEquals("/home/xana/notes copy.md", FileCommands.destinationPath("/home/xana/notes.md", "/home/xana"));
        assertEquals("/home/xana/app copy", FileCommands.destinationPath("/home/xana/app", "/home/xana"));
        assertEquals("/home/xana/.env copy", FileCommands.destinationPath("/home/xana/.env", "/home/xana"));
        assertEquals("/srv/notes.md", FileCommands.destinationPath("/home/xana/notes.md", "/srv"));
    }

    @Test
    void pathsAreQuotedInEveryCommand() {
        String hostile = "/home/xana/it's $(reboot)";
        RemoteEntry file = entry(hostile, RemoteEntry.Type.FILE);

        for (String command : new String[]{FileCommands.read(hostile), FileCommands.delete(file),
                FileCommands.copy(hostile, "/tmp/x"), FileCommands.move(hostile, "/tmp/x"),
                FileCommands.changeMode(hostile, new PermissionMode(0644)), FileCommands.createFolder(hostile),
                FileCommands.createFile(hostile), FileCommands.write(hostile, 3, Optional.of("1.5:3:9"))}) {
            assertTrue(command.contains(ShellQuote.quote(hostile)), command);
        }
    }

    @Test
    void foldersAreDeletedRecursivelyWithoutLeavingTheirFilesystem() {
        assertEquals("rm -rf --one-file-system -- '/home/xana/build'",
                FileCommands.delete(entry("/home/xana/build", RemoteEntry.Type.DIRECTORY)));
        assertEquals("rm -f -- '/home/xana/logs'", FileCommands.delete(entry("/home/xana/logs", RemoteEntry.Type.LINK)));
    }

    @Test
    void theRootCanNeverBeRemoved() {
        assertThrows(IllegalArgumentException.class, () -> FileCommands.delete(entry("/", RemoteEntry.Type.DIRECTORY)));
        assertThrows(IllegalArgumentException.class, () -> FileCommands.move("/", "/x"));
        assertThrows(IllegalArgumentException.class, () -> FileCommands.delete(entry("relative", RemoteEntry.Type.FILE)));
    }

    @Test
    void copyAndMoveRefuseToOverwrite() {
        String copy = FileCommands.copy("/a", "/b");

        assertTrue(copy.contains("[ -e \"$D\" ]"));
        assertTrue(copy.endsWith("cp -a -T -- \"$S\" \"$D\""));
        assertTrue(FileCommands.move("/a", "/b").endsWith("mv -T -- \"$S\" \"$D\""));
    }

    @Test
    void chmodKeepsSpecialBitsWithFourDigits() {
        assertEquals("chmod 2775 -- '/srv/shared'",
                FileCommands.changeMode("/srv/shared", PermissionMode.parseOctal("2775").orElseThrow()));
    }

    @Test
    void newFileRefusesToReplaceAnExistingOne() {
        assertEquals("set -C; : > '/home/xana/todo.txt'", FileCommands.createFile("/home/xana/todo.txt"));
    }

    @Test
    void saveChecksTheVersionOnlyWhenAsked() {
        String checked = FileCommands.write("/a", 12, Optional.of("1726400000.123456789:12:4242"));
        String forced = FileCommands.write("/a", 12, Optional.empty());

        assertTrue(checked.contains("!= '1726400000.123456789:12:4242' ]; then echo 'Changed"));
        assertFalse(forced.contains("Changed on the server"));
        assertTrue(forced.contains("!= '12' ]"));
    }

    @Test
    void saveNeverTouchesTheFileBeforeTheContentIsComplete() {
        String command = FileCommands.write("/srv/app.conf", 12, Optional.empty());

        int completeCheck = command.indexOf("!= '12' ]");
        assertTrue(completeCheck > command.indexOf("cat > \"$T\""));
        assertTrue(completeCheck < command.indexOf("mv -f -T -- \"$T\" \"$P\""));
        assertTrue(completeCheck < command.indexOf("cat -- \"$T\" > \"$P\""));
    }

    @Test
    void saveSwapsTheFileAtomicallyWhenItsOwnerAndModeCanBeKept() {
        String command = FileCommands.write("/srv/app.conf", 12, Optional.empty());

        assertTrue(command.startsWith("P=$(readlink -f -- '/srv/app.conf') || exit 1; D=$(dirname -- \"$P\"); "));
        assertTrue(command.contains("mktemp -p \"$D\" .kubo-save.XXXXXX"));
        assertTrue(command.contains("[ \"$(stat -c %h -- \"$P\")\" = 1 ]"));
        assertTrue(command.contains("chmod --reference=\"$P\" -- \"$T\""));
        assertTrue(command.contains("[ \"$(stat -c %u:%g -- \"$T\")\" = \"$(stat -c %u:%g -- \"$P\")\" ]"));
    }

    @Test
    void inPlaceSaveKeepsASafetyCopyAndPutsItBack() {
        String command = FileCommands.write("/srv/app.conf", 12, Optional.empty());

        int backup = command.indexOf("cp -- \"$P\" \"$B\"");
        int overwrite = command.indexOf("if ! cat -- \"$T\" > \"$P\"");
        assertTrue(backup > 0 && backup < overwrite);
        assertTrue(command.indexOf("cat -- \"$B\" > \"$P\"") > overwrite);
    }

    @Test
    void readsTextWithItsVersionAndWriteAccess() {
        FileText text = FileCommands.parseRead("/a", success("1726400000.5:12:4242 w\nhello\nworld\n"));

        assertEquals("hello\nworld\n", text.text());
        assertEquals("1726400000.5:12:4242", text.version());
        assertFalse(text.readOnly());
        assertFalse(text.crlf());
        assertFalse(text.failed());
    }

    @Test
    void windowsLineBreaksAreShownAsNewlinesAndRestoredOnSave() {
        FileText text = FileCommands.parseRead("/a", success("1:0:1 w\none\r\ntwo\r\n"));

        assertEquals("one\ntwo\n", text.text());
        assertTrue(text.crlf());
        assertArrayEquals("one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8), FileCommands.encode(text.text(), true));
    }

    @Test
    void emptyFileIsEditable() {
        FileText text = FileCommands.parseRead("/a", success("1:0:1 w\n"));

        assertEquals("", text.text());
        assertFalse(text.readOnly());
    }

    @Test
    void contentThatWouldBeDamagedIsReadOnly() {
        assertEquals("Binary file", FileCommands.parseRead("/a", success("1:0:1 w\nELF\0\0")).readOnlyReason());
        assertEquals("", FileCommands.parseRead("/a", success("1:0:1 w\nELF\0\0")).text());
        assertTrue(FileCommands.parseRead("/a", success("1:0:1 w\ncaf�")).readOnly());
        assertTrue(FileCommands.parseRead("/a", success("1:0:1 w\n[31mred")).readOnly());
        assertTrue(FileCommands.parseRead("/a", success("1:0:1 w\none\r\ntwo\rthree")).readOnly());
        assertEquals("No write permission", FileCommands.parseRead("/a", success("1:0:1 r\ntext")).readOnlyReason());
    }

    @Test
    void failedReadKeepsTheServerMessage() {
        CommandEntry denied = new CommandEntry(Instant.EPOCH, "cat", false, 3, "", "Permission denied\n", Duration.ZERO);

        FileText text = FileCommands.parseRead("/a", denied);

        assertTrue(text.failed());
        assertEquals("Permission denied", text.error());
    }

    @Test
    void unexpectedReadAnswerIsAFailure() {
        assertTrue(FileCommands.parseRead("/a", success("no header")).failed());
        assertTrue(FileCommands.parseRead("/a", success("later w\ntext")).failed());
    }

    @Test
    void newVersionIsReadAfterASave() {
        assertEquals(Optional.of("1726400123.2:9:77"), FileCommands.parseVersion(success("1726400123.2:9:77")));
        assertEquals(Optional.empty(), FileCommands.parseVersion(success("")));
        assertEquals(Optional.empty(), FileCommands.parseVersion(success("whatever")));
    }

    @Test
    void problemShowsTheFirstErrorLinesOrTheExitCode() {
        assertEquals("mv: cannot move", FileCommands.problem(
                new CommandEntry(Instant.EPOCH, "mv", false, 1, "", "mv: cannot move\n", Duration.ZERO)));
        assertEquals("Failed with exit code 2", FileCommands.problem(
                new CommandEntry(Instant.EPOCH, "x", false, 2, "", "", Duration.ZERO)));
    }

    @Test
    void missingGnuToolsAreListed() {
        assertEquals(List.of(), FileCommands.parseMissingTools(success("")));
        assertEquals(List.of("find", "stat"), FileCommands.parseMissingTools(success("find stat ")));
        assertEquals(List.of(), FileCommands.parseMissingTools(
                new CommandEntry(Instant.EPOCH, "x", false, CommandEntry.NOT_RUN, "", "lost", Duration.ZERO)));
        assertTrue(FileCommands.GNU_TOOLS.contains("grep -q GNU"));
    }

    @Test
    void readsTheAccount() {
        Account account = FileCommands.parseAccount(success("1000\nxana\nxana sudo docker\n/home/xana\n")).orElseThrow();

        assertEquals(new Account(1000, "xana", Set.of("xana", "sudo", "docker"), "/home/xana"), account);
    }

    @Test
    void incompleteAccountAnswerGivesNothing() {
        assertTrue(FileCommands.parseAccount(success("1000\nxana\n")).isEmpty());
        assertTrue(FileCommands.parseAccount(success("x\nxana\nxana\n/home/xana\n")).isEmpty());
    }

    private static CommandEntry success(String output) {
        return new CommandEntry(Instant.EPOCH, "cmd", false, 0, output, "", Duration.ZERO);
    }

    private static RemoteEntry entry(String path, RemoteEntry.Type type) {
        return new RemoteEntry("x", path, type, 0, Instant.EPOCH, "", "", "", "", RemoteEntry.UNKNOWN, true);
    }
}
