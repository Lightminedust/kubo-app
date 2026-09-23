package com.kubo.network;

import com.kubo.model.network.Certificate;
import com.kubo.model.network.Certificate.Urgency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How worried to be about a certificate, and what its date alone does not say. */
class CertificateTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    private static Certificate expiringIn(long days) {
        return new Certificate("/etc/letsencrypt/live/a/fullchain.pem", NOW.plus(Duration.ofDays(days)).plusSeconds(60),
                "CN=a", "CN=R11, O=Let's Encrypt", "");
    }

    @Test
    @DisplayName("the date decides the colour: fine, late, urgent, gone")
    void gradesByDate() {
        assertEquals(Urgency.FINE, expiringIn(60).urgency(NOW));
        assertEquals(Urgency.SOON, expiringIn(21).urgency(NOW));
        assertEquals(Urgency.SOON, expiringIn(8).urgency(NOW));
        assertEquals(Urgency.URGENT, expiringIn(7).urgency(NOW));
        assertEquals(Urgency.URGENT, expiringIn(0).urgency(NOW));
        assertEquals(Urgency.EXPIRED, new Certificate("x", NOW.minus(Duration.ofDays(2)), "", "", "").urgency(NOW));
        assertEquals(Urgency.UNREAD, Certificate.unreadable("x", "Permission denied").urgency(NOW));
    }

    @Test
    @DisplayName("a Cloudflare Origin certificate says who trusts it, however many years it has left")
    void namesTheCloudflareOrigin() {
        Certificate origin = new Certificate("/etc/ssl/cloudflare/kubo.pem", NOW.plus(Duration.ofDays(5468)),
                "O=CloudFlare, Inc., OU=CloudFlare Origin CA, CN=CloudFlare Origin Certificate",
                "C=US, O=CloudFlare, Inc., OU=CloudFlare Origin SSL Certificate Authority", "");

        assertEquals(Urgency.FINE, origin.urgency(NOW));
        assertTrue(origin.trustedByCloudflareOnly());
        assertTrue(origin.caveat().contains("only Cloudflare's proxy trusts it"));
    }

    @Test
    @DisplayName("a certificate that vouches for itself is said to, and an ordinary one says nothing more")
    void namesSelfSigned() {
        Certificate own = new Certificate("x", NOW.plus(Duration.ofDays(300)), "CN=box", "CN=box", "");

        assertTrue(own.selfSigned());
        assertTrue(own.caveat().contains("Signed by itself"));
        assertFalse(expiringIn(60).selfSigned());
        assertEquals("", expiringIn(60).caveat());
    }
}
