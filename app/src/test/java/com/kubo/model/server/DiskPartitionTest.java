package com.kubo.model.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiskPartitionTest {

    @Test
    void usedPercentageMatchesDfWhenSpaceIsReserved() {
        // 100 GB disk, 40 GB used, 5 GB reserved for root: df shows 55 GB available and 43%.
        DiskPartition disk = new DiskPartition("/", 100, 40, 55);

        assertEquals(40.0 / 95 * 100, disk.usedPercentage(), 1e-9);
    }

    @Test
    void fullDiskIsOneHundredPercent() {
        DiskPartition disk = new DiskPartition("/", 100, 95, 0);

        assertEquals(100.0, disk.usedPercentage(), 1e-9);
    }

    @Test
    void rejectsImpossibleValues() {
        assertThrows(NullPointerException.class, () -> new DiskPartition(null, 100, 40, 55));
        assertThrows(IllegalArgumentException.class, () -> new DiskPartition("/", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new DiskPartition("/", 100, 120, 0));
        assertThrows(IllegalArgumentException.class, () -> new DiskPartition("/", 100, -1, 55));
        assertThrows(IllegalArgumentException.class, () -> new DiskPartition("/", 100, 40, 101));
    }
}
