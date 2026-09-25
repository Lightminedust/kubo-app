package com.kubo.model.shell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One line of a log, read for the two things that make a log readable at a glance: how bad it is, and where
 * the time is.
 *
 * <p>Both are guesses, and they are worth making. A page of one colour is a page nobody scans; the eye finds
 * the red line in a second and the same line in grey takes a minute. Nothing here changes the text, and
 * nothing is hidden: a line read wrongly is a line in the wrong colour, which is a small price against
 * reading everything at the same weight.
 */
public record LogLine(String text, Level level, List<Span> spans) {

    /** How loud a line is. */
    public enum Level {
        /** Something broke. */
        ERROR,
        /** Something is off but still running. */
        WARN,
        /** Something happened and said so on purpose. */
        INFO,
        /** Everything else, which is most of it. */
        PLAIN
    }

    /**
     * A piece of a line, and whether it is a time.
     *
     * @param text  the piece as it appears
     * @param stamp true for a date or a clock time, which is drawn dimmer
     */
    public record Span(String text, boolean stamp) {

        public Span {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * Dates and clock times, in the three shapes a Linux server produces them: ISO from anything modern,
     * the syslog "Sep 25 14:05:06", and a bare clock.
     */
    private static final Pattern STAMP = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?"
                    + "|\\b[A-Z][a-z]{2} [ 0-3]?\\d \\d{2}:\\d{2}:\\d{2}\\b"
                    + "|\\b\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?\\b");

    private static final Pattern ERROR_WORDS = Pattern.compile(
            "\\b(error|errors|err|fatal|critical|crit|panic|alert|emerg(?:ency)?|severe|failed|failure|"
                    + "exception|traceback|refused|denied)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern WARN_WORDS = Pattern.compile(
            "\\b(warn(?:ing)?|deprecat(?:ed|ion)|retry(?:ing)?|timed out|timeout|unavailable)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INFO_WORDS = Pattern.compile(
            "\\b(info|notice|started|starting|ready|listening|online|success(?:ful(?:ly)?)?|connected)\\b",
            Pattern.CASE_INSENSITIVE);

    public LogLine {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(level, "level");
        spans = List.copyOf(Objects.requireNonNull(spans, "spans"));
    }

    public static LogLine of(String raw) {
        String text = raw == null ? "" : raw;
        return new LogLine(text, level(text), split(text));
    }

    /** The style class the whole line is drawn in. */
    public String styleClass() {
        return switch (level) {
            case ERROR -> "log-error";
            case WARN -> "log-warn";
            case INFO -> "log-info";
            case PLAIN -> "log-plain";
        };
    }

    private static Level level(String text) {
        // Worst first. A line saying "failed to start, retrying" is a failure that happens to mention a retry.
        String lower = text.toLowerCase(Locale.ROOT);
        if (ERROR_WORDS.matcher(lower).find()) {
            return Level.ERROR;
        }
        if (WARN_WORDS.matcher(lower).find()) {
            return Level.WARN;
        }
        return INFO_WORDS.matcher(lower).find() ? Level.INFO : Level.PLAIN;
    }

    /** The line cut into the times and everything between them, in order, covering all of it. */
    private static List<Span> split(String text) {
        List<Span> spans = new ArrayList<>();
        Matcher stamps = STAMP.matcher(text);
        int at = 0;
        while (stamps.find()) {
            if (stamps.start() > at) {
                spans.add(new Span(text.substring(at, stamps.start()), false));
            }
            spans.add(new Span(stamps.group(), true));
            at = stamps.end();
        }
        if (at < text.length()) {
            spans.add(new Span(text.substring(at), false));
        }
        if (spans.isEmpty()) {
            spans.add(new Span(text, false));
        }
        return spans;
    }
}
