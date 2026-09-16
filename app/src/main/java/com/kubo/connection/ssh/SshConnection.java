package com.kubo.connection.ssh;

import com.kubo.command.CommandEntry;
import com.kubo.connection.ssh.SshConnectionException.Reason;
import net.schmizz.keepalive.KeepAliveProvider;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.DisconnectReason;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts;
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive;
import net.schmizz.sshj.userauth.method.AuthPassword;
import net.schmizz.sshj.userauth.method.PasswordResponseProvider;
import net.schmizz.sshj.userauth.password.PasswordFinder;
import net.schmizz.sshj.userauth.password.Resource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * An authenticated SSH session to one server.
 */
public final class SshConnection implements AutoCloseable {

    /**
     * Servers trusted by Kubo, in OpenSSH known_hosts format.
     */
    public static final Path KNOWN_HOSTS = Path.of(System.getProperty("user.home"), ".kubo", "known_hosts");

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    // Long enough for the user to compare the fingerprint before the key exchange gives up.
    private static final int HANDSHAKE_TIMEOUT_MS = 180_000;
    private static final int KEEP_ALIVE_SECONDS = 15;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);
    // Output left to read once the command has exited.
    private static final long STREAM_TIMEOUT_MS = 5_000;
    // Output kept per stream; the largest needed are folder listings, well below this.
    static final int MAX_KEPT_BYTES = 8 * 1024 * 1024;

    private static final ExecutorService STREAM_READERS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "kubo-ssh-stream");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * The identity a server presents during the handshake.
     */
    public record HostKey(String host, String algorithm, String fingerprint) {
    }

    @FunctionalInterface
    public interface HostKeyPrompt {

        /**
         * Asks whether to trust a server seen for the first time. Called from a background thread;
         * it may block until the user decides.
         */
        boolean trust(HostKey hostKey);
    }

    private final SSHClient client;
    private final SshTarget target;
    private final String hostKeyWarning;

    private SshConnection(SSHClient client, SshTarget target, String hostKeyWarning) {
        this.client = client;
        this.target = target;
        this.hostKeyWarning = hostKeyWarning;
    }

    /**
     * Why the server's key, trusted during this connection, was not remembered; empty when it was or was known.
     */
    public Optional<String> hostKeyWarning() {
        return Optional.ofNullable(hostKeyWarning);
    }

    /**
     * Connects and authenticates. The secrets inside {@code credentials} are wiped before this returns.
     */
    public static SshConnection open(SshTarget target, SshCredentials credentials, HostKeyPrompt prompt)
            throws SshConnectionException {
        return open(target, credentials, prompt, KNOWN_HOSTS);
    }

    /**
     * Connects and authenticates, trusting the servers listed in another known_hosts file, e.g. for tests.
     */
    public static SshConnection open(SshTarget target, SshCredentials credentials, HostKeyPrompt prompt,
                                     Path knownHosts) throws SshConnectionException {
        Objects.requireNonNull(knownHosts, "knownHosts");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(prompt, "prompt");

        DefaultConfig config = new DefaultConfig();
        config.setKeepAliveProvider(KeepAliveProvider.KEEP_ALIVE);
        SSHClient client = new SSHClient(config);
        client.setConnectTimeout(CONNECT_TIMEOUT_MS);
        client.getTransport().setTimeoutMs(HANDSHAKE_TIMEOUT_MS);

        try {
            // Read the key first: a wrong passphrase is reported without touching the network.
            KeyProvider key = credentials instanceof SshCredentials.KeyFile keyFile
                    ? loadKey(client, keyFile)
                    : null;

            TrustOnFirstUse verifier = createVerifier(prompt, knownHosts);
            client.addHostKeyVerifier(verifier);
            connect(client, target, verifier, knownHosts);
            authenticate(client, target, credentials, key);

            client.getConnection().getKeepAlive().setKeepAliveInterval(KEEP_ALIVE_SECONDS);
            return new SshConnection(client, target, verifier.notSaved());
        } catch (SshConnectionException e) {
            disconnectQuietly(client);
            throw e;
        } finally {
            credentials.wipe();
        }
    }

    public SshTarget target() {
        return target;
    }

    public boolean isConnected() {
        return client.isConnected() && client.isAuthenticated();
    }

    /**
     * Runs a command on the server and waits for it to finish. Never throws: a command that cannot run
     * comes back with {@link CommandEntry#NOT_RUN} and the reason in {@code error}.
     */
    public CommandEntry exec(String command) {
        return exec(command, null, COMMAND_TIMEOUT);
    }

    /**
     * Runs a command with bytes on its standard input and its own time limit, e.g. to save a file or copy
     * a large folder. The input is not part of the command, so it never appears in the history.
     *
     * @param input sent then closed; null to send nothing
     */
    public CommandEntry exec(String command, byte[] input, Duration timeout) {
        return exec(command, input == null ? null : new ByteArrayInputStream(input), sent -> {
        }, () -> false, timeout);
    }

    /**
     * Runs a command fed with a stream, such as a file being uploaded, reporting the bytes sent as they go.
     * Cancelling stops the stream early: the command sees its input end and must notice it is incomplete.
     *
     * @param input     sent then closed; null to send nothing
     * @param onSent    receives the total of bytes sent so far
     * @param cancelled checked between chunks
     * @param timeout   how long the command may run once its whole input is sent
     */
    public CommandEntry exec(String command, InputStream input, LongConsumer onSent, BooleanSupplier cancelled,
                             Duration timeout) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(onSent, "onSent");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(timeout, "timeout");
        Instant executedAt = Instant.now();

        try (Session session = client.startSession()) {
            Session.Command running = session.exec(command);

            // Both streams are drained at the same time: a command filling one of them
            // would otherwise block while we wait on the other.
            Future<String> output = STREAM_READERS.submit(() -> readText(running.getInputStream()));
            Future<String> error = STREAM_READERS.submit(() -> readText(running.getErrorStream()));

            boolean stopped = false;
            // Closing the input tells the command nothing more is coming, so one reading it does not wait forever.
            try (OutputStream stdin = running.getOutputStream()) {
                if (input != null) {
                    stopped = pump(input, stdin, onSent, cancelled);
                }
            } catch (IOException commandStoppedReading) {
                // The command exited before reading everything, e.g. a refused upload: its exit status says why.
            }

            // Throws once the timeout is reached, so a command that never ends cannot block Kubo.
            if (!finished(running, timeout)) {
                return notRun(executedAt, command, describe(new TimeoutException(), timeout));
            }
            Integer exitStatus = running.getExitStatus();
            String out = output.get(STREAM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            String err = error.get(STREAM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (stopped) {
                return notRun(executedAt, command, "Cancelled");
            }
            return new CommandEntry(executedAt, command, false,
                    exitStatus == null ? CommandEntry.NOT_RUN : exitStatus, out, err,
                    Duration.between(executedAt, Instant.now()));
        } catch (IOException | ExecutionException | TimeoutException e) {
            return notRun(executedAt, command, describe(e, timeout));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return notRun(executedAt, command, "Interrupted");
        }
    }

    /**
     * Runs a command whose output is streamed into {@code sink} instead of being kept, such as a file being
     * downloaded, reporting the bytes received as they come. The entry's output stays empty.
     * A failure of the sink stops the command and comes back as {@link CommandEntry#NOT_RUN} with "Local: …".
     *
     * @param cancelled checked between chunks; cancelling closes the session and comes back as "Cancelled"
     * @param timeout   how long the command may run once its output has ended
     */
    public CommandEntry execInto(String command, OutputStream sink, LongConsumer onReceived, BooleanSupplier cancelled,
                                 Duration timeout) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(onReceived, "onReceived");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(timeout, "timeout");
        Instant executedAt = Instant.now();

        try (Session session = client.startSession()) {
            Session.Command running = session.exec(command);
            Future<String> error = STREAM_READERS.submit(() -> readText(running.getErrorStream()));
            running.getOutputStream().close();

            InputStream stdout = running.getInputStream();
            byte[] buffer = new byte[64 * 1024];
            long received = 0;
            int read;
            while ((read = stdout.read(buffer)) >= 0) {
                if (cancelled.getAsBoolean()) {
                    running.close();
                    return notRun(executedAt, command, "Cancelled");
                }
                try {
                    sink.write(buffer, 0, read);
                } catch (IOException localFailure) {
                    running.close();
                    return notRun(executedAt, command, "Local: " + describe(localFailure, timeout));
                }
                received += read;
                onReceived.accept(received);
            }
            sink.flush();

            if (!finished(running, timeout)) {
                return notRun(executedAt, command, describe(new TimeoutException(), timeout));
            }
            Integer exitStatus = running.getExitStatus();
            return new CommandEntry(executedAt, command, false,
                    exitStatus == null ? CommandEntry.NOT_RUN : exitStatus, "",
                    error.get(STREAM_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    Duration.between(executedAt, Instant.now()));
        } catch (IOException | ExecutionException | TimeoutException e) {
            return notRun(executedAt, command, describe(e, timeout));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return notRun(executedAt, command, "Interrupted");
        }
    }

    /**
     * Waits for a command to end.
     *
     * @return false when it is still running after {@code timeout}
     */
    private static boolean finished(Session.Command running, Duration timeout) throws ConnectionException {
        try {
            running.join(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (ConnectionException e) {
            // sshj reports an expired wait as a connection problem; the connection itself is fine.
            if (running.isOpen() && running.getExitStatus() == null) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Copies the input to the command in chunks.
     *
     * @return true when cancelled before the end
     */
    private static boolean pump(InputStream input, OutputStream stdin, LongConsumer onSent, BooleanSupplier cancelled)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long sent = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (cancelled.getAsBoolean()) {
                return true;
            }
            stdin.write(buffer, 0, read);
            sent += read;
            onSent.accept(sent);
        }
        stdin.flush();
        return false;
    }

    @Override
    public void close() {
        disconnectQuietly(client);
    }

    private static CommandEntry notRun(Instant executedAt, String command, String reason) {
        return new CommandEntry(executedAt, command, false, CommandEntry.NOT_RUN, "", reason,
                Duration.between(executedAt, Instant.now()));
    }

    private static String readText(InputStream stream) throws IOException {
        return readLimited(stream, MAX_KEPT_BYTES);
    }

    /**
     * Reads a stream to its end but keeps only its first {@code limit} bytes, so a command printing without end
     * cannot fill Kubo's memory. What was left out is said at the end of the text.
     */
    static String readLimited(InputStream stream, int limit) throws IOException {
        byte[] kept = new byte[Math.min(limit, 64 * 1024)];
        int size = 0;
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            total += read;
            int room = limit - size;
            if (room > 0) {
                int take = Math.min(room, read);
                if (size + take > kept.length) {
                    kept = java.util.Arrays.copyOf(kept, Math.min(limit, Math.max(size + take, kept.length * 2)));
                }
                System.arraycopy(buffer, 0, kept, size, take);
                size += take;
            }
        }
        String text = new String(kept, 0, size, StandardCharsets.UTF_8);
        if (total > size) {
            text += "\n[Kubo kept the first " + size / (1024 * 1024) + " MiB of " + total + " bytes]";
        }
        return text;
    }

    private static String describe(Exception e, Duration timeout) {
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        if (cause instanceof TimeoutException) {
            return "No complete answer within " + timeout.toSeconds() + " s";
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static KeyProvider loadKey(SSHClient client, SshCredentials.KeyFile keyFile) throws SshConnectionException {
        if (!Files.isRegularFile(keyFile.file())) {
            throw new SshConnectionException(Reason.KEY_UNREADABLE, "Key file not found: " + keyFile.file());
        }
        String location = keyFile.file().toString();
        try {
            // The format (PuTTY .ppk, OpenSSH, PEM) is detected from the file content.
            KeyProvider key = keyFile.passphrase().length == 0
                    ? client.loadKeys(location)
                    : client.loadKeys(location, secretFinder(keyFile.passphrase()));
            key.getPrivate();
            return key;
        } catch (IOException e) {
            throw new SshConnectionException(Reason.KEY_UNREADABLE,
                    "Cannot read the private key (wrong passphrase or unsupported format): " + e.getMessage(), e);
        }
    }

    private static TrustOnFirstUse createVerifier(HostKeyPrompt prompt, Path knownHosts) throws SshConnectionException {
        try {
            return new TrustOnFirstUse(prompt, knownHosts);
        } catch (IOException e) {
            throw new SshConnectionException(Reason.HOST_KEY_REJECTED,
                    "Cannot read the trusted servers file " + knownHosts + ": " + e.getMessage(), e);
        }
    }

    private static void connect(SSHClient client, SshTarget target, TrustOnFirstUse verifier, Path knownHosts)
            throws SshConnectionException {
        try {
            client.connect(target.host(), target.port());
        } catch (TransportException e) {
            if (verifier.keyChanged()) {
                throw new SshConnectionException(Reason.HOST_KEY_CHANGED,
                        "The server's host key changed since the last connection. If the server was reinstalled, "
                                + "remove its line from " + knownHosts + "; otherwise someone may be intercepting "
                                + "the connection.", e);
            }
            if (e.getDisconnectReason() == DisconnectReason.HOST_KEY_NOT_VERIFIABLE) {
                throw new SshConnectionException(Reason.HOST_KEY_REJECTED, "The server's host key was not trusted.", e);
            }
            throw new SshConnectionException(Reason.UNREACHABLE, "Connection interrupted: " + e.getMessage(), e);
        } catch (UnknownHostException e) {
            throw new SshConnectionException(Reason.UNREACHABLE, "Unknown host: " + target.host(), e);
        } catch (IOException e) {
            throw new SshConnectionException(Reason.UNREACHABLE,
                    "No answer from " + target.host() + ":" + target.port() + " (" + e.getMessage() + ")", e);
        }
    }

    private static void authenticate(SSHClient client, SshTarget target, SshCredentials credentials, KeyProvider key)
            throws SshConnectionException {
        try {
            if (credentials instanceof SshCredentials.Password password) {
                // Servers take a password through "password" or "keyboard-interactive"; try both.
                PasswordFinder finder = secretFinder(password.password());
                client.auth(target.username(),
                        new AuthPassword(finder),
                        new AuthKeyboardInteractive(new PasswordResponseProvider(finder)));
            } else {
                client.authPublickey(target.username(), key);
            }
        } catch (UserAuthException e) {
            throw new SshConnectionException(Reason.AUTH_FAILED,
                    "Methods accepted by the server: "
                            + String.join(", ", client.getUserAuth().getAllowedMethods()), e);
        } catch (TransportException e) {
            throw new SshConnectionException(Reason.UNREACHABLE,
                    "Connection lost during authentication: " + e.getMessage(), e);
        }
    }

    private static PasswordFinder secretFinder(char[] secret) {
        return new PasswordFinder() {
            @Override
            public char[] reqPassword(Resource<?> resource) {
                return secret.clone();
            }

            @Override
            public boolean shouldRetry(Resource<?> resource) {
                return false;
            }
        };
    }

    private static void disconnectQuietly(SSHClient client) {
        try {
            client.disconnect();
        } catch (IOException ignored) {
            // The connection is already gone; nothing left to release.
        }
    }

    /**
     * SHA-256 fingerprint in the format printed by OpenSSH and PuTTY.
     */
    static String fingerprint(PublicKey key) {
        byte[] encoded = new Buffer.PlainBuffer().putPublicKey(key).getCompactData();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(encoded);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    /**
     * Known servers pass silently, new servers go through the prompt and are remembered,
     * servers whose key changed are refused.
     */
    private static final class TrustOnFirstUse extends OpenSSHKnownHosts {

        private final HostKeyPrompt prompt;
        private final Path knownHosts;
        private volatile boolean keyChanged;
        private volatile String notSaved;

        TrustOnFirstUse(HostKeyPrompt prompt, Path knownHosts) throws IOException {
            super(knownHosts.toFile());
            this.prompt = prompt;
            this.knownHosts = knownHosts;
        }

        boolean keyChanged() {
            return keyChanged;
        }

        @Override
        protected boolean hostKeyUnverifiableAction(String hostname, PublicKey key) {
            KeyType type = KeyType.fromKey(key);
            if (!prompt.trust(new HostKey(hostname, type.toString(), fingerprint(key)))) {
                return false;
            }
            try {
                Files.createDirectories(knownHosts.toAbsolutePath().getParent());
                write(new HostEntry(null, hostname, type, key));
            } catch (IOException e) {
                // Trusted for this session only; the connection reports it so the user knows why they are asked again.
                notSaved = "The server was trusted for this session only: " + knownHosts + " could not be written ("
                        + e.getMessage() + ")";
            }
            return true;
        }

        String notSaved() {
            return notSaved;
        }

        @Override
        protected boolean hostKeyChangedAction(String hostname, PublicKey key) {
            keyChanged = true;
            return false;
        }
    }
}
