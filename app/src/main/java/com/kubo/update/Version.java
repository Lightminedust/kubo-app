package com.kubo.update;

import java.util.Optional;
import java.util.regex.Pattern;

/** Release versions, compared numerically. Drafts and pre-releases are deliberately excluded. */
public record Version(int major, int minor, int patch) implements Comparable<Version> {
    private static final Pattern STABLE = Pattern.compile("v?(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})");

    public static Optional<Version> parse(String value) {
        var match = STABLE.matcher(value == null ? "" : value);
        if (!match.matches()) return Optional.empty();
        return Optional.of(new Version(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)),
                Integer.parseInt(match.group(3))));
    }

    @Override public int compareTo(Version other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) result = Integer.compare(minor, other.minor);
        return result == 0 ? Integer.compare(patch, other.patch) : result;
    }

    @Override public String toString() { return major + "." + minor + "." + patch; }
}
