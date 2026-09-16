package com.kubo.docker;

import com.kubo.files.ShellQuote;
import com.kubo.model.files.RemoteEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reading a container's own filesystem through {@code docker exec}.
 *
 * <p>This is deliberately not the file manager's reading. The server Kubo connects to is a real Linux box with GNU
 * coreutils, so {@code find -printf} can be relied on there; a container image is whatever someone built, and a
 * great many of them are Alpine, whose BusyBox {@code find} has no {@code -printf} at all. So the listing here uses
 * {@code ls -la}, which BusyBox and GNU both print the same way, and reads a little less from it — no modification
 * time, since the date column is the one part the two disagree about.
 *
 * <p>Everything here only reads. There is no writing into a container: a container's filesystem is meant to be
 * rebuilt from its image, and editing it by hand is how servers end up in states nobody can reproduce.
 */
public final class ContainerFiles {

    /** A listing beyond this is cut; a container directory with more is almost always a cache or /proc. */
    public static final int MAX_ENTRIES = 2_000;
    /** Files larger than this are not shown: this is a viewer, not a download. */
    public static final long MAX_VIEW_BYTES = 256 * 1024;

    static final String NO_SHELL = "@@NOSHELL@@";
    static final String DENIED = "@@DENIED@@";

    private ContainerFiles() {
    }

    /**
     * Lists one directory inside a container.
     *
     * <p>The name is taken from the ninth field onwards, so names with spaces survive whole. A name containing a
     * newline would still be read as two broken lines and dropped; that is rare enough inside a container image to
     * be worth the simplicity, and a dropped line shows as a missing entry rather than as a wrong one.
     */
    public static String list(String containerId, String path) {
        String quoted = ShellQuote.quote(path);
        String script = "P=" + quoted + "; "
                + "if [ ! -d \"$P\" ] || [ ! -r \"$P\" ] || [ ! -x \"$P\" ]; then printf '" + DENIED + "'; exit 0; fi; "
                + "ls -la \"$P\" 2>/dev/null | head -n " + (MAX_ENTRIES + 1);
        return shell(containerId, script);
    }

    /**
     * Reads a text file inside a container, capped so a log file cannot fill the window.
     */
    public static String read(String containerId, String path) {
        String quoted = ShellQuote.quote(path);
        String script = "P=" + quoted + "; "
                + "if [ ! -f \"$P\" ] || [ ! -r \"$P\" ]; then printf '" + DENIED + "'; exit 0; fi; "
                + "head -c " + MAX_VIEW_BYTES + " \"$P\"";
        return shell(containerId, script);
    }

    /**
     * Wraps a read-only script for one container. A container built without a shell (a distroless or scratch image)
     * answers with a marker instead of a confusing docker error.
     */
    private static String shell(String containerId, String script) {
        return "docker exec " + ShellQuote.quote(containerId) + " /bin/sh -c " + ShellQuote.quote(script)
                + " 2>/dev/null || printf '" + NO_SHELL + "'";
    }

    /**
     * What a listing said, or why it could not be given.
     *
     * @param problem worded for the user, empty when the entries are good
     */
    public record Listing(List<RemoteEntry> entries, boolean truncated, String problem) {
        public Listing {
            entries = List.copyOf(entries == null ? List.of() : entries);
            problem = problem == null ? "" : problem;
        }

        public boolean failed() {
            return !problem.isEmpty();
        }

        public static Listing failed(String problem) {
            return new Listing(List.of(), false, problem);
        }
    }

    /**
     * Reads what {@link #list} printed.
     *
     * @param path the directory that was listed, so each entry can carry its full path
     */
    public static Listing parse(String path, String output) {
        String text = output == null ? "" : output;
        if (text.contains(NO_SHELL)) {
            return Listing.failed("This container has no shell, so Kubo cannot look inside it. Images built from "
                    + "scratch or distroless have nothing to run.");
        }
        if (text.contains(DENIED)) {
            return Listing.failed("This folder cannot be opened inside the container.");
        }

        List<RemoteEntry> entries = new ArrayList<>();
        boolean truncated = false;
        int lines = 0;
        for (String raw : text.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("total ")) {
                continue;
            }
            if (++lines > MAX_ENTRIES) {
                truncated = true;
                break;
            }
            RemoteEntry entry = readLine(path, line);
            if (entry != null && !entry.name().equals(".") && !entry.name().equals("..")) {
                entries.add(entry);
            }
        }
        if (entries.isEmpty() && lines == 0) {
            return new Listing(List.of(), false, "");
        }
        entries.sort((a, b) -> a.isDirectory() == b.isDirectory()
                ? a.name().compareToIgnoreCase(b.name())
                : a.isDirectory() ? -1 : 1);
        return new Listing(entries, truncated, "");
    }

    /**
     * One {@code ls -la} line: "drwxr-xr-x 2 root root 4096 Jan 1 00:00 name".
     *
     * <p>The first eight fields are fixed, so the name is everything after them; that keeps names with spaces
     * whole. A symlink's "name -&gt; target" is split back apart.
     */
    private static RemoteEntry readLine(String path, String line) {
        String[] fields = line.split("\\s+", 9);
        if (fields.length < 9 || fields[0].length() < 10) {
            return null;
        }
        String mode = fields[0];
        String owner = fields[2];
        String group = fields[3];
        long size = 0;
        String name;
        if (fields[4].endsWith(",")) {
            // A device file prints "major, minor" where a size would be, which is one field more than every other
            // line. Splitting one step further along puts the name back where it belongs.
            String[] wider = line.split("\\s+", 10);
            if (wider.length < 10) {
                return null;
            }
            name = wider[9];
        } else {
            try {
                size = Long.parseLong(fields[4]);
            } catch (NumberFormatException e) {
                size = 0;
            }
            name = fields[8];
        }
        String linkTarget = "";
        int arrow = name.indexOf(" -> ");
        if (arrow >= 0) {
            linkTarget = name.substring(arrow + 4);
            name = name.substring(0, arrow);
        }
        if (name.isEmpty()) {
            return null;
        }

        RemoteEntry.Type type = switch (mode.charAt(0)) {
            case 'd' -> RemoteEntry.Type.DIRECTORY;
            case 'l' -> RemoteEntry.Type.LINK;
            case '-' -> RemoteEntry.Type.FILE;
            default -> RemoteEntry.Type.OTHER;
        };
        boolean readable = mode.length() > 3 && mode.charAt(1) == 'r';
        return new RemoteEntry(name, join(path, name), type, Math.max(0, size),
                // ls prints the date in a form BusyBox and GNU disagree about, so this listing carries none.
                Instant.EPOCH, mode, owner, group, linkTarget, RemoteEntry.UNKNOWN, readable);
    }

    /**
     * A file read from inside a container: its text, or why there is none.
     */
    public record FileRead(String text, String problem) {
        public FileRead {
            text = text == null ? "" : text;
            problem = problem == null ? "" : problem;
        }

        public boolean failed() {
            return !problem.isEmpty();
        }
    }

    /**
     * What {@link #read} printed, or why it could not be read.
     */
    public static String readFile(String output) {
        String text = output == null ? "" : output;
        if (text.contains(NO_SHELL)) {
            return "";
        }
        return text.contains(DENIED) ? "" : text;
    }

    /**
     * True when the answer says the container could not be looked into at all.
     */
    public static String problemIn(String output) {
        String text = output == null ? "" : output;
        if (text.contains(NO_SHELL)) {
            return "This container has no shell, so Kubo cannot read files inside it.";
        }
        if (text.contains(DENIED)) {
            return "This file cannot be read inside the container.";
        }
        return "";
    }

    static String join(String directory, String name) {
        String base = directory.endsWith("/") ? directory : directory + "/";
        return base + name;
    }

    /**
     * True for the files worth opening in the viewer: a regular file, small enough, and not obviously binary.
     */
    public static boolean viewable(RemoteEntry entry) {
        return entry.type() == RemoteEntry.Type.FILE && entry.sizeBytes() <= MAX_VIEW_BYTES
                && !BINARY.contains(extensionOf(entry.name()));
    }

    private static final List<String> BINARY = List.of("so", "bin", "exe", "dll", "png", "jpg", "jpeg", "gif",
            "webp", "ico", "pdf", "zip", "gz", "tgz", "xz", "bz2", "7z", "jar", "war", "class", "o", "a", "db",
            "sqlite", "wasm", "ttf", "woff", "woff2", "mp4", "mp3");

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
