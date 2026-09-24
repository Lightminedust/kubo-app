package com.kubo.model.packages;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What apt says it would do, read out of a simulation before anything is allowed to happen.
 *
 * <p>Apt was already being asked; only its last line was kept. That line says "1 upgraded, 0 newly installed,
 * 14 to remove" and the fourteen are the whole point. Removing one library can take a web server, a database
 * and the thing holding the door open with it, and the number alone does not say which.
 *
 * <p>So the names are kept, and a few of them are treated as answers rather than entries in a list. A machine
 * reached over SSH that is about to lose its SSH server is a machine about to be lost, and that is worth
 * stopping in front of rather than reporting afterwards.
 */
public record PackagePlan(
        List<String> installing,
        List<String> removing,
        List<String> upgrading,
        long downloadBytes,
        long diskBytes,
        boolean freeingDisk,
        String summary,
        String problem
) {

    /**
     * The packages whose removal ends something that cannot be put back from here.
     *
     * <p>Not a list of important software. A list of what this machine is being administered through, plus the
     * two or three things nothing on a Linux system survives without. Losing a web server is a bad afternoon;
     * losing the SSH server means the way back in is the hosting provider's console, if there is one.
     */
    private static final Set<String> NEVER = Set.of(
            "openssh-server", "openssh-sftp-server", "ssh",
            "systemd", "systemd-sysv", "init", "sysvinit-core",
            "libc6", "libc-bin", "dpkg", "apt", "bash", "coreutils",
            "sudo", "linux-image-amd64", "linux-image-generic", "grub-common", "grub-pc");

    /** {@code Inst nginx (1.22.1-9 Debian:12/stable [amd64])} */
    private static final Pattern STEP = Pattern.compile("^(Inst|Remv|Conf)\\s+(\\S+)");

    /** {@code Need to get 12.4 MB of archives.} */
    private static final Pattern NEED = Pattern.compile("Need to get ([\\d.,]+)\\s*([kKMGi]*B)");

    /** {@code After this operation, 420 MB disk space will be freed.} */
    private static final Pattern AFTER =
            Pattern.compile("After this operation, ([\\d.,]+)\\s*([kKMGi]*B) .*?(freed|used)");

    public PackagePlan {
        installing = List.copyOf(installing == null ? List.of() : installing);
        removing = List.copyOf(removing == null ? List.of() : removing);
        upgrading = List.copyOf(upgrading == null ? List.of() : upgrading);
        summary = summary == null ? "" : summary;
        problem = problem == null ? "" : problem;
    }

    /** True when apt refused to answer at all, in which case nothing below means anything. */
    public boolean refused() {
        return !problem.isEmpty();
    }

    /**
     * The packages in this plan that must not go, in the order apt named them.
     *
     * <p>Empty for every ordinary change, which is almost all of them.
     */
    public List<String> breaking() {
        List<String> found = new ArrayList<>();
        for (String name : removing) {
            // "linux-image-6.1.0-18-amd64" and the like: the kernel this machine boots.
            if (NEVER.contains(name) || name.startsWith("linux-image-")) {
                found.add(name);
            }
        }
        return List.copyOf(found);
    }

    /** What to put in front of somebody before they confirm, or empty when there is nothing to say. */
    public String warning() {
        List<String> breaking = breaking();
        if (breaking.isEmpty()) {
            return "";
        }
        String names = String.join(", ", breaking);
        if (breaking.stream().anyMatch(name -> name.startsWith("openssh") || name.equals("ssh"))) {
            return "This removes " + names + ". Kubo is connected over SSH, and that is the SSH server. The "
                    + "session open now stays open; nothing will be able to connect afterwards, Kubo included, "
                    + "and the way back in is your provider's console.";
        }
        return "This removes " + names + ", which this machine needs to run or to boot. Apt will do it if you "
                + "tell it to, and there is no undoing it from here.";
    }

    /** One line saying what it costs, in words rather than in apt's arithmetic. */
    public String cost() {
        List<String> parts = new ArrayList<>();
        if (downloadBytes > 0) {
            parts.add(PackageSheet.bytes(downloadBytes) + " to download");
        }
        if (diskBytes > 0) {
            parts.add(PackageSheet.bytes(diskBytes) + (freeingDisk ? " freed on disk" : " more on disk"));
        }
        return String.join("  ·  ", parts);
    }

    /**
     * Reads {@code apt-get -s} output.
     *
     * <p>Apt prints one {@code Inst}, {@code Remv} or {@code Conf} line per package, then a summary. The
     * {@code Conf} lines are the same packages being configured and are not counted again.
     */
    public static PackagePlan parse(String output) {
        if (output == null || output.isBlank()) {
            return new PackagePlan(List.of(), List.of(), List.of(), 0, 0, false, "", "");
        }
        Set<String> installing = new LinkedHashSet<>();
        Set<String> removing = new LinkedHashSet<>();
        Set<String> upgrading = new LinkedHashSet<>();
        long download = 0;
        long disk = 0;
        boolean freeing = false;
        String summary = "";
        String problem = "";

        for (String raw : output.lines().toList()) {
            String line = raw.strip();
            String lower = line.toLowerCase(Locale.ROOT);
            Matcher step = STEP.matcher(line);
            if (step.find()) {
                String verb = step.group(1);
                String name = step.group(2);
                if (verb.equals("Remv")) {
                    removing.add(name);
                } else if (verb.equals("Inst")) {
                    // "Inst nginx [1.18.0-1] (1.22.1-9 ...)" replaces a version already there;
                    // "Inst nginx (1.22.1-9 ...)" is new. The bracket before the parenthesis is the difference,
                    // and a line with neither must not be read by comparing two -1s.
                    int bracket = line.indexOf('[');
                    int paren = line.indexOf('(');
                    if (bracket >= 0 && (paren < 0 || bracket < paren)) {
                        upgrading.add(name);
                    } else {
                        installing.add(name);
                    }
                }
                continue;
            }
            Matcher need = NEED.matcher(line);
            if (need.find()) {
                download = com.kubo.model.tools.InstallProgress.bytes(need.group(1), need.group(2));
                continue;
            }
            Matcher after = AFTER.matcher(line);
            if (after.find()) {
                disk = com.kubo.model.tools.InstallProgress.bytes(after.group(1), after.group(2));
                freeing = after.group(3).equals("freed");
                continue;
            }
            if (lower.contains("upgraded,") && lower.contains("newly installed")) {
                summary = line;
            } else if (lower.startsWith("e:")) {
                problem = line;
            }
        }
        return new PackagePlan(List.copyOf(installing), List.copyOf(removing), List.copyOf(upgrading),
                download, disk, freeing, summary, problem);
    }
}
