package com.kubo.redis;

import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.command.CommandService.Origin;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.docker.DockerService;
import com.kubo.model.docker.DockerContainer;
import com.kubo.model.redis.RedisInfo;
import com.kubo.model.redis.RedisKey;
import com.kubo.model.redis.RedisStatus;
import com.kubo.model.redis.RedisStatus.Availability;
import com.kubo.model.redis.RedisStatus.Location;
import com.kubo.model.redis.RedisType;
import com.kubo.model.redis.RedisValue;
import com.kubo.privilege.Privilege;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Talks to Redis over the connection, discovering the host process and every running container built from a Redis
 * image. Each discovered process gets its own service endpoint. Every command goes into the session history.
 * Methods block: call them off the UI thread.
 */
public final class RedisService {

    /** One independently addressable Redis process discovered on the host or in a container. */
    public record Detected(RedisService service, RedisStatus status) {
    }

    /**
     * Collections are read at most this many entries at a time.
     */
    public static final int PAGE = 200;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final SshConnection connection;
    private final CommandService commands;
    private final Privilege privilege;
    private final DockerService docker;
    private RedisEndpoint endpoint;

    public RedisService(SshConnection connection, CommandService commands, Privilege privilege, DockerService docker) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.privilege = privilege;
        this.docker = docker;
    }

    private RedisService(SshConnection connection, CommandService commands, Privilege privilege, DockerService docker,
                         RedisEndpoint endpoint) {
        this(connection, commands, privilege, docker);
        this.endpoint = endpoint;
    }

    /**
     * Gives this Redis the password its {@code requirepass} asks for, and checks it at once.
     *
     * <p>The password is kept in memory for the session only; it is never written to disk and never appears in a
     * command Kubo shows or logs.
     *
     * @return true when Redis accepted it
     */
    public boolean authenticate(char[] password) {
        endpoint.usePassword(password);
        CommandEntry ping = run(RedisCommands.PING);
        if (RedisCommands.isPong(ping.output())) {
            return true;
        }
        endpoint.forget();
        return false;
    }

    public boolean needsPassword() {
        return !endpoint.hasPassword();
    }

    /**
     * Forgets the Redis password. Called when the screen closes.
     */
    public void forgetPassword() {
        endpoint.forget();
    }

    /**
     * Finds Redis. Tries the host first, then a running Redis container.
     */
    public RedisStatus detect() {
        List<Detected> detected = detectAll();
        Optional<Detected> usable = detected.stream().filter(item -> item.status().usable()).findFirst();
        if (usable.isPresent()) {
            endpoint = usable.get().service().endpoint;
            return usable.get().status();
        }
        // Nothing usable: if one of them only wants a password, point this service at it so authenticate() talks
        // to the right Redis rather than to the default host one.
        Optional<Detected> locked = detected.stream()
                .filter(item -> item.status().availability() == Availability.NEEDS_AUTH).findFirst();
        if (locked.isPresent()) {
            endpoint = locked.get().service().endpoint;
            return locked.get().status();
        }
        return new RedisStatus(Availability.NOT_FOUND, Location.HOST, "", "");
    }

    /**
     * Finds every reachable Redis process instead of stopping at the first Redis container.
     */
    public List<Detected> detectAll() {
        List<Detected> found = new ArrayList<>();
        CommandEntry host = record(connection.exec("redis-cli " + RedisCommands.PING));
        if (RedisCommands.isPong(host.output())) {
            RedisEndpoint target = RedisEndpoint.host();
            found.add(new Detected(at(target), new RedisStatus(Availability.USABLE, Location.HOST, "host", "")));
        } else if (host.output().toUpperCase(Locale.ROOT).contains("NOAUTH")
                || host.error().toUpperCase(Locale.ROOT).contains("NOAUTH")) {
            found.add(new Detected(at(RedisEndpoint.host()),
                    new RedisStatus(Availability.NEEDS_AUTH, Location.HOST, "host", "")));
        }

        if (docker != null) {
            List<DockerContainer> containers = docker.containers().stream()
                    .filter(container -> container.state().isRunning()
                            && container.image().toLowerCase(Locale.ROOT).contains("redis"))
                    .toList();
            for (DockerContainer container : containers) {
                RedisEndpoint candidate = RedisEndpoint.docker(container.id(), docker.needsRoot(), privilege);
                Availability availability = Availability.USABLE;
                // Only test now if no sudo password is needed yet; otherwise trust it and let the first real call ask.
                if (!(docker.needsRoot() && privilege != null && privilege.needsPassword())) {
                    CommandEntry ping = run(candidate, RedisCommands.PING);
                    if (!RedisCommands.isPong(ping.output())) {
                        String answer = (ping.output() + "\n" + ping.error()).toUpperCase(Locale.ROOT);
                        availability = answer.contains("NOAUTH") ? Availability.NEEDS_AUTH : Availability.NOT_FOUND;
                    }
                }
                if (availability != Availability.NOT_FOUND) {
                    found.add(new Detected(at(candidate),
                            new RedisStatus(availability, Location.DOCKER, container.name(), "")));
                }
            }
        }
        if (found.isEmpty()) {
            found.add(new Detected(this, new RedisStatus(Availability.NOT_FOUND, Location.HOST, "", "")));
        }
        return List.copyOf(found);
    }

    private RedisService at(RedisEndpoint target) {
        return new RedisService(connection, commands, privilege, docker, target);
    }

    public boolean ready() {
        return endpoint != null;
    }

    /**
     * True when Redis is reached through a container that only root can talk to, so a sudo password may be needed.
     */
    public boolean needsRoot() {
        return endpoint != null && endpoint.isDocker() && docker != null && docker.needsRoot();
    }

    /**
     * Checks a sudo password with a harmless command, without recording it.
     */
    public CommandEntry verify(Privilege.Command command) {
        return connection.exec(command.command(), command.input(), TIMEOUT);
    }

    public RedisInfo info() {
        return RedisCommands.parseInfo(run(RedisCommands.info()).output());
    }

    public RedisCommands.ScanPage scan(int db, long cursor, String pattern, int count) {
        return RedisCommands.parseScan(run(RedisCommands.scan(db, cursor, pattern, count)).output());
    }

    public RedisValue value(int db, String key, RedisType type) {
        CommandEntry entry = run(RedisCommands.value(db, key, type, PAGE));
        if (!entry.success()) {
            return RedisValue.failed(type, entry.error().isBlank() ? entry.output() : entry.error());
        }
        return RedisCommands.parseValue(type, entry.output(), PAGE);
    }

    /**
     * Reads one key using the type the server reported for it, so module types such as RedisJSON are handled.
     */
    public RedisValue value(int db, RedisKey key) {
        CommandEntry entry = run(RedisCommands.value(db, key, PAGE));
        if (!entry.success()) {
            String answer = entry.error().isBlank() ? entry.output() : entry.error();
            // An unknown command means the module is not loaded; say what the key is instead of echoing the error.
            return answer.toUpperCase(Locale.ROOT).contains("UNKNOWN COMMAND")
                    ? RedisCommands.parseValue(new RedisKey(key.name(), RedisType.OTHER, key.ttlSeconds(),
                        key.typeName()), "", PAGE)
                    : RedisValue.failed(key.type(), answer);
        }
        return RedisCommands.parseValue(key, entry.output(), PAGE);
    }

    public CommandEntry set(int db, String key, String value) {
        return run(RedisCommands.set(db, key, value));
    }

    public CommandEntry expire(int db, String key, long seconds) {
        return run(RedisCommands.expire(db, key, seconds));
    }

    public CommandEntry persist(int db, String key) {
        return run(RedisCommands.persist(db, key));
    }

    public CommandEntry delete(int db, String key) {
        return run(RedisCommands.delete(db, key));
    }

    public CommandEntry create(int db, String key, RedisValue value, long ttlSeconds) {
        return run(RedisCommands.create(db, key, value, ttlSeconds));
    }

    public CommandEntry cloneKey(int db, String source, String destination) {
        return run(RedisCommands.cloneKey(db, source, destination));
    }

    public CommandEntry replace(int db, String key, RedisValue value) {
        return run(RedisCommands.replace(db, key, value));
    }

    public CommandEntry rename(int db, String source, String destination) {
        return run(RedisCommands.rename(db, source, destination));
    }

    public CommandEntry flushDb(int db) {
        return run(RedisCommands.flushDb(db));
    }

    public CommandEntry flushAll() {
        return run(RedisCommands.flushAll());
    }

    public CommandEntry raw(int db, String commandLine) {
        return run(RedisCommands.raw(db, commandLine));
    }

    public CommandEntry slowlog() {
        return run(RedisCommands.slowlog(10));
    }

    public Map<String, Long> memoryUsage(int db, List<String> keys) {
        CommandEntry entry = run(RedisCommands.memoryUsage(db, keys));
        return entry.success() ? RedisCommands.parseMemoryUsage(entry.output()) : Map.of();
    }

    /** Runs MONITOR for a bounded number of seconds; timeout's expected exit is normalized to success. */
    public CommandEntry monitor(int db, int seconds) {
        int bounded = Math.max(2, Math.min(30, seconds));
        String args = "-n " + Math.max(0, db) + " MONITOR";
        String plain = "timeout --signal=INT " + bounded + "s " + endpoint.plain(args);
        // timeout passes its environment on, so redis-cli still finds REDISCLI_AUTH behind it.
        Privilege.Command command = endpoint.command("timeout --signal=INT " + bounded + "s ", args);
        CommandEntry raw = connection.exec(command.command(), command.input(), Duration.ofSeconds(bounded + 5L));
        int exit = raw.exitCode() == 124 || raw.exitCode() == 130 ? 0 : raw.exitCode();
        CommandEntry entry = new CommandEntry(raw.executedAt(), endpoint.plain(args) + "  # " + bounded + " s",
                endpoint.isDocker() && docker != null && docker.needsRoot(), exit, raw.output(), raw.error(), raw.duration());
        commands.add(entry, Origin.REDIS);
        return entry;
    }

    // Running

    private CommandEntry run(String args) {
        return run(endpoint, args);
    }

    private CommandEntry run(RedisEndpoint endpoint, String args) {
        Privilege.Command command = endpoint.command(args);
        CommandEntry raw = connection.exec(command.command(), command.input(), TIMEOUT);
        // The history shows the plain redis-cli command, not the sudo/docker wrapper or any password.
        CommandEntry entry = new CommandEntry(raw.executedAt(), endpoint.plain(args), endpoint.isDocker() && docker != null
                && docker.needsRoot(), raw.exitCode(), raw.output(), raw.error(), raw.duration());
        commands.add(entry, Origin.REDIS);
        return entry;
    }

    private CommandEntry record(CommandEntry entry) {
        commands.add(entry, Origin.REDIS);
        return entry;
    }
}
