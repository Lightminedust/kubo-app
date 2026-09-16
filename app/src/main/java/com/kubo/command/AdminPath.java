package com.kubo.command;

/**
 * Putting the administration directories back on the PATH.
 *
 * <p>A non-root SSH session on Debian and Ubuntu gets a PATH without {@code /usr/sbin} and {@code /sbin}, so
 * {@code command -v ufw} answers nothing on a machine where UFW is installed and running. Every tool this matters
 * for lives there: {@code ufw}, {@code nginx}, {@code ss}, {@code iptables}, {@code nft}, {@code firewall-cmd}.
 * Without this, Kubo reports "not installed" for things that are plainly installed — and for a firewall that is the
 * worst answer it could give.
 *
 * <p>The prefix goes on the command that is sent, never on the one that is shown or logged: the log is there to
 * say what Kubo asked the server to do, and {@code ufw status numbered} is what it asked.
 */
public final class AdminPath {

    /**
     * The directories a login shell drops. {@code /usr/local/sbin} first, as a locally built tool should win.
     */
    public static final String DIRECTORIES = "/usr/local/sbin:/usr/sbin:/sbin";

    private static final String PREFIX = "export PATH=\"$PATH:" + DIRECTORIES + "\"; ";

    private AdminPath() {
    }

    /**
     * The command as it should be sent: the same command, with the admin directories added to the PATH first.
     */
    public static String with(String command) {
        return PREFIX + command;
    }

    /**
     * A test for whether a tool exists, answering YES or NO.
     *
     * <p>{@code command -v} is used rather than a list of guessed paths, so a tool installed somewhere unusual —
     * {@code /opt}, a snap, a locally built binary — is still found, as long as it is on the widened PATH.
     */
    public static String present(String binary) {
        return with("command -v " + binary + " >/dev/null 2>&1 && echo YES || echo NO");
    }
}
