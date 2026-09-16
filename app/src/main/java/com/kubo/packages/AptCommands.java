package com.kubo.packages;

import com.kubo.model.packages.AptStatus;
import com.kubo.model.packages.PackageEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The apt and dpkg commands Kubo runs, and the reading of their answers.
 *
 * <p>Everything that changes the server is a fixed template over a package name that has been checked against what
 * Debian actually allows in one, so nothing a user types can ever become part of the command itself.
 */
public final class AptCommands {

    /** What Debian policy allows in a package name, and nothing else. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9+.-]{1,99}");
    private static final Pattern UPGRADABLE =
            Pattern.compile("^([^/\\s]+)/(\\S+)\\s+(\\S+)\\s+\\S+\\s+\\[upgradable from: ([^]]+)]");

    /** Answers ABSENT, or the apt version. */
    public static final String STATUS = "if command -v apt-get >/dev/null 2>&1 && command -v dpkg >/dev/null 2>&1; "
            + "then printf 'OK %s\\n' \"$(apt-get --version 2>/dev/null | head -n1 | awk '{print $2}')\"; "
            + "else echo ABSENT; fi";

    /** Everything dpkg knows is installed, one line each. */
    public static final String INSTALLED = "dpkg-query -W -f='${binary:Package}\\t${Version}\\t${Installed-Size}\\t"
            + "${Section}\\t${Priority}\\t${Essential}\\t${db:Status-Status}\\n' 2>/dev/null";

    /** What has a newer version waiting. */
    public static final String UPGRADABLE_LIST = "apt list --upgradable 2>/dev/null";

    /** What apt would remove as no longer needed. */
    public static final String AUTOREMOVE_PREVIEW = "apt-get -s autoremove 2>/dev/null";

    /** The packages someone asked for, as opposed to the ones that came in as dependencies. */
    public static final String MANUAL = "apt-mark showmanual 2>/dev/null";

    /** Whether the kernel or a library update wants a restart. */
    public static final String REBOOT_REQUIRED = "test -f /var/run/reboot-required && echo YES || echo NO";

    private AptCommands() {
    }

    // Actions, all of which need root.

    /** Refreshes the list of what is available; changes nothing installed. */
    public static final String UPDATE_INDEX = "apt-get update";

    /** Installs the waiting versions without adding or removing anything else. */
    public static final String UPGRADE = "DEBIAN_FRONTEND=noninteractive apt-get -y upgrade";

    /** Installs the waiting versions even when that means adding or removing packages. */
    public static final String FULL_UPGRADE = "DEBIAN_FRONTEND=noninteractive apt-get -y full-upgrade";

    /** Removes what nothing depends on any more. */
    public static final String AUTOREMOVE = "DEBIAN_FRONTEND=noninteractive apt-get -y autoremove";

    /** Frees the downloaded archives. */
    public static final String CLEAN = "apt-get clean";

    public static String install(String name) {
        return "DEBIAN_FRONTEND=noninteractive apt-get -y install " + require(name);
    }

    /**
     * Removes a package but keeps whatever it put in /etc.
     */
    public static String remove(String name) {
        return "DEBIAN_FRONTEND=noninteractive apt-get -y remove " + require(name);
    }

    /**
     * Removes a package and its configuration too.
     */
    public static String purge(String name) {
        return "DEBIAN_FRONTEND=noninteractive apt-get -y purge " + require(name);
    }

    /**
     * What apt would do, without doing it. This is what Kubo shows before asking to go ahead.
     */
    public static String dryRunInstall(String name) {
        return "apt-get -s install " + require(name) + " 2>&1";
    }

    public static String dryRunRemove(String name) {
        return "apt-get -s remove " + require(name) + " 2>&1";
    }

    public static String describe(String name) {
        return "apt-cache show " + require(name) + " 2>/dev/null | head -n 40";
    }

    /**
     * Checks a package name against Debian policy and returns it, or refuses.
     */
    static String require(String name) {
        String value = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("A package name uses lower-case letters, digits and + - .");
        }
        return value;
    }

    /**
     * Why a package name cannot be used, or empty when it can.
     */
    public static java.util.Optional<String> nameProblem(String name) {
        try {
            require(name);
            return java.util.Optional.empty();
        } catch (IllegalArgumentException e) {
            return java.util.Optional.of(e.getMessage());
        }
    }

    // Reading

    public static AptStatus parseStatus(String output, String rebootAnswer) {
        String text = output == null ? "" : output.strip();
        boolean reboot = rebootAnswer != null && rebootAnswer.strip().equalsIgnoreCase("YES");
        if (text.startsWith("OK")) {
            String version = text.length() > 2 ? text.substring(2).strip() : "";
            return new AptStatus(AptStatus.Availability.USABLE, version, reboot);
        }
        return AptStatus.absent();
    }

    /**
     * Reads {@link #INSTALLED}. Lines that are not a fully installed package are skipped.
     */
    public static List<PackageEntry> parseInstalled(String output) {
        List<PackageEntry> packages = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length < 7) {
                continue;
            }
            // Only what is actually on disk; removed-but-configured packages are not installed.
            if (!fields[6].strip().equals("installed")) {
                continue;
            }
            long size = 0;
            try {
                size = Long.parseLong(fields[2].strip());
            } catch (NumberFormatException ignored) {
                // dpkg leaves the size empty for a few packages; they simply weigh nothing here.
            }
            packages.add(new PackageEntry(fields[0].strip(), fields[1].strip(), size, fields[3].strip(),
                    fields[4].strip(), fields[5].strip().equalsIgnoreCase("yes"), "", false, false));
        }
        return packages;
    }

    /**
     * Reads {@code apt list --upgradable}: the package, the version waiting, and whether it is a security fix.
     *
     * @return package name to the waiting version, and the set of names whose fix comes from a security suite
     */
    public static Map<String, String[]> parseUpgradable(String output) {
        Map<String, String[]> waiting = new LinkedHashMap<>();
        for (String raw : output.split("\\R")) {
            Matcher matcher = UPGRADABLE.matcher(raw.strip());
            if (!matcher.find()) {
                continue;
            }
            String suite = matcher.group(2).toLowerCase(Locale.ROOT);
            waiting.put(matcher.group(1), new String[]{matcher.group(3),
                    suite.contains("security") ? "security" : ""});
        }
        return waiting;
    }

    /**
     * Reads the names out of {@code apt-get -s autoremove}.
     */
    public static Set<String> parseAutoRemovable(String output) {
        Set<String> names = new LinkedHashSet<>();
        boolean inList = false;
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            if (line.toUpperCase(Locale.ROOT).contains("WILL BE REMOVED")) {
                inList = true;
                continue;
            }
            if (!inList) {
                continue;
            }
            // The block ends at the summary line, which starts with a count.
            if (line.isEmpty() || Character.isDigit(line.charAt(0)) || line.endsWith(":")) {
                break;
            }
            for (String name : line.split("\\s+")) {
                if (NAME.matcher(name).matches()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * Folds the three readings into one list: what is installed, what is waiting, what is no longer needed.
     */
    public static List<PackageEntry> merge(List<PackageEntry> installed, Map<String, String[]> waiting,
                                           Set<String> autoRemovable) {
        return merge(installed, waiting, autoRemovable, Set.of());
    }

    /**
     * Folds the four readings into one list: what is installed, what is waiting, what is no longer needed, and
     * which ones someone actually asked for.
     */
    public static List<PackageEntry> merge(List<PackageEntry> installed, Map<String, String[]> waiting,
                                           Set<String> autoRemovable, Set<String> manual) {
        List<PackageEntry> merged = new ArrayList<>(installed.size());
        for (PackageEntry entry : installed) {
            String[] update = waiting.get(entry.name());
            merged.add(new PackageEntry(entry.name(), entry.version(), entry.installedKb(), entry.section(),
                    entry.priority(), entry.essential(),
                    update == null ? "" : update[0],
                    update != null && "security".equals(update[1]),
                    autoRemovable.contains(entry.name()),
                    manual.contains(entry.name())));
        }
        return List.copyOf(merged);
    }

    /**
     * Reads a plain list of package names, one per line.
     */
    public static Set<String> parseNames(String output) {
        Set<String> names = new LinkedHashSet<>();
        for (String raw : output.lines().toList()) {
            String name = raw.strip();
            if (NAME.matcher(name).matches()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * The one-line summary apt prints at the end of a simulation, for the confirmation card.
     */
    public static String summarise(String dryRun) {
        String fallback = "";
        for (String raw : dryRun.split("\\R")) {
            String line = raw.strip();
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("upgraded,") && lower.contains("newly installed")) {
                return line;
            }
            if (lower.startsWith("e:")) {
                fallback = line;
            }
        }
        return fallback;
    }
}
