package com.kubo.model.packages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What {@code apt-cache show} says about one package, read into something a screen can lay out.
 *
 * <p>Apt prints a control paragraph: {@code Field: value}, with continuation lines indented and a lone dot
 * standing for a blank line inside the description. It is a readable format and it is not a readable screen.
 * Kubo used to show it exactly as it arrived, so the one sentence saying what the thing is for sat below ten
 * lines of checksums.
 *
 * <p>Several versions of a package can be described in one answer, separated by a blank line. Only the first
 * paragraph is read: apt prints the candidate first, which is the one anybody is asking about.
 */
public record PackageSheet(
        String name,
        String version,
        String section,
        String architecture,
        String maintainer,
        String homepage,
        long installedKb,
        long downloadBytes,
        String summary,
        String description,
        List<String> depends,
        List<String> recommends,
        Map<String, String> other
) {

    public PackageSheet {
        name = name == null ? "" : name;
        version = version == null ? "" : version;
        section = section == null ? "" : section;
        architecture = architecture == null ? "" : architecture;
        maintainer = maintainer == null ? "" : maintainer;
        homepage = homepage == null ? "" : homepage;
        summary = summary == null ? "" : summary;
        description = description == null ? "" : description;
        depends = List.copyOf(depends == null ? List.of() : depends);
        recommends = List.copyOf(recommends == null ? List.of() : recommends);
        other = Map.copyOf(other == null ? Map.of() : other);
    }

    public boolean isEmpty() {
        return name.isBlank();
    }

    /**
     * Whether this looks like something a person chose rather than something that came in behind it.
     *
     * <p>Only a hint, and said as one on screen: apt's own priority field is the nearest thing to an answer and
     * it describes the package, not why this machine has it.
     */
    public boolean looksLikeALibrary() {
        return name.startsWith("lib") || section.contains("libs");
    }

    /**
     * Reads one {@code apt-cache show} answer.
     *
     * @return a sheet, empty when there was nothing to read
     */
    public static PackageSheet parse(String output) {
        if (output == null || output.isBlank()) {
            return empty();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        String field = null;
        StringBuilder value = new StringBuilder();
        for (String line : output.lines().toList()) {
            if (line.isBlank()) {
                // A blank line ends the paragraph, and the first paragraph is the candidate version.
                if (!fields.isEmpty()) {
                    break;
                }
                continue;
            }
            if (line.startsWith(" ") || line.startsWith("\t")) {
                // A continuation. A lone dot is apt's way of writing a blank line inside a description.
                String text = line.strip();
                value.append('\n').append(text.equals(".") ? "" : text);
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (field != null) {
                fields.put(field, value.toString());
            }
            field = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            value = new StringBuilder(line.substring(colon + 1).strip());
        }
        if (field != null && !fields.containsKey(field)) {
            fields.put(field, value.toString());
        }
        if (fields.isEmpty()) {
            return empty();
        }

        String whole = fields.getOrDefault("description", "");
        int firstBreak = whole.indexOf('\n');
        String summary = firstBreak < 0 ? whole : whole.substring(0, firstBreak);
        String rest = firstBreak < 0 ? "" : whole.substring(firstBreak + 1).strip();

        Map<String, String> other = new LinkedHashMap<>(fields);
        for (String known : List.of("package", "version", "section", "architecture", "maintainer", "homepage",
                "installed-size", "size", "description", "depends", "recommends")) {
            other.remove(known);
        }
        return new PackageSheet(
                fields.getOrDefault("package", ""),
                fields.getOrDefault("version", ""),
                fields.getOrDefault("section", ""),
                fields.getOrDefault("architecture", ""),
                fields.getOrDefault("maintainer", ""),
                fields.getOrDefault("homepage", ""),
                number(fields.get("installed-size")),
                number(fields.get("size")),
                summary,
                rest,
                names(fields.get("depends")),
                names(fields.get("recommends")),
                other);
    }

    public static PackageSheet empty() {
        return new PackageSheet("", "", "", "", "", "", 0, 0, "", "", List.of(), List.of(), Map.of());
    }

    /**
     * The package names out of a dependency field.
     *
     * <p>{@code libc6 (>= 2.34), libssl3 | libssl1.1, debconf (>= 0.5) | debconf-2.0} becomes the six names in
     * it. Versions and alternatives are dropped on purpose: what a screen does with these is offer them to be
     * opened, and only the name opens anything.
     */
    public static List<String> names(String field) {
        if (field == null || field.isBlank()) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (String part : field.replace('\n', ' ').split("[,|]")) {
            String name = part.strip();
            int bracket = name.indexOf('(');
            if (bracket > 0) {
                name = name.substring(0, bracket).strip();
            }
            int colon = name.indexOf(':');
            if (colon > 0) {
                // "libc6:amd64" is one package; the architecture is not part of what to look up next.
                name = name.substring(0, colon);
            }
            if (!name.isEmpty() && !found.contains(name)) {
                found.add(name);
            }
        }
        return List.copyOf(found);
    }

    private static long number(String text) {
        if (text == null) {
            return 0;
        }
        try {
            return Long.parseLong(text.strip().replaceAll("[^0-9]", ""));
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /** A size in bytes, written the way the rest of the screen writes sizes. */
    public static String bytes(long value) {
        if (value <= 0) {
            return "";
        }
        if (value >= 1024L * 1024 * 1024) {
            return Math.round(value / (1024.0 * 1024 * 1024) * 10) / 10.0 + " GB";
        }
        if (value >= 1024 * 1024) {
            return Math.round(value / (1024.0 * 1024) * 10) / 10.0 + " MB";
        }
        return Math.max(1, Math.round(value / 1024.0)) + " kB";
    }
}
