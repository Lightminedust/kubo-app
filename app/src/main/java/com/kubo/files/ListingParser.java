package com.kubo.files;

import com.kubo.model.files.DirectoryListing;
import com.kubo.model.files.RemoteEntry;
import com.kubo.model.files.RemoteEntry.Type;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads one folder with GNU find. Fields are separated by NUL bytes, the only character a file name
 * cannot contain, so names with spaces, quotes or line breaks are read correctly.
 */
public final class ListingParser {

    static final String DENIED_MARKER = "@@DENIED@@";
    static final String COUNTS_MARKER = "\n@@COUNTS@@\n";
    static final int MAX_ENTRIES = 5_000;
    // Entries one level further down, used to size folders; beyond this, counts are partial.
    static final int MAX_GRANDCHILDREN = 30_000;

    private static final int FIELDS = 8;

    private ListingParser() {
    }

    public static String command(String path) {
        return "P=" + ShellQuote.quote(path) + "; "
                + "if [ ! -d \"$P\" ] || [ ! -r \"$P\" ] || [ ! -x \"$P\" ]; then printf '" + DENIED_MARKER + "'; exit 0; fi; "
                + "find \"$P\" -mindepth 1 -maxdepth 1 -printf '%y\\0%s\\0%T@\\0%M\\0%u\\0%g\\0%l\\0%f\\0' "
                + "| head -z -n " + MAX_ENTRIES * FIELDS + "; "
                + "printf '\\n@@COUNTS@@\\n'; "
                // Kernel pseudo-filesystems hold thousands of virtual entries: not worth counting from "/".
                + "find \"$P\" -mindepth 2 -maxdepth 2 ! -path '/proc/*' ! -path '/sys/*' -printf '%P\\0' "
                + "| head -z -n " + MAX_GRANDCHILDREN;
    }

    /**
     * @param error the command's stderr, where find reports the sub-folders it cannot open
     */
    public static DirectoryListing parse(String path, String output, String error) {
        if (output.startsWith(DENIED_MARKER)) {
            return DirectoryListing.failed(path, "Permission denied");
        }

        int marker = output.indexOf(COUNTS_MARKER);
        String entriesPart = marker < 0 ? output : output.substring(0, marker);
        String countsPart = marker < 0 ? "" : output.substring(marker + COUNTS_MARKER.length());

        Map<String, Integer> counts = childCounts(countsPart);
        Set<String> denied = deniedPaths(error);

        String[] fields = entriesPart.split("\0", -1);
        int complete = (fields.length - 1) / FIELDS;
        List<RemoteEntry> entries = new ArrayList<>(complete);
        for (int i = 0; i < complete; i++) {
            RemoteEntry entry = entry(path, fields, i * FIELDS, counts, denied);
            if (entry != null) {
                entries.add(entry);
            }
        }
        entries.sort(Comparator.comparing((RemoteEntry entry) -> !entry.isDirectory())
                .thenComparing(entry -> entry.name().toLowerCase()));
        return new DirectoryListing(path, entries, complete >= MAX_ENTRIES, "");
    }

    private static RemoteEntry entry(String parent, String[] fields, int offset,
                                     Map<String, Integer> counts, Set<String> denied) {
        String name = fields[offset + 7];
        if (name.isEmpty()) {
            return null;
        }
        Type type = switch (fields[offset]) {
            case "d" -> Type.DIRECTORY;
            case "f" -> Type.FILE;
            case "l" -> Type.LINK;
            default -> Type.OTHER;
        };
        try {
            long size = Long.parseLong(fields[offset + 1]);
            long modifiedMillis = Math.round(Double.parseDouble(fields[offset + 2]) * 1000);
            String path = RemoteEntry.join(parent, name);
            boolean readable = !denied.contains(path);
            int childCount = type == Type.DIRECTORY && readable ? counts.getOrDefault(name, 0) : RemoteEntry.UNKNOWN;
            return new RemoteEntry(name, path, type, Math.max(0, size), Instant.ofEpochMilli(modifiedMillis),
                    fields[offset + 3], fields[offset + 4], fields[offset + 5], fields[offset + 6], childCount, readable);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * "child/grandchild" paths, counted per child.
     */
    private static Map<String, Integer> childCounts(String part) {
        Map<String, Integer> counts = new HashMap<>();
        for (String item : part.split("\0")) {
            int slash = item.indexOf('/');
            if (slash > 0) {
                counts.merge(item.substring(0, slash), 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Paths from lines like "find: ‘/home/xana/private’: Permission denied".
     */
    static Set<String> deniedPaths(String error) {
        Set<String> paths = new HashSet<>();
        for (String line : error.split("\\R")) {
            if (!line.contains("Permission denied")) {
                continue;
            }
            int start = indexOfAny(line, "‘'\"");
            int end = lastIndexOfAny(line, "’'\"");
            if (start >= 0 && end > start) {
                paths.add(line.substring(start + 1, end).replaceAll("/{2,}", "/"));
            }
        }
        return paths;
    }

    private static int indexOfAny(String text, String characters) {
        for (int i = 0; i < text.length(); i++) {
            if (characters.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOfAny(String text, String characters) {
        for (int i = text.length() - 1; i >= 0; i--) {
            if (characters.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }
}
