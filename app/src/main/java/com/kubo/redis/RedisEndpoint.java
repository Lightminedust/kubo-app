package com.kubo.redis;

import com.kubo.files.ShellQuote;
import com.kubo.privilege.Privilege;

import java.util.Arrays;

/**
 * How Kubo reaches redis-cli: straight on the host, or inside a Docker container. When it goes through a container
 * that only root can reach, the command is wrapped with sudo through {@link Privilege}.
 *
 * <p>When the server has a {@code requirepass}, the password travels as the {@code REDISCLI_AUTH} environment
 * variable, never as {@code -a} on the command line: an argument is visible to every user on the machine through
 * {@code ps}, an environment variable of a short-lived process is not. The password is held as a char array, wiped
 * on {@link #forget()}, never written to disk, and {@link #plain(String)} — the form that reaches the shell panel
 * and the session log — never carries it.
 */
public final class RedisEndpoint {

    private enum Where { HOST, DOCKER }

    private final Where where;
    private final String containerId;
    private final boolean needsRoot;
    private final Privilege privilege;
    private char[] password;

    private RedisEndpoint(Where where, String containerId, boolean needsRoot, Privilege privilege) {
        this.where = where;
        this.containerId = containerId;
        this.needsRoot = needsRoot;
        this.privilege = privilege;
    }

    public static RedisEndpoint host() {
        return new RedisEndpoint(Where.HOST, "", false, null);
    }

    public static RedisEndpoint docker(String containerId, boolean needsRoot, Privilege privilege) {
        return new RedisEndpoint(Where.DOCKER, containerId, needsRoot, privilege);
    }

    public boolean isDocker() {
        return where == Where.DOCKER;
    }

    /**
     * Keeps a copy of the password for this session. The caller may wipe its own array afterwards.
     */
    public void usePassword(char[] value) {
        forget();
        password = value == null || value.length == 0 ? null : Arrays.copyOf(value, value.length);
    }

    public boolean hasPassword() {
        return password != null;
    }

    /**
     * Wipes the password from memory. Called when the screen closes, or when Redis refuses it.
     */
    public void forget() {
        if (password != null) {
            Arrays.fill(password, (char) 0);
            password = null;
        }
    }

    /**
     * The plain shell command for a set of redis-cli arguments, without any sudo wrapping and without the password.
     * This is what the user is shown and what the session log keeps.
     */
    public String plain(String args) {
        return where == Where.HOST
                ? "redis-cli " + args
                : "docker exec " + ShellQuote.quote(containerId) + " redis-cli " + args;
    }

    /**
     * The command ready to run: the password carried in the environment when there is one, wrapped as root when the
     * container needs it.
     */
    public Privilege.Command command(String args) {
        return shell(authenticated(args));
    }

    /** Wraps a complete plain command (for example a bounded redis-cli MONITOR) with sudo when necessary. */
    Privilege.Command shell(String plain) {
        // Root may be possible without being on yet: without a sudo password there is no wrapper to build, so the
        // command goes as the connected user and fails the way docker fails for them.
        boolean rootable = needsRoot && privilege != null && privilege.available() && !privilege.needsPassword();
        return rootable ? privilege.asRoot(plain) : new Privilege.Command(plain, null);
    }

    /**
     * The runnable form of {@link #plain}, with the password in the environment of that one process.
     */
    String authenticated(String args) {
        if (password == null) {
            return plain(args);
        }
        String secret = ShellQuote.quote(new String(password));
        return where == Where.HOST
                ? "REDISCLI_AUTH=" + secret + " redis-cli " + args
                : "docker exec -e REDISCLI_AUTH=" + secret + " " + ShellQuote.quote(containerId)
                        + " redis-cli " + args;
    }

    /**
     * A complete plain command with the password added, for the few commands built outside {@link #command}.
     */
    String authenticatedShell(String plainCommand) {
        if (password == null) {
            return plainCommand;
        }
        // The prefix applies to the whole line; for a container the variable has to travel through docker exec.
        return where == Where.HOST
                ? "REDISCLI_AUTH=" + ShellQuote.quote(new String(password)) + " " + plainCommand
                : plainCommand.replaceFirst("docker exec ",
                        "docker exec -e REDISCLI_AUTH=" + java.util.regex.Matcher.quoteReplacement(
                                ShellQuote.quote(new String(password))) + " ");
    }
}
