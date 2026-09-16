package com.kubo.model.redis;

import java.util.Locale;
import java.util.Objects;

/**
 * A key seen while scanning: its name, type and time to live.
 *
 * @param ttlSeconds seconds left before it expires; {@link #PERSISTENT} when it never expires, {@link #MISSING}
 *                   when it is already gone
 * @param typeName   the type exactly as the server names it, which matters for the types Redis modules add
 *                   (a RedisJSON value answers {@code ReJSON-RL}), so the screen can name what it found instead
 *                   of calling everything it does not know "other"
 */
public record RedisKey(String name, RedisType type, long ttlSeconds, String typeName) {

    public static final long PERSISTENT = -1;
    public static final long MISSING = -2;

    public RedisKey {
        Objects.requireNonNull(name, "name");
        type = type == null ? RedisType.OTHER : type;
        typeName = typeName == null || typeName.isBlank() ? type.name().toLowerCase(Locale.ROOT) : typeName.strip();
    }

    public RedisKey(String name, RedisType type, long ttlSeconds) {
        this(name, type, ttlSeconds, null);
    }

    public boolean isPersistent() {
        return ttlSeconds == PERSISTENT;
    }

    public boolean expires() {
        return ttlSeconds >= 0;
    }

    /**
     * What to call this type on screen: the enum for the types Redis ships with, the server's own word for the rest.
     */
    public String label() {
        return type == RedisType.OTHER ? typeName.toUpperCase(Locale.ROOT) : type.name();
    }

    /**
     * True when the value is RedisJSON, which Kubo can still read as text.
     */
    public boolean isJson() {
        return type == RedisType.OTHER && typeName.toLowerCase(Locale.ROOT).contains("json");
    }
}
