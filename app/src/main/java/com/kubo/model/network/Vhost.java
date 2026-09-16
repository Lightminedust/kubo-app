package com.kubo.model.network;

import java.util.List;

/**
 * One Nginx {@code server} block: the names it answers to, the ports it listens on, and where it forwards.
 *
 * @param proxyTargets the {@code proxy_pass} destinations, e.g. "127.0.0.1:3000"
 * @param certificate  the certificate file, empty when the block serves plain HTTP
 * @param root         the directory it serves from when it serves files rather than forwarding
 */
public record Vhost(List<String> serverNames, List<Integer> listenPorts, boolean ssl, List<ProxyTarget> proxyTargets,
                    String certificate, String root) {

    /**
     * A destination Nginx forwards to.
     *
     * @param port the local port, or -1 when the target is not a local port (an upstream name, a socket…)
     */
    public record ProxyTarget(String raw, String host, int port) {

        public boolean isLocalPort() {
            return port > 0 && (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1"));
        }
    }

    public Vhost {
        serverNames = List.copyOf(serverNames == null ? List.of() : serverNames);
        listenPorts = List.copyOf(listenPorts == null ? List.of() : listenPorts);
        proxyTargets = List.copyOf(proxyTargets == null ? List.of() : proxyTargets);
        certificate = certificate == null ? "" : certificate;
        root = root == null ? "" : root;
    }

    /**
     * The name to show: the first real server name, or what it listens on when it has none.
     */
    public String title() {
        for (String name : serverNames) {
            if (!name.equals("_") && !name.isBlank()) {
                return name;
            }
        }
        return listenPorts.isEmpty() ? "default server" : "default :" + listenPorts.get(0);
    }

    public boolean forwards() {
        return !proxyTargets.isEmpty();
    }

    public String url() {
        String name = title();
        if (name.startsWith("default")) {
            return name;
        }
        return (ssl ? "https://" : "http://") + name;
    }
}
