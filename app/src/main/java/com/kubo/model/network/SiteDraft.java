package com.kubo.model.network;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A new Nginx site, described in the terms somebody actually has in their head, and turned into a file.
 *
 * <p>Writing a server block by hand is a semicolon away from taking a site off the internet, and the mistakes
 * are the same four every time: a missing semicolon, a proxy without the headers that let a websocket through,
 * an HTTPS block with no certificate, a root with no try_files. None of those can be made here, because none of
 * them is typed: the file is built out of choices.
 *
 * <p>Nothing typed reaches a shell and nothing typed reaches the file unchecked. Every field is matched against
 * a pattern that allows no semicolon, no brace, no quote, no newline and no space, so the worst a bad answer can
 * do is be refused by {@link #problems()} before anything is written. The file itself travels on standard input,
 * never inside a command.
 *
 * @param serverNames the domains this site answers to, the first of which names the file
 * @param target      what answers a request
 * @param port        the local port, when the target is a port
 * @param root        the directory, when the target is files
 * @param upstream    the pool's name, when the target is a pool
 */
public record SiteDraft(List<String> serverNames, Target target, int port, String root, String upstream,
                        String socket, Security security, String certificate, String certificateKey,
                        boolean websocket, boolean gzip, boolean singlePage, RateLimit rateLimit) {

    /** What answers a request. */
    public enum Target {
        /** A program listening on this machine. */
        PORT("a program on this server"),
        /** Files on disk. */
        FILES("files in a directory"),
        /** A pool of servers already named in the configuration. */
        UPSTREAM("a pool of servers"),
        /** PHP files, handed to a PHP-FPM socket. */
        PHP("PHP files");

        private final String label;

        Target(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Whether the site answers in plain HTTP or in HTTPS with a certificate that already exists. */
    public enum Security {
        /**
         * Port 80 only.
         *
         * <p>This is the shape certbot expects to find when it is run afterwards: it reads the block, adds the
         * HTTPS one and the redirect itself. Kubo does not run certbot.
         */
        PLAIN("HTTP, PORT 80", "plain HTTP on port 80"),
        /** Port 443 with the certificate files named here, and port 80 sending visitors to it. */
        HTTPS("HTTPS", "HTTPS, with a certificate this server already has");

        private final String pill;
        private final String label;

        Security(String pill, String label) {
            this.pill = pill;
            this.label = label;
        }

        /** Short enough for a row of buttons. */
        public String pill() {
            return pill;
        }

        public String label() {
            return label;
        }
    }

    /**
     * How many requests one address may make before Nginx starts refusing.
     *
     * <p>Fixed choices rather than a number somebody types, because the zone this needs lives outside a server
     * block, in a file Kubo writes whole. Three shapes cover what a small server needs, and none of them is
     * built out of anything typed.
     */
    public enum RateLimit {
        NONE("NO LIMIT", "", 0, "Anybody may ask as often as they like."),
        GENTLE("30 / SECOND", "kubo_gentle", 30, "30 requests a second per address, with 20 allowed to bunch up."),
        STRICT("10 / SECOND", "kubo_strict", 10, "10 requests a second per address, with 20 allowed to bunch up."),
        TIGHT("2 / SECOND", "kubo_tight", 2, "2 requests a second per address. For a login page.");

        private final String pill;
        private final String zone;
        private final int perSecond;
        private final String label;

        RateLimit(String pill, String zone, int perSecond, String label) {
            this.pill = pill;
            this.zone = zone;
            this.perSecond = perSecond;
            this.label = label;
        }

        /** Short enough for a row of buttons. */
        public String pill() {
            return pill;
        }

        public String zone() {
            return zone;
        }

        public int perSecond() {
            return perSecond;
        }

        public String label() {
            return label;
        }

        public boolean on() {
            return this != NONE;
        }
    }

    /**
     * A domain: labels of letters, digits and hyphens, separated by dots, no label starting or ending with a
     * hyphen. Deliberately narrower than what Nginx accepts: no wildcards, no regular expressions, no underscore
     * catch-all. Those are for somebody editing the file by hand.
     */
    private static final Pattern DOMAIN = Pattern.compile(
            "^(?!-)[A-Za-z0-9-]{1,63}(?<!-)(\\.(?!-)[A-Za-z0-9-]{1,63}(?<!-))*$");

    /** An absolute path with nothing in it a shell or a configuration file would read as punctuation. */
    private static final Pattern PATH = Pattern.compile("^/[A-Za-z0-9._/@+-]{1,255}$");

    /** An upstream name, as Nginx allows and as {@code parseUpstreams} reads them back. */
    private static final Pattern UPSTREAM = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}$");

    public SiteDraft {
        serverNames = List.copyOf(serverNames == null ? List.of() : serverNames);
        target = target == null ? Target.PORT : target;
        root = root == null ? "" : root.strip();
        upstream = upstream == null ? "" : upstream.strip();
        socket = socket == null ? "" : socket.strip();
        security = security == null ? Security.PLAIN : security;
        certificate = certificate == null ? "" : certificate.strip();
        certificateKey = certificateKey == null ? "" : certificateKey.strip();
        rateLimit = rateLimit == null ? RateLimit.NONE : rateLimit;
    }

    /** An empty draft, for a form that has just opened. */
    public static SiteDraft empty() {
        return new SiteDraft(List.of(), Target.PORT, 0, "", "", "", Security.PLAIN, "", "", false, false,
                false, RateLimit.NONE);
    }

    /**
     * The shapes a site usually has, as one click each.
     *
     * <p>Not templates in the sense of text somebody edits afterwards. Each one is a set of answers to the same
     * questions, and every answer stays visible and changeable underneath, so nothing is hidden behind a name.
     */
    public enum Preset {
        NODE("NODE OR REACT APP",
                "A program on a port, with the two headers a websocket needs to survive the proxy."),
        SPA("SINGLE PAGE APP",
                "Files on disk, with every unknown path handed back to index.html. Without that, a refresh on "
                        + "any route but the first gives a 404."),
        STATIC("PLAIN STATIC SITE",
                "Files on disk, with an unknown path answered as not found."),
        PHP("PHP OR WORDPRESS",
                "Files on disk, with .php handed to PHP-FPM over its socket.");

        private final String title;
        private final String why;

        Preset(String title, String why) {
            this.title = title;
            this.why = why;
        }

        public String title() {
            return title;
        }

        public String why() {
            return why;
        }
    }

    /**
     * This draft with a preset's answers applied, keeping the domain and everything the preset says nothing
     * about.
     */
    public SiteDraft with(Preset preset) {
        return switch (preset) {
            case NODE -> new SiteDraft(serverNames, Target.PORT, port, root, upstream, socket, security,
                    certificate, certificateKey, true, gzip, false, rateLimit);
            case SPA -> new SiteDraft(serverNames, Target.FILES, port, root, upstream, socket, security,
                    certificate, certificateKey, false, true, true, rateLimit);
            case STATIC -> new SiteDraft(serverNames, Target.FILES, port, root, upstream, socket, security,
                    certificate, certificateKey, false, true, false, rateLimit);
            case PHP -> new SiteDraft(serverNames, Target.PHP, port, root, upstream,
                    socket.isEmpty() ? DEFAULT_PHP_SOCKET : socket, security, certificate, certificateKey,
                    false, gzip, false, rateLimit);
        };
    }

    /**
     * The socket Debian and Ubuntu put PHP-FPM on. A starting point, not an answer: the version in the name
     * changes with the distribution, and the field stays there to be corrected.
     */
    public static final String DEFAULT_PHP_SOCKET = "/run/php/php-fpm.sock";

    /**
     * Everything wrong with this draft, in the order somebody would fix it. Empty when it is ready to write.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (serverNames.isEmpty()) {
            problems.add("A domain is needed. It is what a visitor types.");
        }
        for (String name : serverNames) {
            if (!DOMAIN.matcher(name).matches()) {
                problems.add("Not a domain: " + name);
            }
            if (name.length() > 253) {
                problems.add("That domain is longer than a domain can be: " + name);
            }
        }
        switch (target) {
            case PORT -> {
                if (port < 1 || port > 65535) {
                    problems.add("A port is a number between 1 and 65535.");
                }
            }
            case FILES -> {
                if (!PATH.matcher(root).matches()) {
                    problems.add("The directory has to be an absolute path, with no spaces or punctuation: "
                            + (root.isEmpty() ? "nothing given" : root));
                }
            }
            case UPSTREAM -> {
                if (!UPSTREAM.matcher(upstream).matches()) {
                    problems.add("Not the name of a pool: " + (upstream.isEmpty() ? "nothing given" : upstream));
                }
            }
            case PHP -> {
                if (!PATH.matcher(root).matches()) {
                    problems.add("PHP files need a directory: an absolute path, with no spaces or punctuation.");
                }
                if (!PATH.matcher(socket).matches()) {
                    problems.add("The PHP-FPM socket has to be an absolute path, such as "
                            + DEFAULT_PHP_SOCKET + ". Its version number changes with the distribution.");
                }
            }
        }
        if (security == Security.HTTPS) {
            if (!PATH.matcher(certificate).matches()) {
                problems.add("The certificate has to be an absolute path to a file this server already has.");
            }
            if (!PATH.matcher(certificateKey).matches()) {
                problems.add("The private key has to be an absolute path to a file this server already has.");
            }
        }
        if (websocket && (target == Target.FILES || target == Target.PHP)) {
            problems.add("Files on disk have no websocket to carry.");
        }
        if (singlePage && target != Target.FILES) {
            problems.add("Handing unknown paths back to index.html is for a site served off disk.");
        }
        return List.copyOf(problems);
    }

    public boolean ready() {
        return problems().isEmpty();
    }

    /**
     * The file name this site gets in sites-available: its first domain.
     *
     * <p>The name of the file is an administrator's filing choice everywhere else in Kubo, and reading someone
     * else's choices is exactly what makes this screen hard. A new one is named after what it answers to.
     */
    public String fileName() {
        return serverNames.isEmpty() ? "" : serverNames.get(0);
    }

    /** The domains, deduplicated and in order, as one line. */
    private String names() {
        Set<String> unique = new LinkedHashSet<>(serverNames);
        return String.join(" ", unique);
    }

    /** Where a request goes, as the value of a proxy_pass, or empty when this site serves files. */
    public String destination() {
        return switch (target) {
            case PORT -> "http://127.0.0.1:" + port;
            case UPSTREAM -> "http://" + upstream;
            case FILES, PHP -> "";
        };
    }

    /**
     * The whole file, as it will be written.
     *
     * <p>Shown before anything is written, because nobody should agree to a change to their web server on the
     * strength of a form.
     */
    public String toConfig() {
        StringBuilder text = new StringBuilder();
        text.append("# Written by Kubo. Every line of it came from a choice on the NEW SITE form.\n");
        text.append("# Edit it by hand from here on: Kubo will read it back but will not rewrite it.\n\n");

        if (security == Security.HTTPS) {
            text.append("server {\n");
            text.append("    listen 80;\n");
            text.append("    listen [::]:80;\n");
            text.append("    server_name ").append(names()).append(";\n");
            text.append("    return 301 https://$host$request_uri;\n");
            text.append("}\n\n");
        }

        text.append("server {\n");
        if (security == Security.HTTPS) {
            text.append("    listen 443 ssl;\n");
            text.append("    listen [::]:443 ssl;\n");
        } else {
            text.append("    listen 80;\n");
            text.append("    listen [::]:80;\n");
        }
        text.append("    server_name ").append(names()).append(";\n");

        if (security == Security.HTTPS) {
            text.append('\n');
            text.append("    ssl_certificate ").append(certificate).append(";\n");
            text.append("    ssl_certificate_key ").append(certificateKey).append(";\n");
        }

        text.append('\n');
        text.append("    access_log /var/log/nginx/").append(fileName()).append(".access.log;\n");
        text.append("    error_log /var/log/nginx/").append(fileName()).append(".error.log;\n");

        if (gzip) {
            text.append('\n');
            text.append("    gzip on;\n");
            text.append("    gzip_vary on;\n");
            text.append("    gzip_min_length 1024;\n");
            text.append("    gzip_types text/plain text/css text/xml application/json "
                    + "application/javascript image/svg+xml;\n");
        }

        if (target == Target.FILES || target == Target.PHP) {
            text.append('\n');
            text.append("    root ").append(root).append(";\n");
            text.append("    index ").append(target == Target.PHP ? "index.php index.html" : "index.html")
                    .append(";\n");
        }

        text.append('\n');
        text.append("    location / {\n");
        if (rateLimit.on()) {
            text.append("        limit_req zone=").append(rateLimit.zone()).append(" burst=20 nodelay;\n");
        }
        if (target == Target.FILES) {
            // A single page application answers its own routing, so a path it does not know is index.html and
            // not a 404. Getting this wrong is why a refresh works on the first page and nowhere else.
            text.append("        try_files $uri $uri/ ")
                    .append(singlePage ? "/index.html" : "=404").append(";\n");
        } else if (target == Target.PHP) {
            text.append("        try_files $uri $uri/ /index.php?$query_string;\n");
        } else {
            text.append("        proxy_pass ").append(destination()).append(";\n");
            text.append("        proxy_http_version 1.1;\n");
            text.append("        proxy_set_header Host $host;\n");
            text.append("        proxy_set_header X-Real-IP $remote_addr;\n");
            text.append("        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n");
            text.append("        proxy_set_header X-Forwarded-Proto $scheme;\n");
            if (websocket) {
                text.append("        proxy_set_header Upgrade $http_upgrade;\n");
                text.append("        proxy_set_header Connection \"upgrade\";\n");
                text.append("        proxy_read_timeout 86400s;\n");
            }
        }
        text.append("    }\n");

        if (target == Target.PHP) {
            text.append('\n');
            text.append("    location ~ \\.php$ {\n");
            text.append("        fastcgi_split_path_info ^(.+\\.php)(/.+)$;\n");
            text.append("        fastcgi_pass unix:").append(socket).append(";\n");
            text.append("        fastcgi_index index.php;\n");
            // fastcgi_params is on every Nginx install; the Debian snippet is not.
            text.append("        include fastcgi_params;\n");
            text.append("        fastcgi_param SCRIPT_FILENAME $document_root$fastcgi_script_name;\n");
            text.append("    }\n");
            text.append('\n');
            text.append("    location ~ /\\. {\n");
            text.append("        deny all;\n");
            text.append("    }\n");
        }

        text.append("}\n");
        return text.toString();
    }

    /**
     * Whether Kubo's own reader gets back out of this file what was put into it.
     *
     * <p>Not {@code nginx -t}: nothing has been written, so nginx has nothing to test. This is the strongest
     * check there is before writing, and it is a real one — the same parser the rest of the screen uses reads
     * the text and has to find the same domains and the same destination. It says what it checks and nothing
     * more.
     */
    public boolean readsBack() {
        if (!ready()) {
            return false;
        }
        List<Vhost> blocks;
        try {
            blocks = com.kubo.network.NetworkCommands.parseNginx(toConfig());
        } catch (RuntimeException unreadable) {
            return false;
        }
        Vhost serving = blocks.stream().filter(block -> !block.forcesHttps()).reduce((a, b) -> b).orElse(null);
        if (serving == null || !serving.serverNames().equals(List.copyOf(new LinkedHashSet<>(serverNames)))) {
            return false;
        }
        if (security == Security.HTTPS && !serving.certificate().equals(certificate)) {
            return false;
        }
        return switch (target) {
            case PORT, UPSTREAM -> serving.routes().stream()
                    .anyMatch(rule -> rule.kind() == Rule.Kind.PROXY && rule.target().equals(destination())
                            && rule.websocket() == websocket);
            case FILES, PHP -> serving.root().equals(root);
        };
    }

    /**
     * The file holding the rate limit zones, written whole and identically every time.
     *
     * <p>A {@code limit_req_zone} cannot live in a server block, so it cannot live in the site's own file. This
     * is the one other file Kubo writes, it has no field of anyone's in it, and writing it again changes
     * nothing.
     */
    public static String rateLimitZones() {
        StringBuilder text = new StringBuilder();
        text.append("# Written by Kubo. The zones a site can point its limit_req at.\n");
        text.append("# Fixed content: nothing on any form reaches this file.\n");
        for (RateLimit limit : RateLimit.values()) {
            if (!limit.on()) {
                continue;
            }
            text.append("limit_req_zone $binary_remote_addr zone=").append(limit.zone())
                    .append(":10m rate=").append(limit.perSecond()).append("r/s;\n");
        }
        return text.toString();
    }

    /** What this site will do, in one sentence, for the line above the CREATE button. */
    public String summary() {
        String where = switch (target) {
            case PORT -> "the program on port " + port;
            case FILES -> "the files in " + root;
            case UPSTREAM -> "the pool " + upstream;
            case PHP -> "the PHP in " + root;
        };
        List<String> extras = new ArrayList<>();
        if (websocket) {
            extras.add("websockets");
        }
        if (singlePage) {
            extras.add("unknown paths to index.html");
        }
        if (gzip) {
            extras.add("compression");
        }
        if (rateLimit.on()) {
            extras.add(rateLimit.label());
        }
        String tail = extras.isEmpty() ? "" : "  ·  " + String.join(", ", extras);
        return names().toLowerCase(Locale.ROOT) + " answers from " + where
                + "  ·  " + security.label() + tail;
    }
}
