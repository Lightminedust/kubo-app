package com.kubo.ui.files;

import com.kubo.model.files.DirectoryListing;
import com.kubo.model.files.RemoteEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchTreeTest {

    @Test
    void ancestryListsEveryFolderDownToThePath() {
        assertEquals(List.of("/home", "/home/xana"), BranchTree.ancestry("/home/xana"));
        assertEquals(List.of("/srv"), BranchTree.ancestry("/srv/"));
        assertTrue(BranchTree.ancestry("/").isEmpty());
    }

    @Test
    void startingOpensTheRootAndFollowsThePathAsFoldersArrive() {
        BranchTree tree = new BranchTree();
        RemoteEntry root = tree.start("/home/xana", "/home/xana");
        assertTrue(tree.open(0, root));

        assertNotNull(tree.deliver(listing("/", folder("/home"), folder("/etc")), "/", null));
        RemoteEntry home = tree.nextInChain(1);
        assertEquals("/home", home.path());
        assertTrue(tree.open(1, home));
        tree.deliver(listing("/home", folder("/home/xana")), "/home", null);

        assertEquals("/home/xana", tree.nextInChain(2).path());
        assertTrue(tree.chainDone());
        assertEquals(3, tree.size());
    }

    @Test
    void pathStopsWhenAFolderIsMissing() {
        BranchTree tree = new BranchTree();
        tree.open(0, tree.start("/home/xana", "/home/xana"));
        tree.deliver(listing("/", folder("/etc")), "/", null);

        assertNull(tree.nextInChain(1));
        assertTrue(tree.chainDone());
    }

    @Test
    void foldersComeFirstThenTheChosenOrder() {
        BranchTree tree = openedAt("/srv", RemoteEntry.folder("/srv/b").withChildCount(2), file("/srv/a.txt", 10),
                RemoteEntry.folder("/srv/A").withChildCount(5), file("/srv/z.log", 99));

        assertEquals(List.of("A", "b", "a.txt", "z.log"), names(tree.column(2).visible));
        tree.sort(2, BranchTree.SortKey.SIZE);
        assertEquals(List.of("A", "b", "z.log", "a.txt"), names(tree.column(2).visible));
        tree.sort(2, BranchTree.SortKey.SIZE);
        assertEquals(List.of("b", "A", "a.txt", "z.log"), names(tree.column(2).visible));
    }

    @Test
    void filterHidesFilesAndHiddenEntries() {
        BranchTree tree = openedAt("/srv", folder("/srv/app"), folder("/srv/.git"), file("/srv/run.sh", 1));

        assertEquals(List.of("app", "run.sh"), names(tree.column(2).visible));
        tree.setFilter(false, true);
        assertEquals(List.of(".git", "app"), names(tree.column(2).visible));
    }

    @Test
    void filteringOutAnOpenedFolderShortensThePath() {
        BranchTree tree = openedAt("/srv", folder("/srv/.git"));
        tree.setFilter(true, true);
        assertTrue(tree.open(2, tree.column(2).visible.get(0)));

        assertTrue(tree.setFilter(true, false));
        assertEquals(3, tree.size());
    }

    @Test
    void refreshRecordsWhatAppearedAndVanishedAndSelectsTheNewEntry() {
        BranchTree tree = openedAt("/srv", file("/srv/old.txt", 1), file("/srv/keep.txt", 1));
        tree.markRefreshing("/srv");
        tree.clock = 5;

        BranchTree.Delivery delivery = tree.deliver(listing("/srv", file("/srv/keep.txt", 1), file("/srv/new.txt", 1)),
                "/srv/keep.txt", "/srv/new.txt");

        assertTrue(delivery.refreshed());
        assertEquals("/srv/new.txt", delivery.toSelect().path());
        assertTrue(delivery.revealIndex() >= 0);
        BranchTree.Column column = tree.column(2);
        assertEquals(5.0, column.born.get("/srv/new.txt"));
        assertEquals("/srv/old.txt", column.ghosts.get(0).entry().path());
    }

    @Test
    void refreshClosesAFolderThatNoLongerExists() {
        BranchTree tree = openedAt("/srv", folder("/srv/app"));
        tree.open(2, tree.column(2).visible.get(0));
        tree.markRefreshing("/srv");

        BranchTree.Delivery delivery = tree.deliver(listing("/srv"), null, null);

        assertTrue(delivery.pathShortened());
        assertEquals(3, tree.size());
    }

    @Test
    void listingForAFolderNoLongerWaitedForIsIgnored() {
        BranchTree tree = openedAt("/srv");

        assertNull(tree.deliver(listing("/elsewhere"), null, null));
    }

    @Test
    void columnsAreCentredOnTheirOpenedParent() {
        BranchTree tree = openedAt("/srv", folder("/srv/a"), folder("/srv/b"), folder("/srv/c"));
        tree.layout();

        BranchTree.Column column = tree.column(2);
        double middle = tree.nodeY(column, 1);
        double parent = tree.nodeY(tree.column(1), BranchTree.indexOf(tree.column(1).visible, "/srv"));
        assertEquals(parent, middle, 1e-9);
    }

    @Test
    void clickOnANameHitsItsEntryButNotTheEmptySpaceBeyond() {
        BranchTree tree = openedAt("/srv", folder("/srv/app"));
        tree.layout();
        BranchTree.Column column = tree.column(2);
        double x = BranchTree.columnX(2);
        double y = tree.nodeY(column, 0);

        BranchTree.Hit hit = tree.hitAt(x + 20, y, 1);
        assertEquals("/srv/app", hit.entry().path());
        assertNull(tree.hitAt(x + 250, y, 1));
        assertEquals(2, tree.spaceAt(x + 250, y));
    }

    @Test
    void systemSideIsEverythingOutsideHome() {
        BranchTree tree = new BranchTree();
        tree.start("/home/xana", "/home/xana");

        assertFalse(tree.isSystemSide(RemoteEntry.folder("/home/xana/projects")));
        assertTrue(tree.isSystemSide(RemoteEntry.folder("/home/xanadu")));
        assertTrue(tree.isSystemSide(RemoteEntry.folder("/etc")));
    }

    @Test
    void removingTheLastColumnStopsAtTheRootListing() {
        BranchTree tree = openedAt("/srv", folder("/srv/app"));

        assertNotNull(tree.removeLast());
        assertNull(tree.removeLast());
    }

    /**
     * Root, then "/" listed with {@code path}, then {@code path} opened and listed with {@code entries}.
     */
    private static BranchTree openedAt(String path, RemoteEntry... entries) {
        BranchTree tree = new BranchTree();
        tree.open(0, tree.start("/", "/"));
        tree.deliver(listing("/", folder(path)), "/", null);
        tree.open(1, tree.column(1).visible.get(0));
        tree.deliver(listing(path, entries), path, null);
        return tree;
    }

    private static DirectoryListing listing(String path, RemoteEntry... entries) {
        return new DirectoryListing(path, List.of(entries), false, "");
    }

    private static RemoteEntry folder(String path) {
        return RemoteEntry.folder(path).withChildCount(0);
    }

    private static RemoteEntry file(String path, long size) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return new RemoteEntry(name, path, RemoteEntry.Type.FILE, size, Instant.EPOCH, "-rw-r--r--", "x", "x", "",
                RemoteEntry.UNKNOWN, true);
    }

    private static List<String> names(List<RemoteEntry> entries) {
        return entries.stream().map(RemoteEntry::name).toList();
    }
}
