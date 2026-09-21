package com.kubo.platform;

import java.util.List;

/**
 * The console Kubo offers for this PC, beside the one it offers for the server.
 *
 * <p>It is deliberately not interactive: one line in, one result out, no session carried between them. That is
 * why every entry can be shown in full in the log, and why nothing can be left half-typed on a shell nobody is
 * watching.
 *
 * <p>On Windows this is PowerShell. Everywhere else it is {@code /bin/sh}, which every Unix has and which
 * behaves the same on all of them — no profile is loaded, so what runs is only what was typed.
 */
public final class LocalShell {

    private LocalShell() {
    }

    /**
     * The name to show in headings and tabs, in Kubo's uppercase.
     */
    public static String name() {
        return name(Host.kind());
    }

    static String name(Host.Kind kind) {
        return kind == Host.Kind.WINDOWS ? "POWERSHELL" : "SHELL";
    }

    /**
     * The same name written for a window title.
     */
    public static String title() {
        return title(Host.kind());
    }

    static String title(Host.Kind kind) {
        return kind == Host.Kind.WINDOWS ? "PowerShell" : "Shell";
    }

    /**
     * The few characters in front of the input line.
     */
    public static String sign() {
        return sign(Host.kind());
    }

    static String sign(Host.Kind kind) {
        return kind == Host.Kind.WINDOWS ? "PS›" : "$›";
    }

    /**
     * The greyed-out hint inside the empty input line.
     */
    public static String prompt() {
        return prompt(Host.kind());
    }

    static String prompt(Host.Kind kind) {
        return switch (kind) {
            case WINDOWS -> "run one PowerShell command on this Windows PC";
            case MAC -> "run one shell command on this Mac";
            case LINUX, OTHER -> "run one shell command on this PC";
        };
    }

    /**
     * The command line that runs one typed entry.
     *
     * <p>The entry is passed as a single argument, never pasted into a longer command line, so nothing in it
     * can be read as an option. Output is forced to UTF-8 on Windows, where the console would otherwise use
     * the machine's code page and mangle anything outside it.
     */
    public static List<String> command(String entry) {
        return command(entry, Host.kind());
    }

    static List<String> command(String entry, Host.Kind kind) {
        if (kind == Host.Kind.WINDOWS) {
            return List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                    "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); " + entry);
        }
        return List.of("/bin/sh", "-c", entry);
    }
}
