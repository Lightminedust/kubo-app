package com.kubo.connection.ssh;

import com.kubo.command.CommandEntry;
import com.kubo.connection.ssh.SshConnectionException.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(60)
class SshConnectionIntegrationTest {

    @TempDir
    Path temp;

    private KeyPair clientKey;
    private TestServer server;
    private Path knownHosts;
    private final List<SshConnection.HostKey> prompted = new ArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        clientKey = generator.generateKeyPair();
        server = new TestServer(temp.resolve("host-a.ser"), 0, clientKey.getPublic());
        knownHosts = temp.resolve("kubo/known_hosts");
    }

    @AfterEach
    void stopServer() throws IOException {
        server.close();
    }

    private SshConnection connectWithPassword() throws SshConnectionException {
        return SshConnection.open(server.target(), new SshCredentials.Password(TestServer.PASSWORD.toCharArray()),
                hostKey -> prompted.add(hostKey), knownHosts);
    }

    // Trust and authentication

    @Test
    void unknownServerIsAskedAboutOnceThenRemembered() throws Exception {
        try (SshConnection connection = connectWithPassword()) {
            assertTrue(connection.isConnected());
            assertTrue(connection.hostKeyWarning().isEmpty());
        }
        try (SshConnection again = connectWithPassword()) {
            assertTrue(again.isConnected());
        }

        assertEquals(1, prompted.size());
        assertTrue(prompted.get(0).fingerprint().startsWith("SHA256:"));
        assertTrue(Files.readString(knownHosts).contains("127.0.0.1"));
    }

    @Test
    void rejectedServerIsNotConnectedNorRemembered() {
        SshConnectionException failure = assertThrows(SshConnectionException.class, () -> SshConnection.open(
                server.target(), new SshCredentials.Password(TestServer.PASSWORD.toCharArray()), hostKey -> false,
                knownHosts));

        assertEquals(Reason.HOST_KEY_REJECTED, failure.reason());
        assertFalse(Files.exists(knownHosts));
    }

    @Test
    void serverWhoseKeyChangedIsRefusedWithoutAsking() throws Exception {
        connectWithPassword().close();
        int port = server.port();
        server.close();
        server = new TestServer(temp.resolve("host-b.ser"), port, clientKey.getPublic());
        prompted.clear();

        SshConnectionException failure = assertThrows(SshConnectionException.class, this::connectWithPassword);

        assertEquals(Reason.HOST_KEY_CHANGED, failure.reason());
        assertTrue(prompted.isEmpty());
    }

    @Test
    void wrongPasswordIsRefusedAndWiped() {
        char[] password = "wrong".toCharArray();

        SshConnectionException failure = assertThrows(SshConnectionException.class, () -> SshConnection.open(
                server.target(), new SshCredentials.Password(password), hostKey -> true, knownHosts));

        assertEquals(Reason.AUTH_FAILED, failure.reason());
        assertArrayEquals(new char[password.length], password);
    }

    @Test
    void privateKeyAuthenticates() throws Exception {
        Path keyFile = temp.resolve("id_rsa.pem");
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(clientKey.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(keyFile, pem);

        try (SshConnection connection = SshConnection.open(server.target(),
                new SshCredentials.KeyFile(keyFile, new char[0]), hostKey -> true, knownHosts)) {
            assertTrue(connection.isConnected());
        }
    }

    @Test
    void missingKeyFileFailsBeforeTouchingTheNetwork() {
        SshConnectionException failure = assertThrows(SshConnectionException.class, () -> SshConnection.open(
                server.target(), new SshCredentials.KeyFile(temp.resolve("missing.ppk"), new char[0]), hostKey -> true,
                knownHosts));

        assertEquals(Reason.KEY_UNREADABLE, failure.reason());
        assertTrue(prompted.isEmpty());
    }

    @Test
    void closedPortIsUnreachable() throws Exception {
        int port = server.port();
        server.close();

        SshConnectionException failure = assertThrows(SshConnectionException.class, () -> SshConnection.open(
                new SshTarget(TestServer.USER, "127.0.0.1", port),
                new SshCredentials.Password(TestServer.PASSWORD.toCharArray()), hostKey -> true, knownHosts));

        assertEquals(Reason.UNREACHABLE, failure.reason());
        server = new TestServer(temp.resolve("host-a.ser"), 0, null);
    }

    // Commands

    @Test
    void commandOutputAndExitCodeComeBack() throws Exception {
        try (SshConnection connection = connectWithPassword()) {
            CommandEntry echo = connection.exec("echo héllo wörld");
            CommandEntry fail = connection.exec("fail 3 permission denied");

            assertEquals("héllo wörld\n", echo.output());
            assertTrue(echo.success());
            assertEquals(3, fail.exitCode());
            assertEquals("permission denied\n", fail.error());
            assertFalse(echo.asRoot());
        }
    }

    @Test
    void inputReachesTheCommandAndEnds() throws Exception {
        byte[] data = new byte[300_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251);
        }
        try (SshConnection connection = connectWithPassword()) {
            CommandEntry counted = connection.exec("count", data, Duration.ofSeconds(20));

            assertEquals(String.valueOf(data.length), counted.output());
        }
    }

    @Test
    void watchingHandsOverBothStreamsAsTheyArrive() throws Exception {
        java.util.List<String> seen = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        try (SshConnection connection = connectWithPassword()) {
            CommandEntry entry = connection.execWatching("fail 3 waiting for the lock", null, seen::add,
                    Duration.ofSeconds(20));

            // A package manager says the interesting things on standard error: that it is waiting for another
            // apt to let go of the lock, that a repository would not answer, that a key has expired. Watching
            // only standard output left a card sitting there for minutes with nothing on it while apt was
            // explaining itself the whole time.
            assertTrue(seen.contains("waiting for the lock"), "standard error never reached the watcher: " + seen);
            assertEquals(3, entry.exitCode());
            // They are still kept apart in the entry; only the watching is shared.
            assertEquals("waiting for the lock\n", entry.error());
            assertEquals("", entry.output());
        }
    }

    @Test
    void aCommandThatAsksSomethingCanBeAnswered() throws Exception {
        java.util.List<String> seen = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.concurrent.atomic.AtomicReference<SshConnection.Live> live =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch prompted = new java.util.concurrent.CountDownLatch(1);

        try (SshConnection connection = connectWithPassword()) {
            CommandEntry entry = connection.execInteractive("ask hunter2",
                    line -> {
                        seen.add(line.text());
                        if (line.text().contains("Passphrase")) {
                            prompted.countDown();
                        }
                    },
                    handle -> {
                        live.set(handle);
                        // The prompt has no newline after it, so the answer goes once something has arrived.
                        try {
                            prompted.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        handle.answer("hunter2".toCharArray());
                    },
                    Duration.ofSeconds(20));

            // Without a terminal this command would have waited for its input until the timeout, which is what
            // anybody cloning a private repository or unlocking a key ran into.
            assertEquals(0, entry.exitCode(), "the answer never reached the command: " + seen);
            assertTrue(entry.output().contains("unlocked"), entry.output());
        }
    }

    @Test
    void aQuestionArrivesBeforeAnythingIsTyped() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean inTime = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CountDownLatch prompted = new java.util.concurrent.CountDownLatch(1);

        try (SshConnection connection = connectWithPassword()) {
            CommandEntry entry = connection.execInteractive("ask hunter2",
                    line -> {
                        // Waiting, not merely printed: the command stopped on that line without ending it.
                        if (line.waiting() && line.text().contains("Passphrase")) {
                            prompted.countDown();
                        }
                    },
                    handle -> {
                        // Nothing has been written to the command yet. A prompt carries no newline, so if the
                        // reader waits for one it waits forever, and the only thing that ever flushed it was
                        // the terminal echoing what somebody had already typed blind: the question turned up
                        // after the answer. That is what somebody types a password into.
                        try {
                            inTime.set(prompted.await(5, java.util.concurrent.TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        handle.answer("hunter2".toCharArray());
                    },
                    Duration.ofSeconds(20));

            assertTrue(inTime.get(), "the question was never handed over while the command waited for it");
            assertEquals(0, entry.exitCode());
        }
    }

    @Test
    void theAnswerIsWipedOnceItHasGone() throws Exception {
        char[] secret = "hunter2".toCharArray();
        try (SshConnection connection = connectWithPassword()) {
            connection.execInteractive("ask hunter2", line -> { },
                    handle -> handle.answer(secret), Duration.ofSeconds(20));
        }

        // Kubo holds an answer as characters so it can take it back. A string could not be taken back.
        assertArrayEquals(new char[secret.length], secret, "the answer was left in memory");
    }

    @Test
    void aCommandThatWillNotEndCanBeStopped() throws Exception {
        try (SshConnection connection = connectWithPassword()) {
            CommandEntry entry = connection.execInteractive("hang", line -> { },
                    handle -> {
                        // Control-C, which is the way out of something that would otherwise sit there until
                        // the timeout took it.
                        new Thread(() -> {
                            try {
                                Thread.sleep(300);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            handle.interrupt();
                        }).start();
                    },
                    Duration.ofSeconds(20));

            assertTrue(entry.duration().toSeconds() < 15, "it ran for " + entry.duration());
            assertEquals(130, entry.exitCode(), "130 is what a shell reports for a command stopped this way");
        }
    }

    @Test
    void aStreamedCommandCanBeToldSomethingFirst() throws Exception {
        // What sudo is. Without this the only way to stream a command as root was to wrap it in sudo and
        // then close its input, and sudo answered what it always answers: a password is required. Every
        // download of a file only root could read failed that way, with root mode on.
        ByteArrayOutputStream into = new ByteArrayOutputStream();

        try (SshConnection connection = connectWithPassword()) {
            CommandEntry entry = connection.execInto("ask hunter2",
                    "hunter2\n".getBytes(StandardCharsets.UTF_8), into, received -> { }, () -> false,
                    Duration.ofSeconds(20));

            assertEquals(0, entry.exitCode(), entry.error());
            assertTrue(into.toString(StandardCharsets.UTF_8).contains("unlocked"),
                    into.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void aStreamedCommandToldNothingStillRuns() throws Exception {
        ByteArrayOutputStream into = new ByteArrayOutputStream();

        try (SshConnection connection = connectWithPassword()) {
            CommandEntry entry = connection.execInto("echo hello", into, received -> { }, () -> false,
                    Duration.ofSeconds(20));

            assertEquals(0, entry.exitCode());
            assertTrue(into.toString(StandardCharsets.UTF_8).contains("hello"));
        }
    }

    @Test
    void streamedInputReportsProgress() throws Exception {
        byte[] data = new byte[500_000];
        AtomicLong reported = new AtomicLong();
        try (SshConnection connection = connectWithPassword()) {
            CommandEntry counted = connection.exec("count", new ByteArrayInputStream(data), reported::set, () -> false,
                    Duration.ofSeconds(20));

            assertEquals("500000", counted.output());
            assertEquals(data.length, reported.get());
        }
    }

    @Test
    void cancellingStopsSendingAndSaysSo() throws Exception {
        byte[] data = new byte[2_000_000];
        AtomicLong reported = new AtomicLong();
        try (SshConnection connection = connectWithPassword()) {
            CommandEntry cancelled = connection.exec("count", new ByteArrayInputStream(data), reported::set,
                    () -> reported.get() > 100_000, Duration.ofSeconds(20));

            assertEquals(CommandEntry.NOT_RUN, cancelled.exitCode());
            assertEquals("Cancelled", cancelled.error());
            assertTrue(reported.get() < data.length);
        }
    }

    @Test
    void commandThatNeverEndsIsCutAtItsTimeout() throws Exception {
        try (SshConnection connection = connectWithPassword()) {
            CommandEntry hung = connection.exec("hang", null, Duration.ofSeconds(1));

            assertFalse(hung.ran());
            assertTrue(hung.error().contains("1 s"), hung.error());
            // The connection is still usable afterwards.
            assertEquals("still here\n", connection.exec("echo still here").output());
        }
    }

    @Test
    void hugeOutputIsCappedButReadToTheEnd() throws Exception {
        long size = SshConnection.MAX_KEPT_BYTES + 1_000_000L;
        try (SshConnection connection = connectWithPassword()) {
            CommandEntry flood = connection.exec("flood " + size, null, Duration.ofSeconds(30));

            assertTrue(flood.success());
            assertTrue(flood.output().endsWith(" of " + size + " bytes]"));
        }
    }

    @Test
    void outputCanBeStreamedIntoAFile() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        AtomicLong received = new AtomicLong();
        try (SshConnection connection = connectWithPassword()) {
            CommandEntry flood = connection.execInto("flood 250000", sink, received::set, () -> false,
                    Duration.ofSeconds(20));

            assertTrue(flood.success());
            assertEquals("", flood.output());
            assertEquals(250_000, sink.size());
            assertEquals(250_000, received.get());
        }
    }

    @Test
    void failingSinkStopsTheCommand() throws Exception {
        OutputStream full = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("Disk full");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("Disk full");
            }
        };
        try (SshConnection connection = connectWithPassword()) {
            CommandEntry flood = connection.execInto("flood 250000", full, received -> {
            }, () -> false, Duration.ofSeconds(20));

            assertFalse(flood.ran());
            assertTrue(flood.error().startsWith("Local: "), flood.error());
            assertTrue(connection.exec("echo ok").success());
        }
    }
}
