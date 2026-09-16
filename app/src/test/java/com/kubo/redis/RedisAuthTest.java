package com.kubo.redis;

import com.kubo.model.redis.RedisType;
import com.kubo.model.redis.RedisValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Redis password must reach the server and nothing else: not the command line, not the log, not the disk.
 */
class RedisAuthTest {

    @Test
    @DisplayName("the password travels in the environment, never as an argument another user could read with ps")
    void passwordIsNeverAnArgument() {
        RedisEndpoint endpoint = RedisEndpoint.host();
        endpoint.usePassword("s3cr3t".toCharArray());

        String runnable = endpoint.authenticated("-n 0 GET foo");
        assertTrue(runnable.startsWith("REDISCLI_AUTH="), runnable);
        assertTrue(runnable.contains("redis-cli -n 0 GET foo"), runnable);
        assertFalse(runnable.contains("-a "), "-a would put the password in the process list");
    }

    @Test
    @DisplayName("what the user is shown and what the log keeps never carry the password")
    void theLoggedFormStaysClean() {
        RedisEndpoint endpoint = RedisEndpoint.host();
        endpoint.usePassword("s3cr3t".toCharArray());

        assertEquals("redis-cli -n 0 GET foo", endpoint.plain("-n 0 GET foo"));
        assertFalse(endpoint.plain("-n 0 GET foo").contains("s3cr3t"));
    }

    @Test
    @DisplayName("a container gets the password through docker exec -e, and still not on the command line")
    void dockerCarriesItInTheEnvironmentToo() {
        RedisEndpoint endpoint = RedisEndpoint.docker("abc123", false, null);
        endpoint.usePassword("hunter2".toCharArray());

        String runnable = endpoint.authenticated("-n 0 PING");
        assertTrue(runnable.startsWith("docker exec -e REDISCLI_AUTH="), runnable);
        assertTrue(runnable.contains("redis-cli -n 0 PING"), runnable);
        assertFalse(endpoint.plain("-n 0 PING").contains("hunter2"));
    }

    @Test
    @DisplayName("a command built elsewhere, such as the bounded MONITOR, is authenticated the same way")
    void wholeCommandsAreAuthenticatedToo() {
        RedisEndpoint host = RedisEndpoint.host();
        host.usePassword("pw".toCharArray());
        assertTrue(host.authenticatedShell("timeout 5s redis-cli MONITOR").startsWith("REDISCLI_AUTH="));

        RedisEndpoint container = RedisEndpoint.docker("c1", false, null);
        container.usePassword("pw".toCharArray());
        String monitor = container.authenticatedShell("timeout 5s docker exec 'c1' redis-cli MONITOR");
        assertTrue(monitor.contains("docker exec -e REDISCLI_AUTH="), monitor);
    }

    @Test
    @DisplayName("forgetting wipes it, and the commands go back to carrying nothing")
    void forgettingWipesIt() {
        RedisEndpoint endpoint = RedisEndpoint.host();
        char[] password = "wipe-me".toCharArray();
        endpoint.usePassword(password);
        assertTrue(endpoint.hasPassword());

        endpoint.forget();
        assertFalse(endpoint.hasPassword());
        assertEquals("redis-cli PING", endpoint.authenticated("PING"));
    }

    @Test
    @DisplayName("a password with shell characters in it is quoted, not obeyed")
    void aPasswordCannotBecomeAShellCommand() {
        RedisEndpoint endpoint = RedisEndpoint.host();
        endpoint.usePassword("a'b; rm -rf /".toCharArray());

        String runnable = endpoint.authenticated("PING");
        assertFalse(runnable.contains("; rm -rf / "), runnable);
        assertTrue(runnable.endsWith(" redis-cli PING"), runnable);
    }

    @Test
    @DisplayName("no password means the command is exactly what it always was")
    void withoutAPasswordNothingChanges() {
        assertEquals("redis-cli -n 2 INFO", RedisEndpoint.host().authenticated("-n 2 INFO"));
    }

    // Collection editing

    @Test
    @DisplayName("a collection round-trips through the JSON a person edits")
    void collectionsRoundTrip() throws IOException {
        RedisValue hash = RedisValue.collection(RedisType.HASH,
                List.of(new RedisValue.Entry("role", "admin"), new RedisValue.Entry("user", "420")), false);
        RedisValue back = RedisJson.readEntries(RedisType.HASH, RedisJson.writeEntries(hash));
        assertEquals(hash.entries(), back.entries());

        RedisValue zset = RedisValue.collection(RedisType.ZSET,
                List.of(new RedisValue.Entry("alice", "10"), new RedisValue.Entry("bob", "4")), false);
        assertEquals(zset.entries(), RedisJson.readEntries(RedisType.ZSET, RedisJson.writeEntries(zset)).entries());

        RedisValue list = RedisValue.collection(RedisType.LIST,
                List.of(new RedisValue.Entry("first", ""), new RedisValue.Entry("second", "")), false);
        assertEquals(list.entries(), RedisJson.readEntries(RedisType.LIST, RedisJson.writeEntries(list)).entries());
    }

    @Test
    @DisplayName("bad JSON is refused with something a person can act on, not a stack trace")
    void badJsonIsExplained() {
        IOException broken = assertThrows(IOException.class,
                () -> RedisJson.readEntries(RedisType.HASH, "{ not json"));
        assertTrue(broken.getMessage().toLowerCase().contains("not valid json"), broken.getMessage());

        IOException wrongShape = assertThrows(IOException.class,
                () -> RedisJson.readEntries(RedisType.HASH, "[\"a\",\"b\"]"));
        assertTrue(wrongShape.getMessage().contains("HASH"), wrongShape.getMessage());

        IOException empty = assertThrows(IOException.class, () -> RedisJson.readEntries(RedisType.SET, "[]"));
        assertTrue(empty.getMessage().toLowerCase().contains("empty"), empty.getMessage());
    }

    @Test
    @DisplayName("only the types Redis can be told to rebuild are offered for editing")
    void streamsAndModulesStayReadOnly() {
        assertTrue(RedisJson.rebuildable(RedisType.HASH));
        assertTrue(RedisJson.rebuildable(RedisType.ZSET));
        assertFalse(RedisJson.rebuildable(RedisType.STREAM));
        assertFalse(RedisJson.rebuildable(RedisType.OTHER));

        IOException refused = assertThrows(IOException.class,
                () -> RedisJson.readEntries(RedisType.STREAM, "[]"));
        assertTrue(refused.getMessage().contains("REDIS CLI"), refused.getMessage());
    }

    @Test
    @DisplayName("rewriting a collection keeps its TTL and never leaves the key half-written")
    void rewritingIsOneAtomicScript() {
        RedisValue value = RedisValue.collection(RedisType.SET, List.of(new RedisValue.Entry("a", "")), false);
        String command = RedisCommands.replace(0, "friends", value);

        assertTrue(command.contains("EVAL"), command);
        assertTrue(command.contains("PTTL"), "it has to read the TTL before deleting the key");
        assertTrue(command.contains("PEXPIRE"), "and put it back afterwards");
        assertFalse(command.contains("KEYS "), command);
    }
}
