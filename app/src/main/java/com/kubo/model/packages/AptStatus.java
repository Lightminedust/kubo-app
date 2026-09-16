package com.kubo.model.packages;

/**
 * Whether this server manages packages with apt at all, and what shape it is in.
 */
public record AptStatus(Availability availability, String version, boolean rebootRequired) {

    public enum Availability {
        /** apt and dpkg are both there. */
        USABLE,
        /** A Linux that does not use apt: Fedora, Arch, Alpine... */
        ABSENT
    }

    public AptStatus {
        version = version == null ? "" : version;
    }

    public static AptStatus absent() {
        return new AptStatus(Availability.ABSENT, "", false);
    }

    public boolean usable() {
        return availability == Availability.USABLE;
    }
}
