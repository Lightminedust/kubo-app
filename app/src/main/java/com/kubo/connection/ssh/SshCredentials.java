package com.kubo.connection.ssh;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * How to prove who we are to the server. Secrets are char arrays so they can be wiped after use.
 */
public sealed interface SshCredentials {

    /**
     * Overwrites the secret in memory.
     */
    void wipe();

    record Password(char[] password) implements SshCredentials {

        public Password {
            Objects.requireNonNull(password, "password");
        }

        @Override
        public void wipe() {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * A private key file (PuTTY .ppk or OpenSSH). An empty passphrase means the key is not encrypted.
     */
    record KeyFile(Path file, char[] passphrase) implements SshCredentials {

        public KeyFile {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(passphrase, "passphrase");
        }

        @Override
        public void wipe() {
            Arrays.fill(passphrase, '\0');
        }
    }
}
