package com.kubo.connection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Kubo does on a system that cannot encrypt a secret for one account.
 *
 * <p>The failure to guard against is a silent one: a password written to {@code sessions.json} in the clear
 * because storing it looked like it worked.
 */
class NoSecretsTest {

    private final NoSecrets secrets = new NoSecrets();

    @Test
    @DisplayName("it never claims to be able to keep a secret")
    void itIsNeverAvailable() {
        assertFalse(secrets.available());
        assertFalse(new SessionStore(java.nio.file.Path.of("unused.json"), secrets, java.time.Clock.systemUTC())
                .canRememberSecrets(), "the connection screen hides the option on this answer alone");
    }

    @Test
    @DisplayName("storing a secret fails loudly rather than writing it somewhere readable")
    void storingRefuses() {
        assertThrows(IOException.class, () -> secrets.protect("hunter2".toCharArray()));
        assertThrows(IOException.class, () -> secrets.unprotect("anything"));
    }

    @Test
    @DisplayName("the screen is told why, in words that are true of this system")
    void itExplainsItself() {
        String explanation = secrets.explanation();

        assertFalse(explanation.isBlank());
        assertTrue(explanation.contains("Key files"), "key paths are still remembered, and that matters here");
        assertFalse(explanation.contains("DPAPI"), "that is the Windows answer, not this one");
    }
}
