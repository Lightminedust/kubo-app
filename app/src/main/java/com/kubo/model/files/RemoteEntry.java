package com.kubo.model.files;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A file, folder or link on the server.
 *
 * @param permissions symbolic mode as printed by {@code ls -l}, e.g. "drwxr-xr-x"
 * @param linkTarget  where a link points, empty for anything else
 * @param childCount  entries directly inside a folder, or {@link #UNKNOWN} (not a folder, or not readable)
 * @param readable    false for a folder the connected user cannot open
 */
public record RemoteEntry(
        String name,
        String path,
        Type type,
        long sizeBytes,
        Instant modified,
        String permissions,
        String owner,
        String group,
        String linkTarget,
        int childCount,
        boolean readable
) {

    public enum Type { DIRECTORY, FILE, LINK, OTHER }

    public static final int UNKNOWN = -1;

    public RemoteEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(modified, "modified");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(linkTarget, "linkTarget");
        if (name.isEmpty() || path.isEmpty()) {
            throw new IllegalArgumentException("name and path cannot be empty");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes cannot be negative");
        }
        if (childCount < UNKNOWN) {
            throw new IllegalArgumentException("childCount must be UNKNOWN or positive");
        }
    }

    /**
     * A folder known only by its path, such as the home folder before it has been read.
     */
    public static RemoteEntry folder(String path) {
        return new RemoteEntry(nameOf(path), path, Type.DIRECTORY, 0, Instant.EPOCH, "", "", "", "", UNKNOWN, true);
    }

    public RemoteEntry withChildCount(int count) {
        return new RemoteEntry(name, path, type, sizeBytes, modified, permissions, owner, group, linkTarget, count, readable);
    }

    public boolean isDirectory() {
        return type == Type.DIRECTORY;
    }

    public boolean isHidden() {
        return name.startsWith(".");
    }

    /**
     * Lower-case extension without the dot, or an empty string.
     */
    public String extension() {
        int dot = name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Path of a child of the folder at {@code parent}.
     */
    public static String join(String parent, String name) {
        return parent.endsWith("/") ? parent + name : parent + "/" + name;
    }

    /**
     * Path of the folder holding {@code path}; empty for the root.
     */
    public static Optional<String> parentOf(String path) {
        String trimmed = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        if (trimmed.equals("/") || slash < 0) {
            return Optional.empty();
        }
        return Optional.of(slash == 0 ? "/" : trimmed.substring(0, slash));
    }

    private static String nameOf(String path) {
        String trimmed = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        return slash < 0 || trimmed.length() == 1 ? trimmed : trimmed.substring(slash + 1);
    }
}
