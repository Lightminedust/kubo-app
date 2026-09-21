package com.kubo.redis;

import com.kubo.model.redis.RedisType;
import com.kubo.model.redis.RedisValue;
import com.kubo.privilege.Privilege;
import com.kubo.privilege.RootAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Redis password must reach the server and nothing else: not the command line, not the log, not the disk.
 *
 * <p>The command line is the dangerous one. Whatever is written into it is readable by every user on the server
 * through {@code ps} — as the argument list of the shell, of {@code docker}, and of {@code sudo}, which also writes
 * it to the auth log. So the property these tests hold to is simple: the password is never in the line, in any of
 * the ways a Redis command can be built, and it arrives on standard input instead.
 */
class RedisAuthTest {

    private static final String SECRET = "s3cr3t-Pa55";

    private static byte[] line(String text) {
        return (text + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static Privilege sudo(RootAccess access, String password) {
        Privilege privilege = new Privilege(access);
        if (password != null) {
            privilege.setPassword(password.toCharArray());
        }
        return privilege;
    }

    @Test
    @DisplayName("on the host, the password is nowhere in the command line and arrives on standard input")
    void hostKeepsItOutOfTheLine() {
        RedisEndpoint endpoint = RedisEndpoint.host();
        endpoint.usePassword(SECRET.toCharArray());

        Privilege.Command command = endpoint.command("-n 0 GET foo");

        assertFalse(command.command().contains(SECRET), command.command());
        assertTrue(command.command().startsWith("IFS= read -r REDISCLI_AUTH && export REDISCLI_AUTH && "),
                command.command());
        assertTrue(command.command().endsWith("redis-cli -n 0 GET foo"), command.command());
        assertFalse(command.command().contains(" -a "), "-a would put the password in the process list");
        assertArrayEquals(line(SECRET), command.input());
    }

    @Test
    @DisplayName("a container gets it through docker exec -e with no value, so docker copies it, and never sees it")
    void dockerCopiesItFromItsOwnEnvironment() {
        RedisEndpoint endpoint = RedisEndpoint.docker("abc123", false, null);
        endpoint.usePassword(SECRET.toCharArray());

        Privilege.Command command = endpoint.command("-n 0 PING");

        assertFalse(command.command().contains(SECRET), command.command());
        // "-e NAME" with no "=value" is what makes docker take the value from its own environment.
        assertTrue(command.command().contains("docker exec -e REDISCLI_AUTH 'abc123' redis-cli -n 0 PING"),
                command.command());
        assertFalse(command.command().contains("REDISCLI_AUTH="), command.command());
        assertArrayEquals(line(SECRET), command.input());
    }

    @Test
    @DisplayName("through sudo, sudo is forced to eat exactly its own line, and the Redis password follows it")
    void sudoAndRedisShareOneInputInOrder() {
        RedisEndpoint endpoint = RedisEndpoint.docker("c1", true, sudo(RootAccess.SUDO_PASSWORD, "sudo-pw"));
        endpoint.usePassword(SECRET.toCharArray());

        Privilege.Command command = endpoint.command("-n 0 PING");

        // sudo writes its whole command line to the auth log: the password must not be in it.
        assertFalse(command.command().contains(SECRET), command.command());
        // -k: without it a cached sudo ticket would read no line, and the Redis password would be taken as the
        // sudo one.
        assertTrue(command.command().startsWith("sudo -k -S -p ''"), command.command());
        assertArrayEquals(line("sudo-pw\n" + SECRET), command.input());
    }

    @Test
    @DisplayName("with passwordless sudo, nothing but the Redis password is written to standard input")
    void passwordlessSudoLeavesTheInputToRedis() {
        RedisEndpoint endpoint = RedisEndpoint.docker("c1", true, sudo(RootAccess.SUDO_NOPASSWD, null));
        endpoint.usePassword(SECRET.toCharArray());

        Privilege.Command command = endpoint.command("-n 0 PING");

        assertTrue(command.command().startsWith("sudo -n"), command.command());
        assertFalse(command.command().contains(SECRET), command.command());
        assertArrayEquals(line(SECRET), command.input());
    }

    @Test
    @DisplayName("the bounded MONITOR runs redis-cli under timeout, not a variable assignment timeout cannot run")
    void monitorRunsUnderTimeout() {
        RedisEndpoint host = RedisEndpoint.host();
        host.usePassword(SECRET.toCharArray());

        String monitor = host.command("timeout --signal=INT 5s ", "-n 0 MONITOR").command();

        assertFalse(monitor.contains(SECRET), monitor);
        // It used to read "timeout 5s REDISCLI_AUTH=... redis-cli", which timeout tries to run as a program.
        assertTrue(monitor.endsWith("&& timeout --signal=INT 5s redis-cli -n 0 MONITOR"), monitor);

        RedisEndpoint container = RedisEndpoint.docker("c1", false, null);
        container.usePassword(SECRET.toCharArray());
        String inContainer = container.command("timeout --signal=INT 5s ", "-n 0 MONITOR").command();
        assertFalse(inContainer.contains(SECRET), inContainer);
        assertTrue(inContainer.endsWith("timeout --signal=INT 5s docker exec -e REDISCLI_AUTH 'c1' redis-cli -n 0 MONITOR"),
                inContainer);
    }

    @Test
    @DisplayName("the command line is the same whatever the password is, so no password can change what runs")
    void thePasswordCannotShapeTheCommand() {
        RedisEndpoint plainOne = RedisEndpoint.host();
        plainOne.usePassword("simple".toCharArray());
        RedisEndpoint hostile = RedisEndpoint.host();
        hostile.usePassword("a'b; rm -rf / $(reboot) `id`".toCharArray());

        assertEquals(plainOne.command("PING").command(), hostile.command("PING").command());
    }

    @Test
    @DisplayName("what the user is shown and what the log keeps never carry the password")
    void theLoggedFormStaysClean() {
        RedisEndpoint endpoint = RedisEndpoint.host();
        endpoint.usePassword(SECRET.toCharArray());

        assertEquals("redis-cli -n 0 GET foo", endpoint.plain("-n 0 GET foo"));
        assertFalse(endpoint.plain("-n 0 GET foo").contains(SECRET));
    }

    @Test
    @DisplayName("forgetting wipes it, and the commands go back to carrying nothing")
    void forgettingWipesIt() {
        RedisEndpoint endpoint = RedisEndpoint.host();
        endpoint.usePassword("wipe-me".toCharArray());
        assertTrue(endpoint.hasPassword());

        endpoint.forget();

        assertFalse(endpoint.hasPassword());
        Privilege.Command command = endpoint.command("PING");
        assertEquals("redis-cli PING", command.command());
        assertNull(command.input());
    }

    @Test
    @DisplayName("no password means the command is exactly what it always was, with nothing on standard input")
    void withoutAPasswordNothingChanges() {
        Privilege.Command host = RedisEndpoint.host().command("-n 2 INFO");
        assertEquals("redis-cli -n 2 INFO", host.command());
        assertNull(host.input());

        Privilege.Command container = RedisEndpoint.docker("c1", false, null).command("PING");
        assertEquals("docker exec 'c1' redis-cli PING", container.command());
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
