package com.kubo.model.docker;

/**
 * Live figures for a running container.
 *
 * @param cpuPercent    share of a single CPU, so it can exceed 100 % on several cores
 * @param memUsedBytes  memory in use
 * @param memLimitBytes the memory limit, 0 when unknown
 * @param memPercent    memory used as a share of the limit
 */
public record ContainerStats(double cpuPercent, long memUsedBytes, long memLimitBytes, double memPercent) {

    public ContainerStats {
        cpuPercent = Math.max(0, cpuPercent);
        memUsedBytes = Math.max(0, memUsedBytes);
        memLimitBytes = Math.max(0, memLimitBytes);
        memPercent = Math.max(0, memPercent);
    }
}
