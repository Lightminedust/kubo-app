package com.kubo.services;

import com.kubo.model.services.UnitDraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A service somebody is about to create.
 *
 * <p>The file is built here rather than in the screen so that what will be written can be judged before it is
 * sent — and so that the text shown to the user and the text that reaches the server are the same string.
 */
class UnitDraftTest {

    private static UnitDraft draft() {
        return new UnitDraft("backup", "Nightly backup", "/opt/backup/run.sh", "/opt/backup", "xana",
                "on-failure", 5, true);
    }

    @Test
    @DisplayName("the file says what it was asked to say, and Kubo adds the .service itself")
    void theFileIsWritten() {
        UnitDraft draft = draft();

        assertEquals("backup.service", draft.unitName());
        String text = draft.text();
        assertTrue(text.contains("Description=Nightly backup"), text);
        assertTrue(text.contains("ExecStart=/opt/backup/run.sh"), text);
        assertTrue(text.contains("WorkingDirectory=/opt/backup"), text);
        assertTrue(text.contains("User=xana"), text);
        assertTrue(text.contains("Restart=on-failure"), text);
        assertTrue(text.contains("RestartSec=5s"), text);
        // Without this, enabling the service would quietly do nothing at all.
        assertTrue(text.contains("WantedBy=multi-user.target"), text);
    }

    @Test
    @DisplayName("what was left out is left out, rather than written empty")
    void nothingIsInventedOrLeftHanging() {
        String text = new UnitDraft("thing", "", "/usr/bin/thing", "", "", "no", 5, false).text();

        assertFalse(text.contains("WorkingDirectory="), text);
        assertFalse(text.contains("User="), text);
        assertTrue(text.contains("Restart=no"), text);
        assertFalse(text.contains("RestartSec="), "a delay before a restart that never happens says nothing");
        assertTrue(text.contains("Description=thing"), "with no description, the name is the description");
    }

    @Test
    @DisplayName("a path with a space in it is quoted, because systemd splits that line itself")
    void pathsWithSpacesAreQuoted() {
        String text = new UnitDraft("x", "", "/opt/my scripts/run.sh", "/opt/my scripts", "", "no", 0, false)
                .text();

        assertTrue(text.contains("ExecStart=\"/opt/my scripts/run.sh\""), text);
        assertTrue(text.contains("WorkingDirectory=\"/opt/my scripts\""), text);
    }

    @Test
    @DisplayName("a name that could not be a file under /etc is refused, and said so in words")
    void namesAreJudged() {
        assertTrue(UnitDraft.validName("backup"));
        assertTrue(UnitDraft.validName("my-backup_2"));
        assertTrue(UnitDraft.validName("getty@tty1"));

        assertFalse(UnitDraft.validName("../../etc/passwd"));
        assertFalse(UnitDraft.validName("my backup"));
        assertFalse(UnitDraft.validName("back/up"));
        assertFalse(UnitDraft.validName(""));
        assertFalse(UnitDraft.validName("backup.service"), "Kubo adds that part itself");
    }

    @Test
    @DisplayName("a draft says what is still missing, instead of being written half finished")
    void whatIsMissingIsNamed() {
        assertTrue(UnitDraft.blank().problems().size() >= 2);
        assertFalse(UnitDraft.blank().ready());

        UnitDraft relative = new UnitDraft("x", "", "run.sh", "", "", "no", 0, false);
        assertFalse(relative.ready());
        assertTrue(relative.problems().get(0).contains("full path"),
                "systemd gives a service no PATH of its own, so a bare command would never be found");

        assertTrue(draft().ready());
        assertEquals(0, draft().problems().size());
    }

    @Test
    @DisplayName("a delay is kept within an hour, however large a number is typed into it")
    void theDelayIsBounded() {
        assertTrue(new UnitDraft("x", "", "/a", "", "", "always", 99_999, false).text()
                .contains("RestartSec=3600s"));
        assertTrue(new UnitDraft("x", "", "/a", "", "", "always", -5, false).text()
                .contains("RestartSec=0s"));
    }
}
