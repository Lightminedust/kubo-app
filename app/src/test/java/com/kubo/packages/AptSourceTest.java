package com.kubo.packages;

import com.kubo.model.packages.AptSource;
import com.kubo.model.packages.SigningKey;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where apt is allowed to fetch from, and what it checks the signatures against. */
class AptSourceTest {

    /** A line break, written so no patch to this file can turn it into a real one. */
    private static final String NEWLINE = String.valueOf((char) 10);

    private static final String FILES = """
            @@file /etc/apt/sources.list
            deb http://deb.debian.org/debian bookworm main contrib non-free-firmware
            deb-src http://deb.debian.org/debian bookworm main
            # deb http://deb.debian.org/debian bookworm-backports main
            # everything below was added by the hosting provider, do not remove
            @@file /etc/apt/sources.list.d/docker.list
            deb [signed-by=/usr/share/keyrings/docker.gpg arch=amd64] https://download.docker.com/linux/debian bookworm stable
            @@file /etc/apt/sources.list.d/postgres.sources
            Types: deb
            URIs: https://apt.postgresql.org/pub/repos/apt
            Suites: bookworm-pgdg
            Components: main
            Signed-By: /usr/share/keyrings/postgres.gpg
            Enabled: no
            """;

    @Test
    void readsTheOldOneLineFormat() {
        List<AptSource> sources = AptSource.parse(FILES);
        AptSource first = sources.get(0);

        assertEquals("/etc/apt/sources.list", first.file());
        assertEquals(1, first.line());
        assertTrue(first.enabled());
        assertEquals("deb", first.types());
        assertEquals("deb.debian.org", first.host());
        assertEquals("bookworm", first.suites());
        assertEquals("main contrib non-free-firmware", first.components());
        assertTrue(first.fromTheDistribution());
    }

    @Test
    void tellsSourcePackagesFromPrograms() {
        AptSource sources = AptSource.parse(FILES).get(1);

        assertTrue(sources.sourceCode(), "nothing installed on the machine came from a deb-src line");
    }

    @Test
    void readsACommentedLineAsSomethingThatCouldBeSwitchedOn() {
        List<AptSource> sources = AptSource.parse(FILES);
        AptSource backports = sources.stream().filter(s -> s.suites().equals("bookworm-backports"))
                .findFirst().orElseThrow();

        assertFalse(backports.enabled());
        assertEquals(3, backports.line());
    }

    @Test
    void aPlainCommentIsNotASource() {
        // "# everything below was added by..." is prose. Offering to switch it on would be nonsense.
        assertEquals(4, AptSource.parse(FILES).stream()
                .filter(s -> s.file().equals("/etc/apt/sources.list")).count() + 1);
        assertTrue(AptSource.parse("@@file /etc/apt/sources.list\n# just a note").isEmpty());
    }

    @Test
    void readsTheOptionsAndTheKeyringOutOfALine() {
        AptSource docker = AptSource.parse(FILES).stream()
                .filter(s -> s.host().equals("download.docker.com")).findFirst().orElseThrow();

        assertEquals("/usr/share/keyrings/docker.gpg", docker.signedBy());
        assertEquals("bookworm", docker.suites());
        assertEquals("stable", docker.components());
        assertFalse(docker.fromTheDistribution(), "docker is not what this machine is built out of");
    }

    @Test
    void readsTheNewerDeb822Format() {
        AptSource postgres = AptSource.parse(FILES).stream()
                .filter(s -> s.host().equals("apt.postgresql.org")).findFirst().orElseThrow();

        assertTrue(postgres.deb822());
        assertEquals("bookworm-pgdg", postgres.suites());
        assertEquals("/usr/share/keyrings/postgres.gpg", postgres.signedBy());
        // deb822 says Enabled: no rather than commenting the paragraph out.
        assertFalse(postgres.enabled());
    }

    @Test
    void switchingASourceTouchesOneLineOfOneOfAptsOwnFiles() {
        String off = AptCommands.switchSource("/etc/apt/sources.list", 3, false);
        String on = AptCommands.switchSource("/etc/apt/sources.list.d/docker.list", 1, true);

        assertTrue(off.startsWith("sed -i "));
        assertTrue(off.contains("3s/"), "the line number is in the script, so no other line is touched");
        assertTrue(off.endsWith("'/etc/apt/sources.list'"));
        assertTrue(on.contains("1s/"));
        assertTrue(on.contains("'/etc/apt/sources.list.d/docker.list'"));
    }

    @Test
    void refusesAPathThatIsNotAptsOwn() {
        // The path comes from what Kubo read, and is checked again anyway.
        for (String path : List.of("/etc/passwd", "/etc/apt/sources.list.d/../../shadow",
                "/etc/apt/sources.list.d/x;rm -rf /", "", "/etc/apt/sources.listX")) {
            assertThrows(IllegalArgumentException.class, () -> AptCommands.switchSource(path, 1, true), path);
        }
        assertThrows(IllegalArgumentException.class,
                () -> AptCommands.switchSource("/etc/apt/sources.list", 0, true));
    }

    @Test
    void readsWhenASigningKeyStopsBeingAccepted() {
        List<SigningKey> keys = SigningKey.parse("""
                @@keyring /usr/share/keyrings/docker.gpg
                pub:-:4096:1:7EA0A9C3F273FCD8:1487788586:1802284586::-:::scESC::::::23::0:
                fpr:::::::::9DC858229FC7DD38854AE2D88D81803C0EBFCD88:
                uid:-::::1487788586::F52F...::Docker Release (CE deb) <docker@docker.com>::::::::::0:
                """);

        assertEquals(1, keys.size());
        SigningKey key = keys.get(0);
        assertEquals("/usr/share/keyrings/docker.gpg", key.keyring());
        assertTrue(key.identity().startsWith("Docker Release"));
        // 1802284586 seconds since the epoch is 2027-02-10 18:36 UTC, checked rather than guessed.
        assertEquals(LocalDate.of(2027, 2, 10), key.expires());
        assertEquals("F273FCD8", key.shortId());
    }

    @Test
    void saysSoBeforeAKeyExpiresRatherThanAfterUpdatesBreak() {
        SigningKey key = new SigningKey("/k.gpg", "Someone", "ABCD1234", LocalDate.of(2026, 10, 1));

        assertFalse(key.expiredOn(LocalDate.of(2026, 10, 1)), "the last day still works");
        assertTrue(key.expiredOn(LocalDate.of(2026, 10, 2)));
        assertTrue(key.expiringSoon(LocalDate.of(2026, 9, 24)));
        assertFalse(key.expiringSoon(LocalDate.of(2026, 1, 1)), "not worth saying months ahead");
        assertTrue(key.note(LocalDate.of(2026, 9, 24)).contains("start failing"));
        assertTrue(key.note(LocalDate.of(2026, 11, 1)).contains("has stopped updating"));
        assertEquals("", key.note(LocalDate.of(2026, 1, 1)));
    }

    @Test
    void aKeyThatNeverExpiresSaysNothing() {
        List<SigningKey> keys = SigningKey.parse("""
                @@keyring /k.gpg
                pub:-:4096:1:AAAABBBBCCCCDDDD:1487788586:::-:::scESC::::::23::0:
                uid:-::::1487788586::F52F...::Debian Archive Automatic Signing Key::::::::::0:
                """);

        assertFalse(keys.get(0).expiresEver());
        assertEquals("", keys.get(0).note(LocalDate.of(2099, 1, 1)));
    }

    @Test
    void nothingReadableIsAnEmptyListRatherThanAGuess() {
        assertTrue(AptSource.parse("").isEmpty());
        assertTrue(AptSource.parse(null).isEmpty());
        assertTrue(SigningKey.parse("@@nogpg").isEmpty());
        assertTrue(SigningKey.parse(null).isEmpty());
    }

    @Test
    void aParagraphServingBothIsNotSourceCode() {
        // Debian 12 and 13 ship one debian.sources with Types: deb deb-src. Reading the word deb-src anywhere
        // in it filed the distribution's own repository under source code and left its list empty, on a
        // machine plainly running Debian.
        List<AptSource> both = AptSource.parse(String.join(NEWLINE,
                "@@file /etc/apt/sources.list.d/debian.sources",
                "Types: deb deb-src",
                "URIs: http://deb.debian.org/debian",
                "Suites: trixie trixie-updates",
                "Components: main",
                "Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg"));

        assertEquals(1, both.size());
        assertFalse(both.get(0).sourceCode());
        assertTrue(both.get(0).servesPackages());
        assertTrue(both.get(0).fromTheDistribution());
    }

    @Test
    void onlyDebSrcAloneIsSourceCode() {
        assertTrue(AptSource.parse(String.join(NEWLINE,
                "@@file /etc/apt/x.sources", "Types: deb-src", "URIs: http://deb.debian.org/debian",
                "Suites: trixie")).get(0).sourceCode());
        assertTrue(AptSource.parse(String.join(NEWLINE,
                "@@file /etc/apt/sources.list",
                "deb-src http://deb.debian.org/debian bookworm main")).get(0).sourceCode());
    }

    @Test
    void theRetiredKeyringDebianShipsIsNotAProblemToSolve() {
        // Debian keeps every key it has ever retired, back to 2004, so an old archive can still be verified.
        // Apt does not use that file. Reading them as expiries filled the screen with red about 2005.
        SigningKey retired = new SigningKey("/usr/share/keyrings/debian-archive-removed-keys.gpg",
                "Debian Archive Automatic Signing Key (2004)", "1DB114E0", LocalDate.of(2005, 1, 27));
        SigningKey live = new SigningKey("/etc/apt/keyrings/docker.gpg",
                "Docker Release", "0EBFCD88", LocalDate.of(2025, 1, 1));

        assertFalse(retired.inUse());
        assertTrue(live.inUse());
        // It is still expired; it is simply not something anybody has to act on.
        assertTrue(retired.expiredOn(LocalDate.of(2026, 9, 24)));
    }

    @Test
    void aNewSourceGoesInItsOwnFileAndRefusesToOverwriteOne() {
        String command = AptCommands.addSource("docker");

        assertTrue(command.contains("'/etc/apt/sources.list.d/docker.list'"));
        assertTrue(command.contains("exit 17"), "writing over a source somebody already has is refused");
        assertTrue(command.contains("chmod 0644"));
        // The name is the only part of this that reaches a command, and it is checked hard.
        for (String bad : List.of("../../etc/passwd", "a b", "x;rm -rf /", "", "Docker/x")) {
            assertThrows(IllegalArgumentException.class, () -> AptCommands.addSource(bad), bad);
        }
    }

    @Test
    void theLineIsBuiltFromCheckedPartsAndNeverFromWhateverWasTyped() {
        assertEquals("deb [signed-by=/etc/apt/keyrings/docker.gpg] "
                        + "https://download.docker.com/linux/debian bookworm stable" + System.lineSeparator(),
                AptCommands.sourceLine("https://download.docker.com/linux/debian", "bookworm", "stable",
                        "/etc/apt/keyrings/docker.gpg"));
        assertTrue(AptCommands.sourceLine("http://x.example/y", "stable", "main", "").startsWith("deb http"));

        // It goes in through standard input rather than a command, and is still checked: a newline in the
        // address would otherwise put a second source in the file.
        assertThrows(IllegalArgumentException.class,
                () -> AptCommands.sourceLine("https://x.example" + NEWLINE + "deb http://evil", "a", "b", ""));
        assertThrows(IllegalArgumentException.class, () -> AptCommands.sourceLine("ftp://x", "a", "b", ""));
        assertThrows(IllegalArgumentException.class, () -> AptCommands.sourceLine("file:///etc", "a", "b", ""));
        assertThrows(IllegalArgumentException.class,
                () -> AptCommands.sourceLine("https://x.example/y", "a b", "main", ""));
        assertThrows(IllegalArgumentException.class,
                () -> AptCommands.sourceLine("https://x.example/y", "stable", "main", "relative/path.gpg"));
    }
}
