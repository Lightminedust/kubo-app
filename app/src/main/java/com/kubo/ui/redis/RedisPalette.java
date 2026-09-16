package com.kubo.ui.redis;

import com.kubo.model.redis.RedisType;
import javafx.scene.paint.Color;

/**
 * The colour of each Redis type: strings magenta, hashes green, lists and sets blue, sorted sets and streams amber.
 */
final class RedisPalette {

    static final Color STRING = Color.web("#ff3fd8");
    static final Color HASH = Color.web("#35e0a1");
    static final Color LIST = Color.web("#5b6fd6");
    static final Color SET = Color.web("#35e8ff");
    static final Color ZSET = Color.web("#ffb347");
    static final Color STREAM = Color.web("#ffd24d");
    static final Color OTHER = Color.web("#8a8fb0");

    private RedisPalette() {
    }

    static Color color(RedisType type) {
        return switch (type) {
            case STRING -> STRING;
            case HASH -> HASH;
            case LIST -> LIST;
            case SET -> SET;
            case ZSET -> ZSET;
            case STREAM -> STREAM;
            case NONE, OTHER -> OTHER;
        };
    }

    /**
     * A colour as CSS hex, for the inline styles the rows and badges need.
     */
    static String web(Color color) {
        return String.format(java.util.Locale.ROOT, "#%02x%02x%02x",
                Math.round(color.getRed() * 255), Math.round(color.getGreen() * 255),
                Math.round(color.getBlue() * 255));
    }
}
