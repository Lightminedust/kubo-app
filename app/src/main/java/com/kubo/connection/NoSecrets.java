package com.kubo.connection;

import java.io.IOException;

/**
 * The answer on a system where Kubo has no way to encrypt a secret for one account only.
 *
 * <p>Windows has DPAPI, which ties the encrypted form to the logged-in account. Linux has no equivalent every
 * machine is guaranteed to have: a desktop may run a keyring, a headless box or a minimal install may not.
 *
 * <p>So Kubo does not store the password at all there, rather than writing it somewhere it could be read. The
 * connection screen hides the option instead of offering something it cannot keep safe. Key files are still
 * remembered, because a path is not a secret.
 */
public final class NoSecrets implements SessionStore.Protection {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String explanation() {
        return "This system has no per-account encryption Kubo can use, so passwords are never written to disk. "
                + "Key files are still remembered by their path.";
    }

    @Override
    public String protect(char[] secret) throws IOException {
        throw new IOException("This system cannot store a secret for your account alone");
    }

    @Override
    public char[] unprotect(String protectedSecret) throws IOException {
        throw new IOException("This system cannot read back a stored secret");
    }
}
