package com.kubo.model.services;

import java.util.Set;

/**
 * The services that carry the very connection Kubo is talking over.
 *
 * <p>Stopping sshd on a machine you reach only over SSH ends the session and there is no second way in: the
 * server keeps running, perfectly healthy, and unreachable for good. Kubo refuses those outright rather than
 * asking whether you are sure, because being sure is not the problem — a mis-click is.
 *
 * <p>A restart is a different matter and is allowed: sshd keeps its established sessions across one, so the
 * window Kubo is in survives it. It still asks first.
 */
public final class Lifeline {

    /** The ways an SSH daemon is named across distributions. */
    private static final Set<String> DOORS = Set.of("ssh.service", "sshd.service", "ssh.socket", "sshd.socket");

    /** Take one of these away and the machine has no network to answer on. */
    private static final Set<String> NETWORK = Set.of("systemd-networkd.service", "NetworkManager.service",
            "networking.service", "network.service", "network-online.target");

    private Lifeline() {
    }

    /** True when this unit is either the door Kubo came in by or the network under it. */
    public static boolean carriesTheConnection(String unit) {
        return DOORS.contains(unit) || NETWORK.contains(unit);
    }

    /**
     * Why this signal must not be sent to this unit, or null when it may be.
     *
     * <p>A signal that ends the service is a stop by another name, and on the door it has the same result:
     * the session goes and nothing brings it back.
     */
    public static String refusal(Signal signal, String unit) {
        if (!carriesTheConnection(unit) || !signal.rough()) {
            return null;
        }
        boolean door = DOORS.contains(unit);
        return "Sending " + signal.signalName() + " to " + unit + " would "
                + (door ? "end this session and leave no way back into this server."
                        : "take the network away from this server, and this session with it.")
                + " Kubo will not do it from here. If you really mean to, do it from the provider's console, "
                + "where you still have a way in.";
    }

    /**
     * Why this action must not be run on this unit, or null when it may be.
     *
     * <p>The wording is the reason, not a scolding: it is shown to the user in place of the button.
     */
    public static String refusal(Action action, String unit) {
        if (!carriesTheConnection(unit) || !action.takesAway() || action == Action.RESTART) {
            return null;
        }
        boolean door = DOORS.contains(unit);
        String what = action == Action.STOP ? "Stopping" : "Disabling";
        return what + " " + unit + " would "
                + (door ? "end this session and leave no way back into this server."
                        : "take the network away from this server, and this session with it.")
                + " Kubo will not do it from here. If you really mean to, do it from the provider's console, "
                + "where you still have a way in.";
    }
}
