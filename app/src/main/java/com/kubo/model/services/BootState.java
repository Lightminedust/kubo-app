package com.kubo.model.services;

import java.util.Locale;

/**
 * What systemd will do with a service at the next boot, as {@code systemctl list-unit-files} reports it.
 *
 * <p>Only {@link #DISABLED} means "this will not come back on its own". A static or indirect unit has no
 * enable switch of its own because something else pulls it in, which is normal and not a problem — counting
 * those as trouble would bury the two cases that are.
 */
public enum BootState {

    ENABLED, DISABLED, STATIC, MASKED, GENERATED, INDIRECT, TRANSIENT, UNKNOWN;

    public static BootState of(String text) {
        String value = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        // "enabled-runtime" and "disabled-runtime" exist too: they say the same thing about the next boot.
        return switch (value.startsWith("enabled") ? "enabled" : value.startsWith("disabled") ? "disabled" : value) {
            case "enabled" -> ENABLED;
            case "disabled" -> DISABLED;
            case "static" -> STATIC;
            case "masked", "masked-runtime" -> MASKED;
            case "generated" -> GENERATED;
            case "indirect" -> INDIRECT;
            case "transient" -> TRANSIENT;
            default -> UNKNOWN;
        };
    }

    /** True when something will start this unit at boot, whether it is enabled itself or pulled in. */
    public boolean returnsAfterReboot() {
        return this == ENABLED || this == STATIC || this == GENERATED || this == INDIRECT;
    }
}
