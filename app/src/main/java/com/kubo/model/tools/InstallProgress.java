package com.kubo.model.tools;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a package manager is doing, read from the lines it prints while it does it.
 *
 * <p>An install is the one thing in Kubo that takes real time and fetches over the network, and the screen used
 * to say nothing about it but a moving line. Package managers do say what they are doing, in figures: how much
 * there is to fetch, what has arrived, what is being unpacked. This reads those figures out of the words and
 * hands the screen something to write.
 *
 * <p>Nothing is guessed. A manager whose lines are not understood leaves the figures empty, and the screen shows
 * the lines themselves and the time. A made-up percentage would be worse than no percentage.
 */
public final class InstallProgress {

    /** What the manager is doing right now. */
    public enum Phase {
        /** The command has been sent and nothing recognisable has come back yet. */
        STARTING,
        /**
         * The manager is going through its own lists rather than the network.
         *
         * <p>Apt says this at the start of anything it does, and again at the end of reading the index, where
         * it is the last thing that happens. Without it, reading the index ended on UNPACKING, which is not
         * something that occurs while reading an index at all.
         */
        READING,
        /** Packages are coming over the network. */
        FETCHING,
        /** The archives are on the server and are being opened. */
        UNPACKING,
        /** The files are in place and the packages are being configured. */
        SETTING_UP,
        DONE,
        FAILED
    }

    /** How many of the server's own lines are kept to be shown. */
    private static final int TAIL = 7;

    // apt: "Get:12 http://deb.debian.org/debian bookworm/main amd64 lsof amd64 4.95.0-1 [376 kB]"
    private static final Pattern APT_GET = Pattern.compile("^Get:\\d+\\s.*\\[([\\d.,]+)\\s*([kKMGi]*B)]\\s*$");
    // apt: "Need to get 1,234 kB of archives."
    private static final Pattern APT_NEED = Pattern.compile("Need to get ([\\d.,]+)\\s*([kKMGi]*B)");
    // apt: "Fetched 1,234 kB in 2s (600 kB/s)"
    private static final Pattern APT_FETCHED =
            Pattern.compile("^Fetched ([\\d.,]+)\\s*([kKMGi]*B).*?(?:\\(([^)]*)\\))?\\s*$");
    // dnf: "Total download size: 1.2 M", zypper/pacman: "Total Download Size:   0.35 MiB"
    private static final Pattern TOTAL_SIZE =
            Pattern.compile("(?i)^total download size:\\s*([\\d.,]+)\\s*([kKMGi]*B?)\\s*$");
    // dnf: "(1/3): lsof-4.94.0-4.el9.x86_64.rpm   376 kB/s | 250 kB  00:00"
    private static final Pattern DNF_GOT = Pattern.compile("^\\(\\d+/\\d+\\):.*\\|\\s*([\\d.,]+)\\s*([kKMGi]*B)\\s");

    private Phase phase = Phase.STARTING;
    private long fetched;
    private long total;
    private int packages;
    private String speed = "";
    private final Deque<String> tail = new ArrayDeque<>();

    /**
     * Reads one line the server printed.
     *
     * <p>Every line is kept for the readout whether it was understood or not: the server's own words are the
     * one thing here that is certainly true.
     */
    public void accept(String line) {
        if (line == null) {
            return;
        }
        String text = line.strip();
        if (text.isEmpty()) {
            return;
        }
        remember(text);

        Matcher get = APT_GET.matcher(text);
        if (get.matches()) {
            phase = Phase.FETCHING;
            packages++;
            fetched += bytes(get.group(1), get.group(2));
            return;
        }
        Matcher need = APT_NEED.matcher(text);
        if (need.find()) {
            phase = Phase.FETCHING;
            total = bytes(need.group(1), need.group(2));
            return;
        }
        Matcher fetchedAll = APT_FETCHED.matcher(text);
        if (fetchedAll.matches()) {
            fetched = bytes(fetchedAll.group(1), fetchedAll.group(2));
            if (total < fetched) {
                total = fetched;
            }
            speed = fetchedAll.group(3) == null ? "" : fetchedAll.group(3).strip();
            phase = Phase.UNPACKING;
            return;
        }
        Matcher size = TOTAL_SIZE.matcher(text);
        if (size.matches()) {
            phase = Phase.FETCHING;
            total = bytes(size.group(1), size.group(2));
            return;
        }
        Matcher got = DNF_GOT.matcher(text);
        if (got.find()) {
            phase = Phase.FETCHING;
            packages++;
            fetched += bytes(got.group(1), got.group(2));
            return;
        }

        String lower = text.toLowerCase(Locale.ROOT);
        // "Reading package lists" only, never "Reading database": the second one turns up between fetching and
        // unpacking during an install, where it would flick the phase backwards for one line.
        if (lower.startsWith("reading package lists") || lower.startsWith("building dependency tree")
                || lower.startsWith("reading state information")) {
            phase = Phase.READING;
        } else if (lower.startsWith("downloading packages") || lower.startsWith("retrieving package")
                || lower.startsWith("downloading ")) {
            phase = Phase.FETCHING;
        } else if (lower.startsWith("preparing to unpack") || lower.startsWith("unpacking")
                || lower.startsWith("extracting")) {
            phase = Phase.UNPACKING;
        } else if (lower.startsWith("setting up") || lower.startsWith("installing")
                || lower.startsWith("configuring") || lower.startsWith("running scriptlet")) {
            phase = Phase.SETTING_UP;
        }
    }

    /** Says the command finished, which is the one thing its own output does not say reliably. */
    public void finish(boolean worked) {
        phase = worked ? Phase.DONE : Phase.FAILED;
    }

    public Phase phase() {
        return phase;
    }

    /** Bytes fetched so far, 0 when the manager has not said. */
    public long fetched() {
        return fetched;
    }

    /** Bytes there are to fetch in all, 0 when the manager has not said. */
    public long total() {
        return total;
    }

    /** How many packages have come over, which is usually more than the one that was asked for. */
    public int packages() {
        return packages;
    }

    /** The manager's own speed figure, such as "600 kB/s". Empty until it says one. */
    public String speed() {
        return speed;
    }

    /**
     * How far along, between 0 and 1, or -1 when there is no honest way to tell.
     *
     * <p>Unpacking and configuring have no figures at all, so the bar is full and the phase is the thing that
     * moves. That is truer than a bar creeping to 99 percent on a timer.
     */
    public double fraction() {
        if (phase == Phase.DONE) {
            return 1;
        }
        if (phase == Phase.UNPACKING || phase == Phase.SETTING_UP || phase == Phase.READING) {
            return total > 0 ? 1 : -1;
        }
        if (total <= 0 || fetched <= 0) {
            return -1;
        }
        return Math.min(1, (double) fetched / total);
    }

    /** The last lines the server printed, oldest first. */
    public List<String> tail() {
        return List.copyOf(tail);
    }

    /** The last line alone, for a one line readout. Empty before anything has come back. */
    public String last() {
        return tail.isEmpty() ? "" : tail.getLast();
    }

    /** What the phase is called on screen. */
    public String phaseName() {
        return switch (phase) {
            case STARTING -> "ASKING THE PACKAGE MANAGER";
            case READING -> "READING THE LISTS";
            case FETCHING -> "FETCHING";
            case UNPACKING -> "UNPACKING";
            case SETTING_UP -> "SETTING UP";
            case DONE -> "INSTALLED";
            case FAILED -> "FAILED";
        };
    }

    private void remember(String line) {
        tail.addLast(line);
        while (tail.size() > TAIL) {
            tail.removeFirst();
        }
    }

    /**
     * "1,234" plus "kB" in bytes. Managers write thousands with a comma, a dot or a space depending on the
     * locale the server is in, and mean kilobytes either way.
     */
    public static long bytes(String number, String unit) {
        String digits = number.replace(",", "").replace(" ", "");
        double value;
        try {
            value = Double.parseDouble(digits);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
        String scale = unit == null ? "" : unit.strip().toUpperCase(Locale.ROOT);
        long multiplier = 1;
        if (scale.startsWith("K")) {
            multiplier = 1024;
        } else if (scale.startsWith("M")) {
            multiplier = 1024L * 1024;
        } else if (scale.startsWith("G")) {
            multiplier = 1024L * 1024 * 1024;
        }
        return (long) (value * multiplier);
    }
}
