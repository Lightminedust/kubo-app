package com.kubo.model.services;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether a service can be taken off the machine altogether, and what has to happen before its file goes.
 *
 * <p>Kubo deletes only a unit that lives in {@code /etc/systemd/system}, which is the directory an
 * administrator writes to and the only place Kubo itself writes. A service that arrived with a package lives
 * under {@code /usr/lib/systemd/system} and that file belongs to the package: deleting it leaves the package
 * installed and broken, and the next upgrade puts the file back. What to do with those is to disable them, or
 * to remove the package with the package manager that owns it.
 */
public final class Removal {

    /** The one directory a unit file may be in for Kubo to delete it. */
    public static final String LOCAL = "/etc/systemd/system/";

    private Removal() {
    }

    /**
     * Why this service cannot be deleted, or null when it can.
     *
     * @param unit     the unit name, as systemd gives it
     * @param filePath what {@code FragmentPath} says, which is the file systemd is really reading
     */
    public static String refusal(String unit, String filePath) {
        if (Lifeline.carriesTheConnection(unit)) {
            return "Deleting " + unit + " would leave this server with no way back in. Kubo will not do it "
                    + "from here. If you really mean to, do it from the provider's console, where you still "
                    + "have a way in.";
        }
        if (filePath == null || filePath.isBlank()) {
            return "systemd has no file for " + unit + ", so there is nothing here to delete. A unit with no "
                    + "file is usually one whose file has already gone without a daemon-reload after it.";
        }
        if (!filePath.equals(LOCAL + unit)) {
            return unit + " is not Kubo's to delete: systemd reads it from " + filePath + ". Only units in "
                    + LOCAL + " were put there by hand. A file under /usr/lib or /lib belongs to the package "
                    + "that installed it, and removing it would leave that package broken. Disable the "
                    + "service instead, or remove the package.";
        }
        return null;
    }

    /**
     * What has to be run before the file goes, in order.
     *
     * <p>Deleting the file of a running service leaves the process up with nothing behind it until the next
     * reboot, and deleting an enabled one leaves symlinks in the boot directories pointing at a file that is
     * gone. So it is stopped first when it is up, and disabled first when something would start it again.
     */
    public static List<Action> steps(ServiceUnit unit) {
        List<Action> steps = new ArrayList<>(2);
        if (unit.active() || unit.failed()) {
            steps.add(Action.STOP);
        }
        if (unit.boot() == BootState.ENABLED) {
            steps.add(Action.DISABLE);
        }
        return List.copyOf(steps);
    }
}
