package com.kubo.packages;

import com.kubo.model.packages.AptStatus;
import com.kubo.model.packages.PackageEntry;
import com.kubo.model.packages.PackageInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AptCommandsTest {

    @Test
    void statusReadsAptOrSaysItIsAbsent() {
        AptStatus usable = AptCommands.parseStatus("OK 2.6.1", "NO");
        assertTrue(usable.usable());
        assertEquals("2.6.1", usable.version());
        assertFalse(usable.rebootRequired());

        assertTrue(AptCommands.parseStatus("OK 2.6.1", "YES").rebootRequired());
        assertFalse(AptCommands.parseStatus("ABSENT", "NO").usable());
    }

    @Test
    void installedPackagesAreRead() {
        String output = """
                nginx\t1.22.1-9\t1480\tweb\toptional\tno\tinstalled
                libc6\t2.36-9\t13160\tlibs\trequired\tyes\tinstalled
                broken-one\t1.0\t12\tutils\toptional\tno\tconfig-files
                openssh-server\t9.2p1-2\t1820\tnet\toptional\tno\tinstalled
                short line
                """;

        List<PackageEntry> packages = AptCommands.parseInstalled(output);

        // The half-removed package is not installed, so it is not counted.
        assertEquals(3, packages.size());
        PackageEntry libc = packages.get(1);
        assertEquals("libc6", libc.name());
        assertEquals(13160, libc.installedKb());
        assertEquals(13160L * 1024, libc.installedBytes());
        assertTrue(libc.essential());
        assertEquals("libs", libc.family());
        assertFalse(libc.upgradable());
    }

    @Test
    void aSectionWithAComponentPrefixKeepsOnlyItsFamily() {
        PackageEntry entry = new PackageEntry("x", "1", 10, "universe/net", "optional", false, "", false, false);
        assertEquals("net", entry.family());
    }

    @Test
    void upgradableListGivesTheWaitingVersionAndFlagsSecurity() {
        String output = """
                Listing...
                nginx/stable-security 1.22.1-9+deb12u1 amd64 [upgradable from: 1.22.1-9]
                vim/stable 2:9.0.1378-2 amd64 [upgradable from: 2:9.0.1000-4]
                not a package line
                """;

        Map<String, String[]> waiting = AptCommands.parseUpgradable(output);

        assertEquals(2, waiting.size());
        assertEquals("1.22.1-9+deb12u1", waiting.get("nginx")[0]);
        assertEquals("security", waiting.get("nginx")[1]);
        assertEquals("", waiting.get("vim")[1]);
    }

    @Test
    void autoRemovableNamesAreRead() {
        String output = """
                Reading package lists...
                The following packages will be REMOVED:
                  linux-image-6.1.0-9-amd64 linux-headers-6.1.0-9-amd64
                  libfoo1
                0 upgraded, 0 newly installed, 3 to remove and 0 not upgraded.
                """;

        Set<String> names = AptCommands.parseAutoRemovable(output);

        assertEquals(Set.of("linux-image-6.1.0-9-amd64", "linux-headers-6.1.0-9-amd64", "libfoo1"), names);
    }

    @Test
    void nothingToAutoremoveGivesNothing() {
        assertTrue(AptCommands.parseAutoRemovable("0 upgraded, 0 newly installed, 0 to remove.").isEmpty());
    }

    @Test
    void mergingFoldsTheThreeReadingsIntoOneList() {
        List<PackageEntry> installed = AptCommands.parseInstalled("""
                nginx\t1.22.1-9\t1480\tweb\toptional\tno\tinstalled
                libfoo1\t1.0\t90\tlibs\toptional\tno\tinstalled
                """);

        List<PackageEntry> merged = AptCommands.merge(installed,
                Map.of("nginx", new String[]{"1.22.1-9+deb12u1", "security"}), Set.of("libfoo1"));

        PackageEntry nginx = merged.get(0);
        assertTrue(nginx.upgradable());
        assertTrue(nginx.security());
        assertEquals("1.22.1-9+deb12u1", nginx.candidate());
        assertFalse(nginx.autoRemovable());

        PackageEntry libfoo = merged.get(1);
        assertFalse(libfoo.upgradable());
        assertTrue(libfoo.autoRemovable());
    }

    @Test
    void theInventoryGroupsByFamilyLargestFirst() {
        List<PackageEntry> packages = AptCommands.parseInstalled("""
                libc6\t2.36\t13160\tlibs\trequired\tyes\tinstalled
                libssl3\t3.0\t6000\tlibs\toptional\tno\tinstalled
                libz1\t1.2\t120\tlibs\toptional\tno\tinstalled
                nginx\t1.22\t1480\tweb\toptional\tno\tinstalled
                """);
        PackageInventory inventory = new PackageInventory(
                new AptStatus(AptStatus.Availability.USABLE, "2.6", false), packages, List.of());

        assertEquals(4, inventory.total());
        assertEquals((13160L + 6000 + 120 + 1480) * 1024, inventory.bytes());
        assertEquals("libs", inventory.groups().get(0).family());
        assertEquals(3, inventory.groups().get(0).count());
        // Inside a family the heaviest package comes first.
        assertEquals("libc6", inventory.groups().get(0).members().get(0).name());
    }

    @Test
    void waitingPutsSecurityFixesFirst() {
        List<PackageEntry> merged = AptCommands.merge(
                AptCommands.parseInstalled("""
                        vim\t9.0\t100\tutils\toptional\tno\tinstalled
                        nginx\t1.22\t1480\tweb\toptional\tno\tinstalled
                        curl\t7.88\t400\tnet\toptional\tno\tinstalled
                        """),
                Map.of("vim", new String[]{"9.1", ""}, "nginx", new String[]{"1.23", "security"}),
                Set.of());
        PackageInventory inventory = new PackageInventory(
                new AptStatus(AptStatus.Availability.USABLE, "2.6", false), merged, List.of());

        assertEquals(2, inventory.upgradable());
        assertEquals(1, inventory.security());
        assertEquals("nginx", inventory.waiting().get(0).name());
    }

    @ParameterizedTest
    @ValueSource(strings = {"nginx", "libc6", "g++", "python3.11", "linux-image-6.1.0-9-amd64", "lib32z1"})
    void realPackageNamesAreAccepted(String name) {
        assertTrue(AptCommands.nameProblem(name).isEmpty(), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nginx; rm -rf /", "nginx && reboot", "$(reboot)", "`reboot`", "../etc/passwd",
            "nginx |tee", "a", "", " ", "NGINX!"})
    void anythingThatCouldEscapeTheCommandIsRefused(String name) {
        assertTrue(AptCommands.nameProblem(name).isPresent(), name);
        assertThrows(IllegalArgumentException.class, () -> AptCommands.install(name));
        assertThrows(IllegalArgumentException.class, () -> AptCommands.remove(name));
        assertThrows(IllegalArgumentException.class, () -> AptCommands.purge(name));
    }

    @Test
    void actionsAreFixedTemplatesAndNeverInteractive() {
        assertEquals("DEBIAN_FRONTEND=noninteractive apt-get -y install nginx", AptCommands.install("nginx"));
        assertEquals("DEBIAN_FRONTEND=noninteractive apt-get -y remove nginx", AptCommands.remove("NGINX"));
        assertEquals("DEBIAN_FRONTEND=noninteractive apt-get -y purge nginx", AptCommands.purge(" nginx "));
        assertEquals("apt-get -s install nginx 2>&1", AptCommands.dryRunInstall("nginx"));
        assertTrue(AptCommands.UPGRADE.contains("-y"));
        assertTrue(AptCommands.UPDATE_INDEX.equals("apt-get update"));
    }

    @Test
    void theSimulationSummaryIsPickedOut() {
        String dryRun = """
                Reading package lists...
                The following NEW packages will be installed:
                  htop
                0 upgraded, 1 newly installed, 0 to remove and 3 not upgraded.
                """;
        assertEquals("0 upgraded, 1 newly installed, 0 to remove and 3 not upgraded.",
                AptCommands.summarise(dryRun));

        assertEquals("E: Unable to locate package nope",
                AptCommands.summarise("Reading package lists...\nE: Unable to locate package nope"));
    }
}
