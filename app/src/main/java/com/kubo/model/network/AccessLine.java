package com.kubo.model.network;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One line of an Nginx access log, read far enough to be coloured and sorted.
 *
 * <p>The whole point of showing a domain's own log is that a line is scannable: who asked, for what, and what
 * they got. A wall of identical grey text is the file, and the file is already on the server.
 *
 * @param status {@link #NO_STATUS} when the line is not in a shape this reads, in which case only {@code raw}
 *               is filled and the line is still shown
 */
public record AccessLine(String address, String method, String path, int status, String size, String raw) {

    public static final int NO_STATUS = -1;

    /** The common and combined log formats, which is what Nginx writes unless somebody changed it. */
    private static final Pattern COMBINED = Pattern.compile(
            "^(\\S+) \\S+ \\S+ \\[[^]]*] \"(\\S+) (\\S+)[^\"]*\" (\\d{3}) (\\S+)");

    /** How a line reads at a glance. */
    public enum Level {
        /** The request was answered. */
        OK,
        /** The visitor was sent somewhere else. */
        MOVED,
        /** The visitor asked for something wrong: a bad path, a refused login. */
        REFUSED,
        /** The server failed. This is the one somebody opened the log to find. */
        BROKEN,
        /** Not a line this reads. */
        UNKNOWN
    }

    public AccessLine {
        address = address == null ? "" : address;
        method = method == null ? "" : method;
        path = path == null ? "" : path;
        size = size == null ? "" : size;
        raw = raw == null ? "" : raw;
    }

    /**
     * Reads one line, and keeps it whole whether it was understood or not.
     */
    public static AccessLine of(String line) {
        String text = line == null ? "" : line.strip();
        Matcher matcher = COMBINED.matcher(text);
        if (!matcher.find()) {
            return new AccessLine("", "", "", NO_STATUS, "", text);
        }
        int status;
        try {
            status = Integer.parseInt(matcher.group(4));
        } catch (NumberFormatException notANumber) {
            status = NO_STATUS;
        }
        return new AccessLine(matcher.group(1), matcher.group(2), matcher.group(3), status, matcher.group(5),
                text);
    }

    public Level level() {
        if (status == NO_STATUS) {
            return Level.UNKNOWN;
        }
        if (status >= 500) {
            return Level.BROKEN;
        }
        if (status >= 400) {
            return Level.REFUSED;
        }
        if (status >= 300) {
            return Level.MOVED;
        }
        return Level.OK;
    }

    public boolean read() {
        return status != NO_STATUS;
    }

    /** The line as this screen writes it: the status, then who asked for what. */
    public String sentence() {
        if (!read()) {
            return raw;
        }
        return status + "  " + method + " " + path + "   " + address;
    }
}
