package com.kubo.model.network;

import java.util.List;
import java.util.Map;

/**
 * The whole path a request takes into the server, read in one pass and cross-referenced: the firewall at the edge,
 * the Nginx server blocks behind it, the listening ports they forward to, and the processes holding those ports.
 *
 * @param pm2          the PM2 applications, empty when PM2 is not installed
 * @param identity     where the server sits on the network: interfaces, routes, resolvers
 * @param connections  what is connected right now, in either direction
 * @param rates        bytes per second per interface, empty on the first read because a rate needs two
 * @param jails        the fail2ban jails, empty when fail2ban is absent or unreadable
 * @param certificates the TLS certificates Nginx points at
 * @param naming       turns the addresses and the interfaces into places
 */
public record NetworkMap(Firewall firewall, List<Vhost> vhosts, List<Endpoint> endpoints, List<PmApp> pm2,
                         List<Risk> risks, List<String> notes, Identity identity, List<Connection> connections,
                         Map<String, Counter.Rate> rates, List<Jail> jails, List<Certificate> certificates,
                         Naming naming) {

    public NetworkMap {
        firewall = firewall == null ? Firewall.absent() : firewall;
        vhosts = List.copyOf(vhosts == null ? List.of() : vhosts);
        endpoints = List.copyOf(endpoints == null ? List.of() : endpoints);
        pm2 = List.copyOf(pm2 == null ? List.of() : pm2);
        risks = List.copyOf(risks == null ? List.of() : risks);
        notes = List.copyOf(notes == null ? List.of() : notes);
        identity = identity == null ? Identity.empty() : identity;
        connections = List.copyOf(connections == null ? List.of() : connections);
        rates = Map.copyOf(rates == null ? Map.of() : rates);
        jails = List.copyOf(jails == null ? List.of() : jails);
        certificates = List.copyOf(certificates == null ? List.of() : certificates);
        naming = naming == null ? Naming.none() : naming;
    }

    /**
     * The shape the older half of the screen was written against, before the machine had an identity.
     */
    public NetworkMap(Firewall firewall, List<Vhost> vhosts, List<Endpoint> endpoints, List<PmApp> pm2,
                      List<Risk> risks, List<String> notes) {
        this(firewall, vhosts, endpoints, pm2, risks, notes, Identity.empty(), List.of(), Map.of(),
                List.of(), List.of(), Naming.none());
    }

    public static NetworkMap empty() {
        return new NetworkMap(Firewall.absent(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** Connections that came off the internet, which is the list worth reading first during an incident. */
    public List<Connection> fromTheInternet() {
        return connections.stream().filter(Connection::fromTheInternet).toList();
    }

    /** The connections grouped by the two ends, which is the only readable form of this list. */
    public List<Conversation> conversations() {
        return Conversation.group(connections, naming);
    }

    /** The same conversations, gathered under the service each one is on. */
    public List<Traffic> traffic() {
        return Traffic.group(conversations());
    }

    /** Every address banned right now, across every jail. */
    public long bannedAddresses() {
        return jails.stream().flatMap(jail -> jail.banned().stream()).distinct().count();
    }

    /** Certificates that have expired or are about to, worst first. */
    public List<Certificate> certificatesToWorryAbout(java.time.Instant now) {
        return certificates.stream()
                .filter(certificate -> certificate.expired(now) || certificate.expiringSoon(now)
                        || !certificate.readable())
                .sorted(java.util.Comparator.comparingLong(certificate -> certificate.daysLeft(now)))
                .toList();
    }

    public long alerts() {
        return risks.stream().filter(risk -> risk.level() == Risk.Level.ALERT).count();
    }

    public long openToTheWorld() {
        return endpoints.stream().filter(Endpoint::isOpenToTheWorld).count();
    }

    /**
     * The ports the firewall lets through from anywhere, in order.
     */
    public List<Integer> openPorts() {
        return firewall.rules().stream()
                .filter(rule -> rule.action().letsThrough() && !rule.v6())
                .flatMap(rule -> rule.ports().stream())
                .distinct()
                .sorted()
                .toList();
    }
}
