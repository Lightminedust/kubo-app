package com.kubo.files;

import com.kubo.command.CommandEntry;
import com.kubo.model.files.Account;
import com.kubo.model.files.FileText;
import com.kubo.model.files.PermissionMode;
import com.kubo.model.files.RemoteEntry;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The shell commands behind file actions, and the reading of their answers. Every path goes through
 * {@link ShellQuote}; nothing typed by the user is ever run as shell syntax here.
 */
public final class FileCommands {

    // The editor stays fluid up to this size; configuration files and scripts are far below it.
    public static final long MAX_EDIT_BYTES = 524_288;
    /**
     * Exit code of a save refused because the file changed on the server since it was read.
     */
    public static final int CHANGED_EXIT = 9;
    static final int INCOMPLETE_EXIT = 8;
    /**
     * Exit code of an action refused because the destination name is taken.
     */
    public static final int EXISTS_EXIT = 17;

    private static final String CHANGED_MESSAGE = "Changed on the server since it was opened";

    private FileCommands() {
    }

    /**
     * Why a name cannot be used for a new or renamed entry, or empty when it can.
     */
    public static Optional<String> nameProblem(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.of("Type a name");
        }
        if (!name.equals(name.strip())) {
            return Optional.of("No space at the start or the end");
        }
        if (name.equals(".") || name.equals("..")) {
            return Optional.of("\".\" and \"..\" are reserved");
        }
        if (name.indexOf('/') >= 0) {
            return Optional.of("A name cannot contain /");
        }
        if (name.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return Optional.of("No line break or control character");
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > 255) {
            return Optional.of("Too long: 255 bytes at most");
        }
        return Optional.empty();
    }

    /**
     * Why an entry cannot be copied or moved into a folder, or empty when it can.
     */
    public static Optional<String> destinationProblem(String source, String folder, boolean move) {
        if (folder.equals(source) || folder.startsWith(source.endsWith("/") ? source : source + "/")) {
            return Optional.of("A folder cannot go inside itself");
        }
        if (move && RemoteEntry.parentOf(source).map(folder::equals).orElse(false)) {
            return Optional.of("It is already in this folder");
        }
        return Optional.empty();
    }

    /**
     * Where a copy or a move lands. A copy in its own folder gets a new name: "notes.md" becomes "notes copy.md".
     */
    public static String destinationPath(String source, String folder) {
        String name = RemoteEntry.folder(source).name();
        boolean sameFolder = RemoteEntry.parentOf(source).map(folder::equals).orElse(false);
        return RemoteEntry.join(folder, sameFolder ? copyName(name) : name);
    }

    static String copyName(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name + " copy" : name.substring(0, dot) + " copy" + name.substring(dot);
    }

    /**
     * A file's state as {@code find -printf} gives it: modification time to the nanosecond, size and inode.
     * Any save, even within the same second, or any replacement of the file changes it.
     */
    private static final String VERSION = "find -L \"$P\" -maxdepth 0 -printf '%T@:%s:%i' 2>/dev/null";
    private static final java.util.regex.Pattern VERSION_FORMAT =
            java.util.regex.Pattern.compile("[0-9]+(\\.[0-9]+)?:[0-9]+:[0-9]+");

    public static String read(String path) {
        return "P=" + ShellQuote.quote(path) + "; "
                + "if [ ! -f \"$P\" ]; then echo 'Not a regular file' >&2; exit 2; fi; "
                + "if [ ! -r \"$P\" ]; then echo 'Permission denied' >&2; exit 3; fi; "
                + "S=$(stat -L -c %s -- \"$P\"); "
                + "if [ \"$S\" -gt " + MAX_EDIT_BYTES + " ]; then echo \"Too large to edit here: $S bytes, 512 KiB at most\" >&2; exit 4; fi; "
                + "if [ -w \"$P\" ]; then W=w; else W=r; fi; "
                + "printf '%s %s\\n' \"$(" + VERSION + ")\" \"$W\"; "
                + "cat -- \"$P\"";
    }

    /**
     * Reads the answer of {@link #read}: a first line "version w|r", then the content.
     */
    public static FileText parseRead(String path, CommandEntry entry) {
        if (!entry.success()) {
            return FileText.failed(path, problem(entry));
        }
        String output = entry.output();
        int newline = output.indexOf('\n');
        String[] header = newline < 0 ? new String[0] : output.substring(0, newline).split(" ");
        if (header.length != 2 || !VERSION_FORMAT.matcher(header[0]).matches()) {
            return FileText.failed(path, "Unexpected answer from the server");
        }
        String version = header[0];
        String content = output.substring(newline + 1);
        boolean crlf = content.contains("\r\n");
        String text = crlf ? content.replace("\r\n", "\n") : content;

        String reason = "";
        if (text.indexOf('\0') >= 0) {
            reason = "Binary file";
            text = "";
        } else if (text.indexOf('�') >= 0) {
            reason = "Not UTF-8 text: saving would damage it";
        } else if (text.chars().anyMatch(c -> c < 0x20 && c != '\n' && c != '\t' && c != '\r' || c == 0x7f)) {
            reason = "Contains control characters the editor cannot keep";
        } else if (text.indexOf('\r') >= 0) {
            reason = "Mixed line breaks the editor cannot keep";
        } else if (!header[1].equals("w")) {
            reason = "No write permission";
        }
        return new FileText(path, text, version, crlf, reason, "");
    }

    /**
     * Saves the bytes sent on the command's input without ever leaving the file half-written.
     * <ol>
     *     <li>The content first lands in a temporary file next to the real one, and must arrive complete.</li>
     *     <li>When the file can simply be swapped (its folder is writable, it has a single name, and its owner,
     *     group and mode can be given to the new copy), the copy replaces it in one atomic rename.</li>
     *     <li>Otherwise it is rewritten in place, after a safety copy that is written back if writing fails.</li>
     * </ol>
     * A link is followed: the file it points to is saved. Prints the new version on success.
     *
     * @param expectedVersion the version read with the file; the save is refused if it changed. Empty to overwrite.
     */
    public static String write(String path, long byteCount, Optional<String> expectedVersion) {
        String check = expectedVersion.isEmpty() ? ""
                : "if [ \"$(" + VERSION + ")\" != " + ShellQuote.quote(expectedVersion.get()) + " ]; then "
                + "echo '" + CHANGED_MESSAGE + "' >&2; exit " + CHANGED_EXIT + "; fi; ";
        return "P=$(readlink -f -- " + ShellQuote.quote(path) + ") || exit 1; D=$(dirname -- \"$P\"); " + check
                + "T=$(mktemp -p \"$D\" .kubo-save.XXXXXX 2>/dev/null) || T=$(mktemp) || exit 1; cat > \"$T\"; "
                + "if [ \"$(stat -c %s -- \"$T\")\" != '" + byteCount + "' ]; then rm -f -- \"$T\"; "
                + "echo 'Upload incomplete, the file was left unchanged' >&2; exit " + INCOMPLETE_EXIT + "; fi; "
                // Deleted since it was opened and saved anyway: the copy simply takes its place.
                + "if [ ! -e \"$P\" ]; then chmod \"$(printf '%o' $((0666 & ~$(umask))))\" -- \"$T\"; "
                + "mv -T -- \"$T\" \"$P\" || { rm -f -- \"$T\"; exit 1; }; "
                + "elif [ \"$(dirname -- \"$T\")\" = \"$D\" ] && [ \"$(stat -c %h -- \"$P\")\" = 1 ] "
                + "&& chmod --reference=\"$P\" -- \"$T\" "
                + "&& { chown --reference=\"$P\" -- \"$T\" 2>/dev/null; [ \"$(stat -c %u:%g -- \"$T\")\" = \"$(stat -c %u:%g -- \"$P\")\" ]; } "
                + "&& mv -f -T -- \"$T\" \"$P\"; then :; "
                + "else B=$(mktemp) || { rm -f -- \"$T\"; exit 1; }; "
                + "if ! cp -- \"$P\" \"$B\"; then rm -f -- \"$T\" \"$B\"; "
                + "echo 'No room for a safety copy, the file was left unchanged' >&2; exit 1; fi; "
                + "if ! cat -- \"$T\" > \"$P\"; then cat -- \"$B\" > \"$P\"; rm -f -- \"$T\" \"$B\"; "
                + "echo 'Writing failed, the previous content was put back' >&2; exit 1; fi; "
                + "rm -f -- \"$T\" \"$B\"; fi; "
                + VERSION;
    }

    /**
     * The bytes to send for a text, with the file's own line breaks.
     */
    public static byte[] encode(String text, boolean crlf) {
        return (crlf ? text.replace("\n", "\r\n") : text).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The new version printed by a successful {@link #write}.
     */
    public static Optional<String> parseVersion(CommandEntry entry) {
        String version = entry.output().strip();
        return entry.success() && VERSION_FORMAT.matcher(version).matches() ? Optional.of(version) : Optional.empty();
    }

    /**
     * Deletes a file, a link (not its target) or a folder with everything inside, without crossing into
     * other mounted filesystems.
     */
    public static String delete(RemoteEntry entry) {
        requireRemovable(entry.path());
        return entry.isDirectory()
                ? "rm -rf --one-file-system -- " + ShellQuote.quote(entry.path())
                : "rm -f -- " + ShellQuote.quote(entry.path());
    }

    public static String copy(String source, String destination) {
        return transfer("cp -a -T", source, destination);
    }

    /**
     * Moves or renames.
     */
    public static String move(String source, String destination) {
        requireRemovable(source);
        return transfer("mv -T", source, destination);
    }

    public static String changeMode(String path, PermissionMode mode) {
        return "chmod " + mode.chmodArgument() + " -- " + ShellQuote.quote(path);
    }

    public static String createFolder(String path) {
        return "mkdir -- " + ShellQuote.quote(path);
    }

    /**
     * Creates an empty file; fails if anything already has this name.
     */
    public static String createFile(String path) {
        return "set -C; : > " + ShellQuote.quote(path);
    }

    /**
     * The first lines of what went wrong, for the user.
     */
    public static String problem(CommandEntry entry) {
        String error = entry.error().strip();
        if (error.isEmpty()) {
            return entry.ran() ? "Failed with exit code " + entry.exitCode() : "Could not run";
        }
        List<String> lines = error.lines().limit(3).toList();
        return String.join("\n", lines);
    }

    /**
     * GNU tools Kubo's commands rely on (find -printf, stat -c, mv -T, readlink -f, tar --format). Prints the ones
     * that are missing or not GNU, separated by spaces.
     */
    static final String GNU_TOOLS = "for T in find stat mv cp readlink tar mktemp du df; do "
            + "\"$T\" --version 2>/dev/null | head -n 1 | grep -q GNU || printf '%s ' \"$T\"; done";

    static List<String> parseMissingTools(CommandEntry entry) {
        if (!entry.ran()) {
            return List.of();
        }
        String output = entry.output().strip();
        return output.isEmpty() ? List.of() : List.of(output.split("\\s+"));
    }

    /**
     * The id, name, groups and home folder of the connected user, one per line.
     */
    static final String ACCOUNT = "id -u; id -un; id -Gn; printf '%s\\n' \"$HOME\"";

    static Optional<Account> parseAccount(CommandEntry entry) {
        List<String> lines = entry.output().lines().toList();
        if (!entry.success() || lines.size() < 4) {
            return Optional.empty();
        }
        try {
            Set<String> groups = Set.copyOf(Arrays.asList(lines.get(2).strip().split("\\s+")));
            return Optional.of(new Account(Integer.parseInt(lines.get(0).strip()), lines.get(1).strip(), groups,
                    lines.get(3).strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String transfer(String tool, String source, String destination) {
        return "S=" + ShellQuote.quote(source) + "; D=" + ShellQuote.quote(destination) + "; "
                + "if [ -e \"$D\" ] || [ -L \"$D\" ]; then echo 'Something with this name is already there' >&2; "
                + "exit " + EXISTS_EXIT + "; fi; "
                + tool + " -- \"$S\" \"$D\"";
    }

    private static void requireRemovable(String path) {
        if (!path.startsWith("/") || RemoteEntry.parentOf(path).isEmpty()) {
            throw new IllegalArgumentException("Refusing to remove " + path);
        }
    }
}
