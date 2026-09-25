package com.kubo.shell;

import com.kubo.model.shell.EditorCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Opening a file, typed as an editor")
class EditorCallTest {

    @Test
    void theUsualEditorsAreRecognisedWithTheirFile() {
        EditorCall nano = EditorCall.of("nano /etc/hosts").orElseThrow();
        assertEquals("nano", nano.program());
        assertEquals(List.of("/etc/hosts"), nano.paths());
        assertEquals(false, nano.asRoot());

        assertEquals(List.of("notes.txt"), EditorCall.of("vim notes.txt").orElseThrow().paths());
        assertEquals("vi", EditorCall.of("/usr/bin/vi x").orElseThrow().program(),
                "a full path to the program is the same program");
    }

    @Test
    void sudoInFrontIsTheSameCallMadeAsRoot() {
        EditorCall call = EditorCall.of("sudo nano /etc/ssh/sshd_config").orElseThrow();

        assertEquals("nano", call.program());
        assertEquals(List.of("/etc/ssh/sshd_config"), call.paths());
        assertTrue(call.asRoot());
    }

    @Test
    void optionsAndLineNumbersAreNotFiles() {
        assertEquals(List.of("/etc/fstab"), EditorCall.of("vim +12 -R /etc/fstab").orElseThrow().paths());
        assertEquals(List.of(), EditorCall.of("nano").orElseThrow().paths(),
                "an editor on its own is still an editor, it just has nothing to open");
    }

    @Test
    void aPathWithASpaceStaysOnePath() {
        assertEquals(List.of("/home/xana/my notes.txt"),
                EditorCall.of("nano '/home/xana/my notes.txt'").orElseThrow().paths());
        assertEquals(List.of("/home/xana/my notes.txt"),
                EditorCall.of("nano /home/xana/my\\ notes.txt").orElseThrow().paths());
    }

    @Test
    void anythingThatIsMoreThanOneCommandIsLeftAlone() {
        // The editor would open and the rest of the line would quietly never run, which is worse than a
        // program drawing badly.
        assertEquals(Optional.empty(), EditorCall.of("nano notes.txt; reboot"));
        assertEquals(Optional.empty(), EditorCall.of("nano $(cat list)"));
        assertEquals(Optional.empty(), EditorCall.of("vim x | tee y"));
        assertEquals(Optional.empty(), EditorCall.of("nano > out"));
    }

    @Test
    void everythingElseIsAnOrdinaryCommand() {
        assertEquals(Optional.empty(), EditorCall.of("ls -la"));
        assertEquals(Optional.empty(), EditorCall.of("nanorc"), "it has to be the program, not a word starting like it");
        assertEquals(Optional.empty(), EditorCall.of(""));
        assertEquals(Optional.empty(), EditorCall.of(null));
        assertEquals(Optional.empty(), EditorCall.of("sudo"));
    }

    @Test
    void aFileIsFoundFromWhereTheCommandWouldHaveRun() {
        // Each command gets its own session, and a session starts at home. So a bare name means a file in it.
        assertEquals("/home/xana/notes.txt", EditorCall.resolve("notes.txt", "/home/xana"));
        assertEquals("/home/xana/notes.txt", EditorCall.resolve("~/notes.txt", "/home/xana"));
        assertEquals("/home/xana", EditorCall.resolve("~", "/home/xana"));
        assertEquals("/etc/hosts", EditorCall.resolve("/etc/hosts", "/home/xana"));
        assertEquals("/notes.txt", EditorCall.resolve("notes.txt", ""), "no home known is still a real path");
    }
}
