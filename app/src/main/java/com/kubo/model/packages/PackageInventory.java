package com.kubo.model.packages;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything installed on the server, grouped the way the dashboard shows it.
 *
 * @param notes what could not be read, worded for the user
 */
public record PackageInventory(AptStatus status, List<PackageEntry> packages, List<String> notes) {

    public PackageInventory {
        status = status == null ? AptStatus.absent() : status;
        packages = List.copyOf(packages == null ? List.of() : packages);
        notes = List.copyOf(notes == null ? List.of() : notes);
    }

    public static PackageInventory empty() {
        return new PackageInventory(AptStatus.absent(), List.of(), List.of());
    }

    public int total() {
        return packages.size();
    }

    public long bytes() {
        return packages.stream().mapToLong(PackageEntry::installedBytes).sum();
    }

    public long upgradable() {
        return packages.stream().filter(PackageEntry::upgradable).count();
    }

    public long security() {
        return packages.stream().filter(PackageEntry::security).count();
    }

    public long autoRemovable() {
        return packages.stream().filter(PackageEntry::autoRemovable).count();
    }

    /**
     * The packages someone asked for, as opposed to the ones that only arrived as dependencies.
     */
    public long manual() {
        return packages.stream().filter(PackageEntry::manual).count();
    }

    /**
     * The families, largest first, which is the order the dashboard draws them in.
     */
    public List<PackageGroup> groups() {
        Map<String, List<PackageEntry>> byFamily = new LinkedHashMap<>();
        for (PackageEntry entry : packages) {
            byFamily.computeIfAbsent(entry.family(), key -> new ArrayList<>()).add(entry);
        }
        List<PackageGroup> groups = new ArrayList<>();
        byFamily.forEach((family, members) -> {
            members.sort(Comparator.comparingLong(PackageEntry::installedKb).reversed());
            groups.add(new PackageGroup(family, members));
        });
        groups.sort(Comparator.comparingInt(PackageGroup::count).reversed());
        return List.copyOf(groups);
    }

    /**
     * The packages waiting for a new version, the ones that matter most first.
     */
    public List<PackageEntry> waiting() {
        List<PackageEntry> waiting = new ArrayList<>(packages.stream().filter(PackageEntry::upgradable).toList());
        waiting.sort(Comparator.comparing(PackageEntry::security).reversed()
                .thenComparing(PackageEntry::name));
        return List.copyOf(waiting);
    }
}
