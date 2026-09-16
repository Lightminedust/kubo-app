package com.kubo.connection.ssh;

import com.kubo.command.AdminPath;
import com.kubo.command.CommandService;
import com.kubo.model.network.NetworkMap;
import com.kubo.network.NetworkService;
import com.kubo.privilege.Privilege;
import com.kubo.privilege.RootAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A non-root SSH session on Debian and Ubuntu has no {@code /usr/sbin} on its PATH, so every tool the network
 * screen reads — ufw, nginx, ss, nft, iptables, firewall-cmd — is invisible to {@code command -v} unless Kubo puts
 * those directories back. This checks what actually arrives at the server, which is the only place the mistake
 * shows: the commands themselves look perfectly correct on their own.
 */
@Timeout(60)
class AdminPathIntegrationTest {

    @TempDir
    Path temp;

    private TestServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new TestServer(temp.resolve("host.ser"), 0, null);
        TestServer.forgetReceived();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.close();
    }

    @Test
    @DisplayName("every network command reaches the server with the admin directories on its PATH")
    void theServerSeesTheWidenedPath() throws Exception {
        try (SshConnection connection = SshConnection.open(server.target(),
                new SshCredentials.Password(TestServer.PASSWORD.toCharArray()),
                hostKey -> true, temp.resolve("known_hosts"))) {

            new NetworkService(connection, new CommandService(), null, null).read();
        }

        List<String> received = TestServer.received();
        assertFalse(received.isEmpty(), "the service sent nothing");
        for (String line : received) {
            assertTrue(line.startsWith("export PATH="),
                    "this command would not find a tool in /usr/sbin: " + line);
            assertTrue(line.contains("/usr/sbin") && line.contains("/sbin"), line);
        }
    }

    @Test
    @DisplayName("the firewall and the web server are looked for on the widened PATH, not the login one")
    void theToolsAreActuallyLookedFor() throws Exception {
        try (SshConnection connection = SshConnection.open(server.target(),
                new SshCredentials.Password(TestServer.PASSWORD.toCharArray()),
                hostKey -> true, temp.resolve("known_hosts"))) {

            new NetworkService(connection, new CommandService(), null, null).read();
        }

        String all = String.join("\n", TestServer.received());
        for (String tool : new String[]{"ufw", "nginx", "ss "}) {
            assertTrue(all.contains(tool), "nothing ever looked for " + tool + " in:\n" + all);
        }
    }

    @Test
    @DisplayName("what the user is shown is the plain command, not the PATH plumbing")
    void theHistoryStaysReadable() throws Exception {
        CommandService commands = new CommandService();
        try (SshConnection connection = SshConnection.open(server.target(),
                new SshCredentials.Password(TestServer.PASSWORD.toCharArray()),
                hostKey -> true, temp.resolve("known_hosts"))) {

            new NetworkService(connection, commands, null, null).read();
        }

        assertFalse(commands.getHistory().isEmpty());
        for (var entry : commands.getHistory()) {
            assertFalse(entry.command().startsWith("export PATH="),
                    "the log should say what Kubo asked for, not how it was sent: " + entry.command());
        }
    }

    @Test
    @DisplayName("a read that needs root, before anyone has typed the sudo password, reports instead of throwing")
    void rootIsNotDemandedBeforeItIsGiven() throws Exception {
        // This is the state the network screen is in the moment it opens: sudo is possible, ROOT is not on yet.
        Privilege privilege = new Privilege(RootAccess.SUDO_PASSWORD);
        assertTrue(privilege.available());
        assertTrue(privilege.needsPassword());

        try (SshConnection connection = SshConnection.open(server.target(),
                new SshCredentials.Password(TestServer.PASSWORD.toCharArray()),
                hostKey -> true, temp.resolve("known_hosts"))) {

            NetworkService service = new NetworkService(connection, new CommandService(), privilege, null);
            NetworkMap map = assertDoesNotThrow(service::read,
                    "the whole read must not die because the firewall needs root");
            assertFalse(map.notes().isEmpty(), "and it must say why the firewall could not be read");
        }
    }

    @Test
    @DisplayName("the prefix names the three directories a login shell drops, and nothing else")
    void theShapeOfThePrefix() {
        String widened = AdminPath.with("ufw status numbered");

        assertTrue(widened.endsWith("ufw status numbered"), widened);
        assertTrue(widened.startsWith("export PATH=\"$PATH:"), widened);
        assertTrue(widened.contains("/usr/local/sbin:/usr/sbin:/sbin"), widened);
    }
}
