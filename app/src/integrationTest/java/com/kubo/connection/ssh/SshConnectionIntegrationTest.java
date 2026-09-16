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
