package com.kubo.monitor.parse;

import com.kubo.model.server.DiskPartition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DisksParserTest {

    @Test
    void parsesDfOutputWithoutTheHeader() {
        List<DiskPartition> disks = DisksParser.parse("""
                Filesystem        1-blocks        Used    Available Capacity Mounted on
                /dev/sda1     208293904384 41234567168 158502133760      21% /
                /dev/sda15       130256896     6219264    124037632       5% /boot/efi
                /dev/sdb1     107374182400  1073741824 106300440576       1% /mnt/my data
                """);

        assertEquals(List.of(
                new DiskPartition("/", 208293904384L, 41234567168L, 158502133760L),
                new DiskPartition("/boot/efi", 130256896L, 6219264L, 124037632L),
                new DiskPartition("/mnt/my data", 107374182400L, 1073741824L, 106300440576L)
        ), disks);
    }

    @Test
    void unreadableOrEmptyPartitionsAreSkipped() {
        assertNull(DisksParser.parseLine("Filesystem 1-blocks Used Available Capacity Mounted on"));
        assertNull(DisksParser.parseLine("none 0 0 0 - /sys/fs/bpf"));
        assertNull(DisksParser.parseLine("/dev/sda1 208293904384 41234567168"));
    }

    @Test
    void valuesAboveTheTotalAreCapped() {
        DiskPartition disk = DisksParser.parseLine("/dev/vda1 1000 1200 50 100% /");

        assertEquals(new DiskPartition("/", 1000, 1000, 50), disk);
    }
}
