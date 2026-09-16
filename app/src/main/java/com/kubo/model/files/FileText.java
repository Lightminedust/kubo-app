package com.kubo.model.files;

import java.util.Objects;

/**
 * A text file read from the server for editing.
 *
 * @param text           the content with line breaks as "\n", empty for a binary file
 * @param version        the file's state on the server when read (modification time to the nanosecond, size and
 *                       inode), to detect changes made meanwhile
 * @param crlf           the file uses Windows line breaks, restored on save
 * @param readOnlyReason why the file can be shown but not saved, empty when it can be saved
 * @param error          why the file could not be read, empty on success
 */
public record FileText(String path, String text, String version, boolean crlf, String readOnlyReason, String error) {

    public FileText {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(readOnlyReason, "readOnlyReason");
        Objects.requireNonNull(error, "error");
    }

    public static FileText failed(String path, String error) {
        return new FileText(path, "", "", false, "", error.isBlank() ? "Cannot read the file" : error);
    }

    public boolean failed() {
        return !error.isEmpty();
    }

    public boolean readOnly() {
        return !readOnlyReason.isEmpty();
    }
}
