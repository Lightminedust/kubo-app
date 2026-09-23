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

    /** Below this, renewal has failed long enough that the site is days from going dark. */
    public static final int URGENT_DAYS = 7;

    /** How worried to be, which is what decides the colour. */
    public enum Urgency {
        /** Could not be read: nothing is known, and nothing is claimed. */
        UNREAD,
        /** Past its date. Browsers refuse the site now. */
        EXPIRED,
        /** A week or less. Renewal should have happened three weeks ago. */
        URGENT,
        /** Three weeks or less: Let's Encrypt renews at thirty days, so this one is late. */
        SOON,
        FINE
    }

    public Urgency urgency(Instant now) {
        if (!readable()) {
            return Urgency.UNREAD;
        }
        if (expired(now)) {
            return Urgency.EXPIRED;
        }
        long days = daysLeft(now);
        if (days <= URGENT_DAYS) {
            return Urgency.URGENT;
        }
        return days <= SOON_DAYS ? Urgency.SOON : Urgency.FINE;
    }

    /**
     * True for a Cloudflare Origin certificate.
     *
     * <p>It lasts fifteen years, which makes it look like the healthiest certificate on the server, and it is
     * trusted by exactly one client: Cloudflare's proxy. Visitors never see it while the domain goes through
     * Cloudflare. The day the domain points straight at this server, every browser refuses it.
     */
    public boolean trustedByCloudflareOnly() {
        String both = (issuer + " " + subject).toLowerCase(java.util.Locale.ROOT);
        return both.contains("cloudflare origin");
    }

    /** True when it vouches for itself, which every browser warns about. */
    public boolean selfSigned() {
        return !subject.isBlank() && subject.equals(issuer);
    }

    /** What is worth knowing beyond the date, in one sentence, or empty. */
    public String caveat() {
        if (trustedByCloudflareOnly()) {
            return "A Cloudflare Origin certificate: only Cloudflare's proxy trusts it. The site works while the "
                    + "domain goes through Cloudflare, and every browser refuses it the day the domain points "
                    + "straight at this server.";
        }
        if (selfSigned()) {
            return "Signed by itself: every browser warns every visitor before letting them in.";
        }
        return "";
    }
}
