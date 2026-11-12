package com.kubo.model.apps;

import java.util.List;

/**
 * Everything the apps screen shows, from one reading.
 *
 * @param pm2         whether PM2 is installed for this account
 * @param supervisor  whether supervisorctl is installed
 * @param cores       how many cores the machine has, 0 when unknown
 * @param memoryTotal the machine's memory, in bytes, 0 when unknown
 * @param memoryUsed  what of it is in use, in bytes
 * @param boot        whether PM2 is set to start at boot: "enabled", "disabled", or empty when unknown
 * @param notes       what could not be read, in words
 * @param host        who PM2 runs as and where it is, which starting it at boot needs
 * @param coreLoads   how busy each core was since the reading before, 0 to 1; empty on a first reading
 * @param load        the one minute load average, or -1 when unknown
 */
public record AppsReport(boolean pm2, boolean supervisor, List<App> apps, int cores, long memoryTotal,
                         long memoryUsed, String boot, List<String> notes, Host host, List<Double> coreLoads,
                         double load) {

    /**
     * Who PM2 runs as, and where PM2 and Node are on the disk. Read from the server itself, never typed.
     *
     * @param account the connected account
     * @param home    its home folder
     * @param pm2     the full path of the pm2 command, empty when there is none
     * @param node    the full path of node, empty when there is none
     */
    public record Host(String account, String home, String pm2, String node) {

        public static final Host UNKNOWN = new Host("", "", "", "");

        public Host {
            account = account == null ? "" : account;
            home = home == null ? "" : home;
            pm2 = pm2 == null ? "" : pm2;
            node = node == null ? "" : node;
        }

        /** Enough is known to write the unit that starts PM2 at boot. */
        public boolean known() {
            return !account.isEmpty() && !home.isEmpty() && !pm2.isEmpty();
        }
    }

    public static final AppsReport EMPTY = new AppsReport(false, false, List.of(), 0, 0, 0, "", List.of());

    public AppsReport(boolean pm2, boolean supervisor, List<App> apps, int cores, long memoryTotal,
                      long memoryUsed, String boot, List<String> notes) {
        this(pm2, supervisor, apps, cores, memoryTotal, memoryUsed, boot, notes, Host.UNKNOWN, List.of(), -1);
    }

    public AppsReport {
        apps = List.copyOf(apps);
        boot = boot == null ? "" : boot;
        notes = List.copyOf(notes);
        host = host == null ? Host.UNKNOWN : host;
        coreLoads = List.copyOf(coreLoads == null ? List.of() : coreLoads);
    }

    /** PM2 is set to come back when the machine boots. */
    public boolean startsAtBoot() {
        return boot.equals("enabled");
    }

    /** PM2 applications that are not in the saved list. */
    public long unsaved() {
        return apps.stream().filter(app -> !app.saved()).count();
    }

    public long processes() {
        return apps.stream().mapToLong(app -> app.processes().size()).sum();
    }

    public long count(App.State state) {
        return apps.stream().filter(app -> app.state() == state).count();
    }
}
