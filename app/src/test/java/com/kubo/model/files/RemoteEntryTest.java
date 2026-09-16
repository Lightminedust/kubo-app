package com.kubo.model.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteEntryTest {

    @ParameterizedTest
    @CsvSource({
            "/home/xana,  xana",
            "/home/xana/, xana",
            "/,           /",
    })
    void folderIsNamedAfterItsLastSegment(String path, String name) {
        RemoteEntry folder = RemoteEntry.folder(path);

        assertEquals(name, folder.name());
        assertTrue(folder.isDirectory());
        assertEquals(RemoteEntry.UNKNOWN, folder.childCount());
    }

    @ParameterizedTest
    @CsvSource({
            "archive.tar.GZ, gz",
            "README,         ''",
            ".bashrc,        ''",
            "trailing.,      ''",
    })
    void extensionIsLowerCaseWithoutTheDot(String name, String extension) {
        assertEquals(extension, RemoteEntry.folder("/x/" + name).extension());
    }

    @Test
    void joinAvoidsDoubleSlashes() {
        assertEquals("/etc", RemoteEntry.join("/", "etc"));
        assertEquals("/home/xana/docs", RemoteEntry.join("/home/xana", "docs"));
    }

    @Test
    void parentIsTheFolderAbove() {
        assertEquals("/home", RemoteEntry.parentOf("/home/xana").orElseThrow());
        assertEquals("/home", RemoteEntry.parentOf("/home/xana/").orElseThrow());
        assertEquals("/", RemoteEntry.parentOf("/home").orElseThrow());
        assertTrue(RemoteEntry.parentOf("/").isEmpty());
        assertTrue(RemoteEntry.parentOf("relative").isEmpty());
    }

    @Test
    void hiddenEntriesStartWithADot() {
        assertTrue(RemoteEntry.folder("/home/xana/.config").isHidden());
        assertFalse(RemoteEntry.folder("/home/xana/config").isHidden());
    }

    @Test
    void rejectsImpossibleValues() {
        assertThrows(IllegalArgumentException.class, () -> RemoteEntry.folder(""));
        assertThrows(IllegalArgumentException.class, () -> RemoteEntry.folder("/x").withChildCount(-2));
    }
}
