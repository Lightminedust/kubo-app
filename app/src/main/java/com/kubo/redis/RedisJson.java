package com.kubo.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kubo.model.redis.RedisKey;
import com.kubo.model.redis.RedisType;
import com.kubo.model.redis.RedisValue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Versioned JSON interchange for one Redis key on the user's local computer. */
public final class RedisJson {

    public static final String FORMAT = "kubo.redis.key/v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Document(int database, RedisKey key, RedisValue value) {
    }

    private RedisJson() {
    }

    public static void write(Path path, int database, RedisKey key, RedisValue value) throws IOException {
        if (value == null || value.failed()) {
            throw new IOException("The Redis value could not be exported");
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("format", FORMAT);
        root.put("database", database);
        root.put("key", key.name());
        root.put("type", key.type().name().toLowerCase(java.util.Locale.ROOT));
        if (key.isPersistent()) {
            root.putNull("ttl");
        } else {
            root.put("ttl", Math.max(0, key.ttlSeconds()));
        }
        root.set("value", encode(value));
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), root);
    }

    public static Document read(Path path) throws IOException {
        JsonNode root = MAPPER.readTree(path.toFile());
        if (!FORMAT.equals(root.path("format").asText())) {
            throw new IOException("This is not a Kubo Redis key file");
        }
        String name = root.path("key").asText("").strip();
        RedisType type = RedisType.parse(root.path("type").asText());
        if (name.isEmpty() || !creatable(type)) {
            throw new IOException("The JSON key name or type is invalid");
        }
        long ttl = root.path("ttl").isNumber() ? root.path("ttl").asLong() : RedisKey.PERSISTENT;
        RedisValue value = decode(type, root.get("value"));
        return new Document(Math.max(0, root.path("database").asInt()), new RedisKey(name, type, ttl), value);
    }

    /**
     * The entries of a collection as the JSON a person can edit by hand: an object for a hash, member/score items
     * for a sorted set, a plain array otherwise. The same shape the export file uses, so what you edit here and
     * what you export are one format, not two.
     */
    public static String writeEntries(RedisValue value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(encode(value));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "";
        }
    }

    /**
     * Reads back what {@link #writeEntries} produced, for a type that Redis can actually rebuild.
     *
     * @throws IOException with a message meant for the user when the text is not the shape this type needs
     */
    public static RedisValue readEntries(RedisType type, String json) throws IOException {
        if (!creatable(type)) {
            throw new IOException("Kubo cannot rebuild a " + type.name().toLowerCase(java.util.Locale.ROOT)
                    + " value; use the REDIS CLI tab");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(json == null ? "" : json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IOException("This is not valid JSON: " + e.getOriginalMessage());
        }
        return decode(type, node);
    }

    private static JsonNode encode(RedisValue value) {
        if (value.type() == RedisType.STRING) {
            return MAPPER.getNodeFactory().textNode(value.text());
        }
        if (value.type() == RedisType.HASH) {
            ObjectNode object = MAPPER.createObjectNode();
            value.entries().forEach(entry -> object.put(entry.first(), entry.second()));
            return object;
        }
        ArrayNode array = MAPPER.createArrayNode();
        if (value.type() == RedisType.ZSET) {
            value.entries().forEach(entry -> {
                ObjectNode item = array.addObject();
                item.put("member", entry.first());
                item.put("score", entry.second());
            });
        } else {
            value.entries().forEach(entry -> array.add(entry.first()));
        }
        return array;
    }

    private static RedisValue decode(RedisType type, JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            throw new IOException("The JSON value is missing");
        }
        if (type == RedisType.STRING) {
            return RedisValue.string(node.isTextual() ? node.asText() : node.toPrettyString(), false);
        }
        List<RedisValue.Entry> entries = new ArrayList<>();
        if (type == RedisType.HASH && node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            fields.forEachRemaining(field -> entries.add(new RedisValue.Entry(field.getKey(), scalar(field.getValue()))));
        } else if (type == RedisType.ZSET && node.isArray()) {
            for (JsonNode item : node) {
                String member = item.path("member").asText("");
                String score = item.path("score").asText("");
                if (member.isEmpty() || score.isEmpty()) {
                    throw new IOException("Each sorted-set item needs member and score");
                }
                entries.add(new RedisValue.Entry(member, score));
            }
        } else if ((type == RedisType.LIST || type == RedisType.SET) && node.isArray()) {
            node.forEach(item -> entries.add(new RedisValue.Entry(scalar(item), "")));
        } else {
            throw new IOException("The JSON value does not match type " + type.name());
        }
        if (entries.isEmpty()) {
            throw new IOException("Redis cannot create an empty collection");
        }
        return RedisValue.collection(type, entries, false);
    }

    private static String scalar(JsonNode node) {
        return node.isTextual() ? node.asText() : node.toString();
    }

    /**
     * True for the types Kubo can write back in full. Streams and module types are read-only here.
     */
    public static boolean rebuildable(RedisType type) {
        return creatable(type);
    }

    private static boolean creatable(RedisType type) {
        return type == RedisType.STRING || type == RedisType.HASH || type == RedisType.LIST
                || type == RedisType.SET || type == RedisType.ZSET;
    }
}
