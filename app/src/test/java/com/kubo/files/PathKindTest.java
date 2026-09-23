package com.kubo.files;

import com.kubo.model.files.PathKind;
import com.kubo.model.files.RemoteEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a path picker lets somebody choose, by kind and by name, without opening anything. */
class PathKindTest {

    private static RemoteEntry entry(String name, RemoteEntry.Type type) {
        return new RemoteEntry(name, "/x/" + name, type, 1, Instant.EPOCH, "rw-r--r--", "root", "root", "",
                RemoteEntry.UNKNOWN, true);
    }

    @Test
    @DisplayName("a certificate is a .pem, .crt or .cer, and the links Let's Encrypt makes count as much as files")
    void acceptsCertificates() {
        assertTrue(PathKind.CERTIFICATE.accepts(entry("fullchain.pem", RemoteEntry.Type.LINK)));
        assertTrue(PathKind.CERTIFICATE.accepts(entry("site.crt", RemoteEntry.Type.FILE)));
        assertTrue(PathKind.CERTIFICATE.accepts(entry("SITE.CER", RemoteEntry.Type.FILE)));
        assertFalse(PathKind.CERTIFICATE.accepts(entry("README", RemoteEntry.Type.FILE)));
        assertFalse(PathKind.CERTIFICATE.accepts(entry("live.pem", RemoteEntry.Type.DIRECTORY)),
                "a folder named like a certificate is still a folder");
    }

    @Test
    @DisplayName("a private key is a .pem or a .key")
    void acceptsKeys() {
        assertTrue(PathKind.PRIVATE_KEY.accepts(entry("privkey.pem", RemoteEntry.Type.LINK)));
        assertTrue(PathKind.PRIVATE_KEY.accepts(entry("site.key", RemoteEntry.Type.FILE)));
        assertFalse(PathKind.PRIVATE_KEY.accepts(entry("site.crt", RemoteEntry.Type.FILE)));
    }

    @Test
    @DisplayName("a socket is not a file: it is its own kind of entry, or a link to one")
    void acceptsSockets() {
        assertTrue(PathKind.SOCKET.accepts(entry("php8.2-fpm.sock", RemoteEntry.Type.OTHER)));
        assertTrue(PathKind.SOCKET.accepts(entry("php-fpm.sock", RemoteEntry.Type.LINK)));
        assertFalse(PathKind.SOCKET.accepts(entry("php-fpm.sock", RemoteEntry.Type.FILE)),
                "a regular file called .sock is not something PHP-FPM listens on");
        assertFalse(PathKind.SOCKET.accepts(entry("php8.2-fpm.pid", RemoteEntry.Type.FILE)));
    }

    @Test
    @DisplayName("a folder picker takes folders and nothing else")
    void acceptsFolders() {
        assertTrue(PathKind.FOLDER.accepts(entry("gemmi-e", RemoteEntry.Type.DIRECTORY)));
        assertFalse(PathKind.FOLDER.accepts(entry("robots.txt", RemoteEntry.Type.FILE)));
        assertFalse(PathKind.FOLDER.accepts(null));
    }

    @Test
    @DisplayName("each kind opens where those things usually are")
    void startsWhereTheyLive() {
        assertEquals("/etc/letsencrypt/live", PathKind.CERTIFICATE.start());
        assertEquals("/run/php", PathKind.SOCKET.start());
        assertEquals("/var/www", PathKind.FOLDER.start());
        assertTrue(PathKind.CERTIFICATE.places().contains("/etc/ssl/certs"));
    }

    @Test
    @DisplayName("the key beside a Let's Encrypt certificate is found without a second trip")
    void findsTheKeyBeside() {
        assertEquals("/etc/letsencrypt/live/api.gemmi-e.com/privkey.pem",
                PathKind.keyBeside("/etc/letsencrypt/live/api.gemmi-e.com/fullchain.pem"));
        assertEquals("/etc/letsencrypt/live/a/privkey.pem", PathKind.keyBeside("/etc/letsencrypt/live/a/cert.pem"));
        assertEquals("", PathKind.keyBeside("/etc/ssl/certs/site.crt"), "no guess where there is no convention");
        assertEquals("", PathKind.keyBeside(null));
    }

    @Test
    @DisplayName("a key is never offered as a certificate, nor a certificate as a key, however both are named .pem")
    void tellsTheKeyFromTheCertificate() {
        RemoteEntry key = entry("privkey.pem", RemoteEntry.Type.LINK);
        RemoteEntry certificate = entry("fullchain.pem", RemoteEntry.Type.LINK);

        assertFalse(PathKind.CERTIFICATE.accepts(key), "Nginx given the key as the certificate will not start");
        assertTrue(PathKind.PRIVATE_KEY.accepts(key));
        assertFalse(PathKind.PRIVATE_KEY.accepts(certificate));
        assertTrue(PathKind.CERTIFICATE.accepts(certificate));
    }

    @Test
    @DisplayName("of the three certificates Let's Encrypt writes, the one Nginx wants is said")
    void saysWhichCertificate() {
        assertEquals("the one Nginx wants", PathKind.CERTIFICATE.advice(entry("fullchain.pem", RemoteEntry.Type.LINK)));
        assertEquals("the intermediates only", PathKind.CERTIFICATE.advice(entry("chain.pem", RemoteEntry.Type.LINK)));
        assertEquals("missing the intermediates", PathKind.CERTIFICATE.advice(entry("cert.pem", RemoteEntry.Type.LINK)));
        assertEquals("", PathKind.CERTIFICATE.advice(entry("site.crt", RemoteEntry.Type.FILE)));
        assertEquals("", PathKind.PRIVATE_KEY.advice(entry("fullchain.pem", RemoteEntry.Type.LINK)));
    }
    @Test
    @DisplayName("a .pem whose name says neither is offered as both, and said to be undecided")
    void offersAnAmbiguousNameBothWays() {
        RemoteEntry site = entry("site.pem", RemoteEntry.Type.FILE);

        assertTrue(PathKind.CERTIFICATE.accepts(site));
        assertTrue(PathKind.PRIVATE_KEY.accepts(site), "a key called site.pem used to be impossible to pick");
        assertEquals("the name does not say which", PathKind.CERTIFICATE.advice(site));
        assertEquals("the name does not say which", PathKind.PRIVATE_KEY.advice(site));
    }

    @Test
    @DisplayName("the first line settles what the name could not")
    void judgesByTheArmour() {
        assertEquals(PathKind.Verdict.FITS, PathKind.CERTIFICATE.judge("-----BEGIN CERTIFICATE-----"));
        assertEquals(PathKind.Verdict.WRONG, PathKind.CERTIFICATE.judge("-----BEGIN PRIVATE KEY-----"));
        assertEquals(PathKind.Verdict.WRONG, PathKind.CERTIFICATE.judge("-----BEGIN CERTIFICATE REQUEST-----"),
                "a request is what you send to get a certificate, not a certificate");

        assertEquals(PathKind.Verdict.FITS, PathKind.PRIVATE_KEY.judge("-----BEGIN PRIVATE KEY-----"));
        assertEquals(PathKind.Verdict.FITS, PathKind.PRIVATE_KEY.judge("-----BEGIN RSA PRIVATE KEY-----"));
        assertEquals(PathKind.Verdict.FITS, PathKind.PRIVATE_KEY.judge("-----BEGIN EC PRIVATE KEY-----"));
        assertEquals(PathKind.Verdict.LOCKED, PathKind.PRIVATE_KEY.judge("-----BEGIN ENCRYPTED PRIVATE KEY-----"));
        assertEquals(PathKind.Verdict.WRONG, PathKind.PRIVATE_KEY.judge("-----BEGIN CERTIFICATE-----"));

        assertEquals(PathKind.Verdict.UNKNOWN, PathKind.CERTIFICATE.judge(""), "no armour: not PEM, or not readable");
        assertEquals(PathKind.Verdict.UNKNOWN, PathKind.CERTIFICATE.judge(null));
        assertEquals(PathKind.Verdict.UNKNOWN, PathKind.SOCKET.judge("-----BEGIN CERTIFICATE-----"));
    }

    @Test
    @DisplayName("only certificates and keys are worth a look at their first line")
    void looksOnlyWhereItHelps() {
        assertTrue(PathKind.CERTIFICATE.readsFirstLine());
        assertTrue(PathKind.PRIVATE_KEY.readsFirstLine());
        assertFalse(PathKind.SOCKET.readsFirstLine());
        assertFalse(PathKind.FOLDER.readsFirstLine());
    }

    @Test
    @DisplayName("the verdict is said in words, naming what the file turned out to be")
    void explainsTheVerdict() {
        assertEquals("its first line says private key, not a certificate",
                PathKind.CERTIFICATE.explain(PathKind.Verdict.WRONG, "-----BEGIN PRIVATE KEY-----"));
        assertTrue(PathKind.PRIVATE_KEY.explain(PathKind.Verdict.LOCKED, "").contains("passphrase"));
    }
}
