package com.kubo.model.network;

import java.util.List;

/**
 * One Nginx {@code server} block: the names it answers to, the ports it listens on, and where it forwards.
 *
 * @param proxyTargets the {@code proxy_pass} destinations, e.g. "127.0.0.1:3000"
 * @param certificate  the certificate file, empty when the block serves plain HTTP
 * @param root         the directory it serves from when it serves files rather than forwarding
 * @param routes       the {@code location} rules inside it, in the order the file writes them
 * @param file         the configuration file this block was read out of, empty when that is not known
 * @param forcesHttps  true when the block does nothing but send visitors to the secure one
 * @param accessLog    the file this block's requests are written to, empty when the file says nothing
 * @param errorLog     the file this block's failures are written to, empty when the file says nothing
 */
public record Vhost(List<String> serverNames, List<Integer> listenPorts, boolean ssl, List<ProxyTarget> proxyTargets,
                    String certificate, String root, List<Rule> routes, String file, boolean forcesHttps,
                    String accessLog, String errorLog) {

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
        routes = List.copyOf(routes == null ? List.of() : routes);
        file = file == null ? "" : file;
        accessLog = accessLog == null ? "" : accessLog;
        errorLog = errorLog == null ? "" : errorLog;
    }

    /** The shape everything outside the parser builds: a block with no location rules read out of it. */
    public Vhost(List<String> serverNames, List<Integer> listenPorts, boolean ssl, List<ProxyTarget> proxyTargets,
                 String certificate, String root) {
        this(serverNames, listenPorts, ssl, proxyTargets, certificate, root, List.of(), "", false, "", "");
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

    /**
     * What this block is for, in one line: the thing to read before any of the detail.
     */
    public String purpose() {
        if (forcesHttps) {
            return "sends every visitor to the secure address";
        }
        if (!routes.isEmpty()) {
            return routes.size() == 1 ? "one rule" : routes.size() + " rules";
        }
        if (forwards()) {
            return "forwards to " + proxyTargets.get(0).raw();
        }
        return root.isEmpty() ? "no rule Kubo could read" : "serves files from " + root;
    }

    /** The file this came from, without the directory, for a column that has to stay narrow. */
    public String fileName() {
        int slash = file.lastIndexOf('/');
        return slash < 0 ? file : file.substring(slash + 1);
    }

    public String url() {
        String name = title();
        if (name.startsWith("default")) {
            return name;
        }
        return (ssl ? "https://" : "http://") + name;
    }
}
