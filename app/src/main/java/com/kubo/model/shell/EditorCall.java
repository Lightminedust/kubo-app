package com.kubo.model.shell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A typed command that turns out to be somebody opening a file in an editor.
 *
 * <p>nano, vim and the rest draw a screen. They move the cursor around a grid, colour cells, redraw parts of
 * lines in place, and read single keystrokes. A console that shows lines of text can do none of that: what
 * arrives is the drawing instructions, and what the reader gets is a page of them with their file somewhere
 * inside. There is no version of this that works by trying harder.
 *
 * <p>Kubo has its own editor, on the same connection, which reads the file and writes it back. So a command
 * that means "open this file" is answered by opening it, rather than by running a program that cannot be seen.
 */
public record EditorCall(String program, List<String> paths, boolean asRoot) {

    /** The editors worth catching: the ones people actually type at a server. */
    private static final Set<String> EDITORS = Set.of(
            "nano", "pico", "vi", "vim", "nvim", "view", "emacs", "emacsclient", "joe", "micro", "mcedit",
            "jed", "ne", "kak", "helix", "hx", "ed");

    /**
     * What makes a line more than one command. Anything here and the words are not a plain call any more:
     * "nano notes.txt; rm -rf /tmp/x" reads as an editor call right up to the semicolon, and opening the
     * editor would quietly drop the rest of what somebody asked for.
     */
    private static final String SHELL_CHARACTERS = ";|&<>$`()\n";

    public EditorCall {
        Objects.requireNonNull(program, "program");
        paths = List.copyOf(Objects.requireNonNull(paths, "paths"));
    }

    /** The command as typed, if it opens an editor, or empty for anything else. */
    public static Optional<EditorCall> of(String command) {
        String text = command == null ? "" : command.strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        for (int i = 0; i < text.length(); i++) {
            if (SHELL_CHARACTERS.indexOf(text.charAt(i)) >= 0) {
                return Optional.empty();
            }
        }
        List<String> words = split(text);
        if (words.isEmpty()) {
            return Optional.empty();
        }
        boolean asRoot = false;
        int at = 0;
        if (words.get(0).equals("sudo") || words.get(0).equals("doas")) {
            asRoot = true;
            at = 1;
        }
        if (at >= words.size()) {
            return Optional.empty();
        }
        String program = basename(words.get(at)).toLowerCase(Locale.ROOT);
        if (!EDITORS.contains(program)) {
            return Optional.empty();
        }
        List<String> paths = new ArrayList<>();
        for (int i = at + 1; i < words.size(); i++) {
            String word = words.get(i);
            // Options, and vim's "+42" for the line to land on. Neither is a file.
            if (word.startsWith("-") || word.startsWith("+")) {
                continue;
            }
            paths.add(word);
        }
        return Optional.of(new EditorCall(program, paths, asRoot));
    }

    private static String basename(String word) {
        int slash = word.lastIndexOf('/');
        return slash < 0 ? word : word.substring(slash + 1);
    }

    /**
     * The words of a command, with the quoting taken off, so a path with a space in it stays one path.
     */
    private static List<String> split(String command) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean started = false;
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    word.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                started = true;
            } else if (c == '\\' && i + 1 < command.length()) {
                word.append(command.charAt(++i));
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started || word.length() > 0) {
                    words.add(word.toString());
                    word.setLength(0);
                    started = false;
                }
            } else {
                word.append(c);
            }
        }
        if (started || word.length() > 0) {
            words.add(word.toString());
        }
        return words;
    }

    /**
     * The file to open, made absolute against a home folder.
     *
     * <p>Every command in this console runs in its own session, which starts in the home folder. So a
     * relative path means what it would have meant to the shell, and "~" means what it always means.
     */
    public static String resolve(String path, String home) {
        String start = home == null || home.isBlank() ? "/" : home;
        if (path.startsWith("/")) {
            return path;
        }
        if (path.equals("~")) {
            return start;
        }
        if (path.startsWith("~/")) {
            return join(start, path.substring(2));
        }
        return join(start, path);
    }

    private static String join(String folder, String name) {
        return folder.endsWith("/") ? folder + name : folder + "/" + name;
    }
}
