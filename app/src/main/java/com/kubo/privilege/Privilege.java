package com.kubo.privilege;

import com.kubo.files.ShellQuote;

import java.util.Arrays;
import java.util.Objects;

/**
 * Turns a plain shell command into one that runs as root, according to how this user can become root.
 * The sudo password stays in memory only, never on disk, and is wiped with {@link #wipe()}.
 *
 * <p>Kubo never becomes root on its own: every root action is asked about first, in the UI.
 */
public final class Privilege {

    /**
     * A command ready to run: the shell string and the bytes to send on its input (the sudo password, or none).
     *
     * @param input sent on standard input then closed, or null
     */
    public record Command(String command, byte[] input) {
    }

    private final RootAccess access;
    private char[] password;

    public Privilege(RootAccess access) {
        this.access = Objects.requireNonNull(access, "access");
    }

    public RootAccess access() {
        return access;
    }

    public boolean available() {
        return access.available();
    }

    /**
     * True when a root command cannot run yet because the sudo password is needed and not set.
     */
    public boolean needsPassword() {
        return access.needsPassword() && !hasPassword();
    }

    public boolean hasPassword() {
        return password != null && password.length > 0;
    }

    /**
     * Keeps the sudo password for this session; the previous one is wiped. The caller may wipe its own copy after.
     */
    public void setPassword(char[] password) {
        wipe();
        this.password = password == null ? null : password.clone();
    }

    public void wipe() {
        if (password != null) {
            Arrays.fill(password, '\0');
            password = null;
        }
    }

    /**
     * Wraps {@code command} so it runs as root.
     *
     * @throws IllegalStateException when root is not available, or a password is needed but not set
     */
    public Command asRoot(String command) {
        Objects.requireNonNull(command, "command");
        return switch (access) {
            case ROOT -> new Command(command, null);
            // A login shell is not used: -n and -S both keep sudo from ever prompting on a terminal Kubo has not got.
            case SUDO_NOPASSWD -> new Command("sudo -n -- /bin/sh -c " + ShellQuote.quote(command), null);
            case SUDO_PASSWORD -> {
                if (!hasPassword()) {
                    throw new IllegalStateException("The sudo password is needed first");
                }
                // -S reads the password from stdin, -p '' silences the prompt, -k is not used so sudo may cache it.
                yield new Command("sudo -S -p '' -- /bin/sh -c " + ShellQuote.quote(command), passwordBytes());
            }
            case NONE -> throw new IllegalStateException("This user cannot act as root");
        };
    }

    /**
     * A harmless command that checks the sudo password is right, so the UI can tell before running a real action.
     */
    public Command verifyPassword() {
        if (!access.needsPassword()) {
            return new Command("true", null);
        }
        if (!hasPassword()) {
            throw new IllegalStateException("The sudo password is needed first");
        }
        return new Command("sudo -S -p '' -k -- true", passwordBytes());
    }

    private byte[] passwordBytes() {
        char[] withNewline = Arrays.copyOf(password, password.length + 1);
        withNewline[password.length] = '\n';
        byte[] bytes = new String(withNewline).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Arrays.fill(withNewline, '\0');
        return bytes;
    }
}
