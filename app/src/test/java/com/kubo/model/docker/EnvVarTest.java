package com.kubo.model.docker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvVarTest {

    @Test
    void parsesKeyAndValue() {
        EnvVar var = EnvVar.parse("DATABASE_URL=postgres://db:5432/app");

        assertEquals("DATABASE_URL", var.key());
        assertEquals("postgres://db:5432/app", var.value());
        assertFalse(var.sensitive());
    }

    @Test
    void aLineWithoutAnEqualsIsAllKey() {
        EnvVar var = EnvVar.parse("FLAG");

        assertEquals("FLAG", var.key());
        assertEquals("", var.value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MONGO_INITDB_ROOT_PASSWORD", "JWT_SECRET", "API_KEY", "DB_PASSWD", "AUTH_TOKEN",
            "SIGNING_KEY", "APP_KEY"})
    void secretsAreFlagged(String key) {
        assertTrue(EnvVar.parse(key + "=x").sensitive());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PATH", "NODE_ENV", "PORT", "KEYBOARD_LAYOUT", "PUBLIC_URL"})
    void plainVariablesAreNotFlagged(String key) {
        assertFalse(EnvVar.parse(key + "=x").sensitive());
    }

    @Test
    void maskingReplacesEveryCharacterUpToTwelve() {
        assertEquals("••••", EnvVar.parse("K=abcd").masked());
        assertEquals("••••••••••••", EnvVar.parse("K=" + "a".repeat(50)).masked());
        assertEquals("", EnvVar.parse("K=").masked());
    }
}
