package com.kubo.model.packages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One place apt is allowed to fetch packages from.
 *
 * <p>Everything installed on the machine came from one of these, so they are the list of who is trusted to put
 * software on it. A third-party repository added years ago for one tool is still being asked, every time, and
 * is still allowed to ship anything it likes.
 *
 * <p>Apt reads two formats and Kubo has to read both. The old one is a line in a {@code .list} file. The newer
 * deb822 form is a paragraph in a {@code .sources} file, and is what a modern installer writes.
 *
 * @param file      the file it is written in, which is where somebody goes to change it by hand
 * @param line      the line it starts on, counting from 1
 * @param enabled   false when it is commented out, or has {@code Enabled: no}
 * @param signedBy  the keyring that has to have signed it, empty when the line does not say
 */
public record AptSource(String file, int line, boolean enabled, String types, String uri, String suites,
                        String components, String signedBy) {

    /** The domains a Debian or Ubuntu machine is built out of. Anything else came from somewhere. */
    private static final List<String> DISTRIBUTION = List.of(
            "debian.org", "ubuntu.com", "canonical.com", "raspbian.org", "raspberrypi.org",
            "linuxmint.com", "devuan.org", "kali.org", "proxmox.com");

    public AptSource {
        file = file == null ? "" : file;
        types = types == null ? "" : types;
        uri = uri == null ? "" : uri;
        suites = suites == null ? "" : suites;
        components = components == null ? "" : components;
        signedBy = signedBy == null ? "" : signedBy;
    }

    /** The host it fetches from, which is the part worth reading at a glance. */
    public String host() {
        String text = uri;
        int scheme = text.indexOf("://");
        if (scheme >= 0) {
            text = text.substring(scheme + 3);
        }
        int slash = text.indexOf('/');
        if (slash >= 0) {
            text = text.substring(0, slash);
        }
        int at = text.indexOf('@');
        return at >= 0 ? text.substring(at + 1) : text;
    }

    /**
     * Whether this is the distribution's own, as opposed to something added afterwards.
     *
     * <p>A judgement by hostname, and said as one on screen. It is right for the ordinary case, which is the
     * one worth being right about: everything that is not the distribution deserves a second look.
     */
    public boolean fromTheDistribution() {
        String host = host().toLowerCase(Locale.ROOT);
        return DISTRIBUTION.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
    }

    /**
     * Source packages and nothing else: nothing installed on the machine came from one of these.
     *
     * <p>Only when that is all it is. A deb822 paragraph says {@code Types: deb deb-src} and serves both, and
     * reading the word deb-src anywhere in it filed Debian's own repository under source code, which left the
     * distribution's list empty on a machine plainly running Debian.
     */
    public boolean sourceCode() {
        List<String> kinds = List.of(types.toLowerCase(Locale.ROOT).strip().split("\s+"));
        return kinds.contains("deb-src") && !kinds.contains("deb");
    }

    /** Whether it serves installable packages, which is what puts it in the main list. */
    public boolean servesPackages() {
        return !sourceCode();
    }

    /** What it is called on screen. */
    public String label() {
        String where = host();
        return suites.isBlank() ? where : where + "  " + suites;
    }

    /** Whether this is written in the newer deb822 form, which is switched off differently. */
    public boolean deb822() {
        return file.endsWith(".sources");
    }

    /**
     * Reads what {@code cat} of every source file printed, with a {@code @@file} marker before each one.
     */
    public static List<AptSource> parse(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        List<AptSource> found = new ArrayList<>();
        String file = "";
        int number = 0;
        Map<String, String> paragraph = new LinkedHashMap<>();
        int paragraphLine = 0;

        for (String raw : output.lines().toList()) {
            if (raw.startsWith("@@file ")) {
                flush(found, file, paragraphLine, paragraph);
                paragraph.clear();
                file = raw.substring("@@file ".length()).strip();
                number = 0;
                continue;
            }
            number++;
            String line = raw.strip();
            if (file.endsWith(".sources")) {
                if (line.isEmpty()) {
                    flush(found, file, paragraphLine, paragraph);
                    paragraph.clear();
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    if (paragraph.isEmpty()) {
                        paragraphLine = number;
                    }
                    paragraph.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).strip());
                }
                continue;
            }
            AptSource one = oneLine(file, number, line);
            if (one != null) {
                found.add(one);
            }
        }
        flush(found, file, paragraphLine, paragraph);
        return List.copyOf(found);
    }

    private static void flush(List<AptSource> into, String file, int line, Map<String, String> paragraph) {
        if (paragraph.isEmpty() || !paragraph.containsKey("uris")) {
            return;
        }
        // deb822 says Enabled: no rather than commenting the paragraph out.
        String enabled = paragraph.getOrDefault("enabled", "yes").toLowerCase(Locale.ROOT);
        into.add(new AptSource(file, line, !enabled.equals("no") && !enabled.equals("false"),
                paragraph.getOrDefault("types", "deb"),
                paragraph.get("uris"),
                paragraph.getOrDefault("suites", ""),
                paragraph.getOrDefault("components", ""),
                paragraph.getOrDefault("signed-by", "")));
    }

    /**
     * One {@code deb} line, enabled or commented out.
     *
     * <p>A commented line is only a source if it reads like one. {@code # see the wiki} is a comment somebody
     * wrote, and offering to switch it on would be nonsense.
     */
    private static AptSource oneLine(String file, int number, String line) {
        boolean enabled = true;
        String text = line;
        while (text.startsWith("#")) {
            enabled = false;
            text = text.substring(1).strip();
        }
        if (!text.startsWith("deb ") && !text.startsWith("deb-src ") && !text.startsWith("deb[")
                && !text.startsWith("deb-src[")) {
            return null;
        }
        String[] parts = text.split("\\s+");
        String types = parts[0];
        String options = "";
        int at = 1;
        if (at < parts.length && parts[at].startsWith("[")) {
            StringBuilder inside = new StringBuilder();
            while (at < parts.length) {
                inside.append(inside.isEmpty() ? "" : " ").append(parts[at]);
                boolean closed = parts[at].endsWith("]");
                at++;
                if (closed) {
                    break;
                }
            }
            options = inside.toString();
        }
        if (at >= parts.length) {
            return null;
        }
        String uri = parts[at++];
        String suites = at < parts.length ? parts[at++] : "";
        StringBuilder components = new StringBuilder();
        while (at < parts.length) {
            components.append(components.isEmpty() ? "" : " ").append(parts[at++]);
        }
        return new AptSource(file, number, enabled, types, uri, suites, components.toString(),
                signedBy(options));
    }

    private static String signedBy(String options) {
        for (String option : options.replace("[", "").replace("]", "").split("\\s+")) {
            if (option.toLowerCase(Locale.ROOT).startsWith("signed-by=")) {
                return option.substring("signed-by=".length());
            }
        }
        return "";
    }
}
