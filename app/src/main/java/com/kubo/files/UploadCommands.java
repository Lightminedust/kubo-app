package com.kubo.files;

import com.kubo.command.CommandEntry;

import java.util.Optional;

/**
 * Commands receiving an upload on their input. What arrives lands in a hidden temporary entry of the destination
 * folder and takes its real name only once complete, so a cancelled or broken upload leaves nothing half-written.
 */
public final class UploadCommands {

    /**
     * What to do when the destination already has an entry with the same name.
     */
    public enum Conflict {
        /**
         * Stop with {@link FileCommands#EXISTS_EXIT}, so the user can choose.
         */
        REFUSE,
        /**
         * Replace a file of the same name; never used for folders.
         */
        REPLACE,
        /**
         * Pick a free name: "notes (2).md".
         */
        KEEP_BOTH
    }

    static final String INCOMPLETE = "Upload incomplete, nothing was kept";

    private UploadCommands() {
    }

    /**
     * Receives one file of {@code size} bytes; prints the path it ends at.
     */
    public static String file(String folder, String name, long size, Conflict conflict) {
        return destination(folder, name, conflict, false)
                + "T=$(mktemp -p \"$F\" .kubo-upload.XXXXXX) || exit 1; cat > \"$T\"; "
                + "if [ \"$(stat -c %s -- \"$T\")\" != '" + size + "' ]; then rm -f -- \"$T\"; "
                + "echo '" + INCOMPLETE + "' >&2; exit " + FileCommands.INCOMPLETE_EXIT + "; fi; "
                // mktemp creates private files: give the upload the mode a new file normally gets.
                + "chmod \"$(printf '%o' $((0666 & ~$(umask))))\" -- \"$T\"; "
                + "mv -T -- \"$T\" \"$D\" && printf '%s' \"$D\"";
    }

    /**
     * Receives a folder as a tar stream whose root is {@code name}; prints the path it ends at.
     *
     * @param entries the folder itself plus everything inside, to check nothing is missing
     */
    public static String folder(String folder, String name, long entries, Conflict conflict) {
        if (conflict == Conflict.REPLACE) {
            throw new IllegalArgumentException("A folder is never replaced by an upload");
        }
        return destination(folder, name, conflict, true)
                + "T=$(mktemp -d -p \"$F\" .kubo-upload.XXXXXX) || exit 1; "
                + "if tar -x -f - -C \"$T\" --no-same-owner --no-same-permissions "
                + "&& [ \"$(find \"$T/$N\" -printf x | wc -c)\" = '" + entries + "' ]; then "
                + "mv -T -- \"$T/$N\" \"$D\" && rmdir -- \"$T\" && printf '%s' \"$D\"; "
                + "else rm -rf -- \"$T\"; echo '" + INCOMPLETE + "' >&2; exit " + FileCommands.INCOMPLETE_EXIT + "; fi";
    }

    /**
     * The path printed by a successful upload.
     */
    public static Optional<String> parseDestination(CommandEntry entry) {
        String path = entry.output().strip();
        return entry.success() && path.startsWith("/") ? Optional.of(path) : Optional.empty();
    }

    /**
     * "notes (2).md" for "notes.md"; folders keep their dots.
     */
    static String[] stemAndExtension(String name, boolean directory) {
        int dot = name.lastIndexOf('.');
        if (directory || dot <= 0) {
            return new String[]{name, ""};
        }
        return new String[]{name.substring(0, dot), name.substring(dot)};
    }

    private static String destination(String folder, String name, Conflict conflict, boolean directory) {
        String base = folder.endsWith("/") ? folder : folder + "/";
        StringBuilder script = new StringBuilder()
                .append("F=").append(ShellQuote.quote(base))
                .append("; N=").append(ShellQuote.quote(name))
                .append("; D=\"$F$N\"; ");
        switch (conflict) {
            case REFUSE -> script.append("if [ -e \"$D\" ] || [ -L \"$D\" ]; then ")
                    .append("echo 'Something with this name is already there' >&2; exit ")
                    .append(FileCommands.EXISTS_EXIT).append("; fi; ");
            case KEEP_BOTH -> {
                String[] parts = stemAndExtension(name, directory);
                script.append("B=").append(ShellQuote.quote(parts[0]))
                        .append("; E=").append(ShellQuote.quote(parts[1]))
                        .append("; I=2; while [ -e \"$D\" ] || [ -L \"$D\" ]; do D=\"$F$B ($I)$E\"; I=$((I+1)); done; ");
            }
            case REPLACE -> script.append("if [ -d \"$D\" ] && [ ! -L \"$D\" ]; then ")
                    .append("echo 'A folder has this name: it is not replaced' >&2; exit ")
                    .append(FileCommands.EXISTS_EXIT).append("; fi; ");
        }
        return script.toString();
    }
}
