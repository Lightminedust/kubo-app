package com.kubo.model.network;

import java.util.List;

/**
 * The whole path a request takes into the server, read in one pass and cross-referenced: the firewall at the edge,
 * the Nginx server blocks behind it, the listening ports they forward to, and the processes holding those ports.
 *
 * @param pm2 the PM2 applications, empty when PM2 is not installed
 */
public record NetworkMap(Firewall firewall, List<Vhost> vhosts, List<Endpoint> endpoints, List<PmApp> pm2,
                         List<Risk> risks, List<String> notes) {

    public NetworkMap {
        firewall = firewall == null ? Firewall.absent() : firewall;
        vhosts = List.copyOf(vhosts == null ? List.of() : vhosts);
        endpoints = List.copyOf(endpoints == null ? List.of() : endpoints);
        pm2 = List.copyOf(pm2 == null ? List.of() : pm2);
        risks = List.copyOf(risks == null ? List.of() : risks);
        notes = List.copyOf(notes == null ? List.of() : notes);
    }

    public static NetworkMap empty() {
        return new NetworkMap(Firewall.absent(), List.of(), List.of(), List.of(), List.of(), List.of());
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
