package com.kubo.model.packages;

import java.util.Locale;
import java.util.Objects;

/**
 * One package installed on the server.
 *
 * @param installedKb what it occupies on disk, as dpkg reports it, in kibibytes
 * @param section     dpkg's own grouping: admin, libs, python, net, devel...
 * @param candidate   the version waiting to be installed, empty when it is up to date
 * @param security    true when the waiting version comes from a security suite
 */
public record PackageEntry(String name, String version, long installedKb, String section, String priority,
                           boolean essential, String candidate, boolean security, boolean autoRemovable,
                           boolean manual) {

    /**
     * Without the manual flag, for the readings that do not know it yet.
     */
    public PackageEntry(String name, String version, long installedKb, String section, String priority,
                        boolean essential, String candidate, boolean security, boolean autoRemovable) {
        this(name, version, installedKb, section, priority, essential, candidate, security, autoRemovable, false);
    }

    public PackageEntry {
        Objects.requireNonNull(name, "name");
        version = version == null ? "" : version;
        section = section == null || section.isBlank() ? "other" : section;
        priority = priority == null ? "" : priority;
        candidate = candidate == null ? "" : candidate;
    }

    public boolean upgradable() {
        return !candidate.isEmpty();
    }

    public long installedBytes() {
        return installedKb * 1024;
    }

    /**
     * The family this package belongs to, which is what the dashboard groups by. dpkg sections carry a component
     * prefix such as "universe/net", and the part after the slash is the one that means anything.
     */
    /**
     * What this package is for, which is how the map groups it.
     */
    public PackageRole role() {
        return PackageRole.of(this);
    }

    public String family() {
        String value = section.toLowerCase(Locale.ROOT);
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }
}
