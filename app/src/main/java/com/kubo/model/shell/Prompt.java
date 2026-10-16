package com.kubo.model.shell;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reading the last line a command printed, to guess whether it is waiting for a secret.
 *
 * <p>A guess, and treated as one everywhere it is used. SSH carries no "the remote turned echo off" message:
 * the terminal lives on the server, its driver stops echoing, and the client is told nothing. So the only
 * thing left to look at is what the program wrote, which is text a program chose and could have chosen
 * differently.
 *
 * <p>That is why it only ever decides where a switch starts, never where it stays. Somebody who can see their
 * own screen knows better than this does, and can say so with one click either way.
 */
public final class Prompt {

    /** Words that only turn up when something is asking to be let in. */
    private static final List<String> SECRET = List.of(
            "password", "passphrase", "pin", "secret", "token", "unlock code", "verification code",
            "mot de passe", "phrase secrète");

    /** Words that turn up in a question that is not a secret, and must not be masked. */
    private static final List<String> NOT_SECRET = List.of(
            "username", "user name", "login", "email", "account", "fingerprint", "yes/no", "[y/n]");

    private Prompt() {
    }

    /**
     * Whether this line reads like a request for something that should not be shown while it is typed.
     *
     * <p>Two conditions, both needed. The line has to end the way a prompt ends, with a colon or a question
     * mark and nothing after it, and it has to name the kind of thing being asked. "Password:" is a prompt;
     * "password authentication failed" is a report about one, and masking the next thing somebody types
     * because of it would be wrong and confusing.
     */
    public static boolean asksForASecret(String line) {
        String text = line == null ? "" : line.strip();
        if (text.isEmpty() || !endsLikeAQuestion(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (NOT_SECRET.stream().anyMatch(lower::contains)) {
            return false;
        }
        return SECRET.stream().anyMatch(lower::contains);
    }

    /**
     * Whether this line reads like a question at all, secret or not.
     *
     * <p>What the console uses to decide it is worth showing the line above the answer field rather than
     * leaving somebody to find it in the output.
     */
    public static boolean asksSomething(String line) {
        String text = line == null ? "" : line.strip();
        return !text.isEmpty() && endsLikeAQuestion(text) && text.length() <= 200;
    }

    private static boolean endsLikeAQuestion(String text) {
        char last = text.charAt(text.length() - 1);
        if (last == ':' || last == '?') {
            return true;
        }
        // "Overwrite it? [y/N]" and "Continue [Y/n]": the commonest ending of all on Linux, and it ends on a
        // bracket rather than on any punctuation that looks like a question.
        String lower = text.toLowerCase(Locale.ROOT);
        return last == ']' && (lower.endsWith("[y/n]") || lower.endsWith("[n/y]")
                || lower.endsWith("[yes/no]") || lower.endsWith("[o/n]"));
    }

    /**
     * The folder a shell prompt says it is sitting in, or empty when the line is not a shell prompt.
     *
     * <p>The default prompt on Debian, Ubuntu and their family is "user@host:/where/it/is$". The folder is
     * written into it, which is the only way Kubo can know where an interactive shell has got to: every
     * command it sends of its own gets a fresh session that starts at home, and it never sees the "cd" that
     * somebody typed into a shell it is only relaying for.
     *
     * <p>A prompt somebody has rewritten will not match, and that is answered by saying so rather than by
     * guessing: opening the wrong file is worse than opening none.
     */
    public static Optional<String> shellFolder(String line) {
        String text = line == null ? "" : line.strip();
        if (text.length() < 2) {
            return Optional.empty();
        }
        char last = text.charAt(text.length() - 1);
        if (last != '#' && last != '$') {
            return Optional.empty();
        }
        int colon = text.lastIndexOf(':');
        int at = text.indexOf('@');
        if (colon < 0 || at < 0 || at > colon) {
            return Optional.empty();
        }
        String folder = text.substring(colon + 1, text.length() - 1).strip();
        if (folder.isEmpty() || folder.indexOf(' ') >= 0) {
            return Optional.empty();
        }
        return folder.startsWith("/") || folder.startsWith("~") ? Optional.of(folder) : Optional.empty();
    }

    /**
     * The question, tidied for a label.
     *
     * <p>A terminal redraws in place, so what arrives can carry escape sequences and the odd stray return.
     * They belong in a terminal and not in a line of prose above a text field.
     */
    public static String tidy(String line) {
        String text = line == null ? "" : line;
        // Everything a terminal reads and never shows, in the order it has to be taken out.
        return text
                // The window title, which a themed shell rewrites on every prompt. It is the longest of
                // these and the one that left a hostname and a bell sitting in the middle of a line.
                .replaceAll("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)?", "")
                // Anything from an escape up to the letter that ends the sequence: colour, cursor moves.
                .replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "")
                .replaceAll("\u001B[()][A-Za-z0-9]", "")
                // The two-character ones, which match none of the above. ESC = is the keypad mode a
                // shell sets when it takes the line, and it came out as a box and an equals sign.
                .replaceAll("\u001B[=><78MncDEHZ]", "")
                .replace("\u0007", "")
                .replace("\r", "")
                .strip();
    }
}
