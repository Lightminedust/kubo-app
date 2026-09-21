package com.kubo.model.services;

import java.util.Locale;

/**
 * The five things Kubo will ask systemd to do. Each is a fixed verb: nothing here is ever built out of what a
 * user typed.
 *
 * <p>Two of them add and three take away, which is why they are not offered the same way. Starting or enabling
 * a service can be undone by stopping or disabling it; stopping the wrong one on a machine you reach only over
 * SSH can end the session you are holding.
 */
public enum Action {

    START("START", "Starts it now. It changes nothing about the next boot."),
    STOP("STOP", "Stops it now. If it is enabled, it comes back at the next boot."),
    RESTART("RESTART", "Stops it and starts it again, in one step."),
    ENABLE("ENABLE", "Makes it start at every boot from now on. It does not start it now."),
    DISABLE("DISABLE", "Stops it starting at boot. It keeps running until something stops it.");

    private final String label;
    private final String meaning;

    Action(String label, String meaning) {
        this.label = label;
        this.meaning = meaning;
    }

    public String label() {
        return label;
    }

    public String meaning() {
        return meaning;
    }

    /** The systemd verb, which is the enum's own name in lower case. */
    public String verb() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** True for the ones that take something away, which are the ones that ask before they act. */
    public boolean takesAway() {
        return this == STOP || this == RESTART || this == DISABLE;
    }
}
