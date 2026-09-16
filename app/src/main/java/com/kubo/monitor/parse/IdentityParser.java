package com.kubo.monitor.parse;

import com.kubo.model.server.ServerIdentity;
import com.kubo.model.server.ServerIdentity.UserIdentity;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what does not change during a session: host, system, hardware, and who we are logged in as.
 */
public final class IdentityParser {

    public static final String COMMAND = Sections.script(
            "hostname", "hostname",
            "os", "cat /etc/os-release",
            "kernel", "uname -r",
            "cpu", "grep -m1 'model name' /proc/cpuinfo",
            "cores", "nproc",
            "memory", "grep MemTotal /proc/meminfo",
            "user", "id");

    private static final Pattern UID = Pattern.compile("uid=(\\d+)\\(([^)]*)\\)");

    private IdentityParser() {
    }

    /**
     * @throws IllegalArgumentException when an essential section is missing or malformed
     */
    public static ServerIdentity parse(String output) {
        Map<String, List<String>> sections = Sections.split(output);

        String cores = Sections.firstLine(sections, "cores");
        String memory = Sections.keyValues(Sections.lines(sections, "memory")).get("MemTotal");
        if (memory == null) {
            throw new IllegalArgumentException("Missing MemTotal");
        }

        try {
            return new ServerIdentity(
                    Sections.firstLine(sections, "hostname"),
                    osName(Sections.lines(sections, "os")),
                    Sections.firstLine(sections, "kernel"),
                    cpuModel(Sections.lines(sections, "cpu")),
                    Integer.parseInt(cores),
                    Long.parseLong(memory) * 1024,
                    user(Sections.firstLine(sections, "user")));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed number in identity: " + e.getMessage(), e);
        }
    }

    static UserIdentity user(String idLine) {
        Matcher uid = UID.matcher(idLine);
        if (!uid.find()) {
            throw new IllegalArgumentException("Unexpected id output: " + idLine);
        }
        int groupsStart = idLine.indexOf("groups=");
        String groups = groupsStart < 0 ? "" : idLine.substring(groupsStart);
        return new UserIdentity(
                uid.group(2),
                uid.group(1).equals("0"),
                groups.contains("(sudo)") || groups.contains("(wheel)") || groups.contains("(admin)"),
                groups.contains("(docker)"));
    }

    private static String osName(List<String> osRelease) {
        String name = null;
        for (String line : osRelease) {
            if (line.startsWith("PRETTY_NAME=")) {
                return unquote(line.substring("PRETTY_NAME=".length()));
            }
            if (line.startsWith("NAME=")) {
                name = unquote(line.substring("NAME=".length()));
            }
        }
        return name != null ? name : "Linux";
    }

    private static String cpuModel(List<String> cpuLines) {
        // ARM servers have no "model name" line.
        if (cpuLines.isEmpty()) {
            return "Unknown CPU";
        }
        String line = cpuLines.get(0);
        int colon = line.indexOf(':');
        String model = colon < 0 ? "" : line.substring(colon + 1).strip();
        return model.isEmpty() ? "Unknown CPU" : model;
    }

    private static String unquote(String value) {
        String result = value.strip();
        if (result.length() >= 2 && (result.startsWith("\"") && result.endsWith("\"")
                || result.startsWith("'") && result.endsWith("'"))) {
            return result.substring(1, result.length() - 1);
        }
        return result;
    }
}
