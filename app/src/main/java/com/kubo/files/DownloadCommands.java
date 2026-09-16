package com.kubo.files;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Commands sending a file or a folder of the server on their output. What they announce about the file travels
 * on stderr, so the output carries nothing but the content.
 */
public final class DownloadCommands {

    static final String HEADER = "@@FILE ";

    /**
     * Size and modification time announced before a file's content.
     */
    public record Announced(long size, long modified) {
    }

    private DownloadCommands() {
    }

    public static String file(String path) {
        return "P=" + ShellQuote.quote(path) + "; "
                + "if [ ! -f \"$P\" ] || [ ! -r \"$P\" ]; then echo 'Not a file you can read' >&2; exit 3; fi; "
                + "printf '" + HEADER + "%s\\n' \"$(stat -L -c '%s %Y' -- \"$P\")\" >&2; "
                + "cat -- \"$P\"";
    }

    /**
     * The folder as a POSIX tar stream whose root is the folder's own name. Entries tar cannot read are
     * reported on stderr and left out.
     */
    public static String folder(String path) {
        if (!path.startsWith("/") || path.equals("/")) {
            throw new IllegalArgumentException("Refusing to download " + path);
        }
        return "P=" + ShellQuote.quote(path) + "; "
                + "if [ ! -d \"$P\" ] || [ ! -r \"$P\" ] || [ ! -x \"$P\" ]; then "
                + "echo 'Not a folder you can read' >&2; exit 3; fi; "
                + "cd -- \"$(dirname -- \"$P\")\" && tar --format=posix -c -f - -- \"$(basename -- \"$P\")\"";
    }

    public static Optional<Announced> announced(String error) {
        return error.lines()
                .filter(line -> line.startsWith(HEADER))
                .findFirst()
                .flatMap(line -> {
                    String[] parts = line.substring(HEADER.length()).strip().split(" ");
                    try {
                        return parts.length == 2
                                ? Optional.of(new Announced(Long.parseLong(parts[0]), Long.parseLong(parts[1])))
                                : Optional.empty();
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                });
    }

    /**
     * Stderr without the announcement: what went wrong, if anything.
     */
    public static String problems(String error) {
        return error.lines()
                .filter(line -> !line.startsWith(HEADER) && !line.isBlank())
                .collect(Collectors.joining("\n"));
    }
}
