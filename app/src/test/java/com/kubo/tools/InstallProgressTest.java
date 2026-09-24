package com.kubo.tools;

import com.kubo.model.tools.InstallProgress;
import com.kubo.model.tools.InstallProgress.Phase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallProgressTest {

    private static InstallProgress reading(String... lines) {
        InstallProgress progress = new InstallProgress();
        for (String line : lines) {
            progress.accept(line);
        }
        return progress;
    }

    @Test
    void readsWhatAptSaysItWillFetchAndWhatHasArrived() {
        InstallProgress progress = reading(
                "Reading package lists...",
                "Building dependency tree...",
                "Need to get 1,234 kB of archives.",
                "Get:1 http://deb.debian.org/debian bookworm/main amd64 libtirpc3 amd64 1.3.3+ds-1 [80.0 kB]",
                "Get:2 http://deb.debian.org/debian bookworm/main amd64 lsof amd64 4.95.0-1 [376 kB]");

        assertEquals(Phase.FETCHING, progress.phase());
        assertEquals(2, progress.packages());
        assertEquals(InstallProgress.bytes("1,234", "kB"), progress.total());
        assertEquals(InstallProgress.bytes("80.0", "kB") + InstallProgress.bytes("376", "kB"),
                progress.fetched());
        assertTrue(progress.fraction() > 0 && progress.fraction() < 1);
    }

    @Test
    void theFetchedLineClosesTheDownloadAndCarriesTheSpeed() {
        InstallProgress progress = reading(
                "Need to get 1,234 kB of archives.",
                "Fetched 1,234 kB in 2s (600 kB/s)");

        assertEquals(Phase.UNPACKING, progress.phase());
        assertEquals("600 kB/s", progress.speed());
        assertEquals(progress.total(), progress.fetched());
    }

    @Test
    void theLinesMoveThePhaseAlong() {
        assertEquals(Phase.UNPACKING, reading("Preparing to unpack .../lsof_4.95.0-1_amd64.deb ...").phase());
        assertEquals(Phase.UNPACKING, reading("Unpacking lsof (4.95.0-1) ...").phase());
        assertEquals(Phase.SETTING_UP, reading("Setting up lsof (4.95.0-1) ...").phase());
    }

    @Test
    void readsDnfAsWell() {
        InstallProgress progress = reading(
                "Dependencies resolved.",
                "Total download size: 1.2 M",
                "Downloading Packages:",
                "(1/2): lsof-4.94.0-4.el9.x86_64.rpm   376 kB/s | 250 kB  00:00",
                "(2/2): libtirpc-1.3.3-1.el9.x86_64.rpm  80 kB/s | 100 kB  00:01",
                "  Installing       : lsof-4.94.0-4.el9.x86_64");

        assertEquals(Phase.SETTING_UP, progress.phase());
        assertEquals(2, progress.packages());
        assertEquals(InstallProgress.bytes("1.2", "M"), progress.total());
        assertEquals(InstallProgress.bytes("250", "kB") + InstallProgress.bytes("100", "kB"),
                progress.fetched());
    }

    @Test
    void aManagerNobodyUnderstandsLeavesTheFiguresEmptyRatherThanInventingThem() {
        InstallProgress progress = reading(
                "==> Retrieving packages...",
                "some line nothing here knows how to read");

        assertEquals(0, progress.total());
        assertEquals(0, progress.fetched());
        assertEquals(-1, progress.fraction(), "no figures means no bar, not a guessed one");
        assertEquals("some line nothing here knows how to read", progress.last());
    }

    @Test
    void unpackingWithNoFiguresAtAllStillRefusesToDrawABar() {
        InstallProgress progress = reading("Unpacking lsof (4.95.0-1) ...");

        assertEquals(Phase.UNPACKING, progress.phase());
        assertEquals(-1, progress.fraction());
    }

    @Test
    void unpackingAfterADownloadShowsTheBarFull() {
        InstallProgress progress = reading(
                "Need to get 376 kB of archives.",
                "Fetched 376 kB in 1s (300 kB/s)",
                "Unpacking lsof (4.95.0-1) ...");

        assertEquals(1, progress.fraction());
    }

    @Test
    void onlyTheLastFewLinesAreKept() {
        InstallProgress progress = new InstallProgress();
        for (int i = 1; i <= 20; i++) {
            progress.accept("line " + i);
        }

        assertEquals(7, progress.tail().size());
        assertEquals("line 14", progress.tail().get(0));
        assertEquals("line 20", progress.last());
    }

    @Test
    void blankLinesAreNotKept() {
        InstallProgress progress = reading("Reading package lists...", "   ", "", "Done");

        assertEquals(2, progress.tail().size());
    }

    @Test
    void finishingSaysWhichWayItWent() {
        InstallProgress worked = reading("Setting up lsof (4.95.0-1) ...");
        worked.finish(true);
        assertEquals(Phase.DONE, worked.phase());
        assertEquals(1, worked.fraction());

        InstallProgress failed = reading("E: Unable to locate package lsof");
        failed.finish(false);
        assertEquals(Phase.FAILED, failed.phase());
    }

    @Test
    void sizesAreReadInWhicheverWayTheServerWritesThem() {
        assertEquals(1024, InstallProgress.bytes("1", "kB"));
        assertEquals(1024, InstallProgress.bytes("1", "KiB"));
        assertEquals(1024 * 1024, InstallProgress.bytes("1", "M"));
        assertEquals(1024L * 1024 * 1024, InstallProgress.bytes("1", "GB"));
        assertEquals(1264 * 1024, InstallProgress.bytes("1,264", "kB"));
        assertEquals(376, InstallProgress.bytes("376", "B"));
        assertEquals(0, InstallProgress.bytes("not a number", "kB"));
    }

    @Test
    void everyPhaseHasAWordForTheScreen() {
        for (Phase phase : Phase.values()) {
            InstallProgress progress = new InstallProgress();
            if (phase == Phase.DONE) {
                progress.finish(true);
            } else if (phase == Phase.FAILED) {
                progress.finish(false);
            }
            assertTrue(!progress.phaseName().isBlank());
        }
    }

    @Test

    void readsAnIndexUpdateToTheEnd() {
        InstallProgress progress = reading(
                "Hit:1 http://deb.debian.org/debian bookworm InRelease",
                "Get:2 http://security.debian.org/debian-security bookworm-security InRelease [48.0 kB]",
                "Fetched 344 kB in 1s (312 kB/s)",
                "Reading package lists...");

        // Nothing is unpacked while reading an index, and the phase used to say it was.
        assertEquals(Phase.READING, progress.phase());
        assertEquals("READING THE LISTS", progress.phaseName());
        assertEquals(1, progress.fraction(), 0.001, "the download is over; the lists are what is left");
    }

    @Test

    void stillReadsAnInstallInOrder() {
        assertEquals(Phase.READING, reading("Reading package lists... Done").phase());
        assertEquals(Phase.READING, reading("Building dependency tree... Done").phase());
        assertEquals(Phase.FETCHING, reading("Reading package lists... Done",
                "Need to get 1,234 kB of archives.").phase());
        // "Reading database" turns up between fetching and unpacking, and must not send the phase backwards.
        assertEquals(Phase.UNPACKING, reading("Fetched 1,234 kB in 2s (600 kB/s)",
                "(Reading database ... 41203 files and directories currently installed.)").phase());
    }
}
