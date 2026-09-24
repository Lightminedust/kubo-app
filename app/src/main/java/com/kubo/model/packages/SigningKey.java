package com.kubo.model.packages;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

/**
 * A key apt checks a repository's signature against.
 *
 * <p>These are the reason apt can be told a package came from who it says it did. When one expires, apt stops
 * accepting anything signed with it, and the way that shows up is {@code apt update} failing on that one
 * repository with a message about a key, weeks after the machine could have been told.
 *
 * <p>An expiry is the one thing here worth acting on ahead of time, which is why it is read at all.
 *
 * @param keyring    the file it lives in, which is what a source's {@code signed-by} points at
 * @param identity   who the key says it belongs to
 * @param fingerprint the long identifier, shortened for the screen
 * @param expires    the day it stops being accepted, or null when it never does
 */
public record SigningKey(String keyring, String identity, String fingerprint, LocalDate expires) {

    /** Close enough that somebody should deal with it before it is a broken update. */
    private static final int SOON_DAYS = 45;

    public SigningKey {
        keyring = keyring == null ? "" : keyring;
        identity = identity == null ? "" : identity;
        fingerprint = fingerprint == null ? "" : fingerprint;
    }

    /**
     * Whether apt would ever check a signature against this key.
     *
     * <p>Debian ships every key it has ever retired in
     * {@code /usr/share/keyrings/debian-archive-removed-keys.gpg}, going back to 2004, so that an old archive
     * can still be verified by somebody who asks for it. Apt does not use that file. Reading those as expiries
     * that need attention filled the screen with twenty red lines about keys from 2005, which is alarming and
     * wrong, and buries the one key that really is about to go.
     */
    public boolean inUse() {
        String file = keyring.toLowerCase(Locale.ROOT);
        int slash = file.lastIndexOf('/');
        String name = slash < 0 ? file : file.substring(slash + 1);
        return !name.contains("removed-keys") && !name.contains("removed_keys");
    }

    public boolean expiresEver() {
        return expires != null;
    }

    public boolean expiredOn(LocalDate day) {
        return expires != null && day.isAfter(expires);
    }

    /** True while it still works and there is not long left. */
    public boolean expiringSoon(LocalDate day) {
        return expires != null && !expiredOn(day)
                && Duration.between(day.atStartOfDay(), expires.atStartOfDay()).toDays() <= SOON_DAYS;
    }

    /** What to say about it, or empty when there is nothing worth saying. */
    public String note(LocalDate day) {
        if (expiredOn(day)) {
            return "Expired on " + expires + ". Apt refuses anything signed with it, so whichever repository "
                    + "uses this keyring has stopped updating.";
        }
        if (expiringSoon(day)) {
            long days = Duration.between(day.atStartOfDay(), expires.atStartOfDay()).toDays();
            return "Expires in " + days + (days == 1 ? " day" : " days") + ", on " + expires
                    + ". Updates from whichever repository uses it will start failing that day.";
        }
        return "";
    }

    /** The last eight characters, which is how a key is named in conversation. */
    public String shortId() {
        return fingerprint.length() <= 8 ? fingerprint : fingerprint.substring(fingerprint.length() - 8);
    }

    /**
     * Reads {@code gpg --list-keys --with-colons}, run once per keyring with a {@code @@keyring} marker before
     * each.
     *
     * <p>The colon format is gpg's own machine-readable one, and is the only form of its output that is stable
     * across versions. A {@code pub} record carries the fingerprint's short form and the expiry in field 7;
     * the {@code uid} record after it carries who it belongs to.
     */
    public static List<SigningKey> parse(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        List<SigningKey> found = new ArrayList<>();
        String keyring = "";
        String fingerprint = "";
        LocalDate expires = null;
        boolean waitingForIdentity = false;

        for (String raw : output.lines().toList()) {
            String line = raw.strip();
            if (line.startsWith("@@keyring ")) {
                keyring = line.substring("@@keyring ".length()).strip();
                waitingForIdentity = false;
                continue;
            }
            String[] fields = line.split(":", -1);
            if (fields.length < 7) {
                continue;
            }
            if (fields[0].equals("pub")) {
                fingerprint = fields[4];
                expires = day(fields[6]);
                waitingForIdentity = true;
            } else if (fields[0].equals("uid") && waitingForIdentity) {
                // gpg escapes a colon inside a name as \x3a; nothing else in it needs undoing here.
                String identity = fields.length > 9 ? fields[9].replace("\\x3a", ":") : "";
                found.add(new SigningKey(keyring, identity, fingerprint, expires));
                waitingForIdentity = false;
            }
        }
        return List.copyOf(found);
    }

    /**
     * gpg writes a date as seconds since the epoch, or as an ISO date on older builds, and leaves it empty for
     * a key that never expires.
     */
    private static LocalDate day(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        try {
            if (text.matches("\\d+")) {
                return LocalDate.ofInstant(Instant.ofEpochSecond(Long.parseLong(text)), ZoneOffset.UTC);
            }
            if (text.matches("\\d{8}T.*")) {
                return LocalDate.parse(text.substring(0, 4) + "-" + text.substring(4, 6) + "-"
                        + text.substring(6, 8));
            }
            return LocalDate.parse(text);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
