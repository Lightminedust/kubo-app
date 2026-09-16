package com.kubo.model.redis;

import java.util.List;
import java.util.Objects;

/**
 * The content of one key. A string keeps its text; a collection keeps its entries (one field per row, and a second
 * cell for hashes and sorted sets).
 *
 * @param truncated true when only the first entries were read
 * @param error     why the value could not be read, empty on success
 */
public record RedisValue(RedisType type, String text, List<Entry> entries, boolean truncated, String error) {

    /**
     * One entry of a collection. {@code second} is empty unless the type stores pairs (hash fields, zset scores).
     */
    public record Entry(String first, String second) {
        public Entry {
            Objects.requireNonNull(first, "first");
            second = second == null ? "" : second;
        }
    }

    public RedisValue {
        Objects.requireNonNull(type, "type");
        text = text == null ? "" : text;
        entries = List.copyOf(entries == null ? List.of() : entries);
        error = error == null ? "" : error;
    }

    public static RedisValue string(String text, boolean truncated) {
        return new RedisValue(RedisType.STRING, text, List.of(), truncated, "");
    }

    public static RedisValue collection(RedisType type, List<Entry> entries, boolean truncated) {
        return new RedisValue(type, "", entries, truncated, "");
    }

    public static RedisValue failed(RedisType type, String error) {
        return new RedisValue(type, "", List.of(), false, error.isBlank() ? "Cannot read the value" : error);
    }

    public boolean failed() {
        return !error.isEmpty();
    }

    public int size() {
        return type == RedisType.STRING ? (text.isEmpty() ? 0 : 1) : entries.size();
    }
}
