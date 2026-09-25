package com.kubo.model.shell;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A typed command whose whole output is a table drawn in text.
 *
 * <p>Those tables are made of box characters and columns padded with spaces. Reading one back means counting
 * spaces and trusting the next version of the tool to pad them the same way, and it breaks quietly the day it
 * does not. Every one of these tools will hand over the same information as data if asked, so Kubo asks for
 * that instead and draws the table itself, where a row knows which thing it is and can carry a button.
 */
public record TableCall(Kind kind, boolean asRoot) {

    /** Which table it is. The shape is here for docker and lsblk to join. */
    public enum Kind {
        /** {@code pm2 list} and its spellings, read back as {@code pm2 jlist}. */
        PM2,
        /** htop, top and the rest, read back as columns from ps. */
        PROCESSES
    }

    /** What makes a line more than one command; those are left to the shell. */
    private static final String SHELL_CHARACTERS = ";|&<>$`()\n";

    private static final List<String> PM2_LIST = List.of("list", "ls", "l", "status");

    /**
     * Programs whose whole job is to show what is running, drawn full screen.
     *
     * <p>Each one of them draws a terminal, refreshes it in place and never ends. Kubo asks ps the same
     * question instead and draws the answer, which is the same information, sortable, and with the process
     * id attached to the row rather than printed somewhere in it.
     */
    private static final List<String> MONITORS = List.of("htop", "top", "btop", "btop++", "atop", "glances");

    /** The two spellings of asking ps for everything, which is the same question without the drawing. */
    private static final List<String> PS_ALL = List.of("aux", "auxf", "-ef", "-ely", "ax");

    public TableCall {
        Objects.requireNonNull(kind, "kind");
    }

    /** The command as typed, if Kubo has a table for it, or empty for anything else. */
    public static Optional<TableCall> of(String typed) {
        String text = typed == null ? "" : typed.strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        for (int i = 0; i < text.length(); i++) {
            if (SHELL_CHARACTERS.indexOf(text.charAt(i)) >= 0) {
                return Optional.empty();
            }
        }
        List<String> words = List.of(text.split("\\s+"));
        boolean asRoot = words.get(0).equals("sudo") || words.get(0).equals("doas");
        int at = asRoot ? 1 : 0;
        String program = basename(words.get(at)).toLowerCase(Locale.ROOT);

        // On their own. "top -b -n1" is somebody wanting the text on purpose, most likely into a file.
        if (words.size() == at + 1 && MONITORS.contains(program)) {
            return Optional.of(new TableCall(Kind.PROCESSES, asRoot));
        }
        // Exactly the program and its subcommand. "pm2 list --watch" is somebody asking for something else.
        if (words.size() != at + 2) {
            return Optional.empty();
        }
        String subcommand = words.get(at + 1).toLowerCase(Locale.ROOT);
        if (program.equals("pm2") && PM2_LIST.contains(subcommand)) {
            return Optional.of(new TableCall(Kind.PM2, asRoot));
        }
        if (program.equals("ps") && PS_ALL.contains(subcommand)) {
            return Optional.of(new TableCall(Kind.PROCESSES, asRoot));
        }
        return Optional.empty();
    }

    private static String basename(String word) {
        int slash = word.lastIndexOf('/');
        return slash < 0 ? word : word.substring(slash + 1);
    }
}
