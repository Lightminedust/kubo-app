package com.kubo.redis;

import com.kubo.files.ShellQuote;
import com.kubo.model.redis.RedisInfo;
import com.kubo.model.redis.RedisKey;
import com.kubo.model.redis.RedisType;
import com.kubo.model.redis.RedisValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code redis-cli} arguments Kubo runs and the reading of their plain (non-interactive) output.
 * These are the arguments after {@code redis-cli}; {@link RedisEndpoint} adds the transport (host or a container).
 *
 * <p>The key browser never runs {@code KEYS *}, which would block Redis's single thread. It scans a page at a time
 * with a small server-side script that also reads each key's type and TTL in one round trip.
 */
public final class RedisCommands {

    /**
     * SCAN one page and, for each key, its type and TTL. Returns a flat list: cursor, then (key, type, ttl) triples.
     * Written without single quotes so the whole script can be single-quoted for the shell.
     */
    static final String SCAN_SCRIPT = "local r=redis.call(\"SCAN\",ARGV[1],\"MATCH\",ARGV[2],\"COUNT\",ARGV[3]) "
            + "local out={r[1]} local keys=r[2] for i=1,#keys do local k=keys[i] out[#out+1]=k "
            + "out[#out+1]=redis.call(\"TYPE\",k).ok out[#out+1]=redis.call(\"TTL\",k) end return out";

    static final String CREATE_SCRIPT = "local k=KEYS[1] if redis.call(\"EXISTS\",k)==1 then "
            + "return redis.error_reply(\"KEY ALREADY EXISTS\") end local t=ARGV[1] local ttl=tonumber(ARGV[2]) "
            + "if t==\"string\" then redis.call(\"SET\",k,ARGV[3]) "
            + "elseif t==\"hash\" then for i=3,#ARGV,2 do redis.call(\"HSET\",k,ARGV[i],ARGV[i+1]) end "
            + "elseif t==\"list\" then for i=3,#ARGV do redis.call(\"RPUSH\",k,ARGV[i]) end "
            + "elseif t==\"set\" then for i=3,#ARGV do redis.call(\"SADD\",k,ARGV[i]) end "
            + "elseif t==\"zset\" then for i=3,#ARGV,2 do redis.call(\"ZADD\",k,ARGV[i],ARGV[i+1]) end "
            + "else return redis.error_reply(\"UNSUPPORTED TYPE\") end "
            + "if ttl and ttl>0 then redis.call(\"EXPIRE\",k,ttl) end return \"OK\"";

    static final String CLONE_SCRIPT = "if redis.call(\"EXISTS\",KEYS[1])==0 then "
            + "return redis.error_reply(\"SOURCE KEY DOES NOT EXIST\") end "
            + "if redis.call(\"EXISTS\",KEYS[2])==1 then return redis.error_reply(\"DESTINATION KEY ALREADY EXISTS\") end "
            + "return redis.call(\"COPY\",KEYS[1],KEYS[2])";

    static final String REPLACE_SCRIPT = "local k=KEYS[1] if redis.call(\"EXISTS\",k)==0 then "
            + "return redis.error_reply(\"KEY DOES NOT EXIST\") end local pttl=redis.call(\"PTTL\",k) "
            + "local t=ARGV[1] redis.call(\"DEL\",k) "
            + "if t==\"string\" then redis.call(\"SET\",k,ARGV[2]) "
            + "elseif t==\"hash\" then for i=2,#ARGV,2 do redis.call(\"HSET\",k,ARGV[i],ARGV[i+1]) end "
            + "elseif t==\"list\" then for i=2,#ARGV do redis.call(\"RPUSH\",k,ARGV[i]) end "
            + "elseif t==\"set\" then for i=2,#ARGV do redis.call(\"SADD\",k,ARGV[i]) end "
            + "elseif t==\"zset\" then for i=2,#ARGV,2 do redis.call(\"ZADD\",k,ARGV[i],ARGV[i+1]) end "
            + "else return redis.error_reply(\"UNSUPPORTED TYPE\") end "
            + "if pttl>0 then redis.call(\"PEXPIRE\",k,pttl) end return \"OK\"";

    static final String MEMORY_SCRIPT = "local out={} for i=1,#ARGV do out[#out+1]=ARGV[i] "
            + "out[#out+1]=redis.call(\"MEMORY\",\"USAGE\",ARGV[i]) or 0 end return out";

    /**
     * A page of keys and where the scan continues; cursor 0 means the scan is finished.
     */
    public record ScanPage(long cursor, List<RedisKey> keys) {
        public ScanPage {
            keys = List.copyOf(keys);
        }

        public boolean done() {
            return cursor == 0;
        }
    }

    private RedisCommands() {
    }

    public static final String PING = "ping";

    public static String info() {
        return "info";
    }

    public static String scan(int db, long cursor, String pattern, int count) {
        return "-n " + db + " EVAL " + ShellQuote.quote(SCAN_SCRIPT) + " 0 " + cursor + " "
                + ShellQuote.quote(pattern == null || pattern.isBlank() ? "*" : pattern) + " " + count;
    }

    /**
     * The command that reads a key's value; collections are capped at {@code limit} entries.
     */
    public static String value(int db, String key, RedisType type, int limit) {
        String k = ShellQuote.quote(key);
        return "-n " + db + " " + switch (type) {
            case STRING -> "GET " + k;
            case HASH -> "HGETALL " + k;
            case LIST -> "LRANGE " + k + " 0 " + (limit - 1);
            case SET -> "SMEMBERS " + k;
            case ZSET -> "ZRANGE " + k + " 0 " + (limit - 1) + " WITHSCORES";
            case STREAM -> "XLEN " + k;
            case NONE, OTHER -> "TYPE " + k;
        };
    }

    /**
     * The command that reads one key, using what the server calls its type. A value a Redis module owns has no
     * generic read, but RedisJSON answers {@code JSON.GET}, which is worth asking for rather than giving up.
     */
    public static String value(int db, RedisKey key, int limit) {
        if (key.isJson()) {
            return "-n " + db + " JSON.GET " + ShellQuote.quote(key.name());
        }
        return value(db, key.name(), key.type(), limit);
    }

    public static String set(int db, String key, String value) {
        return "-n " + db + " SET " + ShellQuote.quote(key) + " " + ShellQuote.quote(value);
    }

    public static String expire(int db, String key, long seconds) {
        return "-n " + db + " EXPIRE " + ShellQuote.quote(key) + " " + seconds;
    }

    public static String persist(int db, String key) {
        return "-n " + db + " PERSIST " + ShellQuote.quote(key);
    }

    /**
     * Deletes a key asynchronously ({@code UNLINK}), which does not block on large values.
     */
    public static String delete(int db, String key) {
        return "-n " + db + " UNLINK " + ShellQuote.quote(key);
    }

    public static String create(int db, String key, RedisValue value, long ttlSeconds) {
        if (value == null || value.failed() || !creatable(value.type())) {
            throw new IllegalArgumentException("A supported Redis value is required");
        }
        List<String> values = new ArrayList<>();
        values.add(value.type().name().toLowerCase(java.util.Locale.ROOT));
        values.add(Long.toString(ttlSeconds));
        if (value.type() == RedisType.STRING) {
            values.add(value.text());
        } else {
            if (value.entries().isEmpty()) {
                throw new IllegalArgumentException("Redis cannot store an empty collection");
            }
            for (RedisValue.Entry entry : value.entries()) {
                if (value.type().isPaired()) {
                    values.add(value.type() == RedisType.ZSET ? entry.second() : entry.first());
                    values.add(value.type() == RedisType.ZSET ? entry.first() : entry.second());
                } else {
                    values.add(entry.first());
                }
            }
        }
        return "-n " + db + " EVAL " + ShellQuote.quote(CREATE_SCRIPT) + " 1 " + ShellQuote.quote(key)
                + " " + quoteAll(values);
    }

    public static String cloneKey(int db, String source, String destination) {
        return "-n " + db + " EVAL " + ShellQuote.quote(CLONE_SCRIPT) + " 2 "
                + ShellQuote.quote(source) + " " + ShellQuote.quote(destination);
    }

    public static String replace(int db, String key, RedisValue value) {
        if (value == null || value.failed() || !creatable(value.type())) {
            throw new IllegalArgumentException("A supported Redis value is required");
        }
        List<String> values = valueArguments(value);
        return "-n " + db + " EVAL " + ShellQuote.quote(REPLACE_SCRIPT) + " 1 " + ShellQuote.quote(key)
                + " " + quoteAll(values);
    }

    public static String rename(int db, String source, String destination) {
        return "-n " + db + " RENAME " + ShellQuote.quote(source) + " " + ShellQuote.quote(destination);
    }

    public static String flushDb(int db) {
        return "-n " + db + " FLUSHDB ASYNC";
    }

    public static String flushAll() {
        return "FLUSHALL ASYNC";
    }

    public static String slowlog(int limit) {
        return "SLOWLOG GET " + Math.max(1, Math.min(100, limit));
    }

    public static String memoryUsage(int db, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("At least one key is required");
        }
        return "-n " + db + " EVAL " + ShellQuote.quote(MEMORY_SCRIPT) + " 0 " + quoteAll(keys);
    }

    /**
     * Turns a Redis command line into individually shell-quoted arguments. Shell operators in key names stay data.
     */
    public static String raw(int db, String commandLine) {
        List<String> tokens = tokenize(commandLine);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Redis command is empty");
        }
        return "-n " + db + " " + quoteAll(tokens);
    }

    public static Map<String, Long> parseMemoryUsage(String output) {
        List<String> lines = output.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        Map<String, Long> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < lines.size(); i += 2) {
            result.put(lines.get(i), parseLong(lines.get(i + 1)));
        }
        return Map.copyOf(result);
    }

    private static boolean creatable(RedisType type) {
        return type == RedisType.STRING || type == RedisType.HASH || type == RedisType.LIST
                || type == RedisType.SET || type == RedisType.ZSET;
    }

    private static List<String> valueArguments(RedisValue value) {
        List<String> values = new ArrayList<>();
        values.add(value.type().name().toLowerCase(java.util.Locale.ROOT));
        if (value.type() == RedisType.STRING) {
            values.add(value.text());
        } else {
            if (value.entries().isEmpty()) throw new IllegalArgumentException("Redis cannot store an empty collection");
            for (RedisValue.Entry entry : value.entries()) {
                if (value.type().isPaired()) {
                    values.add(value.type() == RedisType.ZSET ? entry.second() : entry.first());
                    values.add(value.type() == RedisType.ZSET ? entry.first() : entry.second());
                } else {
                    values.add(entry.first());
                }
            }
        }
        return values;
    }

    private static String quoteAll(List<String> values) {
        return values.stream().map(ShellQuote::quote).collect(java.util.stream.Collectors.joining(" "));
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false;
        boolean doubled = false;
        boolean escaping = false;
        boolean started = false;
        for (int i = 0; i < (text == null ? 0 : text.length()); i++) {
            char c = text.charAt(i);
            if (escaping) {
                current.append(c);
                escaping = false;
                started = true;
            } else if (c == '\\' && !single) {
                escaping = true;
                started = true;
            } else if (c == '\'' && !doubled) {
                single = !single;
                started = true;
            } else if (c == '"' && !single) {
                doubled = !doubled;
                started = true;
            } else if (Character.isWhitespace(c) && !single && !doubled) {
                if (started) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
            } else {
                current.append(c);
                started = true;
            }
        }
        if (escaping || single || doubled) {
            throw new IllegalArgumentException("Unclosed quote or escape in Redis command");
        }
        if (started) {
            tokens.add(current.toString());
        }
        return List.copyOf(tokens);
    }

    // Parsing

    public static boolean isPong(String output) {
        return output.strip().equalsIgnoreCase("PONG");
    }

    public static RedisInfo parseInfo(String output) {
        String version = "";
        long uptime = 0;
        String used = "—";
        String peak = "—";
        int clients = 0;
        long ops = 0;
        List<RedisInfo.Db> keyspace = new ArrayList<>();
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            int colon = line.indexOf(':');
            if (line.startsWith("#") || colon < 0) {
                continue;
            }
            String key = line.substring(0, colon);
            String value = line.substring(colon + 1).strip();
            switch (key) {
                case "redis_version" -> version = value;
                case "uptime_in_seconds" -> uptime = parseLong(value);
                case "used_memory_human" -> used = value;
                case "used_memory_peak_human" -> peak = value;
                case "connected_clients" -> clients = (int) parseLong(value);
                case "instantaneous_ops_per_sec" -> ops = parseLong(value);
                default -> {
                    if (key.startsWith("db") && value.contains("keys=")) {
                        parseDb(key, value).ifPresent(keyspace::add);
                    }
                }
            }
        }
        return new RedisInfo(version, uptime, used, peak, clients, ops, keyspace);
    }

    public static ScanPage parseScan(String output) {
        List<String> lines = output.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        if (lines.isEmpty()) {
            return new ScanPage(0, List.of());
        }
        long cursor = parseLong(lines.get(0));
        List<RedisKey> keys = new ArrayList<>();
        for (int i = 1; i + 3 <= lines.size(); i += 3) {
            String typeName = lines.get(i + 1);
            keys.add(new RedisKey(lines.get(i), RedisType.parse(typeName), parseLong(lines.get(i + 2)), typeName));
        }
        return new ScanPage(cursor, keys);
    }

    public static RedisValue parseValue(RedisType type, String output, int limit) {
        List<String> lines = new ArrayList<>(output.lines().toList());
        // redis-cli adds a trailing blank line.
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        switch (type) {
            case STRING -> {
                String text = output.endsWith("\n") ? output.substring(0, output.length() - 1) : output;
                return RedisValue.string(text, false);
            }
            case STREAM -> {
                String length = lines.isEmpty() ? "0" : lines.get(0);
                return RedisValue.collection(RedisType.STREAM,
                        List.of(new RedisValue.Entry("ENTRIES", length)), false);
            }
            case HASH, ZSET -> {
                List<RedisValue.Entry> entries = new ArrayList<>();
                for (int i = 0; i + 1 < lines.size() && entries.size() < limit; i += 2) {
                    entries.add(new RedisValue.Entry(lines.get(i), lines.get(i + 1)));
                }
                return RedisValue.collection(type, entries, entries.size() >= limit);
            }
            case LIST, SET -> {
                List<RedisValue.Entry> entries = new ArrayList<>();
                for (int i = 0; i < lines.size() && entries.size() < limit; i++) {
                    entries.add(new RedisValue.Entry(lines.get(i), ""));
                }
                return RedisValue.collection(type, entries, entries.size() >= limit);
            }
            default -> {
                return RedisValue.failed(type, "Unsupported type");
            }
        }
    }

    /**
     * Reads a value knowing what the server calls its type. RedisJSON answers plain JSON text; any other type a
     * module owns is named honestly instead of being dismissed as unsupported.
     */
    public static RedisValue parseValue(RedisKey key, String output, int limit) {
        if (key.isJson()) {
            String text = output.endsWith("\n") ? output.substring(0, output.length() - 1) : output;
            return text.isBlank()
                    ? RedisValue.failed(key.type(), "This RedisJSON key returned nothing.")
                    : RedisValue.string(text, false);
        }
        if (key.type() == RedisType.OTHER) {
            return RedisValue.failed(key.type(), "This key holds a \"" + key.typeName()
                    + "\" value, a type a Redis module owns. Kubo can rename, re-time, export and delete it, but "
                    + "it cannot show its content: reading it needs that module's own commands, which you can run "
                    + "from the REDIS CLI tab below.");
        }
        return parseValue(key.type(), output, limit);
    }

    private static java.util.Optional<RedisInfo.Db> parseDb(String key, String value) {
        try {
            int index = Integer.parseInt(key.substring(2));
            long keys = 0;
            long expires = 0;
            for (String pair : value.split(",")) {
                String[] kv = pair.split("=");
                if (kv.length == 2) {
                    if (kv[0].strip().equals("keys")) {
                        keys = parseLong(kv[1]);
                    } else if (kv[0].strip().equals("expires")) {
                        expires = parseLong(kv[1]);
                    }
                }
            }
            return java.util.Optional.of(new RedisInfo.Db(index, keys, expires));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
