package com.kubo.model.services;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The one file Kubo gives a service of its own, edited a line at a time.
 *
 * <p>systemd reads a drop-in after the file a package shipped, so whatever is here wins, and taking the file
 * away puts the service back exactly as it was. Kubo keeps one such file per service and rewrites it: several
 * successive changes are one file at the end, not a pile of them.
 *
 * <p>Rewriting means reading what is there and changing only the line in question. Everything else — comments,
 * blank lines, settings Kubo knows nothing about, whatever the user typed — is left exactly where it was.
 * Regenerating the file from a model of what Kubo understands would quietly throw the rest away.
 */
public final class DropIn {

    /** What Kubo writes into an empty one, so the file it saves is always a valid unit fragment. */
    public static final String SECTION = "[Service]";

    private DropIn() {
    }

    /**
     * The same file with one setting set: the line replaced where it already is, or added under
     * {@code [Service]} when it is not there yet.
     */
    public static String with(String text, String key, String value) {
        List<String> lines = lines(text);
        Pattern shape = shapeOf(key);
        for (int i = 0; i < lines.size(); i++) {
            if (shape.matcher(lines.get(i)).find()) {
                lines.set(i, key + "=" + value);
                return String.join("\n", lines) + "\n";
            }
        }
        int section = sectionAt(lines);
        lines.add(section + 1, key + "=" + value);
        return String.join("\n", lines) + "\n";
    }

    /** The same file with that setting taken out, and nothing else touched. */
    public static String without(String text, String key) {
        List<String> lines = lines(text);
        Pattern shape = shapeOf(key);
        lines.removeIf(line -> shape.matcher(line).find());
        return String.join("\n", lines) + "\n";
    }

    /** What that setting is in this file, or null when the file does not set it. */
    public static String valueOf(String text, String key) {
        Pattern shape = shapeOf(key);
        for (String line : lines(text)) {
            if (shape.matcher(line).find()) {
                return line.substring(line.indexOf('=') + 1).trim();
            }
        }
        return null;
    }

    /** True when the file says nothing at all, in which case it is worth removing rather than saving. */
    public static boolean saysNothing(String text) {
        for (String line : lines(text)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith(";")
                    && !trimmed.startsWith("[")) {
                return false;
            }
        }
        return true;
    }

    /** A setting line for that key, whatever spacing was used around it, and never a commented-out one. */
    private static Pattern shapeOf(String key) {
        return Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*=");
    }

    private static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            lines.add(SECTION);
            return lines;
        }
        for (String line : text.strip().split("\\R", -1)) {
            lines.add(line);
        }
        if (sectionAt(lines) < 0) {
            lines.add(0, SECTION);
        }
        return lines;
    }

    /** Where {@code [Service]} is, or -1 when the file has no section header at all. */
    private static int sectionAt(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equalsIgnoreCase(SECTION)) {
                return i;
            }
        }
        return -1;
    }
}
