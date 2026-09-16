package com.kubo.ui.files;

import com.kubo.model.files.DirectoryListing;
import com.kubo.model.files.RemoteEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The open path of the file tree: one column per opened folder, each with its sorted and filtered entries,
 * and where every entry sits in world units. No drawing and no input: {@link BranchCanvas} turns user gestures
 * into calls here, {@link BranchPainter} reads it to draw.
 */
final class BranchTree {

    enum SortKey {
        NAME("NAME", true),
        DATE("DATE", false),
        SIZE("SIZE", false),
        TYPE("TYPE", true);

        final String label;
        final boolean ascendingByDefault;

        SortKey(String label, boolean ascendingByDefault) {
            this.label = label;
            this.ascendingByDefault = ascendingByDefault;
        }
    }

    // World units: one row per entry, one gap per depth level.
    static final double ROW = 24;
    static final double COLUMN_GAP = 330;
    static final double LABEL_MIN_FONT = 6;
    // "Fit" frames at most this many rows around each opened folder.
    static final int FIT_ROWS = 40;
    private static final double REVEAL_SECONDS = 0.75;
    private static final double TRANSITION_SECONDS = 0.45;

    record Ghost(RemoteEntry entry, double worldY, double start) {
    }

    static final class Column {
        RemoteEntry directory;
        List<RemoteEntry> entries = List.of();
        List<RemoteEntry> visible = List.of();
        // Entries that appeared or vanished at the last refresh, for their animations.
        Map<String, Double> born = new HashMap<>();
        List<Ghost> ghosts = new ArrayList<>();
        boolean refreshing;
        boolean loading;
        boolean truncated;
        String error = "";
        double loadingStart;
        double revealStart = -1;
        String openedPath;
        SortKey sortKey = SortKey.NAME;
        boolean ascending = true;
        Map<String, Double> previousY = Map.of();
        double transitionStart = -1;
        double top;

        Column(RemoteEntry directory) {
            this.directory = directory;
        }
    }

    /**
     * An entry of column {@code column}.
     */
    record Hit(RemoteEntry entry, int column) {
    }

    /**
     * What a listing changed.
     *
     * @param refreshed    true for a folder read again, false for a folder opened for the first time
     * @param pathShortened an opened folder disappeared, so the path now ends earlier
     * @param toSelect     the entry to select again or newly, or null
     * @param revealIndex  the row to bring on screen, or -1
     */
    record Delivery(int column, boolean refreshed, boolean pathShortened, RemoteEntry toSelect, int revealIndex) {
    }

    private final List<Column> columns = new ArrayList<>();
    private boolean showFiles = true;
    private boolean showHidden;
    private String homePath;
    private List<String> pendingChain = List.of();

    /**
     * Seconds, as the canvas animates; transitions and reveals are measured against it.
     */
    double clock;

    List<Column> columns() {
        return columns;
    }

    Column column(int k) {
        return columns.get(k);
    }

    int size() {
        return columns.size();
    }

    boolean isEmpty() {
        return columns.isEmpty();
    }

    // Opening the tree

    /**
     * Starts over from the server root; the path down to {@code path} is then opened column by column.
     *
     * @return the root entry, to select and open
     */
    RemoteEntry start(String homePath, String path) {
        this.homePath = Objects.requireNonNull(homePath, "homePath");
        columns.clear();
        RemoteEntry root = RemoteEntry.folder("/");
        Column rootColumn = new Column(null);
        rootColumn.entries = List.of(root);
        rootColumn.visible = rootColumn.entries;
        columns.add(rootColumn);
        pendingChain = ancestry(Objects.requireNonNull(path, "path"));
        return root;
    }

    String homePath() {
        return homePath;
    }

    /**
     * Adds a loading column for a folder of column {@code column}, closing anything opened after it.
     *
     * @return false when it is not a folder or already open
     */
    boolean open(int column, RemoteEntry entry) {
        if (!entry.isDirectory()) {
            return false;
        }
        Column source = columns.get(column);
        if (entry.path().equals(source.openedPath) && columns.size() > column + 1) {
            return false;
        }
        truncateAfter(column);
        source.openedPath = entry.path();
        Column next = new Column(entry);
        next.loading = true;
        next.loadingStart = clock;
        columns.add(next);
        return true;
    }

    /**
     * The next folder of the path being opened, found in column {@code column}; null when the path is complete or
     * the folder is not there (the path then stops).
     */
    RemoteEntry nextInChain(int column) {
        if (pendingChain.isEmpty()) {
            return null;
        }
        int index = indexOf(columns.get(column).visible, pendingChain.get(0));
        if (index < 0) {
            pendingChain = List.of();
            return null;
        }
        pendingChain = pendingChain.subList(1, pendingChain.size());
        return columns.get(column).visible.get(index);
    }

    boolean chainDone() {
        return pendingChain.isEmpty();
    }

    /**
     * Closes the last opened folder.
     *
     * @return the removed column, or null when only the root and its content are left
     */
    Column removeLast() {
        if (columns.size() <= 2) {
            return null;
        }
        Column removed = columns.remove(columns.size() - 1);
        columns.get(columns.size() - 1).openedPath = null;
        return removed;
    }

    void truncateAfter(int column) {
        while (columns.size() > column + 1) {
            columns.remove(columns.size() - 1);
        }
    }

    /**
     * Marks the open columns showing a folder as being read again.
     *
     * @return their folders, to read
     */
    List<RemoteEntry> markRefreshing(String path) {
        List<RemoteEntry> folders = new ArrayList<>();
        for (int k = 1; k < columns.size(); k++) {
            Column column = columns.get(k);
            if (column.directory.path().equals(path) && !column.loading && !column.refreshing) {
                column.refreshing = true;
                column.loadingStart = clock;
                folders.add(column.directory);
            }
        }
        return folders;
    }

    /**
     * Puts a folder's content in the column waiting for it.
     *
     * @param selectedPath  the entry selected now
     * @param pendingSelect an entry to select once its folder is read again, or null
     * @return what changed, or null when no column waits for this folder any more
     */
    Delivery deliver(DirectoryListing listing, String selectedPath, String pendingSelect) {
        for (int k = 1; k < columns.size(); k++) {
            Column column = columns.get(k);
            if (column.refreshing && column.directory.path().equals(listing.path())) {
                return refresh(k, listing, selectedPath, pendingSelect);
            }
            if (column.loading && column.directory.path().equals(listing.path())) {
                column.loading = false;
                column.error = listing.error();
                column.truncated = listing.truncated();
                column.entries = listing.entries();
                column.visible = arrange(column);
                column.revealStart = clock;
                if (!listing.failed()) {
                    updateChildCount(k - 1, listing.path(), listing.entries().size());
                }
                return new Delivery(k, false, false, null, -1);
            }
        }
        return null;
    }

    private Delivery refresh(int k, DirectoryListing listing, String selectedPath, String pendingSelect) {
        Column column = columns.get(k);
        column.refreshing = false;
        layout();
        beginTransition(column);

        Set<String> before = new HashSet<>();
        column.entries.forEach(entry -> before.add(entry.path()));
        Set<String> after = new HashSet<>();
        listing.entries().forEach(entry -> after.add(entry.path()));
        for (int i = 0; i < column.visible.size(); i++) {
            RemoteEntry old = column.visible.get(i);
            if (!after.contains(old.path())) {
                column.ghosts.add(new Ghost(old, nodeY(column, i), clock));
            }
        }
        for (String path : after) {
            if (!before.contains(path)) {
                column.born.put(path, clock);
            }
        }

        column.error = listing.error();
        column.truncated = listing.truncated();
        column.entries = listing.entries();
        column.visible = arrange(column);
        if (!listing.failed()) {
            updateChildCount(k - 1, listing.path(), listing.entries().size());
        }

        boolean shortened = false;
        if (column.openedPath != null) {
            int opened = indexOf(column.visible, column.openedPath);
            if (opened < 0) {
                truncateAfter(k);
                column.openedPath = null;
                shortened = true;
            } else if (k + 1 < columns.size()) {
                // Keep the next column's folder in step, e.g. after its mode changed.
                columns.get(k + 1).directory = column.visible.get(opened);
            }
        }
        String wanted = pendingSelect != null ? pendingSelect : selectedPath;
        int index = indexOf(column.visible, wanted);
        RemoteEntry toSelect = index >= 0 ? column.visible.get(index) : null;
        int reveal = index >= 0 && wanted.equals(pendingSelect) ? index : -1;
        return new Delivery(k, true, shortened, toSelect, reveal);
    }

    // Order and filter

    /**
     * Applies what the tree shows to every column.
     *
     * @return true when an opened folder is now filtered out, so the path was shortened
     */
    boolean setFilter(boolean showFiles, boolean showHidden) {
        layout();
        for (int k = 1; k < columns.size(); k++) {
            beginTransition(columns.get(k));
        }
        this.showFiles = showFiles;
        this.showHidden = showHidden;
        for (int k = 1; k < columns.size(); k++) {
            columns.get(k).visible = arrange(columns.get(k));
        }
        // A folder that is now filtered out cannot stay open.
        for (int k = 1; k < columns.size() - 1; k++) {
            Column column = columns.get(k);
            if (column.openedPath != null && indexOf(column.visible, column.openedPath) < 0) {
                truncateAfter(k);
                column.openedPath = null;
                return true;
            }
        }
        return false;
    }

    /**
     * Sorts one column; the same key again flips the direction.
     */
    void sort(int column, SortKey key) {
        if (column <= 0 || column >= columns.size()) {
            return;
        }
        Column target = columns.get(column);
        layout();
        boolean ascending = target.sortKey == key ? !target.ascending : key.ascendingByDefault;
        beginTransition(target);
        target.sortKey = key;
        target.ascending = ascending;
        target.visible = arrange(target);
    }

    List<RemoteEntry> arrange(Column column) {
        Comparator<RemoteEntry> order = switch (column.sortKey) {
            case NAME -> Comparator.comparing(entry -> entry.name().toLowerCase(Locale.ROOT));
            case DATE -> Comparator.comparing(RemoteEntry::modified);
            case SIZE -> Comparator.comparingLong(entry -> entry.isDirectory()
                    ? Math.max(0, entry.childCount()) : entry.sizeBytes());
            case TYPE -> Comparator.comparing(RemoteEntry::extension)
                    .thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT));
        };
        if (!column.ascending) {
            order = order.reversed();
        }
        // Folders stay above files whatever the order.
        Comparator<RemoteEntry> foldersFirst = Comparator.comparing((RemoteEntry entry) -> !entry.isDirectory());
        return column.entries.stream()
                .filter(entry -> showFiles || entry.isDirectory())
                .filter(entry -> showHidden || !entry.isHidden())
                .sorted(foldersFirst.thenComparing(order))
                .toList();
    }

    private void updateChildCount(int columnIndex, String path, int count) {
        Column column = columns.get(columnIndex);
        List<RemoteEntry> entries = new ArrayList<>(column.entries);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).path().equals(path)) {
                entries.set(i, entries.get(i).withChildCount(count));
            }
        }
        column.entries = List.copyOf(entries);
        column.visible = columnIndex == 0 ? column.entries : arrange(column);
    }

    // Where things are

    static double columnX(int column) {
        return column * COLUMN_GAP;
    }

    /**
     * Centres every column on its opened parent; must run before reading node positions.
     */
    void layout() {
        for (int k = 0; k < columns.size(); k++) {
            Column column = columns.get(k);
            double parentY = 0;
            if (k > 0) {
                Column parent = columns.get(k - 1);
                int index = indexOf(parent.visible, parent.openedPath);
                parentY = index >= 0 ? nodeY(parent, index) : parent.top;
            }
            column.top = parentY - (Math.max(1, column.visible.size()) - 1) * ROW / 2;
        }
    }

    /**
     * World y of a node, gliding from its previous place during a sort or filter change.
     */
    double nodeY(Column column, int index) {
        double target = column.top + index * ROW;
        if (column.transitionStart < 0) {
            return target;
        }
        double t = (clock - column.transitionStart) / TRANSITION_SECONDS;
        if (t >= 1) {
            column.transitionStart = -1;
            column.previousY = Map.of();
            return target;
        }
        Double previous = column.previousY.get(column.visible.get(index).path());
        if (previous == null) {
            return target;
        }
        double eased = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
        return previous + (target - previous) * eased;
    }

    private void beginTransition(Column column) {
        Map<String, Double> positions = new HashMap<>();
        for (int i = 0; i < column.visible.size(); i++) {
            positions.put(column.visible.get(i).path(), nodeY(column, i));
        }
        column.previousY = positions;
        column.transitionStart = clock;
    }

    /**
     * How far a node's appearance has gone, 0 to 1, rows following each other.
     */
    double progress(Column column, int index) {
        if (column.revealStart < 0) {
            return 1;
        }
        double stagger = Math.min(0.012, 0.4 / Math.max(1, column.visible.size()));
        return BranchStyle.clamp((clock - column.revealStart - index * stagger) / REVEAL_SECONDS, 0, 1);
    }

    /**
     * World position of an entry's dot, or null when it is not in the tree.
     */
    double[] nodePoint(String path) {
        for (int k = columns.size() - 1; k >= 0; k--) {
            Column column = columns.get(k);
            int index = indexOf(column.visible, path);
            if (index >= 0) {
                return new double[]{columnX(k), nodeY(column, index)};
            }
        }
        return null;
    }

    /**
     * The entry under a world point, reading its dot and name. {@code zoom} matters because names keep a readable
     * size on screen whatever the zoom.
     */
    Hit hitAt(double worldX, double worldY, double zoom) {
        if (columns.isEmpty()) {
            return null;
        }
        layout();
        for (int k = columns.size() - 1; k >= 0; k--) {
            Column column = columns.get(k);
            double x = columnX(k);
            if (worldX < x - ROW * 0.8 || worldX > x + COLUMN_GAP - 50 || column.visible.isEmpty()) {
                continue;
            }
            int index = (int) Math.round((worldY - column.top) / ROW);
            if (index >= 0 && index < column.visible.size() && Math.abs(worldY - nodeY(column, index)) <= ROW / 2
                    && worldX <= x + rowReach(column.visible.get(index), zoom)) {
                return new Hit(column.visible.get(index), k);
            }
        }
        return null;
    }

    /**
     * How far right of its node an entry can be clicked: the dot and its name, so the space beyond stays free.
     */
    static double rowReach(RemoteEntry entry, double zoom) {
        double row = ROW * zoom;
        double fontSize = Math.min(11, row * 0.5);
        if (fontSize < LABEL_MIN_FONT) {
            return ROW;
        }
        double maxRadius = Math.max(1.2, row * 0.42);
        int maxCharacters = Math.max(6, (int) ((COLUMN_GAP * zoom - 60) / (fontSize * 0.55)));
        double width = Math.min(maxRadius, 10) + 7 + Math.min(entry.name().length(), maxCharacters) * fontSize * 0.54;
        if (entry.isDirectory() && entry.childCount() > 0) {
            width += String.valueOf(entry.childCount()).length() * fontSize * 0.5 + 6;
        }
        return Math.max(ROW, (width + 12) / zoom);
    }

    /**
     * The folder whose branch lies under a world point: the fibers leading to a column and its dots belong to that
     * column's folder, and everything right of the last column belongs to the last folder.
     *
     * @return the column index, or -1
     */
    int spaceAt(double worldX, double worldY) {
        if (columns.size() < 2) {
            return -1;
        }
        layout();
        int k = Math.min(columns.size() - 1, (int) Math.ceil((worldX - ROW) / COLUMN_GAP));
        if (k < 1) {
            return -1;
        }
        double[] bounds = spaceBounds(k);
        return worldY >= bounds[0] && worldY <= bounds[1] ? k : -1;
    }

    /**
     * World top and bottom of a folder's space.
     */
    double[] spaceBounds(int k) {
        Column column = columns.get(k);
        double top = column.top - ROW * 2.5;
        double bottom = column.top + (Math.max(1, column.visible.size()) - 1) * ROW + ROW * 2.5;
        return new double[]{top, bottom};
    }

    /**
     * World bounds framing the open path: min x, max x, min y, max y.
     */
    double[] fitBounds() {
        layout();
        double minX = -60;
        double maxX = columnX(columns.size() - 1) + COLUMN_GAP * 0.8;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Column column : columns) {
            int count = Math.max(1, column.visible.size());
            int opened = indexOf(column.visible, column.openedPath);
            double center = opened >= 0 ? nodeY(column, opened) : column.top + (count - 1) * ROW / 2;
            double reach = Math.min(count, FIT_ROWS) * ROW / 2;
            minY = Math.min(minY, Math.max(column.top - ROW, center - reach));
            maxY = Math.max(maxY, Math.min(column.top + count * ROW, center + reach));
        }
        return new double[]{minX, maxX, minY, maxY};
    }

    int columnOf(RemoteEntry directory) {
        if (directory == null) {
            return -1;
        }
        for (int k = columns.size() - 1; k >= 1; k--) {
            if (columns.get(k).directory.path().equals(directory.path())) {
                return k;
            }
        }
        return -1;
    }

    /**
     * The folder whose entries column {@code k} shows; null for the column holding the server root.
     */
    RemoteEntry folderOf(int k) {
        return k >= 1 ? columns.get(k).directory : null;
    }

    /**
     * The folder at the end of the open path, or null before the tree starts.
     */
    RemoteEntry currentDirectory() {
        if (columns.isEmpty()) {
            return null;
        }
        Column last = columns.get(columns.size() - 1);
        return last.directory != null ? last.directory : last.entries.get(0);
    }

    /**
     * True for anything outside the home folder: the system side of the tree.
     */
    boolean isSystemSide(RemoteEntry directory) {
        if (homePath == null || directory == null) {
            return true;
        }
        String path = directory.path();
        return !path.equals(homePath) && !path.startsWith(homePath + "/");
    }

    /**
     * "/home/xana" gives ["/home", "/home/xana"].
     */
    static List<String> ancestry(String path) {
        List<String> chain = new ArrayList<>();
        int slash = path.indexOf('/', 1);
        while (slash > 0) {
            chain.add(path.substring(0, slash));
            slash = path.indexOf('/', slash + 1);
        }
        if (path.length() > 1) {
            chain.add(path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
        }
        return chain.stream().distinct().toList();
    }

    static int indexOf(List<RemoteEntry> entries, String path) {
        if (path == null) {
            return -1;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).path().equals(path)) {
                return i;
            }
        }
        return -1;
    }
}
