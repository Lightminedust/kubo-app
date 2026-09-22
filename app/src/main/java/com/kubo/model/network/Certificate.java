package com.kubo.model.network;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A TLS certificate on this server, and when it stops being valid.
 *
 * <p>An expired certificate takes a site off the internet as surely as a stopped service, and nothing on this
 * screen said a word about it. Renewal is usually automatic, which is exactly why nobody notices the day it
 * stops working.
 *
 * @param notAfter the expiry, or null when the file could not be read
 * @param problem  why it could not be read, empty when it could
 */
public record Certificate(String file, Instant notAfter, String subject, String issuer, String problem) {

    /** Below this, renewal is late enough to be worth saying out loud. Let's Encrypt renews at 30 days. */
    public static final int SOON_DAYS = 21;

    public Certificate {
        Objects.requireNonNull(file, "file");
        subject = subject == null ? "" : subject;
        issuer = issuer == null ? "" : issuer;
        problem = problem == null ? "" : problem;
    }

    public static Certificate unreadable(String file, String problem) {
        return new Certificate(file, null, "", "", problem);
    }

    public boolean readable() {
        return notAfter != null;
    }

    /** Days until it expires, negative once it has. Zero when the expiry is not known. */
    public long daysLeft(Instant now) {
        if (notAfter == null || now == null) {
            return 0;
        }
        return Duration.between(now, notAfter).toDays();
    }

    public boolean expired(Instant now) {
        return notAfter != null && now != null && notAfter.isBefore(now);
    }

    public boolean expiringSoon(Instant now) {
        return readable() && !expired(now) && daysLeft(now) <= SOON_DAYS;
    }
}
