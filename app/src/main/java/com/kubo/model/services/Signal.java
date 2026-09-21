package com.kubo.model.services;

/**
 * A signal Kubo will send to a service, without going through the stop it usually does.
 *
 * <p>The list is short on purpose. These are the ones with a plain meaning to somebody running a server; the
 * rest belong in a terminal, where whoever types them knows exactly what they are for.
 */
public enum Signal {

    HUP("SIGHUP", "Re-read the configuration. Most servers do this without dropping anything.", false),
    USR1("SIGUSR1", "Whatever this service chose it to mean. nginx reopens its log files.", false),
    USR2("SIGUSR2", "Whatever this service chose it to mean. nginx starts a new binary.", false),
    TERM("SIGTERM", "Ask it to stop, the way a stop would. It gets to tidy up first.", true),
    KILL("SIGKILL", "End it now. It gets no chance to tidy up, and may leave things half written.", true);

    private final String name;
    private final String meaning;
    private final boolean rough;

    Signal(String name, String meaning, boolean rough) {
        this.name = name;
        this.meaning = meaning;
        this.rough = rough;
    }

    public String signalName() {
        return name;
    }

    public String meaning() {
        return meaning;
    }

    /** True for the ones that end something, which are the ones that ask before they act. */
    public boolean rough() {
        return rough;
    }
}
