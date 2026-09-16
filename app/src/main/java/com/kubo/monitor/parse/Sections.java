package com.kubo.monitor.parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Several commands sent as one script, each output introduced by a marker line ("@@name"),
 * so a whole reading costs a single SSH round trip.
 */
public final class Sections {

    private static final String MARKER = "@@";

    private Sections() {
    }

    /**
     * Builds the script from pairs of section name and command: {@code script("load", "cat /proc/loadavg", ...)}.
     */
    public static String script(String... namesAndCommands) {
        if (namesAndCommands.length == 0 || namesAndCommands.length % 2 != 0) {
            throw new IllegalArgumentException("Expected pairs of section name and command");
        }
        StringBuilder script = new StringBuilder();
        for (int i = 0; i < namesAndCommands.length; i += 2) {
            if (!script.isEmpty()) {
                script.append("; ");
            }
            script.append("echo ").append(MARKER).append(namesAndCommands[i])
                    .append("; ").append(namesAndCommands[i + 1]);
        }
        return script.toString();
    }

    /**
     * Splits a script's output into its sections. Blank lines are dropped; lines before the first marker are ignored.
     */
    public static Map<String, List<String>> split(String output) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        List<String> current = null;
        for (String line : output.split("\\R")) {
            if (line.startsWith(MARKER)) {
                current = new ArrayList<>();
                sections.put(line.substring(MARKER.length()).strip(), current);
            } else if (current != null && !line.isBlank()) {
                current.add(line);
            }
        }
        return sections;
    }

    /**
     * The lines of a section, or an empty list when it is missing.
     */
    static List<String> lines(Map<String, List<String>> sections, String name) {
        return sections.getOrDefault(name, List.of());
    }

    /**
     * The first line of a section.
     *
     * @throws IllegalArgumentException when the section is missing or empty
     */
    static String firstLine(Map<String, List<String>> sections, String name) {
        List<String> lines = lines(sections, name);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Missing section: " + name);
        }
        return lines.get(0).strip();
    }

    /**
     * Reads "Key: value" lines such as /proc/meminfo; the value keeps only its first word.
     */
    static Map<String, String> keyValues(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String value = line.substring(colon + 1).strip();
                int space = value.indexOf(' ');
                values.put(line.substring(0, colon).strip(), space < 0 ? value : value.substring(0, space));
            }
        }
        return values;
    }
}
