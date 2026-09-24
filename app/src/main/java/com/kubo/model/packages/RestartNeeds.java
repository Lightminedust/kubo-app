package com.kubo.model.packages;

import java.util.ArrayList;
import java.util.List;

/**
 * What is still running on libraries that have already been replaced.
 *
 * <p>Upgrading a library writes a new file. Everything already running goes on using the old one, which is
 * still in memory, until it is restarted. So a server can be fully patched on disk and still be serving
 * traffic through the flaw the patch closed, and nothing about it looks wrong. This is the gap between
 * "upgraded" and "actually running the upgrade".
 *
 * <p>Kubo showed RESTART REQUIRED and nothing else, which says a reboot is due without saying that restarting
 * two services would do instead. {@code needrestart} knows which two.
 *
 * @param services the systemd units running on something that has been replaced
 * @param sessions logged-in sessions in the same position, which only their owner can deal with
 */
public record RestartNeeds(Kernel kernel, String running, String expected, List<String> services,
                           List<String> sessions, boolean available) {

    /** What {@code needrestart} says about the kernel, which is the one thing a restart cannot fix. */
    public enum Kernel {
        /** The kernel running is the one that is meant to be. */
        CURRENT,
        /** A newer kernel is installed with a compatible interface. A reboot is tidy, not urgent. */
        ABI_COMPATIBLE,
        /** A newer kernel is installed. Only a reboot will run it. */
        REBOOT_NEEDED,
        UNKNOWN
    }

    public RestartNeeds {
        kernel = kernel == null ? Kernel.UNKNOWN : kernel;
        running = running == null ? "" : running;
        expected = expected == null ? "" : expected;
        services = List.copyOf(services == null ? List.of() : services);
        sessions = List.copyOf(sessions == null ? List.of() : sessions);
    }

    /** Nothing is running on anything stale, and the kernel is the one installed. */
    public boolean settled() {
        return available && services.isEmpty() && sessions.isEmpty()
                && (kernel == Kernel.CURRENT || kernel == Kernel.UNKNOWN);
    }

    public boolean needsReboot() {
        return kernel == Kernel.REBOOT_NEEDED;
    }

    /** One line for the top of the card. */
    public String headline() {
        if (!available) {
            return "Kubo cannot tell which services are affected without needrestart.";
        }
        List<String> parts = new ArrayList<>();
        if (!services.isEmpty()) {
            parts.add(services.size() + (services.size() == 1 ? " service is running"
                    : " services are running") + " on libraries that have been replaced");
        }
        if (needsReboot()) {
            parts.add("a newer kernel is installed and only a reboot will run it");
        }
        if (parts.isEmpty()) {
            return "Nothing is running on anything that has been replaced.";
        }
        String line = String.join(", and ", parts);
        return Character.toUpperCase(line.charAt(0)) + line.substring(1) + ".";
    }

    /** What the kernel line says, or empty when there is nothing to say about it. */
    public String kernelNote() {
        return switch (kernel) {
            case REBOOT_NEEDED -> "Running " + running + ", and " + expected + " is installed. A restart of a "
                    + "service cannot change this; only a reboot can.";
            case ABI_COMPATIBLE -> "Running " + running + ", with " + expected + " installed. Their interfaces "
                    + "match, so this can wait for a reboot that suits you.";
            case CURRENT -> "";
            case UNKNOWN -> "";
        };
    }

    /** Reads {@code needrestart -b}, whose output is one {@code NEEDRESTART-FIELD: value} per line. */
    public static RestartNeeds parse(String output) {
        if (output == null || output.isBlank() || output.contains("NEEDRESTART-ABSENT")) {
            return absent();
        }
        Kernel kernel = Kernel.UNKNOWN;
        String running = "";
        String expected = "";
        List<String> services = new ArrayList<>();
        List<String> sessions = new ArrayList<>();
        boolean sawAnything = false;

        for (String raw : output.lines().toList()) {
            String line = raw.strip();
            int colon = line.indexOf(':');
            if (!line.startsWith("NEEDRESTART-") || colon < 0) {
                continue;
            }
            sawAnything = true;
            String field = line.substring("NEEDRESTART-".length(), colon).strip();
            String value = line.substring(colon + 1).strip();
            switch (field) {
                case "KCUR" -> running = value;
                case "KEXP" -> expected = value;
                case "KSTA" -> kernel = kernelOf(value);
                case "SVC" -> {
                    if (!value.isEmpty() && !services.contains(value)) {
                        services.add(value);
                    }
                }
                case "SESS" -> {
                    if (!value.isEmpty()) {
                        sessions.add(value);
                    }
                }
                default -> {
                    // VER, UCSTA and anything a later needrestart adds: not ours to interpret.
                }
            }
        }
        return sawAnything
                ? new RestartNeeds(kernel, running, expected, services, sessions, true)
                : absent();
    }

    /**
     * needrestart's own numbering: 0 unknown, 1 the running kernel is the expected one, 2 a newer kernel with a
     * compatible interface, 3 a newer kernel that needs a reboot.
     */
    private static Kernel kernelOf(String value) {
        return switch (value) {
            case "1" -> Kernel.CURRENT;
            case "2" -> Kernel.ABI_COMPATIBLE;
            case "3" -> Kernel.REBOOT_NEEDED;
            default -> Kernel.UNKNOWN;
        };
    }

    /** needrestart is not installed, so nothing here could be answered. */
    public static RestartNeeds absent() {
        return new RestartNeeds(Kernel.UNKNOWN, "", "", List.of(), List.of(), false);
    }

}
