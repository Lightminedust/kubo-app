package com.kubo.model.packages;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The map is only as honest as this classification: a package the user sees under SYSTEM had better be one the
 * machine actually needs.
 */
class PackageRoleTest {

    private static PackageEntry entry(String name, String section, String priority, boolean essential) {
        return new PackageEntry(name, "1.0", 100, section, priority, essential, "", false, false, false);
    }

    @ParameterizedTest
    @CsvSource({
            "libc6, libs, required",
            "bash, shells, required",
            "systemd, admin, important",
            "dpkg, admin, optional",
            "linux-image, kernel, optional",
            "init-system-helpers, init, optional",
            "task-ssh-server, metapackages, optional"})
    @DisplayName("what the machine needs to run is system, whatever section it was filed under")
    void theMachineItself(String name, String section, String priority) {
        assertEquals(PackageRole.SYSTEM, PackageRole.of(entry(name, section, priority, false)));
    }

    @Test
    @DisplayName("an essential package is system even when its section says otherwise")
    void essentialOutranksTheSection() {
        assertEquals(PackageRole.SYSTEM, PackageRole.of(entry("coreutils", "utils", "optional", true)));
    }

    @ParameterizedTest
    @CsvSource({"libssl3, libs", "libpng16-16, libs", "libfoo-perl, libfoo"})
    @DisplayName("shared code is libraries")
    void sharedCode(String name, String section) {
        assertEquals(PackageRole.LIBRARIES, PackageRole.of(entry(name, section, "optional", false)));
    }

    @ParameterizedTest
    @CsvSource({"nginx, web", "postgresql, database", "openssh-server, net", "postfix, mail"})
    @DisplayName("what listens and serves is services")
    void whatServes(String name, String section) {
        assertEquals(PackageRole.SERVICES, PackageRole.of(entry(name, section, "optional", false)));
    }

    @ParameterizedTest
    @CsvSource({"gcc, devel", "libssl-dev, libdevel", "git, vcs", "python3.11, python", "nodejs, javascript"})
    @DisplayName("what builds software is development, and libdevel is a build tool rather than a library")
    void whatBuilds(String name, String section) {
        assertEquals(PackageRole.DEVELOPMENT, PackageRole.of(entry(name, section, "optional", false)));
    }

    @ParameterizedTest
    @CsvSource({"manpages, doc", "fonts-dejavu, fonts", "locales-all, localization"})
    @DisplayName("text is docs and data")
    void justText(String name, String section) {
        assertEquals(PackageRole.DOCUMENTATION, PackageRole.of(entry(name, section, "optional", false)));
    }

    @Test
    @DisplayName("anything else is something a person runs")
    void everythingElse() {
        assertEquals(PackageRole.APPLICATIONS, PackageRole.of(entry("htop", "utils", "optional", false)));
        assertEquals(PackageRole.APPLICATIONS, PackageRole.of(entry("gimp", "graphics", "optional", false)));
    }

    @Test
    @DisplayName("the component prefix Ubuntu adds to a section is ignored")
    void theComponentPrefixDoesNotConfuseIt() {
        assertEquals(PackageRole.SERVICES, PackageRole.of(entry("nginx", "universe/web", "optional", false)));
        assertEquals(PackageRole.LIBRARIES, PackageRole.of(entry("libz", "main/libs", "optional", false)));
    }

    @Test
    @DisplayName("a package with no section at all still lands somewhere")
    void nothingIsLeftUnclassified() {
        assertEquals(PackageRole.APPLICATIONS,
                PackageRole.of(new PackageEntry("odd", "1.0", 1, null, null, false, "", false, false, false)));
    }

    @Test
    @DisplayName("the inventory counts what was asked for apart from what came in as a dependency")
    void askedForAgainstPulledIn() {
        PackageEntry asked = new PackageEntry("nginx", "1.0", 10, "web", "optional", false, "", false, false, true);
        PackageEntry pulled = new PackageEntry("libz", "1.0", 10, "libs", "optional", false, "", false, false, false);
        PackageInventory inventory = new PackageInventory(AptStatus.absent(), List.of(asked, pulled), List.of());

        assertEquals(1, inventory.manual());
        assertEquals(2, inventory.total());
    }
}
