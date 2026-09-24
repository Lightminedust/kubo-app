package com.kubo.packages;

import com.kubo.model.packages.PackageSheet;
import com.kubo.platform.Host;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading what apt says about one package into something a screen can lay out. */
class PackageSheetTest {

    /** A real answer, with the continuation lines and the lone dot apt writes inside a description. */
    private static final String NGINX = """
            Package: nginx
            Version: 1.22.1-9
            Installed-Size: 520
            Maintainer: Debian Nginx Maintainers <pkg-nginx-maintainers@alioth-lists.debian.net>
            Architecture: amd64
            Depends: libc6 (>= 2.34), libssl3 (>= 3.0.0), nginx-common (= 1.22.1-9)
            Recommends: nginx-doc
            Size: 521268
            Section: httpd
            Priority: optional
            Homepage: https://nginx.org
            Description: small, powerful, scalable web/proxy server
             Nginx is a web and proxy server with a strong focus on high concurrency.
             .
             This package installs the full version.
            SHA256: 6d1f0f2c9f1c2c5e0e1b6b1b2c3d4e5f60718293a4b5c6d7e8f9012345678901
            """;

    @Test
    void readsTheFieldsThatMatter() {
        PackageSheet sheet = PackageSheet.parse(NGINX);

        assertEquals("nginx", sheet.name());
        assertEquals("1.22.1-9", sheet.version());
        assertEquals("httpd", sheet.section());
        assertEquals("amd64", sheet.architecture());
        assertEquals("https://nginx.org", sheet.homepage());
        assertEquals(520, sheet.installedKb());
        assertEquals(521268, sheet.downloadBytes());
        assertTrue(sheet.maintainer().startsWith("Debian Nginx"));
    }

    @Test
    void splitsTheOneLineSummaryFromTheRest() {
        PackageSheet sheet = PackageSheet.parse(NGINX);

        // The first line is what goes at the top of the card; the rest is the long description.
        assertEquals("small, powerful, scalable web/proxy server", sheet.summary());
        assertTrue(sheet.description().startsWith("Nginx is a web and proxy server"));
        assertTrue(sheet.description().contains("This package installs the full version."));
        assertFalse(sheet.description().contains(" . "), "a lone dot is a blank line, not a full stop");
    }

    @Test
    void readsDependenciesAsNamesAlone() {
        PackageSheet sheet = PackageSheet.parse(NGINX);

        // Versions are dropped: the only thing a screen does with these is offer them to be opened.
        assertEquals(List.of("libc6", "libssl3", "nginx-common"), sheet.depends());
        assertEquals(List.of("nginx-doc"), sheet.recommends());
    }

    @Test
    void readsAlternativesAndArchitecturesOutOfADependencyField() {
        assertEquals(List.of("debconf", "debconf-2.0"),
                PackageSheet.names("debconf (>= 0.5) | debconf-2.0"));
        assertEquals(List.of("libc6"), PackageSheet.names("libc6:amd64 (>= 2.34)"));
        assertEquals(List.of("a", "b"), PackageSheet.names("a, b, a"), "the same name twice is one button");
        assertEquals(List.of(), PackageSheet.names(null));
        assertEquals(List.of(), PackageSheet.names("   "));
    }

    @Test
    void keepsOnlyTheFirstVersionAptDescribes() {
        PackageSheet sheet = PackageSheet.parse("""
                Package: nginx
                Version: 1.22.1-9

                Package: nginx
                Version: 1.18.0-1
                """);

        // Apt prints the candidate first, and that is the one anybody is asking about.
        assertEquals("1.22.1-9", sheet.version());
    }

    @Test
    void anythingElseIsAnEmptySheetRatherThanAGuess() {
        assertTrue(PackageSheet.parse("").isEmpty());
        assertTrue(PackageSheet.parse(null).isEmpty());
        assertTrue(PackageSheet.parse("E: No packages found").isEmpty());
        assertTrue(PackageSheet.empty().isEmpty());
    }

    @Test
    void writesSizesTheWayTheRestOfTheScreenDoes() {
        assertEquals("", PackageSheet.bytes(0));
        assertEquals("509 kB", PackageSheet.bytes(521268));
        assertEquals("1.5 MB", PackageSheet.bytes(1024L * 1024 * 3 / 2));
        assertEquals("2.0 GB", PackageSheet.bytes(1024L * 1024 * 1024 * 2));
    }

    @Test
    void onlyOpensPlainWebAddresses() {
        // The homepage comes out of the server's package index, which Kubo did not write. A browser handed a
        // file:// or a custom scheme does things nobody asked for.
        assertThrows(IllegalArgumentException.class, () -> Host.openLink("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> Host.openLink("javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> Host.openLink("nginx.org"));
        assertThrows(IllegalArgumentException.class, () -> Host.openLink(""));
        assertThrows(IllegalArgumentException.class, () -> Host.openLink(null));

        assertEquals(List.of("xdg-open", "https://nginx.org"),
                Host.openLinkCommand("https://nginx.org", Host.Kind.LINUX));
        assertEquals(List.of("open", "https://nginx.org"),
                Host.openLinkCommand("https://nginx.org", Host.Kind.MAC));
        // Not explorer: explorer hands anything it does not recognise to the shell.
        assertTrue(Host.openLinkCommand("https://nginx.org", Host.Kind.WINDOWS).contains("rundll32"));
    }
}
