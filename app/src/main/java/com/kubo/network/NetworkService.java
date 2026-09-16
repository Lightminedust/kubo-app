package com.kubo.network;

import com.kubo.command.AdminPath;
import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.command.CommandService.Origin;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.docker.DockerService;
import com.kubo.model.docker.DockerContainer;
import com.kubo.model.docker.PortMapping;
import com.kubo.model.network.Endpoint;
import com.kubo.model.network.Firewall;
import com.kubo.model.network.ListeningSocket;
import com.kubo.model.network.NetworkMap;
import com.kubo.model.network.PmApp;
import com.kubo.model.network.Risk;
import com.kubo.model.network.ServiceOwner;
import com.kubo.model.network.Vhost;
import com.kubo.model.server.ListeningPort.Protocol;
import com.kubo.privilege.Privilege;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Reads the whole network path in one pass and cross-references it: UFW at the edge, the Nginx server blocks behind
 * it, the ports they forward to, and the PM2 applications, containers and daemons holding those ports.
 *
 * <p>UFW and Nginx normally answer only to root, so those two reads go through {@link Privilege} when the account
 * is not already root. Methods block: call them off the UI thread.
 */
public final class NetworkService {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Duration ACTION_TIMEOUT = Duration.ofSeconds(45);

    private final SshConnection connection;
    private final CommandService commands;
    private final Privilege privilege;
    private final DockerService docker;

    public NetworkService(SshConnection connection, CommandService commands, Privilege privilege,
                          DockerService docker) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.privilege = privilege;
        this.docker = docker;
    }

    /**
     * True when reading the firewall and the web configuration needs a sudo password Kubo does not have yet.
     */
    public boolean needsPassword() {
        return privilege != null && privilege.needsPassword();
    }

    /**
     * Checks a sudo password with a harmless command, without recording it.
     */
    public CommandEntry verify(Privilege.Command command) {
        return connection.exec(command.command(), command.input(), TIMEOUT);
    }

    /**
     * Reads every source and returns the correlated map. Sources that are absent or unreadable leave a note rather
     * than failing the whole read.
     */
    public NetworkMap read() {
        List<String> notes = new ArrayList<>();
        List<ListeningSocket> sockets = NetworkCommands.parseSockets(run(NetworkCommands.SOCKETS).output());
        if (sockets.stream().noneMatch(ListeningSocket::hasPid)) {
            notes.add("No process could be named for the listening ports. Naming another user's process needs root.");
        }

        List<PmApp> pm2 = NetworkCommands.parsePm2(run(NetworkCommands.PM2).output());

        Firewall firewall = readFirewall(notes);
        List<Vhost> vhosts = readNginx(notes);

        List<DockerContainer> containers = docker == null ? List.<DockerContainer>of() : safeContainers();

        List<Endpoint> endpoints = correlate(sockets, pm2, containers, vhosts, firewall);
        List<Risk> risks = endpoints.stream()
                .map(Risk::of)
                .flatMap(java.util.Optional::stream)
                .sorted(Comparator.comparing(Risk::level).thenComparingInt(Risk::port))
                .toList();
        return new NetworkMap(firewall, vhosts, endpoints, pm2, risks, notes);
    }

    /**
     * Docker is a nice-to-have here: when it is absent or refuses, the map is built without container identities.
     */
    private List<DockerContainer> safeContainers() {
        try {
            return docker.containers();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Reads whichever firewall this server runs. UFW first because it is the one Kubo can also change, then
     * firewalld, then the raw tables. The first tool that gives a usable answer wins; a tool that is present but
     * unreadable stops the search, since falling through to a lower-level view of the same rules would only produce
     * a second unreliable answer.
     */
    private Firewall readFirewall(List<String> notes) {
        List<Firewall.Kind> tools = FirewallCommands.parseTools(run(FirewallCommands.DETECT).output());
        if (tools.isEmpty()) {
            notes.add(Firewall.absent().problem());
            return Firewall.absent();
        }
        Firewall firewall = Firewall.absent();
        for (Firewall.Kind kind : tools) {
            firewall = switch (kind) {
                case UFW -> FirewallCommands.parseUfw(runAsRoot(FirewallCommands.UFW, TIMEOUT).output());
                case FIREWALLD ->
                        FirewallCommands.parseFirewalld(runAsRoot(FirewallCommands.FIREWALLD, TIMEOUT).output());
                case NFTABLES ->
                        FirewallCommands.parseNftables(runAsRoot(FirewallCommands.NFTABLES, TIMEOUT).output());
                case IPTABLES ->
                        FirewallCommands.parseIptables(runAsRoot(FirewallCommands.IPTABLES, TIMEOUT).output());
                case NONE -> Firewall.absent();
            };
            // A tool that is enforcing, or that cannot be read, is the answer: there is no point asking iptables
            // what UFW already told us, and no point pretending a lower layer is clearer.
            if (firewall.state() != Firewall.State.INACTIVE) {
                break;
            }
        }
        if (firewall.state() == Firewall.State.UNREADABLE) {
            firewall = withoutRoot(firewall);
        }
        String problem = firewall.problem();
        if (!problem.isEmpty()) {
            notes.add(problem);
        }
        return firewall;
    }

    /**
     * The rules needed root and did not get it. Their content cannot be had any other way, but whether the firewall
     * is switched on can be, and that is most of what someone wants to know: "running, rules need root" is a far
     * more useful answer than "unknown", and "switched off" is the one that actually needs acting on.
     */
    private Firewall withoutRoot(Firewall firewall) {
        FirewallCommands.Liveness liveness =
                FirewallCommands.parseLiveness(run(FirewallCommands.liveness(firewall.kind())).output());
        return switch (liveness) {
            case RUNNING -> Firewall.of(firewall.kind(), Firewall.State.UNRECOGNISED);
            case STOPPED -> Firewall.of(firewall.kind(), Firewall.State.INACTIVE);
            case UNKNOWN -> firewall;
        };
    }

    private List<Vhost> readNginx(List<String> notes) {
        if (run(NetworkCommands.NGINX_PRESENT).output().strip().equals("NO")) {
            return List.of();
        }
        CommandEntry entry = runAsRoot(NetworkCommands.NGINX, TIMEOUT);
        List<Vhost> vhosts = NetworkCommands.parseNginx(entry.output());
        if (vhosts.isEmpty()) {
            // `nginx -T` needs root, but the configuration files themselves are usually world-readable, so the
            // server blocks can often be had anyway. This is only a fallback: it misses whatever an include pulls
            // in from somewhere unusual, which is exactly what -T exists to resolve.
            vhosts = NetworkCommands.parseNginx(run(NetworkCommands.NGINX_FILES, TIMEOUT).output());
        }
        if (vhosts.isEmpty()) {
            notes.add("Nginx is installed but only root may read its configuration. Turn on ROOT in the header "
                    + "and refresh to see which domains it serves and where it sends them.");
        }
        return vhosts;
    }

    /**
     * The heart of the screen: gives every socket an identity and the domains that reach it.
     */
    public static List<Endpoint> correlate(List<ListeningSocket> sockets, List<PmApp> pm2,
                                           List<DockerContainer> containers, List<Vhost> vhosts,
                                           Firewall firewall) {
        Map<Integer, PmApp> byPid = new HashMap<>();
        for (PmApp app : pm2) {
            if (app.pid() > 0) {
                byPid.put(app.pid(), app);
            }
        }
        // A container's process is not visible to ss on the host, so containers are matched on their published port.
        Map<Integer, DockerContainer> byPublishedPort = new HashMap<>();
        for (DockerContainer container : containers) {
            for (PortMapping mapping : container.publishedPorts()) {
                byPublishedPort.putIfAbsent(mapping.hostPort(), container);
            }
        }

        List<Endpoint> endpoints = new ArrayList<>();
        for (ListeningSocket socket : sockets) {
            ServiceOwner owner = ownerOf(socket, byPid, byPublishedPort);
            List<Vhost> servedBy = vhosts.stream()
                    .filter(vhost -> vhost.proxyTargets().stream()
                            .anyMatch(target -> target.port() == socket.port() && target.isLocalPort()))
                    .toList();
            endpoints.add(new Endpoint(socket, owner, servedBy, Endpoint.reachOf(socket, firewall)));
        }
        endpoints.sort(Comparator.comparingInt(Endpoint::port)
                .thenComparing(endpoint -> endpoint.socket().bindAddress()));
        return List.copyOf(endpoints);
    }

    private static ServiceOwner ownerOf(ListeningSocket socket, Map<Integer, PmApp> byPid,
                                        Map<Integer, DockerContainer> byPublishedPort) {
        PmApp app = socket.hasPid() ? byPid.get(socket.pid()) : null;
        if (app != null) {
            return new ServiceOwner(ServiceOwner.Kind.PM2, app.name(),
                    "pm2 · " + app.status() + " · " + app.restarts() + " restarts", app.name(), socket.pid());
        }
        String process = socket.processName().toLowerCase(Locale.ROOT);
        DockerContainer container = byPublishedPort.get(socket.port());
        // docker-proxy holds the host side of a published container port.
        if (container != null && (process.contains("docker") || process.isEmpty())) {
            return new ServiceOwner(ServiceOwner.Kind.DOCKER, container.name(), container.image(),
                    container.id(), socket.pid());
        }
        if (!socket.processName().isBlank()) {
            return ServiceOwner.system(socket.processName(), socket.pid());
        }
        return ServiceOwner.unknown();
    }

    // Actions

    public CommandEntry allow(int port, Protocol protocol) {
        return runAsRoot(NetworkCommands.ufwAllow(port, protocol), ACTION_TIMEOUT);
    }

    public CommandEntry closePort(int port, Protocol protocol) {
        return runAsRoot(NetworkCommands.ufwDeleteAllow(port, protocol), ACTION_TIMEOUT);
    }

    public CommandEntry deny(int port, Protocol protocol) {
        return runAsRoot(NetworkCommands.ufwDeny(port, protocol), ACTION_TIMEOUT);
    }

    public CommandEntry restartApp(String app) {
        return run(NetworkCommands.pm2Restart(app), ACTION_TIMEOUT);
    }

    public CommandEntry stopApp(String app) {
        return run(NetworkCommands.pm2Stop(app), ACTION_TIMEOUT);
    }

    public CommandEntry appLogs(String app, int lines) {
        return run(NetworkCommands.pm2Logs(app, lines), ACTION_TIMEOUT);
    }

    /**
     * Ends a process. A process Kubo does not own needs root, so this goes through sudo.
     */
    public CommandEntry kill(int pid) {
        return runAsRoot(NetworkCommands.kill(pid), ACTION_TIMEOUT);
    }

    /**
     * Checks the Nginx configuration and reloads it only if it is valid.
     */
    public CommandEntry reloadNginx() {
        return runAsRoot(NetworkCommands.NGINX_RELOAD, ACTION_TIMEOUT);
    }

    // Running

    private CommandEntry run(String command) {
        return run(command, TIMEOUT);
    }

    /**
     * Sends a command with the administration directories on the PATH, and records the plain one. Every tool this
     * screen reads — ss, ufw, nginx, nft, iptables, firewall-cmd — lives in /usr/sbin or /sbin, which a non-root
     * SSH session does not have on its PATH.
     */
    private CommandEntry run(String command, Duration timeout) {
        CommandEntry raw = connection.exec(AdminPath.with(command), null, timeout);
        CommandEntry entry = new CommandEntry(raw.executedAt(), command, false, raw.exitCode(), raw.output(),
                raw.error(), raw.duration());
        commands.add(entry, Origin.NETWORK);
        return entry;
    }

    /**
     * Runs a command as root when the account is not already root. The history keeps the plain command with its
     * root flag, never the sudo wrapper and never the password.
     */
    private CommandEntry runAsRoot(String command, Duration timeout) {
        // Root mode may be possible without being on yet: nobody has typed the sudo password. Running the command
        // as the connected user is the honest thing to do — it fails with "you need to be root", which is exactly
        // what the screen then reports, instead of throwing and leaving the read half done.
        if (privilege == null || !privilege.available() || privilege.needsPassword()) {
            return run(command, timeout);
        }
        Privilege.Command wrapped = privilege.asRoot(AdminPath.with(command));
        CommandEntry raw = connection.exec(wrapped.command(), wrapped.input(), timeout);
        CommandEntry entry = new CommandEntry(raw.executedAt(), command, true, raw.exitCode(), raw.output(),
                raw.error(), raw.duration());
        commands.add(entry, Origin.NETWORK);
        return entry;
    }
}
