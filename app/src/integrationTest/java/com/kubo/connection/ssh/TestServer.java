package com.kubo.connection.ssh;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Arrays;

/**
 * An SSH server living inside the test. It does not run a real shell: a few fake commands are enough to check
 * what Kubo sends and how it reads the answers.
 *
 * <ul>
 *     <li>{@code echo TEXT}: prints TEXT and a line break</li>
 *     <li>{@code fail CODE MESSAGE}: prints MESSAGE on stderr and exits with CODE</li>
 *     <li>{@code cat}: copies its input to its output</li>
 *     <li>{@code count}: reads its input to the end and prints how many bytes arrived</li>
 *     <li>{@code flood BYTES}: prints BYTES times the letter x</li>
 *     <li>{@code hang}: never ends</li>
 * </ul>
 */
final class TestServer implements AutoCloseable {

    static final String USER = "xana";
    static final String PASSWORD = "correct horse";

    private final SshServer server;
    /** Every command line the server was asked to run, so a test can check what Kubo actually sends. */
    private static final java.util.List<String> RECEIVED =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * @param hostKey where the server keeps its own key; another file means another server identity
     * @param port    0 for any free port
     * @param allowedKey the only public key accepted, or null to refuse key authentication
     */
    TestServer(Path hostKey, int port, PublicKey allowedKey) throws IOException {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(port);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        server.setPasswordAuthenticator((user, password, session) -> USER.equals(user) && PASSWORD.equals(password));
        server.setPublickeyAuthenticator((user, key, session) -> allowedKey != null && USER.equals(user)
                && Arrays.equals(key.getEncoded(), allowedKey.getEncoded()));
        server.setCommandFactory((channel, command) -> new FakeCommand(command));
        server.start();
    }

    int port() {
        return server.getPort();
    }

    /**
     * What the server has been asked to run since {@link #forgetReceived()}.
     */
    static java.util.List<String> received() {
        synchronized (RECEIVED) {
            return java.util.List.copyOf(RECEIVED);
        }
    }

    static void forgetReceived() {
        RECEIVED.clear();
    }

    SshTarget target() {
        return new SshTarget(USER, "127.0.0.1", port());
    }

    @Override
    public void close() throws IOException {
        server.stop(true);
    }

    private static final class FakeCommand implements Command {

        private final String line;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback exit;
        private Thread thread;

        FakeCommand(String line) {
            this.line = line;
        }

        @Override
        public void setInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            this.err = err;
        }

        @Override
        public void setExitCallback(ExitCallback exit) {
            this.exit = exit;
        }

        @Override
        public void start(ChannelSession channel, Environment environment) {
            thread = new Thread(this::run, "fake-command");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void destroy(ChannelSession channel) {
            if (thread != null) {
                thread.interrupt();
            }
        }

        private void run() {
            RECEIVED.add(line);
            String[] parts = line.split(" ", 3);
            int code = 0;
            try {
                switch (parts[0]) {
                    case "echo" -> write(out, line.substring(5) + "\n");
                    case "fail" -> {
                        write(err, parts.length > 2 ? parts[2] + "\n" : "");
                        code = Integer.parseInt(parts[1]);
                    }
                    case "cat" -> in.transferTo(out);
                    case "count" -> write(out, String.valueOf(in.transferTo(OutputStream.nullOutputStream())));
                    case "flood" -> {
                        byte[] chunk = new byte[64 * 1024];
                        Arrays.fill(chunk, (byte) 'x');
                        long left = Long.parseLong(parts[1]);
                        while (left > 0) {
                            int size = (int) Math.min(chunk.length, left);
                            out.write(chunk, 0, size);
                            left -= size;
                        }
                    }
                    case "hang" -> Thread.sleep(Long.MAX_VALUE);
                    default -> {
                        write(err, "unknown command: " + line + "\n");
                        code = 127;
                    }
                }
                out.flush();
                err.flush();
            } catch (InterruptedException e) {
                return;
            } catch (IOException e) {
                code = 1;
            }
            exit.onExit(code);
        }

        private static void write(OutputStream stream, String text) throws IOException {
            stream.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
