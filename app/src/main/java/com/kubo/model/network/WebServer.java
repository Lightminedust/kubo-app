package com.kubo.model.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A web server or reverse proxy found on the machine.
 *
 * <p>Kubo reads Nginx in full: its sites, its rules, its logs, and it writes new ones. It does none of that for
 * the others, and this is what lets it say so instead of saying nothing. A server running Caddy used to make
 * the screen report that no Nginx was installed, which reads as "no web server" to anybody skimming, and is the
 * same mistake the firewall check used to make: answering "I did not find what I was looking for" as though it
 * meant "there is nothing here".
 *
 * <p>So this names what is there, says where its configuration lives and gives the command that checks it. That
 * is the whole of it. Naming a thing correctly is worth a great deal more than half managing it.
 *
 * @param running whether a process by that name is up, as opposed to merely installed
 */
public record WebServer(Kind kind, boolean running) {

    /**
     * The ones worth knowing about. Each carries what Kubo can honestly say about it without reading a single
     * one of its configuration files.
     */
    public enum Kind {

        NGINX("Nginx", "/etc/nginx", "nginx -t",
                "Kubo reads its sites, its rules and its logs, and can write new ones.",
                "nginx"),

        APACHE("Apache", "/etc/apache2 or /etc/httpd", "apachectl configtest",
                "The classic, and still what most existing production servers run. Its virtual hosts sit in "
                        + "sites-available and are switched on with a2ensite, much as Nginx does it.",
                "apache2", "httpd"),

        CADDY("Caddy", "/etc/caddy/Caddyfile", "caddy validate --config /etc/caddy/Caddyfile",
                "Gets and renews its own HTTPS certificates, with no Certbot and no cron job behind it. One "
                        + "Caddyfile holds every site rather than one file each.",
                "caddy"),

        TRAEFIK("Traefik", "/etc/traefik", "traefik healthcheck",
                "Takes its routes from running containers rather than from a file, so a route appears when a "
                        + "container starts. What it is doing right now is in DOCKER.SCOPE, on the containers "
                        + "themselves.",
                "traefik"),

        HAPROXY("HAProxy", "/etc/haproxy/haproxy.cfg", "haproxy -c -f /etc/haproxy/haproxy.cfg",
                "A load balancer before it is a proxy: it spreads traffic over several machines and watches "
                        + "which of them are still answering.",
                "haproxy");

        private final String label;
        private final String configHome;
        private final String check;
        private final String what;
        private final List<String> binaries;

        Kind(String label, String configHome, String check, String what, String... binaries) {
            this.label = label;
            this.configHome = configHome;
            this.check = check;
            this.what = what;
            this.binaries = List.of(binaries);
        }

        public String label() {
            return label;
        }

        /** Where its configuration lives, for somebody who will go and look at it themselves. */
        public String configHome() {
            return configHome;
        }

        /** The command that checks its configuration, to run by hand. Kubo does not run it. */
        public String check() {
            return check;
        }

        /** One sentence on what it is, for somebody who has only heard the name. */
        public String what() {
            return what;
        }

        /** The names it goes by on disk and in the process list. Apache answers to two. */
        public List<String> binaries() {
            return binaries;
        }

        /** Only Nginx. Everything else Kubo names and leaves alone. */
        public boolean managed() {
            return this == NGINX;
        }

        /** Which kind a binary or process name belongs to. */
        public static Optional<Kind> of(String name) {
            if (name == null) {
                return Optional.empty();
            }
            String wanted = name.strip().toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.binaries.contains(wanted)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }

        /** Every name to look for, in one list, for building the command that looks. */
        public static List<String> everyBinary() {
            List<String> names = new ArrayList<>();
            for (Kind kind : values()) {
                names.addAll(kind.binaries);
            }
            return List.copyOf(names);
        }
    }

    public WebServer {
        Objects.requireNonNull(kind, "kind");
    }

    public String label() {
        return kind.label();
    }

    /** What the screen says about it in one line. */
    public String sentence() {
        return kind.label() + (running ? " is running on this machine." : " is installed but not running.");
    }

    /**
     * Reads what the detection command printed.
     *
     * <p>Two kinds of line, in any order: {@code HAVE nginx} for a binary that exists, {@code RUN nginx} for a
     * process that is up. A name nobody here recognises is passed over rather than guessed at.
     *
     * <p>Nginx comes first when it is there, because it is the one Kubo actually manages, and the rest follow
     * with whatever is running ahead of whatever is merely installed.
     */
    public static List<WebServer> parse(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        Map<Kind, Boolean> found = new LinkedHashMap<>();
        for (String line : output.lines().toList()) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length != 2) {
                continue;
            }
            // A process may be reported with its path; only the last part of it is the name.
            String name = parts[1].substring(parts[1].lastIndexOf('/') + 1);
            Optional<Kind> kind = Kind.of(name);
            if (kind.isEmpty()) {
                continue;
            }
            boolean up = parts[0].equals("RUN");
            // Installed and running are two facts about one thing: whichever line arrives, the other stands.
            found.merge(kind.get(), up, (before, now) -> before || now);
        }
        List<WebServer> servers = new ArrayList<>();
        found.forEach((kind, running) -> servers.add(new WebServer(kind, running)));
        servers.sort(Comparator.comparing((WebServer server) -> server.kind() != Kind.NGINX)
                .thenComparing(server -> !server.running())
                .thenComparing(server -> server.kind().ordinal()));
        return List.copyOf(servers);
    }

    /** Whether Nginx is among them, which is the only one the rest of this screen can work with. */
    public static boolean hasNginx(List<WebServer> servers) {
        return servers.stream().anyMatch(server -> server.kind() == Kind.NGINX);
    }

    /**
     * What to tell somebody who opened the web screen on a machine with no Nginx on it.
     *
     * @return empty when Nginx is there, or when nothing at all was found: with nothing found there is nothing
     *         to say that the empty screen does not already say
     */
    public static String noteFor(List<WebServer> servers) {
        if (servers.isEmpty() || hasNginx(servers)) {
            return "";
        }
        List<String> names = servers.stream().map(WebServer::label).toList();
        String list = names.size() == 1 ? names.get(0)
                : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
        return "This machine serves the web with " + list + ", not Nginx. Kubo reads Nginx in full and only "
                + "names the others, so it cannot show you these sites. Nothing here is broken.";
    }
}
