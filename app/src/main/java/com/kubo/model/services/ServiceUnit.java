package com.kubo.model.services;

import java.time.Duration;
import java.util.Objects;

/**
 * One systemd service, as the two listing commands describe it, plus how long it took at the last boot.
 *
 * @param load    systemd's own word: loaded, not-found, masked, error
 * @param activeState active, inactive, failed, activating, deactivating, reloading
 * @param subState    running, exited, dead, failed, start-pre, auto-restart
 * @param type    simple, forking, oneshot, notify… empty until the detail of this unit is read
 * @param startup how long it held up the last boot, or null when the boot times could not be read
 */
public record ServiceUnit(String name, String description, String load, String activeState, String subState,
                          BootState boot, String type, Duration startup) {

    public ServiceUnit {
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        load = load == null ? "" : load;
        activeState = activeState == null ? "" : activeState;
        subState = subState == null ? "" : subState;
        boot = boot == null ? BootState.UNKNOWN : boot;
        type = type == null ? "" : type;
    }

    public static ServiceUnit of(String name, String description, String load, String active, String sub) {
        return new ServiceUnit(name, description, load, active, sub, BootState.UNKNOWN, "", null);
    }

    public ServiceUnit withBoot(BootState value) {
        return new ServiceUnit(name, description, load, activeState, subState, value, type, startup);
    }

    public ServiceUnit withType(String value) {
        return new ServiceUnit(name, description, load, activeState, subState, boot, value, startup);
    }

    public ServiceUnit withStartup(Duration value) {
        return new ServiceUnit(name, description, load, activeState, subState, boot, type, value);
    }

    /** The name without its {@code .service} tail, which every unit here carries. */
    public String shortName() {
        return name.endsWith(".service") ? name.substring(0, name.length() - ".service".length()) : name;
    }

    public boolean failed() {
        return activeState.equals("failed") || subState.equals("failed");
    }

    /** Up and holding, as opposed to a one-shot task that ran and stayed "active (exited)". */
    public boolean running() {
        return activeState.equals("active") && subState.equals("running");
    }

    public boolean active() {
        return activeState.equals("active") || activeState.equals("activating")
                || activeState.equals("reloading");
    }

    /**
     * A task meant to run once and stop. Those are enabled and yet idle for most of the machine's life, which
     * is normal — calling them missing would be a false alarm on every screen.
     */
    public boolean oneShot() {
        return type.equals("oneshot") || subState.equals("exited");
    }

    public Standing standing() {
        if (failed()) {
            return Standing.FAILED;
        }
        if (running()) {
            return boot == BootState.DISABLED ? Standing.WILL_NOT_RETURN : Standing.RUNNING;
        }
        if (boot == BootState.ENABLED && !oneShot() && !active()) {
            return Standing.NOT_RUNNING;
        }
        return Standing.IDLE;
    }
}
