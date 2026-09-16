package com.kubo.files;

import java.util.Objects;

/**
 * Makes any text safe to pass as a single argument to a POSIX shell.
 */
public final class ShellQuote {

    private ShellQuote() {
    }

    /**
     * Wraps the text in single quotes; inside them nothing is interpreted except the quote itself,
     * which is closed, escaped and reopened. {@code it's} becomes {@code 'it'\''s'}.
     */
    public static String quote(String text) {
        Objects.requireNonNull(text, "text");
        return "'" + text.replace("'", "'\\''") + "'";
    }
}
