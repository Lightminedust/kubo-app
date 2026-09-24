package com.kubo.model.packages;

import java.util.List;
import java.util.Locale;

/**
 * What restarting one service actually costs, because they are not the same act.
 *
 * <p>A list of seventeen identical RESTART buttons invites somebody to work down it, and two or three of those
 * buttons are not like the others. Restarting an application server drops whatever request it was serving.
 * Restarting the SSH daemon, the message bus or the network is how a machine reached only over the network
 * stops being reachable.
 *
 * <p>The judgement is by unit name, which is a rule of thumb and is shown as one. It is right for the units
 * that matter, and being wrong in the cautious direction costs a confirmation nobody minds.
 */
public enum ServiceRisk {

    /**
     * Restarting this can end the way in.
     *
     * <p>sshd itself usually survives, because it forks a process per connection and the one already open is
     * not the one being restarted. What does not survive is a configuration it refuses to start with, and that
     * only shows at the next connection.
     */
    CUTS_THE_WAY_IN("MAY CLOSE THE WAY IN",
            "Kubo is connected through this. The session open now will very likely survive, but if the "
                    + "service will not come back up, nothing new can connect and the way in is your "
                    + "provider's console."),

    /**
     * Restarting this shakes things that everything else is standing on.
     */
    SHAKES_THE_MACHINE("THE MACHINE LEANS ON THIS",
            "Much of the system talks through this. Restarting it while things are running can leave other "
                    + "services in a state only a reboot clears."),

    /** Ordinary: it stops, it starts, and what it was doing at that instant is dropped. */
    ORDINARY("", "");

    /** The network and the way in. */
    private static final List<String> WAY_IN = List.of(
            "ssh", "sshd", "openssh-server", "networking", "systemd-networkd", "networkmanager",
            "network-manager", "wpa_supplicant", "systemd-resolved");

    /** What the rest of the system is built on top of. */
    private static final List<String> FOUNDATION = List.of(
            "dbus", "dbus-broker", "systemd-logind", "systemd-journald", "systemd-udevd", "polkit",
            "systemd-machined", "init");

    private final String badge;
    private final String warning;

    ServiceRisk(String badge, String warning) {
        this.badge = badge;
        this.warning = warning;
    }

    /** A short word for the row, empty for an ordinary service. */
    public String badge() {
        return badge;
    }

    /** What to put in front of somebody before they press it, empty when there is nothing to say. */
    public String warning() {
        return warning;
    }

    /** True when this one should be asked about rather than done. */
    public boolean asks() {
        return this != ORDINARY;
    }

    /**
     * How risky restarting this unit is.
     *
     * @param unit a systemd unit name, with or without its {@code .service} ending
     */
    public static ServiceRisk of(String unit) {
        String name = bare(unit);
        if (WAY_IN.contains(name)) {
            return CUTS_THE_WAY_IN;
        }
        if (FOUNDATION.contains(name)) {
            return SHAKES_THE_MACHINE;
        }
        // A template instance such as "user@1000.service" is named by what comes before the @.
        int at = name.indexOf('@');
        if (at > 0) {
            String base = name.substring(0, at);
            if (WAY_IN.contains(base)) {
                return CUTS_THE_WAY_IN;
            }
            if (FOUNDATION.contains(base) || base.equals("user") || base.equals("getty")) {
                return SHAKES_THE_MACHINE;
            }
        }
        return ORDINARY;
    }

    /** Whether a batch that only touches the ordinary ones may include this unit. */
    public static boolean safeToBatch(String unit) {
        return of(unit) == ORDINARY;
    }

    private static String bare(String unit) {
        String name = unit == null ? "" : unit.strip().toLowerCase(Locale.ROOT);
        for (String ending : List.of(".service", ".socket", ".target", ".scope", ".mount", ".timer")) {
            if (name.endsWith(ending)) {
                return name.substring(0, name.length() - ending.length());
            }
        }
        return name;
    }
}
