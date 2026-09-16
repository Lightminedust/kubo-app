package com.kubo.model.server;

import java.util.Objects;

/**
 * Disk partition storage snapshot, as reported by {@code df -P}.
 *
 * @param availableBytes space usable by normal users; lower than total - used on ext4,
 *                       which reserves about 5% for root
 */
public record DiskPartition(
    String mountPoint,
    long totalBytes,
    long usedBytes,
    long availableBytes
) {
    public DiskPartition {
        Objects.requireNonNull(mountPoint, "mountPoint cannot be null");

        if (totalBytes <= 0) {
            throw new IllegalArgumentException("totalBytes must be greater than 0");
        }
        if (usedBytes < 0 || usedBytes > totalBytes) {
            throw new IllegalArgumentException("usedBytes must be between 0 and totalBytes");
        }
        if (availableBytes < 0 || availableBytes > totalBytes) {
            throw new IllegalArgumentException("availableBytes must be between 0 and totalBytes");
        }
    }

    /**
     * Same figure as the "Use%" column of df: the reserved space does not count as free.
     */
    public double usedPercentage() {
        long usable = usedBytes + availableBytes;
        return usable == 0 ? 100.0 : (double) usedBytes / usable * 100.0;
    }
}
