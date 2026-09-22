package com.kubo.model.tools;

/**
 * The package manager this server uses, and the one command Kubo will ever run with it.
 *
 * <p>The package names never come from anything typed: they are fields of {@link Tool}. The only thing that
 * varies here is which of five fixed sentences is built.
 */
public enum PackageManager {

    /** Debian, Ubuntu and their family. */
    APT("apt-get", "Debian · Ubuntu"),
    /** Fedora, RHEL 8 and later, Rocky, Alma. */
    DNF("dnf", "Fedora · RHEL · Rocky · Alma"),
    /** CentOS 7 and the older Red Hat line. */
    YUM("yum", "CentOS 7 · older Red Hat"),
    /** Arch and its derivatives. */
    PACMAN("pacman", "Arch"),
    /** openSUSE. */
    ZYPPER("zypper", "openSUSE"),
    /** Alpine. */
    APK("apk", "Alpine"),
    /** Nothing Kubo knows how to drive. */
    NONE("", "");

    private final String binary;
    private final String family;

    PackageManager(String binary, String family) {
        this.binary = binary;
        this.family = family;
    }

    public String binary() {
        return binary;
    }

    /** The distributions this manager belongs to, for the card to say which one it found. */
    public String family() {
        return family;
    }

    public boolean known() {
        return this != NONE;
    }

    /**
     * The install command for one package.
     *
     * <p>Non-interactive everywhere: Kubo has no terminal to answer a prompt on, and a command that stops to
     * ask something nobody can see would hang until the timeout.
     *
     * <p>apt tries once and refreshes its lists only if that failed. A server whose index is months old cannot
     * install anything, and running update every time would make a thirty second job out of a five second one.
     */
    public String install(String packageName) {
        return switch (this) {
            case APT -> "DEBIAN_FRONTEND=noninteractive apt-get install -y " + packageName
                    + " || { apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y "
                    + packageName + "; }";
            case DNF -> "dnf install -y " + packageName;
            case YUM -> "yum install -y " + packageName;
            case PACMAN -> "pacman -S --noconfirm --needed " + packageName;
            case ZYPPER -> "zypper --non-interactive install " + packageName;
            case APK -> "apk add --no-cache " + packageName;
            case NONE -> throw new IllegalStateException("No package manager Kubo can drive was found");
        };
    }

    public static PackageManager of(String binary) {
        for (PackageManager manager : values()) {
            if (manager.known() && manager.binary.equals(binary)) {
                return manager;
            }
        }
        return NONE;
    }
}
