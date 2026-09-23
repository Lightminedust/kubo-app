package com.kubo.network;

import com.kubo.model.network.Rule;
import com.kubo.model.network.Upstream;
import com.kubo.model.network.Vhost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading what a domain actually does: every rule under it, the pool it forwards to, and the file it lives in.
 */
class NginxRulesTest {

    /** The shape {@code nginx -T} prints: every file it read, under a line naming it. */
    private static final String CONFIG = """
            # configuration file /etc/nginx/nginx.conf:
            http {
                upstream backend_nodes {
                    least_conn;
                    server 127.0.0.1:3000 weight=2;
                    server 127.0.0.1:3001;
                    server 127.0.0.1:3002 down;
                    server 127.0.0.1:3003 backup;
                }
            }

            # configuration file /etc/nginx/sites-enabled/gemmie:
            server {
                listen 80;
                server_name gemmi-e.com www.gemmi-e.com;
                return 301 https://$host$request_uri;
            }

            server {
                listen 443 ssl;
                server_name gemmi-e.com www.gemmi-e.com;
                ssl_certificate /etc/letsencrypt/live/gemmi-e.com/fullchain.pem;
                access_log /var/log/nginx/gemmie.access.log;
                error_log /var/log/nginx/gemmie.error.log;

                location / {
                    proxy_pass http://127.0.0.1:3000;
                    proxy_set_header Host $host;
                }

                location /api/v1 {
                    proxy_pass http://backend_nodes;
                }

                location /ws {
                    proxy_pass http://127.0.0.1:8081;
                    proxy_set_header Upgrade $http_upgrade;
                    proxy_set_header Connection "upgrade";
                }

                location /static {
                    alias /var/www/assets;
                }

                location = /health {
                    return 200 'ok';
                }
            }
            """;

    private static Vhost secure() {
        return NetworkCommands.parseNginx(CONFIG).stream()
                .filter(vhost -> vhost.listenPorts().contains(443)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("every location under a domain is read, not only the first destination")
    void readsEveryRule() {
        List<Rule> rules = secure().routes();

        assertEquals(5, rules.size());
        assertEquals("/", rules.get(0).path());
        assertEquals(Rule.Kind.PROXY, rules.get(0).kind());
        assertEquals("http://127.0.0.1:3000", rules.get(0).target());
        assertTrue(rules.get(0).catchAll());
    }

    @Test
    @DisplayName("a rule that carries a websocket says so")
    void findsWebsockets() {
        List<Rule> rules = secure().routes();

        Rule sockets = rules.stream().filter(rule -> rule.path().equals("/ws")).findFirst().orElseThrow();
        assertTrue(sockets.websocket(), "Upgrade and Connection together are what a websocket needs");
        assertFalse(rules.get(0).websocket(), "Host alone is not a websocket");
    }

    @Test
    @DisplayName("each kind of rule is told apart: a proxy, a folder, an answer")
    void tellsTheKindsApart() {
        List<Rule> rules = secure().routes();

        Rule files = rules.stream().filter(rule -> rule.path().equals("/static")).findFirst().orElseThrow();
        assertEquals(Rule.Kind.FILES, files.kind());
        assertEquals("/var/www/assets", files.target());

        Rule health = rules.stream().filter(rule -> rule.path().contains("/health")).findFirst().orElseThrow();
        assertEquals(Rule.Kind.ANSWER, health.kind());
    }

    @Test
    @DisplayName("a rule pointing at a local port names it, so it can be matched to something listening")
    void namesTheLocalPort() {
        List<Rule> rules = secure().routes();

        assertEquals(3000, rules.get(0).localPort());
        assertEquals(-1, rules.stream().filter(rule -> rule.path().equals("/api/v1"))
                .findFirst().orElseThrow().localPort(), "a pool is not a port");
        assertEquals(-1, rules.stream().filter(rule -> rule.path().equals("/static"))
                .findFirst().orElseThrow().localPort());
    }

    @Test
    @DisplayName("a block that only sends visitors to the secure address says that and nothing else")
    void findsTheHttpsRedirect() {
        Vhost plain = NetworkCommands.parseNginx(CONFIG).stream()
                .filter(vhost -> vhost.listenPorts().contains(80)).findFirst().orElseThrow();

        assertTrue(plain.forcesHttps());
        assertEquals("sends every visitor to the secure address", plain.purpose());
        assertFalse(secure().forcesHttps());
    }

    @Test
    @DisplayName("each block says which file it came out of")
    void namesTheFile() {
        assertEquals("/etc/nginx/sites-enabled/gemmie", secure().file());
        assertEquals("gemmie", secure().fileName());
    }

    @Test
    @DisplayName("the log files a block writes to are read, so the screen can show that domain's own lines")
    void findsTheLogs() {
        assertEquals("/var/log/nginx/gemmie.access.log", secure().accessLog());
        assertEquals("/var/log/nginx/gemmie.error.log", secure().errorLog());
    }

    @Test
    @DisplayName("a pool of servers is read with its weights, its spares and the ones marked out of service")
    void readsUpstreams() {
        List<Upstream> pools = NetworkCommands.parseUpstreams(CONFIG);

        assertEquals(1, pools.size());
        Upstream backend = pools.get(0);
        assertEquals("backend_nodes", backend.name());
        assertEquals("least_conn", backend.method());
        assertEquals("to whichever has the fewest open connections", backend.sharing());
        assertEquals(4, backend.servers().size());
        assertEquals(2, backend.servers().get(0).weight());
        assertTrue(backend.servers().get(2).down());
        assertEquals("OUT OF SERVICE", backend.servers().get(2).state());
        assertTrue(backend.servers().get(3).backup());
        assertEquals(3, backend.working());
    }

    @Test
    @DisplayName("a configuration with no pools and no locations still reads, and says so rather than failing")
    void survivesAPlainConfiguration() {
        String plain = """
                server {
                    listen 80;
                    server_name example.com;
                    root /var/www/html;
                }
                """;

        List<Vhost> vhosts = NetworkCommands.parseNginx(plain);

        assertEquals(1, vhosts.size());
        assertTrue(vhosts.get(0).routes().isEmpty());
        assertEquals("", vhosts.get(0).file());
        assertEquals("serves files from /var/www/html", vhosts.get(0).purpose());
        assertTrue(NetworkCommands.parseUpstreams(plain).isEmpty());
    }

    @Test
    @DisplayName("the members of an upstream are not mistaken for server blocks")
    void doesNotReadUpstreamMembersAsServers() {
        assertEquals(2, NetworkCommands.parseNginx(CONFIG).size(),
                "two server blocks, whatever the upstream calls its members");
    }
    @Test
    @DisplayName("a location with no destination of its own serves the server's root, as Nginx does")
    void inheritsTheServerRoot() {
        String config = """
                server {
                    listen 80;
                    server_name docs.example.com;
                    root /var/www/docs;

                    location / {
                        try_files $uri $uri/ =404;
                    }

                    location /images {
                        root /data;
                    }

                    location /api {
                        proxy_pass http://127.0.0.1:9000;
                    }
                }
                """;

        List<Rule> rules = NetworkCommands.parseNginx(config).get(0).routes();

        assertEquals(Rule.Kind.FILES, rules.get(0).kind());
        assertEquals("/var/www/docs", rules.get(0).target(), "the server's root, not the one inside /images");
        assertEquals("/data", rules.get(1).target(), "a location's own root is its own");
        assertEquals(Rule.Kind.PROXY, rules.get(2).kind(), "a proxy is not turned into files");
    }

    @Test
    @DisplayName("with no root at the server's level, a bare location still says it has no destination")
    void doesNotInventARoot() {
        String config = """
                server {
                    listen 80;
                    location /images { root /data; }
                    location /other { expires 1d; }
                }
                """;

        List<Rule> rules = NetworkCommands.parseNginx(config).get(0).routes();

        assertEquals(Rule.Kind.UNSET, rules.get(1).kind(), "/data belongs to /images and to nobody else");
    }
}
