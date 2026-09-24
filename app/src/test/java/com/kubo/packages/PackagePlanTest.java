package com.kubo.packages;

import com.kubo.model.packages.PackagePlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading what apt says it would do, before it is allowed to do it. */
class PackagePlanTest {

    @Test
    void readsWhatWouldBeInstalledUpgradedAndRemoved() {
        PackagePlan plan = PackagePlan.parse("""
                NOTE: This is only a simulation!
                Remv old-thing [1.0-1]
                Inst libssl3 [3.0.11-1] (3.0.13-1 Debian:12/stable [amd64])
                Inst nginx (1.22.1-9 Debian:12/stable [amd64])
                Conf nginx (1.22.1-9 Debian:12/stable [amd64])
                Need to get 12.4 MB of archives.
                After this operation, 3,072 kB of additional disk space will be used.
                1 upgraded, 1 newly installed, 1 to remove and 0 not upgraded.
                """);

        // A bracket before the parenthesis means apt is replacing a version already there.
        assertEquals(List.of("libssl3"), plan.upgrading());
        assertEquals(List.of("nginx"), plan.installing(), "Conf is the same package again, not a second one");
        assertEquals(List.of("old-thing"), plan.removing());
        // Apt writes its sizes in thousands and Kubo's converter reads them in 1024s, so the figure runs a few
        // per cent high. It is shown as "12.4 MB to download" either way, and the converter is shared with the
        // progress readout, where only the ratio of two numbers read the same way matters.
        assertEquals(Math.round(12.4 * 1024 * 1024), plan.downloadBytes());
        assertFalse(plan.freeingDisk());
        assertTrue(plan.summary().startsWith("1 upgraded"));
        assertFalse(plan.refused());
    }

    @Test
    void readsDiskSpaceBeingGivenBack() {
        PackagePlan plan = PackagePlan.parse("""
                Remv doc-thing [2.0]
                After this operation, 420 MB disk space will be freed.
                0 upgraded, 0 newly installed, 1 to remove and 0 not upgraded.
                """);

        assertTrue(plan.freeingDisk());
        assertTrue(plan.cost().contains("freed on disk"), plan.cost());
        assertFalse(plan.cost().contains("download"), "nothing is downloaded to remove something");
    }

    @Test
    void saysNothingAboutAnOrdinaryChange() {
        PackagePlan plan = PackagePlan.parse("""
                Remv cowsay [3.03+dfsg2-8]
                0 upgraded, 0 newly installed, 1 to remove and 0 not upgraded.
                """);

        assertEquals(List.of(), plan.breaking());
        assertEquals("", plan.warning(), "most changes are ordinary and should be left alone");
    }

    @Test
    void stopsInFrontOfLosingTheWayIn() {
        PackagePlan plan = PackagePlan.parse("""
                Remv openssh-server [1:9.2p1-2]
                Remv libssl3 [3.0.11-1]
                0 upgraded, 0 newly installed, 2 to remove and 0 not upgraded.
                """);

        assertEquals(List.of("openssh-server"), plan.breaking());
        String warning = plan.warning();
        assertTrue(warning.contains("Kubo is connected over SSH"), warning);
        assertTrue(warning.contains("provider's console"), warning);
    }

    @Test
    void stopsInFrontOfLosingTheMachine() {
        PackagePlan plan = PackagePlan.parse("""
                Remv libc6 [2.36-9]
                Remv systemd [252.22-1]
                Remv linux-image-6.1.0-18-amd64 [6.1.76-1]
                0 upgraded, 0 newly installed, 3 to remove and 0 not upgraded.
                """);

        // The kernel is named with its version, so it is matched by its beginning rather than by a fixed name.
        assertEquals(List.of("libc6", "systemd", "linux-image-6.1.0-18-amd64"), plan.breaking());
        assertTrue(plan.warning().contains("needs to run or to boot"), plan.warning());
        assertFalse(plan.warning().contains("SSH"), "this one is not about the way in");
    }

    @Test
    void aRefusalIsSaidRatherThanReadAsAnEmptyPlan() {
        PackagePlan plan = PackagePlan.parse("E: Unable to locate package nosuchthing");

        assertTrue(plan.refused());
        assertTrue(plan.problem().startsWith("E:"));
        assertEquals(List.of(), plan.removing());
    }

    @Test
    void nothingAtAllIsAnEmptyPlan() {
        assertFalse(PackagePlan.parse("").refused());
        assertEquals(List.of(), PackagePlan.parse(null).installing());
        assertEquals("", PackagePlan.parse("").cost());
    }
}
