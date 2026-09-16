package com.kubo.redis;

import com.kubo.model.redis.RedisInfo;
import com.kubo.model.redis.RedisKey;
import com.kubo.model.redis.RedisType;
import com.kubo.model.redis.RedisValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisCommandsTest {

    @Test
    void pingIsRecognised() {
        assertTrue(RedisCommands.isPong("PONG\n"));
        assertFalse(RedisCommands.isPong("NOAUTH Authentication required."));
    }

    @Test
    void scanNeverUsesKeysAndCarriesTheScriptSafely() {
        String command = RedisCommands.scan(0, 42, "user:*", 100);

        assertTrue(command.startsWith("-n 0 EVAL "));
        assertTrue(command.contains(" 0 42 'user:*' 100"));
        // The scan uses SCAN, never the blocking KEYS command.
        assertTrue(RedisCommands.SCAN_SCRIPT.contains("\"SCAN\""));
        assertFalse(RedisCommands.SCAN_SCRIPT.contains("\"KEYS\""));
        assertFalse(RedisCommands.SCAN_SCRIPT.contains("'"));
    }

    @Test
    void everyKeyIsQuoted() {
        String hostile = "a'; FLUSHALL; '";
        for (String command : List.of(RedisCommands.value(0, hostile, RedisType.STRING, 100),
                RedisCommands.set(1, hostile, "v"), RedisCommands.expire(0, hostile, 60),
                RedisCommands.persist(0, hostile), RedisCommands.delete(0, hostile),
                RedisCommands.rename(0, hostile, "new"), RedisCommands.cloneKey(0, hostile, "copy"))) {
            assertTrue(command.contains("'a'\\''; FLUSHALL; '\\'''"), command);
        }
    }

    @Test
    void rawConsoleQuotesEveryArgumentAndRejectsBrokenQuotes() {
        String command = RedisCommands.raw(4, "HSET myhash field1 \"hello world\" ;FLUSHALL");
        assertEquals("-n 4 'HSET' 'myhash' 'field1' 'hello world' ';FLUSHALL'", command);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> RedisCommands.raw(0, "SET broken 'value"));
    }

    @Test
    void typedCreationUsesOneAtomicScript() {
        RedisValue hash = RedisValue.collection(RedisType.HASH,
                List.of(new RedisValue.Entry("name", "Kubo"), new RedisValue.Entry("role", "admin")), false);
        String command = RedisCommands.create(2, "user:1", hash, 3600);
        assertTrue(command.startsWith("-n 2 EVAL "));
        assertTrue(command.endsWith(" 1 'user:1' 'hash' '3600' 'name' 'Kubo' 'role' 'admin'"));
        assertTrue(RedisCommands.CREATE_SCRIPT.contains("KEY ALREADY EXISTS"));
        String replace = RedisCommands.replace(2, "user:1", hash);
        assertTrue(replace.startsWith("-n 2 EVAL "));
        assertTrue(RedisCommands.REPLACE_SCRIPT.contains("PEXPIRE"));
    }

    @Test
    void flushIsAsyncAndMemoryUsageIsParsed() {
        assertEquals("-n 3 FLUSHDB ASYNC", RedisCommands.flushDb(3));
        assertEquals("FLUSHALL ASYNC", RedisCommands.flushAll());
        assertEquals(java.util.Map.of("alpha", 64L, "beta", 128L),
                RedisCommands.parseMemoryUsage("alpha\n64\nbeta\n128\n"));
    }

    @Test
    void deleteIsAsynchronousAndValueReadsMatchTheType() {
        assertTrue(RedisCommands.delete(0, "k").contains("UNLINK"));
        assertTrue(RedisCommands.value(0, "k", RedisType.STRING, 100).endsWith("GET 'k'"));
        assertTrue(RedisCommands.value(0, "k", RedisType.HASH, 100).endsWith("HGETALL 'k'"));
        assertTrue(RedisCommands.value(0, "k", RedisType.LIST, 50).endsWith("LRANGE 'k' 0 49"));
        assertTrue(RedisCommands.value(0, "k", RedisType.ZSET, 50).endsWith("ZRANGE 'k' 0 49 WITHSCORES"));
        assertTrue(RedisCommands.value(3, "k", RedisType.SET, 100).startsWith("-n 3 SMEMBERS"));
    }

    @Test
    void infoReadsTheDashboardFigures() {
        String output = """
                # Server
                redis_version:7.2.4
                uptime_in_seconds:86400
                # Clients
                connected_clients:12
                # Memory
                used_memory_human:32.7M
                used_memory_peak_human:48.1M
                # Stats
                instantaneous_ops_per_sec:1450
                # Keyspace
                db0:keys=1420,expires=12,avg_ttl=0
                db1:keys=3,expires=0,avg_ttl=0
                """;

        RedisInfo info = RedisCommands.parseInfo(output);

        assertEquals("7.2.4", info.version());
        assertEquals(86400, info.uptimeSeconds());
        assertEquals("32.7M", info.usedMemory());
        assertEquals("48.1M", info.peakMemory());
        assertEquals(12, info.connectedClients());
        assertEquals(1450, info.opsPerSec());
        assertEquals(2, info.keyspace().size());
        assertEquals(1420, info.keyspace().get(0).keys());
        assertEquals(1423, info.totalKeys());
    }

    @Test
    void scanReadsCursorKeysTypesAndTtls() {
        String output = "48\nuser:1\nhash\n-1\nsession:9\nstring\n120\ncart:3\nset\n-1\n";

        RedisCommands.ScanPage page = RedisCommands.parseScan(output);

        assertEquals(48, page.cursor());
        assertFalse(page.done());
        assertEquals(3, page.keys().size());
        RedisKey session = page.keys().get(1);
        assertEquals("session:9", session.name());
        assertEquals(RedisType.STRING, session.type());
        assertEquals(120, session.ttlSeconds());
        assertTrue(page.keys().get(0).isPersistent());
    }

    @Test
    void anEmptyScanIsDone() {
        RedisCommands.ScanPage page = RedisCommands.parseScan("0\n");
        assertTrue(page.done());
        assertTrue(page.keys().isEmpty());
    }

    @Test
    void stringValueKeepsItsExactText() {
        RedisValue value = RedisCommands.parseValue(RedisType.STRING, "hello\nworld\n", 100);

        assertEquals("hello\nworld", value.text());
        assertEquals(1, value.size());
    }

    @Test
    void hashAndZsetValuesArePaired() {
        RedisValue hash = RedisCommands.parseValue(RedisType.HASH, "name\nGemmie\nrole\nadmin\n", 100);
        assertEquals(2, hash.entries().size());
        assertEquals("name", hash.entries().get(0).first());
        assertEquals("Gemmie", hash.entries().get(0).second());

        RedisValue zset = RedisCommands.parseValue(RedisType.ZSET, "alice\n10\nbob\n25\n", 100);
        assertEquals("25", zset.entries().get(1).second());
    }

    @Test
    void listValuesAreCappedAndMarkedTruncated() {
        String output = "a\nb\nc\nd\n";
        RedisValue capped = RedisCommands.parseValue(RedisType.LIST, output, 2);

        assertEquals(2, capped.entries().size());
        assertTrue(capped.truncated());
    }

    @Test
    void streamShowsItsLength() {
        RedisValue stream = RedisCommands.parseValue(RedisType.STREAM, "17\n", 100);
        assertEquals("17", stream.entries().get(0).second());
    }

    @Test
    void scanKeepsTheTypeTheServerReported() {
        RedisCommands.ScanPage page = RedisCommands.parseScan("0\nprod:1\nReJSON-RL\n-1\nplain:1\nstring\n-1\n");

        RedisKey json = page.keys().get(0);
        assertEquals(RedisType.OTHER, json.type());
        assertEquals("ReJSON-RL", json.typeName());
        assertEquals("REJSON-RL", json.label());
        assertTrue(json.isJson());

        RedisKey plain = page.keys().get(1);
        assertEquals("STRING", plain.label());
        assertFalse(plain.isJson());
    }

    @Test
    void aRedisJsonKeyIsReadAsJsonText() {
        RedisKey key = new RedisKey("prod:1", RedisType.OTHER, RedisKey.PERSISTENT, "ReJSON-RL");

        assertEquals("-n 0 JSON.GET 'prod:1'", RedisCommands.value(0, key, 100));

        RedisValue value = RedisCommands.parseValue(key, "{\"a\":1}\n", 100);
        assertFalse(value.failed());
        assertEquals("{\"a\":1}", value.text());
    }

    @Test
    void anUnknownModuleTypeIsNamedInsteadOfDismissed() {
        RedisKey key = new RedisKey("ts:1", RedisType.OTHER, RedisKey.PERSISTENT, "TSDB-TYPE");

        RedisValue value = RedisCommands.parseValue(key, "TSDB-TYPE\n", 100);

        assertTrue(value.failed());
        assertTrue(value.error().contains("TSDB-TYPE"), value.error());
        assertTrue(value.error().contains("REDIS CLI"), value.error());
    }

    @Test
    void aKeyWithoutAReportedTypeFallsBackToItsEnum() {
        RedisKey key = new RedisKey("k", RedisType.HASH, 60);

        assertEquals("hash", key.typeName());
        assertEquals("HASH", key.label());
        assertEquals("-n 0 HGETALL 'k'", RedisCommands.value(0, key, 100));
    }
}
