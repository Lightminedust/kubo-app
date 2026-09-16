package com.kubo.model.files;

import com.kubo.model.files.PermissionMode.Who;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountTest {

    private static final Account XANA = new Account(1000, "xana", Set.of("xana", "docker"), "/home/xana");
    private static final Account ROOT = new Account(0, "root", Set.of("root"), "/root");

    @Test
    void ownerRightsApplyToTheOwner() {
        RemoteEntry notes = entry("-rw-r-----", "xana", "staff");

        assertEquals(Who.OWNER, XANA.classOf(notes));
        assertTrue(XANA.canRead(notes));
        assertTrue(XANA.canWrite(notes));
        assertFalse(XANA.canExecute(notes));
    }

    @Test
    void groupRightsApplyToMembers() {
        RemoteEntry compose = entry("-rw-rw-r--", "root", "docker");

        assertEquals(Who.GROUP, XANA.classOf(compose));
        assertTrue(XANA.canWrite(compose));
    }

    @Test
    void othersRightsApplyToEveryoneElse() {
        RemoteEntry shadow = entry("-rw-r-----", "root", "shadow");

        assertEquals(Who.OTHERS, XANA.classOf(shadow));
        assertFalse(XANA.canRead(shadow));
    }

    @Test
    void onlyTheMatchingClassCountsEvenWhenAnotherGrantsMore() {
        RemoteEntry odd = entry("----rwxrwx", "xana", "xana");

        assertFalse(XANA.canRead(odd));
    }

    @Test
    void numericOwnerIsRecognised() {
        assertTrue(XANA.owns(entry("-rw-------", "1000", "1000")));
    }

    @Test
    void rootMayDoAnything() {
        RemoteEntry locked = entry("d---------", "xana", "xana");

        assertTrue(ROOT.canRead(locked));
        assertTrue(ROOT.canChangeMode(locked));
        assertTrue(ROOT.canRemove(locked, entry("d---------", "xana", "xana")));
    }

    @Test
    void unknownModeIsLeftToTheServer() {
        assertTrue(XANA.canWrite(RemoteEntry.folder("/")));
    }

    @Test
    void onlyTheOwnerChangesTheMode() {
        assertTrue(XANA.canChangeMode(entry("-r--------", "xana", "xana")));
        assertFalse(XANA.canChangeMode(entry("-rwxrwxrwx", "deploy", "docker")));
    }

    @Test
    void creatingNeedsWriteAndEnterOnTheFolder() {
        assertTrue(XANA.canCreateIn(entry("drwxr-xr-x", "xana", "xana")));
        assertFalse(XANA.canCreateIn(entry("drw-r-xr-x", "xana", "xana")));
        assertFalse(XANA.canCreateIn(entry("drwxr-xr-x", "root", "root")));
    }

    @Test
    void removingDependsOnTheFolderNotTheEntry() {
        RemoteEntry readOnlyFile = entry("-r--r--r--", "root", "root");

        assertTrue(XANA.canRemove(readOnlyFile, entry("drwxr-xr-x", "xana", "xana")));
        assertFalse(XANA.canRemove(entry("-rwxrwxrwx", "xana", "xana"), entry("dr-xr-xr-x", "xana", "xana")));
    }

    @Test
    void stickyFolderKeepsOtherUsersEntries() {
        RemoteEntry tmp = entry("drwxrwxrwt", "root", "root");

        assertTrue(XANA.canRemove(entry("-rw-r--r--", "xana", "xana"), tmp));
        assertFalse(XANA.canRemove(entry("-rw-rw-rw-", "deploy", "deploy"), tmp));
    }

    @Test
    void rejectsIncompleteAccounts() {
        assertThrows(IllegalArgumentException.class, () -> new Account(-1, "x", Set.of(), "/home/x"));
        assertThrows(IllegalArgumentException.class, () -> new Account(1, " ", Set.of(), "/home/x"));
        assertThrows(IllegalArgumentException.class, () -> new Account(1, "x", Set.of(), "home/x"));
    }

    private static RemoteEntry entry(String permissions, String owner, String group) {
        RemoteEntry.Type type = permissions.startsWith("d") ? RemoteEntry.Type.DIRECTORY : RemoteEntry.Type.FILE;
        return new RemoteEntry("item", "/srv/item", type, 0, Instant.EPOCH, permissions, owner, group, "",
                RemoteEntry.UNKNOWN, true);
    }
}
