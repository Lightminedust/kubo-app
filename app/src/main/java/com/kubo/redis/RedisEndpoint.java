package com.kubo.redis;

import com.kubo.files.ShellQuote;
import com.kubo.privilege.Privilege;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * How Kubo reaches redis-cli: straight on the host, or inside a Docker container. When it goes through a container
 * that only root can reach, the command is wrapped with sudo through {@link Privilege}.
 *
 * <p>When the server has a {@code requirepass}, the password never appears in the command line at all. It is
 * written to the command's standard input, and the line begins by reading it into {@code REDISCLI_AUTH}:
 *
 * <pre>IFS= read -r REDISCLI_AUTH &amp;&amp; export REDISCLI_AUTH &amp;&amp; redis-cli ...</pre>
 *
 * <p>Anything written into the line itself would be readable by every user on the server through {@code ps} —
 * as the argument list of the shell that runs it, of {@code docker}, and of {@code sudo}, which also writes it to
 * the auth log. An earlier version put {@code REDISCLI_AUTH=<password>} in the line on the belief that an
 * environment assignment stays private; it does not, because the assignment is itself part of the line.
 *
 * <p>A container gets it through {@code docker exec -e REDISCLI_AUTH} with no value, which makes docker copy the
 * value from its own environment. The password is held as a char array, wiped on {@link #forget()}, never turned
 * into a String, never written to disk, and {@link #plain(String)} — the form that reaches the shell panel and the
 * session log — never carries it.
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

    /** Reads the password from standard input into the environment of everything that follows on the line. */
    private static final String READ_PASSWORD = "IFS= read -r REDISCLI_AUTH && export REDISCLI_AUTH && ";

    /**
     * The command ready to run for a set of redis-cli arguments.
     */
    public Privilege.Command command(String args) {
        return command("", args);
    }

    /**
     * The command ready to run, with {@code prefix} placed in front of redis-cli — the bounded MONITOR runs it under
     * {@code timeout}. The prefix is one of Kubo's own templates, never something a person typed.
     *
     * <p>Without a password the line is exactly what it always was. With one, the line carries no secret: the
     * password is the first line Kubo writes to standard input, after the sudo password when there is one.
     */
    public Privilege.Command command(String prefix, String args) {
        boolean withPassword = password != null;
        String invocation = prefix + (where == Where.HOST
                ? "redis-cli " + args
                : "docker exec " + (withPassword ? "-e REDISCLI_AUTH " : "") + ShellQuote.quote(containerId)
                        + " redis-cli " + args);
        if (!withPassword) {
            return shell(invocation);
        }
        String line = READ_PASSWORD + invocation;
        byte[] secret = passwordLine();
        if (!rootable()) {
            return new Privilege.Command(line, secret);
        }
        // The sudo password and the Redis password share one standard input, so sudo must consume exactly one
        // line every time. A cached sudo ticket would otherwise let the Redis password be read as the sudo one,
        // and leave nothing for REDISCLI_AUTH.
        Privilege.Command wrapped = privilege.asRootReadingInput(line);
        return new Privilege.Command(wrapped.command(), join(wrapped.input(), secret));
    }

    /** Wraps a complete plain command with sudo when necessary. Used only where no Redis password is involved. */
    Privilege.Command shell(String plain) {
        return rootable() ? privilege.asRoot(plain) : new Privilege.Command(plain, null);
    }

    /**
     * Root may be possible without being on yet: without a sudo password there is no wrapper to build, so the
     * command goes as the connected user and fails the way docker fails for them.
     */
    private boolean rootable() {
        return needsRoot && privilege != null && privilege.available() && !privilege.needsPassword();
    }

    /** The password as one line of UTF-8, built from the char array without ever becoming a String. */
    private byte[] passwordLine() {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] line = new byte[encoded.remaining() + 1];
        encoded.get(line, 0, line.length - 1);
        line[line.length - 1] = '\n';
        Arrays.fill(encoded.array(), (byte) 0);
        return line;
    }

    private static byte[] join(byte[] first, byte[] second) {
        if (first == null || first.length == 0) {
            return second;
        }
        byte[] both = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }
}
