package com.kubo.network;

import com.kubo.command.AdminPath;
import com.kubo.command.CommandEntry;
import com.kubo.command.CommandService;
import com.kubo.command.CommandService.Origin;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.docker.DockerService;
import com.kubo.model.docker.DockerContainer;
import com.kubo.model.docker.PortMapping;
import com.kubo.model.network.Certificate;
import com.kubo.model.network.Connection;
import com.kubo.model.network.Counter;
import com.kubo.model.network.Endpoint;
import com.kubo.model.network.Identity;
import com.kubo.model.network.Jail;
import com.kubo.model.network.Link;
import com.kubo.model.network.Naming;
import com.kubo.model.network.Resolvers;
import com.kubo.model.network.Route;
import com.kubo.model.network.Firewall;
import com.kubo.model.network.ListeningSocket;
import com.kubo.model.network.NetworkMap;
import com.kubo.model.network.PmApp;
import com.kubo.model.network.Risk;
import com.kubo.model.network.ServiceOwner;
import com.kubo.model.network.SiteDraft;
import com.kubo.model.network.Vhost;
import com.kubo.model.server.ListeningPort.Protocol;
import com.kubo.privilege.Privilege;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashSet;
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

    /** The previous byte counters and when they were read. A rate needs two readings; this is the first one. */
    private Map<String, Counter> previousCounters = Map.of();
    private Instant countersReadAt;

    // What this server has, as opposed to what it is doing. Which firewall tool is installed, whether nginx is
    // there and what it serves, whether fail2ban is there: none of that changes in twelve seconds, and asking
    // every twelve seconds cost several sudo calls a cycle. Worse, when one of those was refused, the same
    // refusal came back in the log every twelve seconds and never a different answer.
    // These are null until the first look, and forget() puts them back to null. A refusal is kept too: it is an
    // answer, and repeating the question does not change it.
    private List<Firewall.Kind> firewallTools;
    private Firewall firewallRefusal;
    private List<Vhost> nginxSites;
    private List<com.kubo.model.network.Upstream> nginxUpstreams;
    private String nginxProblem = "";
    private Boolean fail2banInstalled;
    private List<com.kubo.model.docker.DockerNetwork> dockerNets;
    /** Whether the last read went through sudo, so a change of root state can throw the rest away by itself. */
    private Boolean rootLastRead;

    public NetworkService(SshConnection connection, CommandService commands, Privilege privilege,
                          DockerService docker) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.privilege = privilege;
        this.docker = docker;
    }

    /**
     * Throws away everything read once and kept, so the next read asks the server again.
     *
     * <p>Called on a re-read and after anything Kubo changes on the server. It is also done here, without being
     * asked, when root is turned on or off: that is the one thing that turns "only root may read this" into an
     * answer.
     */
    public void forget() {
        firewallTools = null;
        firewallRefusal = null;
        nginxSites = null;
        nginxUpstreams = null;
        nginxProblem = "";
        fail2banInstalled = null;
        dockerNets = null;
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
        boolean root = usesSudo();
        if (rootLastRead != null && rootLastRead != root) {
            forget();
        }
        rootLastRead = root;
        List<String> notes = new ArrayList<>();
        // One command for everything the machine reports. The connection runs commands one at a time, so six
        // reads meant six waits and anything asked for meanwhile queued behind them. Through sudo, because
        // ss only names a process it is allowed to read: without that every row said "not named without root",
        // including with root mode on.
        String machine = runAsRoot(LinkCommands.MACHINE, TIMEOUT).output();
        List<ListeningSocket> sockets =
                NetworkCommands.parseSockets(LinkCommands.section(machine, "sockets"));
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

        Identity identity = readIdentity(machine);
        List<Connection> connections = readConnections(sockets, notes);
        Map<String, Counter.Rate> rates = readRates(machine);
        List<Jail> jails = readJails(notes);
        if (connections.isEmpty() && !sockets.isEmpty()) {
            notes.add("No open connection was read, on a server that is listening on "
                    + sockets.size() + " ports. If that is wrong, the ss state filter is the thing to check.");
        }

        // Certificates are not read here. They change once every few months, and this runs every twelve
        // seconds. The edge card asks for them when it opens.
        return new NetworkMap(firewall, vhosts, endpoints, pm2, risks, notes, identity, connections, rates,
                jails, List.of(), Naming.of(identity, dockerNetworks()));
    }

    /**
     * Where the server sits on the network. None of this needs root.
     *
     * <p>Each reading is asked for as JSON and read from the plain output when that comes back empty, which is
     * what an iproute2 older than 2018 gives.
     */
    private Identity readIdentity(String machine) {
        List<Link> links = LinkCommands.parseLinksJson(LinkCommands.section(machine, "links"));
        if (links.isEmpty()) {
            // Only an iproute2 older than 2018 lands here, and only then does it cost a second round trip.
            links = LinkCommands.parseLinksText(run(LinkCommands.LINKS_TEXT).output());
        }
        List<Route> routes = LinkCommands.parseRoutesJson(LinkCommands.section(machine, "routes"));
        if (routes.isEmpty()) {
            routes = LinkCommands.parseRoutesText(run(LinkCommands.ROUTES_TEXT).output());
        }
        Resolvers resolvers = LinkCommands.parseResolvers(LinkCommands.resolverSections(machine));
        // The address the operator typed to get here. It is the one address known to work from outside, and it
        // costs nothing: no third party is asked, which is the whole point.
        String reachedAt = connection.target() == null ? "" : connection.target().host();
        return new Identity(links, routes, resolvers, reachedAt);
    }

    /**
     * What is connected right now. The ports this server listens on decide which end called.
     */
    private List<Connection> readConnections(List<ListeningSocket> sockets, List<String> notes) {
        java.util.Set<Integer> listening = new LinkedHashSet<>();
        for (ListeningSocket socket : sockets) {
            listening.add(socket.port());
        }
        // Root, because only root can name the process holding a connection.
        //
        // This used to read as root, find nothing, and read again as the connected user, because on a real
        // server root came back empty while the ordinary user saw everything. That was never a sudo problem:
        // the parser counted columns, and root filling in the process field moved the column it counted to.
        // With that fixed, an empty answer here means an idle server, and asking a second time would be one
        // more command every twelve seconds for a question already answered.
        CommandEntry elevated = runAsRoot(TrafficCommands.CONNECTIONS, TIMEOUT);
        List<Connection> connections = TrafficCommands.parseConnections(elevated.output(), listening);
        if (connections.isEmpty() && !elevated.success()) {
            String said = elevated.error().isBlank() ? elevated.output() : elevated.error();
            notes.add("The open connections could not be read."
                    + (said.isBlank() ? "" : " The server said: " + firstLine(said)));
        }
        return connections;
    }

    /** True when acting as root means going through sudo rather than already being root. */
    private boolean usesSudo() {
        return privilege != null && privilege.available() && !privilege.needsPassword();
    }

    private static String firstLine(String text) {
        return text == null ? "" : text.strip().lines().findFirst().orElse("");
    }

    /**
     * Bytes per second per interface, from this reading and the one before it.
     *
     * <p>Empty on the first read of a session, and after any reading where a counter went backwards. Showing
     * nothing is right there: a made-up first value would be a number the reader has no way to check.
     */
    private Map<String, Counter.Rate> readRates(String machine) {
        Instant now = Instant.now();
        Map<String, Counter> current = new HashMap<>();
        for (Counter counter : TrafficCommands.parseCounters(LinkCommands.section(machine, "counters"))) {
            current.put(counter.device(), counter);
        }
        Map<String, Counter.Rate> rates = new HashMap<>();
        if (countersReadAt != null) {
            Duration elapsed = Duration.between(countersReadAt, now);
            for (Counter counter : current.values()) {
                Counter.Rate rate = counter.since(previousCounters.get(counter.device()), elapsed);
                if (rate != Counter.Rate.NONE) {
                    rates.put(counter.device(), rate);
                }
            }
        }
        previousCounters = Map.copyOf(current);
        countersReadAt = now;
        return rates;
    }

    /**
     * The fail2ban jails. Its socket is root-owned on every install Kubo has seen, so this goes through sudo.
     */
    private List<Jail> readJails(List<String> notes) {
        // A server without fail2ban is asked once, not every cycle: what is banned changes, what is installed
        // does not.
        if (Boolean.FALSE.equals(fail2banInstalled)) {
            return List.of();
        }
        String output = runAsRoot(GuardCommands.WATCH, TIMEOUT).output();
        fail2banInstalled = GuardCommands.installed(output);
        if (!fail2banInstalled) {
            return List.of();
        }
        List<Jail> jails = GuardCommands.parseJails(output);
        if (jails.isEmpty()) {
            notes.add("fail2ban is installed but its status could not be read. Reading it needs root.");
        }
        return jails;
    }

    /**
     * The certificates Nginx points at, read once each however many server blocks share them.
     */
    public List<Certificate> certificates(List<Vhost> vhosts) {
        java.util.Set<String> files = new LinkedHashSet<>();
        for (Vhost vhost : vhosts) {
            if (!vhost.certificate().isBlank()) {
                files.add(vhost.certificate());
            }
        }
        if (files.isEmpty()) {
            return List.of();
        }
        // Private keys are root-only and so, on many installs, is the directory the certificate sits in.
        return GuardCommands.parseCertificates(
                runAsRoot(GuardCommands.certificates(List.copyOf(files)), TIMEOUT).output());
    }

    /**
     * The Docker networks, which give a bridge interface the name Docker knows it by.
     *
     * <p>A bridge is called br- followed by the first twelve characters of the network id, so this is the whole
     * of what it takes to turn br-41317674355a into the name somebody wrote in a compose file.
     *
     * <p>Read once a session: a network's subnet is fixed when the network is created and does not move after.
     */
    private List<com.kubo.model.docker.DockerNetwork> dockerNetworks() {
        if (dockerNets != null) {
            return dockerNets;
        }
        if (docker == null) {
            return List.of();
        }
        try {
            dockerNets = docker.networks();
        } catch (RuntimeException e) {
            dockerNets = List.of();
        }
        return dockerNets;
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
        // Asked as root. On plenty of servers /usr/sbin/nft and /usr/sbin/iptables are executable by root
        // only, and command -v answers nothing for anybody else. Looking as the connected user and concluding
        // "no firewall" from that is how this screen came to tell a protected server it was wide open.
        // Root said no last time. Asking again on every cycle put the same refusal in the log every twelve
        // seconds and never produced a different answer; turning root on throws this away by itself.
        if (firewallRefusal != null) {
            notes.add(firewallRefusal.problem());
            return firewallRefusal;
        }
        if (firewallTools == null) {
            CommandEntry found = runAsRoot(FirewallCommands.DETECT, TIMEOUT);
            if (!found.success()) {
                // The look itself failed. That is not the same as finding nothing, and it must not read as it.
                // Not kept: unlike a refusal, this one is worth asking again.
                notes.add(Firewall.unchecked().problem());
                return Firewall.unchecked();
            }
            firewallTools = FirewallCommands.parseTools(found.output());
        }
        // Which tool is installed was found once. What it is enforcing is read on every cycle: those are rules
        // somebody can change, including from this screen.
        List<Firewall.Kind> tools = firewallTools;
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
        if (firewall.state() == Firewall.State.UNREADABLE || firewall.state() == Firewall.State.UNRECOGNISED) {
            firewallRefusal = firewall;
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

    /**
     * The domains this server serves, read once and kept.
     *
     * <p>{@code nginx -T} prints every configuration file on the server and needs root to do it, so it is the
     * longest read on this screen. It is also a configuration: it changes when somebody edits it, not on its own.
     * Kubo asks once, keeps the answer, and asks again when the operator re-reads or changes something, which is
     * what {@link #forget()} is for.
     */
    private List<Vhost> readNginx(List<String> notes) {
        if (nginxSites == null) {
            lookForNginx();
        }
        if (!nginxProblem.isEmpty()) {
            notes.add(nginxProblem);
        }
        return nginxSites;
    }

    private void lookForNginx() {
        nginxProblem = "";
        nginxUpstreams = List.of();
        if (run(NetworkCommands.NGINX_PRESENT).output().strip().equals("NO")) {
            nginxSites = List.of();
            return;
        }
        CommandEntry entry = runAsRoot(NetworkCommands.NGINX, TIMEOUT);
        nginxUpstreams = NetworkCommands.parseUpstreams(entry.output());
        List<Vhost> vhosts = NetworkCommands.parseNginx(entry.output());
        if (vhosts.isEmpty()) {
            // `nginx -T` needs root, but the configuration files themselves are usually world-readable, so the
            // server blocks can often be had anyway. This is only a fallback: it misses whatever an include pulls
            // in from somewhere unusual, which is exactly what -T exists to resolve.
            String files = run(NetworkCommands.NGINX_FILES, TIMEOUT).output();
            vhosts = NetworkCommands.parseNginx(files);
            nginxUpstreams = NetworkCommands.parseUpstreams(files);
        }
        if (vhosts.isEmpty()) {
            nginxProblem = "Nginx is installed but only root may read its configuration. Turn on ROOT in the header "
                    + "and refresh to see which domains it serves and where it sends them.";
        }
        nginxSites = vhosts;
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

    /**
     * Opens a port to one source address instead of to the world.
     *
     * @throws IllegalArgumentException when the source is not an address
     */
    public CommandEntry allowFrom(String source, int port, Protocol protocol) {
        return runAsRoot(NetworkCommands.ufwAllowFrom(source, port, protocol), ACTION_TIMEOUT);
    }

    /** Refuses a port to one source, or to everyone when the source is empty. */
    public CommandEntry denyFrom(String source, int port, Protocol protocol) {
        return runAsRoot(NetworkCommands.ufwDenyFrom(source, port, protocol), ACTION_TIMEOUT);
    }

    /** Removes several UFW rules, highest number first. The caller re-reads afterwards: the numbers move. */
    public CommandEntry deleteRules(java.util.List<Integer> numbers) {
        return runAsRoot(NetworkCommands.ufwDeleteRules(numbers), ACTION_TIMEOUT);
    }

    /**
     * Removes a UFW rule by the number printed beside it. The caller re-reads afterwards: the numbers move.
     */
    public CommandEntry deleteRule(int number) {
        return runAsRoot(NetworkCommands.ufwDeleteRule(number), ACTION_TIMEOUT);
    }

    /**
     * Bans an address in a jail, or lifts a ban.
     *
     * @throws IllegalArgumentException when the address or the jail name is not one
     */
    public CommandEntry ban(String jail, String address) {
        return runAsRoot(GuardCommands.ban(jail, address), ACTION_TIMEOUT);
    }

    public CommandEntry unban(String jail, String address) {
        return runAsRoot(GuardCommands.unban(jail, address), ACTION_TIMEOUT);
    }

    /** Whether the Nginx configuration parses. Changes nothing. */
    public CommandEntry testNginx() {
        return runAsRoot(NetworkCommands.NGINX_TEST, ACTION_TIMEOUT);
    }

    /** The sites available and the sites enabled. */
    public CommandEntry nginxSites() {
        return runAsRoot(NetworkCommands.NGINX_SITES, TIMEOUT);
    }

    /**
     * Turns a site on, tests the configuration, and undoes the link if Nginx refuses it.
     */
    public CommandEntry enableSite(String site) {
        return runAsRoot(NetworkCommands.enableSite(site), ACTION_TIMEOUT);
    }

    public CommandEntry disableSite(String site) {
        return runAsRoot(NetworkCommands.disableSite(site), ACTION_TIMEOUT);
    }

    /**
     * One configuration file as it is written on disk, read as root because most of /etc/nginx is.
     */
    public CommandEntry nginxFile(String path) {
        return runAsRoot(NetworkCommands.readFile(path), TIMEOUT);
    }

    /**
     * The last lines of one log file, as root: Nginx writes its logs owned by root on every install Kubo has
     * seen.
     */
    public CommandEntry nginxLog(String path, int lines) {
        return runAsRoot(NetworkCommands.readLog(path, lines), TIMEOUT);
    }

    /** Every pool of servers the configuration names, from the reading already kept. */
    public List<com.kubo.model.network.Upstream> upstreams() {
        return nginxUpstreams == null ? List.of() : nginxUpstreams;
    }

    /**
     * One folder on the server, for choosing a site's directory by walking to it.
     *
     * <p>The same reading FILES.SCOPE does, so a folder reads the same wherever it is opened from. As root when
     * root is on, because the folders a web server serves from are often not the connected user's to open.
     */
    public com.kubo.model.files.DirectoryListing listFolder(String path) {
        CommandEntry entry = runAsRoot(com.kubo.files.ListingParser.command(path), TIMEOUT);
        if (!entry.ran()) {
            return com.kubo.model.files.DirectoryListing.failed(path, entry.error());
        }
        return com.kubo.files.ListingParser.parse(path, entry.output(), entry.error());
    }

    /**
     * The armour line of a certificate or key file, or empty when it has none. As root, because the files under
     * /etc/letsencrypt are root's.
     */
    public String pemArmour(String path) {
        return runAsRoot(NetworkCommands.pemArmour(path), TIMEOUT).output().strip();
    }

    /** Whether a site file of this name is already on the server. */
    public boolean siteExists(String site) {
        return runAsRoot(NetworkCommands.siteExists(site), TIMEOUT).output().strip().equals("YES");
    }

    /**
     * Creates a site: writes its file, writes the rate limit zones when it needs one, then switches it on,
     * which tests the configuration and undoes the link if Nginx refuses it.
     *
     * <p>The one thing in Kubo that writes a configuration file. Every value in that file came from a choice
     * or from a field already refused by {@link SiteDraft#problems()}, and the file itself travels on standard
     * input, so no part of it is ever read by a shell.
     *
     * @return the entry that decides it: the switch on, or whichever step failed first
     */
    public CommandEntry createSite(SiteDraft draft) {
        if (!draft.ready()) {
            throw new IllegalArgumentException(String.join(" ", draft.problems()));
        }
        if (draft.rateLimit().on()) {
            CommandEntry zones = writeAsRoot(NetworkCommands.writeRateLimits(), SiteDraft.rateLimitZones());
            if (!zones.success()) {
                return zones;
            }
        }
        CommandEntry written = writeAsRoot(NetworkCommands.writeSite(draft.fileName()), draft.toConfig());
        if (!written.success()) {
            return written;
        }
        CommandEntry on = enableSite(draft.fileName());
        if (!on.success()) {
            // The site is on disk and off. Left there on purpose: it is the thing to look at to find out why
            // Nginx refused it, and deleting somebody's file because a test failed would be worse than keeping
            // a switched-off file around.
            forget();
        }
        return on;
    }

    /** Removes a site Kubo just created, for undoing one that was refused. */
    public CommandEntry deleteSite(String site) {
        CommandEntry entry = runAsRoot(NetworkCommands.deleteSite(site), ACTION_TIMEOUT);
        forget();
        return entry;
    }

    /**
     * Runs a command that reads a file from its own standard input, as root.
     *
     * <p>The sudo password goes first and the content follows it on the same stream, which is safe only because
     * {@link Privilege#asRootReadingInput} drops any cached ticket so sudo always eats exactly one line.
     */
    private CommandEntry writeAsRoot(String command, String content) {
        byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (privilege == null) {
            throw new IllegalStateException("Writing a configuration file needs root.");
        }
        Privilege.Command wrapped = privilege.asRootReadingInput(AdminPath.with(command));
        byte[] input = wrapped.input() == null ? data : join(wrapped.input(), data);
        CommandEntry raw = connection.exec(wrapped.command(), input, ACTION_TIMEOUT);
        // The history keeps the command, as it keeps every command. It has never kept what a command was fed,
        // and this is the one place where that mattered anyway.
        CommandEntry entry = new CommandEntry(raw.executedAt(), command, true, raw.exitCode(), raw.output(),
                raw.error(), raw.duration());
        commands.add(entry, CommandService.Origin.NETWORK);
        return entry;
    }

    private static byte[] join(byte[] first, byte[] second) {
        byte[] both = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
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
