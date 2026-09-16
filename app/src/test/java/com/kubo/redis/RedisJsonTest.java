package com.kubo.redis;

import com.kubo.model.redis.RedisKey;
import com.kubo.model.redis.RedisType;
import com.kubo.model.redis.RedisValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisJsonTest {

    @TempDir Path temporary;

    @Test
    void hashRoundTripsWithNameDatabaseAndTtl() throws Exception {
        Path file = temporary.resolve("user.json");
        RedisKey key = new RedisKey("user:42", RedisType.HASH, 900);
        RedisValue value = RedisValue.collection(RedisType.HASH,
                List.of(new RedisValue.Entry("name", "Kubo"), new RedisValue.Entry("admin", "true")), false);

        RedisJson.write(file, 3, key, value);
        RedisJson.Document read = RedisJson.read(file);

        assertEquals(3, read.database());
        assertEquals(key, read.key());
        assertEquals(value, read.value());
        assertTrue(java.nio.file.Files.readString(file).contains(RedisJson.FORMAT));
    }

    @Test
    void zsetRoundTripsAsMemberScoreObjects() throws Exception {
        Path file = temporary.resolve("ranking.json");
        RedisValue value = RedisValue.collection(RedisType.ZSET,
                List.of(new RedisValue.Entry("alice", "12.5")), false);
        RedisJson.write(file, 0, new RedisKey("ranking", RedisType.ZSET, -1), value);

        assertEquals(value, RedisJson.read(file).value());
    }
}
