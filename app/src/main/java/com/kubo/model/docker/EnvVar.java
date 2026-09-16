package com.kubo.model.docker;

import java.util.Locale;
import java.util.Objects;

/**
 * One environment variable of a container. Variables whose name suggests a secret are marked so the UI can hide
 * their value until the user asks to see it.
 */
public record EnvVar(String key, String value, boolean sensitive) {

    private static final String[] SECRET_HINTS = {"PASSWORD", "PASSWD", "SECRET", "TOKEN", "APIKEY", "API_KEY",
            "PRIVATE", "CREDENTIAL", "PWD", "AUTH", "SIGNING"};

    public EnvVar {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
    }

    /**
     * Reads a "KEY=value" line, guessing whether it is a secret from its name.
     */
    public static EnvVar parse(String line) {
        int equals = line.indexOf('=');
        String key = equals < 0 ? line : line.substring(0, equals);
        String value = equals < 0 ? "" : line.substring(equals + 1);
        return new EnvVar(key, value, looksSecret(key));
    }

    /**
     * The value with every character replaced by a dot, for a masked display.
     */
    public String masked() {
        return value.isEmpty() ? "" : "•".repeat(Math.min(12, value.length()));
    }

    private static boolean looksSecret(String key) {
        String upper = key.toUpperCase(Locale.ROOT);
        for (String hint : SECRET_HINTS) {
            if (upper.contains(hint)) {
                return true;
            }
        }
        // A bare "KEY" name, but not "PUBLIC_KEY" is already caught; avoid flagging "KEYBOARD" etc.
        return upper.equals("KEY") || upper.endsWith("_KEY");
    }
}
