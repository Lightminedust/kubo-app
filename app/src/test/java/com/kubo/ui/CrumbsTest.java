package com.kubo.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The walk back up a server path, which is what every crumb and every "go up" in a picker relies on. */
class CrumbsTest {

    @Test
    void aPathBecomesEveryFolderAboveIt() {
        assertEquals(List.of("/", "/var", "/var/www", "/var/www/site"), Crumbs.chain("/var/www/site"));
    }

    @Test
    void theRootIsItsOwnChain() {
        assertEquals(List.of("/"), Crumbs.chain("/"));
        assertEquals(List.of("/"), Crumbs.chain(""));
        assertEquals(List.of("/"), Crumbs.chain(null));
    }

    @Test
    void repeatedAndTrailingSlashesChangeNothing() {
        assertEquals(List.of("/", "/var", "/var/www"), Crumbs.chain("//var//www/"));
    }

    @Test
    void theNameIsTheLastPart() {
        assertEquals("site", Crumbs.name("/var/www/site"));
        assertEquals("site", Crumbs.name("/var/www/site/"));
        assertEquals("/", Crumbs.name("/"));
    }

    @Test
    void theParentOfAnythingUnderTheRootIsTheRoot() {
        assertEquals("/var/www", Crumbs.parent("/var/www/site"));
        assertEquals("/", Crumbs.parent("/var"));
        assertEquals("/", Crumbs.parent("/"));
    }
}
