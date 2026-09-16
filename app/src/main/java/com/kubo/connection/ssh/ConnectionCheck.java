package com.kubo.connection.ssh;

import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.model.server.DiskPartition;
import com.kubo.model.server.ListeningPort;
import com.kubo.model.server.Server;
import com.kubo.model.server.ServerIdentity;
import com.kubo.model.server.ServerMetrics;
import com.kubo.monitor.ServerMonitor;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Manual check from the console: connects with a private key or a password, prints one monitoring
 * snapshot of the server, then disconnects.
 * Run it with: gradlew connectionCheck --console=plain -q
 */
public final class ConnectionCheck {

    private static final Scanner CONSOLE = new Scanner(System.in);

    private ConnectionCheck() {
    }

    public static void main(String[] args) {
        System.out.print("Target (user@host[:port]): ");
        SshTarget target = SshTarget.parse(CONSOLE.nextLine());

        System.out.print("Private key file (.ppk or OpenSSH, empty to use a password): ");
        String keyFile = unquote(CONSOLE.nextLine().strip());

        SshCredentials credentials;
        if (keyFile.isEmpty()) {
            System.out.print("Password: ");
            credentials = new SshCredentials.Password(CONSOLE.nextLine().toCharArray());
        } else {
            Path path;
            try {
                path = Path.of(keyFile);
            } catch (InvalidPathException e) {
                System.out.println("Invalid key file path: " + keyFile);
                return;
            }
            System.out.print("Key passphrase (empty if none): ");
            credentials = new SshCredentials.KeyFile(path, CONSOLE.nextLine().toCharArray());
        }

        try (SshConnection connection = SshConnection.open(target, credentials, ConnectionCheck::askInConsole)) {
            System.out.println("Connected to " + connection.target());
            monitorOnce(connection);
        } catch (SshConnectionException e) {
            System.out.println(e.reason() + ": " + e.getMessage());
        }
    }

    /**
     * Waits for the first snapshot that has metrics, then prints it with the commands that were sent.
     */
    private static void monitorOnce(SshConnection connection) {
        CommandService commands = new CommandService();
        CompletableFuture<Server> firstMetrics = new CompletableFuture<>();
        try (ServerMonitor monitor = new ServerMonitor(connection, commands,
                server -> {
                    if (server.metrics().isPresent()) {
                        firstMetrics.complete(server);
                    }
                },
                message -> firstMetrics.completeExceptionally(new IllegalStateException(message)))) {
            monitor.start();
            printReport(firstMetrics.get(20, TimeUnit.SECONDS));
        } catch (ExecutionException e) {
            System.out.println("Monitoring failed: " + e.getCause().getMessage());
        } catch (TimeoutException e) {
            System.out.println("No metrics after 20 s.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println();
        System.out.println("Commands sent:");
        for (CommandEntry entry : commands.getHistory()) {
            System.out.printf("  exit %-3d %5d ms  %s%n", entry.exitCode(), entry.duration().toMillis(),
                    entry.command().length() > 70 ? entry.command().substring(0, 70) + "..." : entry.command());
            if (!entry.error().isBlank()) {
                System.out.println("           stderr: " + entry.error().strip());
            }
        }
    }

    private static void printReport(Server server) {
        ServerIdentity identity = server.identity();
        ServerMetrics metrics = server.metrics().orElseThrow();

        System.out.println();
        System.out.println(identity.hostname() + " — " + identity.osRelease() + " — kernel " + identity.kernelVersion());
        System.out.println(identity.cores() + " × " + identity.cpuModel() + ", " + gigabytes(identity.totalMemoryBytes()) + " RAM");
        System.out.println("Logged in as " + identity.user().username()
                + (identity.user().isRoot() ? " (root)" : "")
                + (identity.user().inSudoGroup() ? " [sudo]" : "")
                + (identity.user().inDockerGroup() ? " [docker]" : ""));

        System.out.println();
        System.out.printf("CPU %.1f %%   steal %.1f %%   load %.2f %.2f %.2f   %d processes   up %d h%n",
                metrics.cpuPercent(), metrics.stealPercent(), metrics.loadAverage().oneMin(),
                metrics.loadAverage().fiveMin(), metrics.loadAverage().fifteenMin(), metrics.processCount(),
                metrics.uptimeSeconds() / 3600);
        System.out.printf("RAM %s / %s (%.0f %%)   swap %s / %s%n",
                gigabytes(metrics.memory().usedBytes()), gigabytes(metrics.memory().totalBytes()),
                metrics.memory().usagePercentage(),
                gigabytes(metrics.memory().usedSwapBytes()), gigabytes(metrics.memory().totalSwapBytes()));
        System.out.printf("Network ↓ %d B/s   ↑ %d B/s%n",
                metrics.network().rxBytesPerSec(), metrics.network().txBytesPerSec());

        System.out.println();
        System.out.println("Listening ports:");
        for (ListeningPort port : server.openPorts()) {
            System.out.printf("  %-8s %-3s %-22s %5d  %s%n", port.exposure(), port.protocol(), port.bindAddress(),
                    port.port(), port.processName().isEmpty() ? "?" : port.processName());
        }

        System.out.println();
        System.out.println("Disks:");
        for (DiskPartition disk : server.disks()) {
            System.out.printf("  %-20s %s / %s (%.0f %%)%n", disk.mountPoint(), gigabytes(disk.usedBytes()),
                    gigabytes(disk.totalBytes()), disk.usedPercentage());
        }
    }

    private static String gigabytes(long bytes) {
        return String.format("%.1f GB", bytes / 1_073_741_824.0);
    }

    private static boolean askInConsole(SshConnection.HostKey hostKey) {
        System.out.println("Unknown server " + hostKey.host());
        System.out.println(hostKey.algorithm() + " key fingerprint is " + hostKey.fingerprint());
        System.out.print("Trust this server (yes/no)? ");
        String answer = CONSOLE.nextLine().strip();
        return answer.equalsIgnoreCase("yes") || answer.equalsIgnoreCase("y");
    }

    /**
     * Removes the quotes Windows adds with "Copy as path".
     */
    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
