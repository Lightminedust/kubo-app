package com.kubo.model.redis;

import java.util.Locale;

/**
 * The kind of value a Redis key holds, with the read command it takes.
 */
public enum RedisType {

    STRING("GET"),
    LIST("LRANGE"),
    SET("SMEMBERS"),
    HASH("HGETALL"),
    ZSET("ZRANGE"),
    STREAM("XRANGE"),
    NONE(""),
    OTHER("");

    private final String readCommand;

    RedisType(String readCommand) {
        this.readCommand = readCommand;
    }

    public String readCommand() {
        return readCommand;
    }

    public static RedisType parse(String value) {
        if (value == null) {
            return OTHER;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "string" -> STRING;
            case "list" -> LIST;
            case "set" -> SET;
            case "hash" -> HASH;
            case "zset" -> ZSET;
            case "stream" -> STREAM;
            case "none" -> NONE;
            default -> OTHER;
        };
    }

    /**
     * True when the value is a collection of entries rather than a single string.
     */
    public boolean isCollection() {
        return this == LIST || this == SET || this == HASH || this == ZSET;
    }

    /**
     * True when entries are field/value or member/score pairs.
     */
    public boolean isPaired() {
        return this == HASH || this == ZSET;
    }
}
