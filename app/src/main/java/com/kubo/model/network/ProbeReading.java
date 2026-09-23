package com.kubo.model.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a ping, a trace or a port check actually said, read out of the text they print.
 *
 * <p>The raw answer stays on screen. This is the line above it: the verdict somebody would have worked out by
 * reading the raw answer, worked out for them.
 */
public final class ProbeReading {

    private ProbeReading() {
    }

    /** How a reading went, which is what decides its colour. */
    public enum Verdict {
        GOOD,
        SLOW,
        BAD,
        /** Not something a verdict can be drawn from. */
        UNKNOWN
    }

    // Ping

    private static final Pattern PACKETS = Pattern.compile("(\\d+) packets transmitted, (\\d+) (?:packets )?received");
    private static final Pattern AVERAGE = Pattern.compile("= [\\d.]+/([\\d.]+)/[\\d.]+");

    /**
     * What a ping said.
     *
     * @param averageMs -1 when nothing came back to average
     */
    public record Ping(int sent, int received, double averageMs) {

        public static Ping parse(String output) {
            String text = output == null ? "" : output;
            Matcher packets = PACKETS.matcher(text);
            if (!packets.find()) {
                return new Ping(0, 0, -1);
            }
            int sent = Integer.parseInt(packets.group(1));
            int received = Integer.parseInt(packets.group(2));
            Matcher average = AVERAGE.matcher(text);
            double ms = average.find() ? Double.parseDouble(average.group(1)) : -1;
            return new Ping(sent, received, ms);
        }

        /**
         * Under 20 ms is a good answer, under 100 a slow one, and anything lost or nothing answering is bad.
         * Rough on purpose: the gateway should answer in a millisecond and the other side of the world in two
         * hundred, and both are fine.
         */
        public Verdict verdict() {
            if (sent == 0) {
                return Verdict.UNKNOWN;
            }
            if (received < sent) {
                return Verdict.BAD;
            }
            return averageMs < 20 ? Verdict.GOOD : averageMs < 100 ? Verdict.SLOW : Verdict.BAD;
        }

        public String sentence() {
            if (sent == 0) {
                return "Ping printed nothing Kubo could read. The answer is below.";
            }
            if (received == 0) {
                return "Nothing answered: " + sent + " sent, none came back.";
            }
            String answered = received + " of " + sent + " answered";
            String speed = averageMs >= 0 ? ", " + format(averageMs) + " on average" : "";
            return received < sent ? answered + speed + ". Some were lost on the way." : answered + speed + ".";
        }
    }

    // Trace

    /** "  3.|-- 10.20.30.1     0.0%     3    1.2   1.4   1.1   1.9   0.3" */
    private static final Pattern HOP = Pattern.compile(
            "^\\s*(\\d+)\\.\\|--\\s+(\\S+)\\s+([\\d.]+)%?\\s+(\\d+)\\s+([\\d.]+)\\s+([\\d.]+)");

    /**
     * One hop of a trace.
     *
     * @param loss      the share of probes it did not answer, 0 to 100
     * @param averageMs the time it took to answer, when it did
     */
    public record Hop(int number, String host, double loss, double averageMs) {

        /** True for a hop that answered nothing at all. */
        public boolean silent() {
            return host.equals("???") || loss >= 100;
        }
    }

    /** Every hop mtr printed, in order. */
    public static List<Hop> hops(String output) {
        List<Hop> hops = new ArrayList<>();
        for (String line : (output == null ? "" : output).split("\\R")) {
            Matcher matcher = HOP.matcher(line);
            if (matcher.find()) {
                hops.add(new Hop(Integer.parseInt(matcher.group(1)), matcher.group(2),
                        Double.parseDouble(matcher.group(3)), Double.parseDouble(matcher.group(6))));
            }
        }
        return hops;
    }

    /**
     * What a hop's numbers mean, read the way mtr has to be read.
     *
     * <p>Plenty of routers refuse to answer pings addressed to themselves and forward everybody else's traffic
     * without a fault. So a hop that loses everything, or some, is only a problem when the hops after it lose too.
     * A silent hop followed by one that answers is a router being private, not a road being closed. The place a
     * route really stops is the first hop after which nothing answers any more.
     */
    public static Verdict judge(List<Hop> hops, int index) {
        Hop hop = hops.get(index);
        boolean laterAnswers = hops.subList(index + 1, hops.size()).stream().anyMatch(later -> !later.silent());
        boolean last = index == hops.size() - 1;
        if (hop.silent()) {
            return laterAnswers ? Verdict.UNKNOWN : Verdict.BAD;
        }
        if (hop.loss() > 0) {
            if (last) {
                return Verdict.SLOW;
            }
            // Loss here that does not carry on to the end is this router rationing its own replies.
            Hop end = hops.get(hops.size() - 1);
            return end.loss() > 0 ? Verdict.SLOW : Verdict.UNKNOWN;
        }
        return Verdict.GOOD;
    }

    /** What to write under a hop in words. */
    public static String note(List<Hop> hops, int index) {
        Hop hop = hops.get(index);
        return switch (judge(hops, index)) {
            case GOOD -> format(hop.averageMs());
            case SLOW -> format(hop.averageMs()) + "  ·  " + String.format(Locale.ROOT, "%.0f%%", hop.loss())
                    + " lost";
            case BAD -> "Nothing answers from here on: this is where the route stops.";
            case UNKNOWN -> hop.silent()
                    ? "Does not answer pings itself, but the hops after it do, so traffic goes through."
                    : "Drops some of its own replies, but nothing is lost further on, so traffic goes through.";
        };
    }

    // Port

    /**
     * What trying a port said.
     *
     * <p>The difference between REFUSED and SILENT is the useful part. Refused means the machine is there and
     * answered no: nothing listens on that port. Silent means nothing answered at all within the time, which is
     * a firewall dropping the packet, or a machine that is not there.
     */
    public enum PortState {
        OPEN("OPEN", "Something is listening and took the connection."),
        REFUSED("REFUSED", "The machine answered, and nothing is listening on that port."),
        SILENT("NO ANSWER", "Nothing answered within three seconds. A firewall is dropping it, or the machine is "
                + "not there."),
        UNREACHABLE("UNREACHABLE", "The network says there is no way to that machine from here."),
        NO_NAME("NO SUCH NAME", "That name does not resolve to an address, so there was nothing to try."),
        UNKNOWN("UNKNOWN", "The check printed nothing Kubo could read. The answer is below.");

        private final String word;
        private final String meaning;

        PortState(String word, String meaning) {
            this.word = word;
            this.meaning = meaning;
        }

        public String word() {
            return word;
        }

        public String meaning() {
            return meaning;
        }

        public Verdict verdict() {
            return switch (this) {
                case OPEN -> Verdict.GOOD;
                case REFUSED, SILENT, UNREACHABLE, NO_NAME -> Verdict.BAD;
                case UNKNOWN -> Verdict.UNKNOWN;
            };
        }

        /**
         * From what the check printed: the {@code EXIT:n} line it ends with, and bash's own words when it failed.
         *
         * <p>The exit code alone is not enough. Bash returns 1 for a refused connection and for a name that does
         * not resolve, and those are opposite answers: one says the machine is there, the other that there is no
         * machine to ask.
         */
        public static PortState parse(String output) {
            String text = output == null ? "" : output;
            Matcher matcher = Pattern.compile("EXIT:(\\d+)").matcher(text);
            if (!matcher.find()) {
                return UNKNOWN;
            }
            int exit = Integer.parseInt(matcher.group(1));
            String said = text.toLowerCase(Locale.ROOT);
            if (exit == 0) {
                return OPEN;
            }
            if (exit == 124) {
                return SILENT;
            }
            if (said.contains("refused")) {
                return REFUSED;
            }
            if (said.contains("no route") || said.contains("unreachable")) {
                return UNREACHABLE;
            }
            if (said.contains("not known") || said.contains("resolve") || said.contains("unknown host")) {
                return NO_NAME;
            }
            return UNKNOWN;
        }
    }

    private static String format(double ms) {
        if (ms < 0) {
            return "";
        }
        return (ms < 10 ? String.format(Locale.ROOT, "%.1f", ms) : String.format(Locale.ROOT, "%.0f", ms)) + " ms";
    }
}
