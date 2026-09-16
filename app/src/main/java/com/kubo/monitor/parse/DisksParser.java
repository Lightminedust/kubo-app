package com.kubo.monitor.parse;

import com.kubo.model.server.DiskPartition;

import java.util.List;
import java.util.Objects;

/**
 * Reads local disk usage in bytes, leaving out memory-backed and Docker layer filesystems.
 */
public final class DisksParser {

    public static final String COMMAND = "df -P -B1 -l -x tmpfs -x devtmpfs -x overlay -x squashfs -x efivarfs";

    private DisksParser() {
    }

    /**
     * Lines that cannot be read are skipped rather than failing the whole list.
     */
    public static List<DiskPartition> parse(String output) {
        return output.lines()
                .map(DisksParser::parseLine)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * "/dev/sda1 208293904384 41234567168 158502133760 21% /" — the mount point may contain spaces.
     */
    static DiskPartition parseLine(String line) {
        String[] fields = line.strip().split("\\s+", 6);
        if (fields.length < 6) {
            return null;
        }
        try {
            long total = Long.parseLong(fields[1]);
            long used = Long.parseLong(fields[2]);
            long available = Long.parseLong(fields[3]);
            if (total <= 0 || used < 0 || available < 0) {
                return null;
            }
            return new DiskPartition(fields[5], total, Math.min(used, total), Math.min(available, total));
        } catch (NumberFormatException e) {
            // Header line or unexpected format.
            return null;
        }
    }
}
