package com.kubo.model.services;

/**
 * Where a service stands, read across the two things a terminal shows separately: what is running now, and
 * what will be running after a reboot. The two mismatches between them are the whole point of the screen.
 */
public enum Standing {

    /** It tried and it broke. */
    FAILED("FAILED", "It tried to start and could not, or it stopped with an error."),

    /** Running, but nothing will start it again. */
    WILL_NOT_RETURN("WON'T SURVIVE A REBOOT",
            "Running right now, but disabled at boot: restart the machine and it is gone for good."),

    /** Meant to be up, is not. */
    NOT_RUNNING("ENABLED, NOT RUNNING", "Enabled at boot, but not running now. Something stopped it."),

    /** Running, and it will be again. */
    RUNNING("RUNNING", "Up now, and started again at every boot."),

    /** Everything else: finished one-shot tasks, units nothing asks for. */
    IDLE("IDLE", "Not running, and nothing is asking it to: one-shot tasks that are done, and units on standby.");

    private final String label;
    private final String meaning;

    Standing(String label, String meaning) {
        this.label = label;
        this.meaning = meaning;
    }

    public String label() {
        return label;
    }

    public String meaning() {
        return meaning;
    }

    /** True for the two that need looking at. */
    public boolean wrong() {
        return this == FAILED || this == WILL_NOT_RETURN || this == NOT_RUNNING;
    }
}
