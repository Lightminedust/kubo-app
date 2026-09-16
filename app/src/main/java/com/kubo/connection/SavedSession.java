package com.kubo.connection;

import com.kubo.connection.ssh.SshTarget;

import java.util.Objects;

/**
 * A server the user connected to, remembered for next time. The key file is remembered as a path; the
 * password or passphrase only when the user explicitly asked, and then only encrypted for their Windows account.
 *
 * @param keyFile        path of the private key, empty for a password session
 * @param protectedSecret the password or passphrase encrypted by {@link WindowsSecrets}, empty when not remembered
 * @param lastUsedMillis  when it was last used, to list the most recent first
 */
public record SavedSession(String id, String username, String host, int port, Auth auth, String keyFile,
                           String protectedSecret, long lastUsedMillis) {

    public enum Auth { PASSWORD, KEY }

    public SavedSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(auth, "auth");
        keyFile = keyFile == null ? "" : keyFile;
        protectedSecret = protectedSecret == null ? "" : protectedSecret;
        // Validates username, host and port the same way a typed target is.
        new SshTarget(username, host, port);
        if (auth == Auth.KEY && keyFile.isBlank()) {
            throw new IllegalArgumentException("A key session needs its key file");
        }
    }

    public SshTarget target() {
        return new SshTarget(username, host, port);
    }

    public boolean remembersSecret() {
        return !protectedSecret.isEmpty();
    }

    /**
     * True for the same server, user, method and key: saving it again updates this entry instead of adding one.
     */
    public boolean sameAs(SshTarget target, Auth auth, String keyFile) {
        return target().equals(target) && this.auth == auth && this.keyFile.equals(keyFile == null ? "" : keyFile);
    }

    public SavedSession withSecret(String protectedSecret, long usedMillis) {
        return new SavedSession(id, username, host, port, auth, keyFile, protectedSecret, usedMillis);
    }
}
