package com.kubo.tools;

import com.kubo.files.ShellQuote;

import java.util.regex.Pattern;

/**
 * The questions Kubo can ask from the server once the tools are there: does this answer, who owns this, where
 * does the traffic stop.
 *
 * <p>Every one of these takes a target from the operator, so every one of them checks it first. A target is an
 * address or a host name and nothing else, and it is quoted after it is checked. All of them are read-only and
 * none needs root.
 *
 * <p>They are also all bounded. A ping with no count runs until somebody stops it, and nobody can stop it from
 * here: each command is given a count and a timeout so it always ends on its own.
 */
public final class ProbeCommands {

    /** An IPv4 or IPv6 literal, or a host name. Nothing else ever reaches a command. */
    private static final Pattern TARGET = Pattern.compile(
            "^(?=.{1,253}$)([0-9a-fA-F:.]{2,45}|([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)"
                    + "(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*)$");

    private ProbeCommands() {
    }

    /**
     * @throws IllegalArgumentException when the text is not an address or a host name
     */
    public static String checkedTarget(String target) {
        String text = target == null ? "" : target.strip();
        if (!TARGET.matcher(text).matches()) {
            throw new IllegalArgumentException("Not an address or a host name: " + target);
        }
        return text;
    }

    /** Three packets, two seconds each: an answer or a verdict, never a command left running. */
    public static String ping(String target) {
        return "ping -c 3 -W 2 " + ShellQuote.quote(checkedTarget(target));
    }

    /**
     * Asks one resolver for one name.
     *
     * @param resolver the server to ask, empty to use whatever the machine is configured with
     */
    public static String dig(String name, String resolver) {
        String at = resolver == null || resolver.isBlank() ? "" : "@" + checkedTarget(resolver) + " ";
        return "dig +time=2 +tries=1 " + at + ShellQuote.quote(checkedTarget(name));
    }

    /** Every hop to a target, three rounds, as a report rather than a live screen. */
    public static String route(String target) {
        return "mtr -r -n -c 3 " + ShellQuote.quote(checkedTarget(target));
    }

    /** Who an address belongs to. This one leaves the network: it asks a public whois server. */
    public static String whois(String target) {
        return "whois " + ShellQuote.quote(checkedTarget(target));
    }

    /**
     * Bandwidth by program, for a few seconds, then it stops.
     *
     * <p>nethogs is a full screen tool by default. {@code -t} makes it print lines instead, and {@code -c} ends
     * it after that many samples, which is the only reason it can be run from here at all.
     */
    public static String bandwidthByProgram(String device) {
        return "nethogs -t -c 4 " + ShellQuote.quote(checkedDevice(device));
    }

    /** An interface name as the kernel writes them. */
    private static final Pattern DEVICE = Pattern.compile("^[A-Za-z0-9._@-]{1,32}$");

    public static String checkedDevice(String device) {
        String text = device == null ? "" : device.strip();
        if (!DEVICE.matcher(text).matches()) {
            throw new IllegalArgumentException("Not an interface name: " + device);
        }
        return text;
    }
}
