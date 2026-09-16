package com.kubo.model.files;

import java.util.List;
import java.util.Objects;

/**
 * The content of one folder, or why it could not be read.
 *
 * @param truncated true when the folder holds more entries than Kubo reads at once
 * @param error     empty when the folder was read
 */
public record DirectoryListing(String path, List<RemoteEntry> entries, boolean truncated, String error) {

    public DirectoryListing {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(error, "error");
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static DirectoryListing failed(String path, String error) {
        return new DirectoryListing(path, List.of(), false, error);
    }

    public boolean failed() {
        return !error.isEmpty();
    }
}
