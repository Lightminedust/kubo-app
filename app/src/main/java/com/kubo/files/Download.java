package com.kubo.files;

import java.nio.file.Path;

/**
 * How a download ended.
 *
 * @param saved     where it was saved, null unless it succeeded
 * @param problem   what went wrong, empty on success
 * @param left      server entries that could not be read, or local names that could not be created
 * @param renamed   names changed because Windows refuses them
 */
public record Download(Path saved, String problem, boolean cancelled, int left, int renamed) {

    public static Download failed(String problem) {
        return new Download(null, problem.isBlank() ? "Download failed" : problem, false, 0, 0);
    }

    public static Download cancelledByUser() {
        return new Download(null, "Cancelled", true, 0, 0);
    }

    public boolean success() {
        return saved != null;
    }
}
