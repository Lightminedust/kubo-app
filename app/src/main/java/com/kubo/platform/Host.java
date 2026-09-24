package com.kubo.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.net.URISyntaxException;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * The computer Kubo itself is running on.
 *
 * <p>Kubo manages Linux servers, but it is a desktop application, and the desktop is not always Windows. Every
 * place that has to know which one keeps its answer here, so adding a system is one file rather than a search.
 *
 * <p>Each action is split in two: a pure method that builds the command line, which is what the tests check,
 * and a thin one that runs it.
 */
public final class Host {

    /**
     * The desktop systems Kubo is packaged for. Anything else still runs — it simply gets no file manager
     * and no local console.
     */
    public enum Kind {
        WINDOWS, LINUX, MAC, OTHER
    }

    private static final Kind KIND = of(System.getProperty("os.name", ""));

    private Host() {
    }

    /**
     * Reads a system name the way {@code os.name} reports it.
     */
    public static Kind of(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) {
            return Kind.WINDOWS;
        }
        if (name.startsWith("linux")) {
            return Kind.LINUX;
        }
        // Java reports macOS as "Mac OS X"; older builds said "Darwin".
        if (name.startsWith("mac") || name.startsWith("darwin")) {
            return Kind.MAC;
        }
        return Kind.OTHER;
    }

    public static Kind kind() {
        return KIND;
    }

    public static boolean isWindows() {
        return KIND == Kind.WINDOWS;
    }

    public static boolean isLinux() {
        return KIND == Kind.LINUX;
    }

    public static boolean isMac() {
        return KIND == Kind.MAC;
    }

    /**
     * Opens the folder holding a file, highlighting the file where the system can do that.
     *
     * <p>Only Windows and macOS can point at the file itself. Linux has no portable way to ask a file manager
     * to select one, so the folder is opened and the file is left to be found — better than doing nothing.
     */
    public static List<String> revealCommand(Path file, Kind kind) {
        Path parent = file.getParent() == null ? file : file.getParent();
        return switch (kind) {
            case WINDOWS -> List.of("explorer.exe", "/select," + file);
            case MAC -> List.of("open", "-R", file.toString());
            case LINUX, OTHER -> List.of("xdg-open", parent.toString());
        };
    }

    public static List<String> openFolderCommand(Path folder, Kind kind) {
        return switch (kind) {
            case WINDOWS -> List.of("explorer.exe", folder.toString());
            case MAC -> List.of("open", folder.toString());
            case LINUX, OTHER -> List.of("xdg-open", folder.toString());
        };
    }

    /**
     * @throws IOException when no file manager could be started
     */
    public static void reveal(Path file) throws IOException {
        start(revealCommand(file, KIND));
    }

    /**
     * @throws IOException when no file manager could be started
     */
    public static void openFolder(Path folder) throws IOException {
        start(openFolderCommand(folder, KIND));
    }

    /**
     * The command that opens a web address in whatever browser this computer uses.
     *
     * <p>On Windows the address goes through {@code rundll32} rather than {@code explorer}, because explorer
     * hands anything it does not recognise to the shell.
     */
    public static List<String> openLinkCommand(String url, Kind kind) {
        return switch (kind) {
            case WINDOWS -> List.of("rundll32", "url.dll,FileProtocolHandler", url);
            case MAC -> List.of("open", url);
            case LINUX, OTHER -> List.of("xdg-open", url);
        };
    }

    /**
     * Opens a web address on this computer, never on the server.
     *
     * <p>The address is checked before it is handed to anything. Addresses shown by Kubo can come off the
     * server, out of a package index it did not write, and a browser handed {@code file://} or a custom scheme
     * does things nobody asked for. Only plain http and https are opened; anything else is refused and said so.
     *
     * @throws IllegalArgumentException when it is not an http or https address
     * @throws IOException when no browser could be started
     */
    public static void openLink(String url) throws IOException {
        String address = url == null ? "" : url.strip();
        URI parsed;
        try {
            parsed = new URI(address);
        } catch (URISyntaxException notAnAddress) {
            throw new IllegalArgumentException("That is not a web address", notAnAddress);
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || parsed.getHost() == null) {
            throw new IllegalArgumentException("Only http and https addresses are opened, and this is: " + address);
        }
        start(openLinkCommand(parsed.toString(), KIND));
    }

    private static void start(List<String> command) throws IOException {
        new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}
