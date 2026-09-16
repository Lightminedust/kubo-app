package com.kubo.privilege;

/**
 * How the connected user can act as root on this server, found once per session.
 */
public enum RootAccess {

    /**
     * The user is already root: nothing special is needed.
     */
    ROOT,

    /**
     * The user may sudo without a password (NOPASSWD).
     */
    SUDO_NOPASSWD,

    /**
     * The user is a sudoer but must give their password.
     */
    SUDO_PASSWORD,

    /**
     * No way to become root.
     */
    NONE;

    /**
     * The command that finds this out. It never blocks: {@code sudo -n} fails at once when a password is needed.
     */
    public static final String DETECT = "if [ \"$(id -u)\" = 0 ]; then echo ROOT; "
            + "elif command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then echo SUDO_NOPASSWD; "
            + "elif command -v sudo >/dev/null 2>&1 && id -nG 2>/dev/null | tr ' ' '\\n' | grep -qxE 'sudo|wheel|admin'; "
            + "then echo SUDO_PASSWORD; else echo NONE; fi";

    public static RootAccess parse(String output) {
        return switch (output.strip()) {
            case "ROOT" -> ROOT;
            case "SUDO_NOPASSWD" -> SUDO_NOPASSWD;
            case "SUDO_PASSWORD" -> SUDO_PASSWORD;
            default -> NONE;
        };
    }

    public boolean available() {
        return this != NONE;
    }

    /**
     * True when acting as root is possible but a sudo password is still needed.
     */
    public boolean needsPassword() {
        return this == SUDO_PASSWORD;
    }
}
