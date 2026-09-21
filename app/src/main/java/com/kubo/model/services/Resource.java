package com.kubo.model.services;

import java.util.regex.Pattern;

/**
 * A limit that can be put on a running service without stopping it.
 *
 * <p>These are the two that answer the question people actually have — a service is eating the machine, hold
 * it back until I can look at it properly. systemd applies both at once and keeps them across a restart.
 *
 * <p>The property name is one of these fixed strings and never anything typed. The value is typed, so it is
 * checked against the shape systemd accepts before it goes anywhere near a command, and it is quoted on the
 * way. A value that does not match is refused here rather than sent and argued about.
 */
public enum Resource {

    MEMORY_MAX("MemoryMax", "MEMORY LIMIT",
            "512M, 2G, 1.5G, or infinity for none. The service is killed if it goes past it.",
            Pattern.compile("^(infinity|[0-9]+(\\.[0-9]+)?[KMGT]?)$")),

    CPU_QUOTA("CPUQuota", "CPU LIMIT",
            "50% is half a core, 200% is two of them, infinity for none.",
            Pattern.compile("^(infinity|[0-9]{1,4}%)$"));

    private final String property;
    private final String label;
    private final String meaning;
    private final Pattern shape;

    Resource(String property, String label, String meaning, Pattern shape) {
        this.property = property;
        this.label = label;
        this.meaning = meaning;
        this.shape = shape;
    }

    /** The name systemd knows it by. */
    public String property() {
        return property;
    }

    public String label() {
        return label;
    }

    public String meaning() {
        return meaning;
    }

    /** True when this is a value systemd would take, which is the only kind Kubo will send. */
    public boolean accepts(String value) {
        return value != null && shape.matcher(value.trim()).matches();
    }
}
