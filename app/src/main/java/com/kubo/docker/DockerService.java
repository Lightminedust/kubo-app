package com.kubo.docker;

import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.command.CommandService.Origin;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.model.docker.ContainerDetails;
import com.kubo.model.docker.ContainerStats;
import com.kubo.model.docker.DockerContainer;
import com.kubo.model.docker.DockerImage;
import com.kubo.model.docker.DockerNetwork;
import com.kubo.model.docker.DockerStatus;
import com.kubo.privilege.Privilege;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs Docker commands over the connection, becoming root through {@link Privilege} when the user is not in the
 * docker group. Every command it runs goes into the session history. Methods block: call them off the UI thread.
 */
public final class DockerService {

    // An action (start/stop/restart/remove) or a stopped container's logs may take a while.
    private static final Duration ACTION_TIMEOUT = Duration.ofSeconds(30);

    private final SshConnection connection;
    private final CommandService commands;
    private final Privilege privilege;
    private boolean needsRoot;

    public DockerService(SshConnection connection, CommandService commands, Privilege privilege) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.privilege = Objects.requireNonNull(privilege, "privilege");
    }

    /**
     * Finds out whether Docker is installed and reachable. When the user cannot reach the daemon but can act as
     * root, it checks again as root and, if that works, remembers to run every later command as root.
     */
    public DockerStatus detect() {
        DockerStatus asUser = DockerCommands.parseStatus(run(DockerCommands.STATUS).output(), false);
        if (asUser.availability() != DockerStatus.Availability.NO_PERMISSION || !privilege.available()
                || privilege.needsPassword()) {
            needsRoot = false;
            return asUser;
        }
        DockerStatus asRoot = DockerCommands.parseStatus(runAsRoot(DockerCommands.STATUS).output(), true);
        needsRoot = asRoot.usable();
        return asRoot.usable() ? asRoot : asUser;
    }

    public boolean needsRoot() {
        return needsRoot;
    }

    /**
     * Every container, with live figures added for the running ones.
     */
    public List<DockerContainer> containers() {
        List<DockerContainer> containers = DockerCommands.parseList(docker(DockerCommands.LIST).output());
        Map<String, ContainerStats> stats = DockerCommands.parseStats(docker(DockerCommands.STATS).output());
        return containers.stream().map(container -> {
            ContainerStats figures = stats.get(container.id());
            if (figures == null) {
                figures = stats.get(container.shortId());
            }
            return figures == null ? container : container.withStats(figures);
        }).toList();
    }

    public ContainerDetails inspect(String id) {
        return DockerCommands.parseInspect(docker(DockerCommands.inspect(id)).output());
    }

    /**
     * Images on disk, largest first.
     */
    public List<DockerImage> images() {
        List<DockerImage> images = new ArrayList<>(DockerCommands.parseImages(docker(DockerCommands.IMAGES).output()));
        images.sort((a, b) -> Long.compare(b.sizeBytes(), a.sizeBytes()));
        return images;
    }

    public CommandEntry removeImage(String id) {
        return docker(DockerCommands.removeImage(id), ACTION_TIMEOUT);
    }

    public CommandEntry pruneImages() {
        return docker(DockerCommands.PRUNE_IMAGES, ACTION_TIMEOUT);
    }

    /**
     * Networks, with the containers attached to each read from the running containers.
     */
    public List<DockerNetwork> networks() {
        List<DockerNetwork> networks = DockerCommands.parseNetworks(docker(DockerCommands.NETWORKS).output());
        Map<String, List<String>> membership = DockerCommands.parseNetworkMembership(docker(DockerCommands.LIST).output());
        return networks.stream().map(network -> {
            List<String> members = new ArrayList<>();
            membership.forEach((container, nets) -> {
                if (nets.contains(network.name())) {
                    members.add(container);
                }
            });
            return network.withContainers(members);
        }).toList();
    }

    /**
     * Frees disk space; returns the command whose output holds how much was reclaimed.
     */
    public CommandEntry prune() {
        return docker(DockerCommands.PRUNE, Duration.ofMinutes(2));
    }

    /**
     * Restarts every container of a stack at once.
     */
    public CommandEntry restartStack(List<String> ids) {
        return docker(DockerCommands.restartAll(ids), Duration.ofMinutes(2));
    }

    /**
     * Starts a new container. Docker pulls the image first if it is not present, so this may take a while.
     */
    public CommandEntry run(String image, String name, List<String> ports) {
        return docker(DockerCommands.run(image, name, ports), Duration.ofMinutes(5));
    }

    public CommandEntry logs(String id, int lines) {
        return docker(DockerCommands.logs(id, lines), ACTION_TIMEOUT);
    }

    public CommandEntry start(String id) {
        return docker(DockerCommands.start(id), ACTION_TIMEOUT);
    }

    public CommandEntry stop(String id) {
        return docker(DockerCommands.stop(id), ACTION_TIMEOUT);
    }

    public CommandEntry restart(String id) {
        return docker(DockerCommands.restart(id), ACTION_TIMEOUT);
    }

    public CommandEntry remove(String id) {
        return docker(DockerCommands.remove(id), ACTION_TIMEOUT);
    }

    /**
     * Runs one command inside a container, as typed by the user.
     */
    public CommandEntry exec(String id, String command) {
        return docker(DockerCommands.exec(id, command), ACTION_TIMEOUT);
    }

    /**
     * Lists one folder inside a container, read-only.
     */
    public ContainerFiles.Listing browse(String id, String path) {
        CommandEntry entry = docker(ContainerFiles.list(id, path), ACTION_TIMEOUT);
        if (!entry.success() && entry.output().isBlank()) {
            return ContainerFiles.Listing.failed(entry.error().isBlank()
                    ? "Docker refused to look inside this container." : entry.error().strip());
        }
        return ContainerFiles.parse(path, entry.output());
    }

    /**
     * Reads one text file inside a container, capped. One round trip: the text, or why there is none.
     */
    public ContainerFiles.FileRead readInContainer(String id, String path) {
        CommandEntry entry = docker(ContainerFiles.read(id, path), ACTION_TIMEOUT);
        String problem = ContainerFiles.problemIn(entry.output());
        if (problem.isEmpty() && !entry.success() && entry.output().isBlank()) {
            problem = entry.error().isBlank() ? "Docker refused to read this file." : entry.error().strip();
        }
        return new ContainerFiles.FileRead(ContainerFiles.readFile(entry.output()), problem);
    }

    /**
     * Runs an already-formed sudo check to see if the password is right, without recording it in the history.
     */
    public CommandEntry verify(Privilege.Command command) {
        return connection.exec(command.command(), command.input(), Duration.ofSeconds(15));
    }

    // Running commands

    private CommandEntry docker(String command) {
        return docker(command, null);
    }

    private CommandEntry docker(String command, Duration timeout) {
        return needsRoot ? runAsRoot(command, timeout) : run(command, timeout);
    }

    private CommandEntry run(String command) {
        return run(command, null);
    }

    private CommandEntry run(String command, Duration timeout) {
        CommandEntry entry = timeout == null ? connection.exec(command) : connection.exec(command, null, timeout);
        commands.add(entry, Origin.DOCKER);
        return entry;
    }

    private CommandEntry runAsRoot(String command) {
        return runAsRoot(command, null);
    }

    private CommandEntry runAsRoot(String command, Duration timeout) {
        // Root may be possible without being on yet. Running as the connected user then fails with docker's own
        // permission message, which is something the screen can show; asking Privilege for a wrapper it cannot
        // build would throw and kill the read.
        if (privilege == null || !privilege.available() || privilege.needsPassword()) {
            return run(command, timeout);
        }
        Privilege.Command rooted = privilege.asRoot(command);
        Duration limit = timeout == null ? Duration.ofSeconds(15) : timeout;
        CommandEntry raw = connection.exec(rooted.command(), rooted.input(), limit);
        // The history shows the plain docker command and that it ran as root, never the sudo wrapper or password.
        CommandEntry entry = new CommandEntry(raw.executedAt(), command, true, raw.exitCode(), raw.output(),
                raw.error(), raw.duration());
        commands.add(entry, Origin.DOCKER);
        return entry;
    }
}
