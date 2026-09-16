package com.kubo.model.packages;

import java.util.List;

/**
 * One family of packages, as the dashboard draws it: how many there are, what they weigh, and how many are waiting
 * to be upgraded.
 */
public record PackageGroup(String family, List<PackageEntry> members) {

    public PackageGroup {
        members = List.copyOf(members == null ? List.of() : members);
    }

    public int count() {
        return members.size();
    }

    public long bytes() {
        return members.stream().mapToLong(PackageEntry::installedBytes).sum();
    }

    public long upgradable() {
        return members.stream().filter(PackageEntry::upgradable).count();
    }

    public long security() {
        return members.stream().filter(PackageEntry::security).count();
    }
}
