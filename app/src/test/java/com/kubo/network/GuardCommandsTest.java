package com.kubo.network;

import com.kubo.model.network.Certificate;
import com.kubo.model.network.Jail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fail2ban and the TLS certificates.
 *
 * <p>Both act on text that came off the server, and one of them takes an address from the user and puts it in a
 * command, so what is refused matters as much as what is read.
 */
class GuardCommandsTest {

    private static final String STATUS = """
            Status
            |- Number of jail:      2
            `- Jail list:   sshd, nginx-http-auth
            === sshd
            Status for the jail: sshd
            |- Filter
            |  |- Currently failed: 3
            |  |- Total failed:     1892
            |  `- File list:        /var/log/auth.log
            `- Actions
               |- Currently banned: 2
               |- Total banned:     57
               `- Banned IP list:   45.135.232.17 193.32.162.8
            === nginx-http-auth
            Status for the jail: nginx-http-auth
            |- Filter
            |  |- Currently failed: 0
            |  |- Total failed:     0
            `- Actions
               |- Currently banned: 0
               |- Total banned:     0
               `- Banned IP list:
            """;

    @Test
    @DisplayName("every jail is read with its counts and the addresses banned right now")
    void readsJails() {
        List<Jail> jails = GuardCommands.parseJails(STATUS);

        assertEquals(List.of("sshd", "nginx-http-auth"), jails.stream().map(Jail::name).toList());
        Jail sshd = jails.get(0);
        assertEquals(3, sshd.currentlyFailed());
        assertEquals(1892, sshd.totalFailed());
        assertEquals(57, sshd.totalBanned());
        assertEquals(List.of("45.135.232.17", "193.32.162.8"), sshd.banned());
        assertEquals(2, sshd.currentlyBanned());
    }

    @Test
    @DisplayName("a jail with nothing to report says so instead of looking broken")
    void readsAQuietJail() {
        Jail quiet = GuardCommands.parseJails(STATUS).get(1);

        assertTrue(quiet.quiet());
        assertEquals(List.of(), quiet.banned());
    }

    @Test
    @DisplayName("fail2ban not being installed gives no jails rather than a parse of an error message")
    void readsNothingUseful() {
        assertEquals(List.of(), GuardCommands.parseJails("fail2ban-client: command not found"));
        assertEquals(List.of(), GuardCommands.parseJails(""));
    }

    @Test
    @DisplayName("only an address ever reaches a ban command")
    void refusesAnythingThatIsNotAnAddress() {
        assertEquals("LC_ALL=C fail2ban-client set 'sshd' banip '45.135.232.17'",
                GuardCommands.ban("sshd", "45.135.232.17"));
        assertEquals("LC_ALL=C fail2ban-client set 'sshd' unbanip '2a02:c207::1'",
                GuardCommands.unban("sshd", "2a02:c207::1"));

        for (String bad : List.of("1.2.3.4; rm -rf /", "$(id)", "`id`", "", "  ", "evil.example.com")) {
            assertThrows(IllegalArgumentException.class, () -> GuardCommands.ban("sshd", bad), bad);
        }
        for (String bad : List.of("sshd; rm -rf /", "../etc", "")) {
            assertThrows(IllegalArgumentException.class, () -> GuardCommands.ban(bad, "1.2.3.4"), bad);
        }
    }

    @Test
    @DisplayName("a certificate gives its expiry, its subject and its issuer")
    void readsACertificate() {
        String output = """
                === /etc/letsencrypt/live/gemmi-e.com/fullchain.pem
                notAfter=Dec 11 07:22:41 2026 GMT
                subject=CN = gemmi-e.com
                issuer=C = US, O = Let's Encrypt, CN = R11
                """;

        Certificate certificate = GuardCommands.parseCertificates(output).get(0);

        assertTrue(certificate.readable());
        assertEquals("/etc/letsencrypt/live/gemmi-e.com/fullchain.pem", certificate.file());
        assertEquals(Instant.parse("2026-12-11T07:22:41Z"), certificate.notAfter());
        assertEquals("CN = gemmi-e.com", certificate.subject());
        assertTrue(certificate.issuer().contains("Let's Encrypt"));
    }

    @Test
    @DisplayName("a certificate that cannot be read still appears, with the reason")
    void keepsUnreadableCertificates() {
        String output = """
                === /etc/ssl/private/site.pem
                Could not open file or uri for loading certificate: No such file or directory
                """;

        List<Certificate> certificates = GuardCommands.parseCertificates(output);

        assertEquals(1, certificates.size());
        assertFalse(certificates.get(0).readable());
        assertTrue(certificates.get(0).problem().contains("No such file"));
    }

    @Test
    @DisplayName("expired, nearly expired and fine are told apart")
    void judgesTheExpiry() {
        Instant now = Instant.parse("2026-12-01T00:00:00Z");
        Certificate expired = new Certificate("a", Instant.parse("2026-11-01T00:00:00Z"), "", "", "");
        Certificate soon = new Certificate("b", Instant.parse("2026-12-11T00:00:00Z"), "", "", "");
        Certificate fine = new Certificate("c", Instant.parse("2027-03-01T00:00:00Z"), "", "", "");

        assertTrue(expired.expired(now));
        assertFalse(expired.expiringSoon(now), "already gone is not the same as nearly gone");
        assertEquals(-30, expired.daysLeft(now));

        assertTrue(soon.expiringSoon(now));
        assertEquals(10, soon.daysLeft(now));

        assertFalse(fine.expiringSoon(now));
        assertFalse(fine.expired(now));
    }

    @Test
    @DisplayName("the command names each file before reading it, so a missing one is still reported")
    void announcesEachFile() {
        String command = GuardCommands.certificates(List.of("/etc/ssl/a.pem", "/etc/ssl/b.pem"));

        assertTrue(command.contains("=== /etc/ssl/a.pem"));
        assertTrue(command.contains("'/etc/ssl/a.pem'"), "the path is quoted where it is read");
        assertTrue(command.contains("=== /etc/ssl/b.pem"));
        assertEquals("", GuardCommands.certificates(List.of()));
    }
}
