package com.kubo.ui.files;

import com.kubo.model.files.RemoteEntry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferTest {

    private static final RemoteEntry PROJECTS = RemoteEntry.folder("/home/xana/projects");

    @Test
    void durationsReadNaturally() {
        assertEquals("0 S", Transfer.duration(-3));
        assertEquals("42 S", Transfer.duration(42.4));
        assertEquals("2 MIN 5 S", Transfer.duration(125));
        assertEquals("1 H 1 MIN", Transfer.duration(3_660));
    }

    @Test
    void anEstimatedTotalNeverLooksFinishedEarly() {
        Transfer transfer = new Transfer(true, Path.of("C:\\Downloads"), PROJECTS, "projects");
        transfer.total = 100;
        transfer.sent = 250;

        assertEquals(1.0, transfer.fraction());
        transfer.estimated = true;
        assertEquals(0.99, transfer.fraction());
    }

    @Test
    void speedIsSmoothedAndDrivesTheTimeLeft() {
        Transfer transfer = new Transfer(false, Path.of("a.bin"), PROJECTS, "a.bin");
        transfer.total = 10_000_000;
        transfer.start(1_000_000_000L);

        transfer.sent = 1_000_000;
        transfer.measureSpeed(2_000_000_000L);
        assertEquals(1_000_000, transfer.speed(), 1);
        transfer.sent = 4_000_000;
        transfer.measureSpeed(3_000_000_000L);
        assertEquals(1_600_000, transfer.speed(), 1);

        String status = transfer.statusText();
        assertTrue(status.startsWith("40 %  ·  "), status);
        assertTrue(status.contains("4 S LEFT"), status);
        assertTrue(status.endsWith("INTO /home/xana/projects"), status);
    }

    @Test
    void speedIsNotMeasuredMoreThanFourTimesASecond() {
        Transfer transfer = new Transfer(false, Path.of("a.bin"), PROJECTS, "a.bin");
        transfer.start(1_000_000_000L);
        transfer.sent = 500;

        transfer.measureSpeed(1_100_000_000L);

        assertEquals(0, transfer.speed());
    }

    @Test
    void runningCoversEverythingBeforeTheEnd() {
        Transfer transfer = new Transfer(false, Path.of("a.bin"), PROJECTS, "a.bin");

        assertTrue(transfer.running());
        transfer.state = Transfer.State.CONFLICT;
        assertFalse(transfer.running());
    }

    @Test
    void downloadSaysWhereItLands() {
        Transfer transfer = new Transfer(true, Path.of("C:\\Users\\xana\\Downloads"), PROJECTS, "projects");

        assertEquals("WAITING  ·  TO C:\\Users\\xana\\Downloads", transfer.statusText());
    }
}
