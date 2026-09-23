package com.kubo.network;

import com.kubo.model.network.Rule;
import com.kubo.model.network.SiteDraft;
import com.kubo.model.network.SiteDraft.RateLimit;
import com.kubo.model.network.SiteDraft.Security;
import com.kubo.model.network.SiteDraft.Target;
import com.kubo.model.network.Vhost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file a form turns into, and everything the form refuses to turn into a file.
 */
class SiteDraftTest {

    private static SiteDraft toPort(int port) {
        return new SiteDraft(List.of("api.gemmi-e.com"), Target.PORT, port, "", "", "", Security.PLAIN, "", "",
                false, false, false, RateLimit.NONE);
    }

    @Test
    @DisplayName("a site forwarding to a local port is written with the headers a proxy needs")
    void writesAProxy() {
        String config = toPort(8080).toConfig();

        assertTrue(config.contains("listen 80;"));
        assertTrue(config.contains("server_name api.gemmi-e.com;"));
        assertTrue(config.contains("proxy_pass http://127.0.0.1:8080;"));
        assertTrue(config.contains("proxy_set_header Host $host;"));
        assertTrue(config.contains("proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;"));
        assertFalse(config.contains("Upgrade"), "no websocket was asked for");
    }

    @Test
    @DisplayName("what it writes reads back as what was asked for, through Kubo's own parser")
    void whatItWritesItCanRead() {
        SiteDraft draft = new SiteDraft(List.of("api.gemmi-e.com", "www.api.gemmi-e.com"), Target.PORT, 8080,
                "", "", "", Security.HTTPS, "/etc/letsencrypt/live/api.gemmi-e.com/fullchain.pem",
                "/etc/letsencrypt/live/api.gemmi-e.com/privkey.pem", true, true, false, RateLimit.STRICT);

        List<Vhost> read = NetworkCommands.parseNginx(draft.toConfig());

        assertEquals(2, read.size(), "the redirect block and the secure one");
        Vhost plain = read.get(0);
        assertTrue(plain.forcesHttps());

        Vhost secure = read.get(1);
        assertTrue(secure.ssl());
        assertTrue(secure.listenPorts().contains(443));
        assertEquals(List.of("api.gemmi-e.com", "www.api.gemmi-e.com"), secure.serverNames());
        assertEquals("/etc/letsencrypt/live/api.gemmi-e.com/fullchain.pem", secure.certificate());
        assertEquals("/var/log/nginx/api.gemmi-e.com.access.log", secure.accessLog());

        assertEquals(1, secure.routes().size());
        Rule rule = secure.routes().get(0);
        assertEquals("/", rule.path());
        assertEquals(Rule.Kind.PROXY, rule.kind());
        assertEquals(8080, rule.localPort());
        assertTrue(rule.websocket(), "the websocket toggle writes both headers or it is not a websocket");
    }

    @Test
    @DisplayName("a site serving files gets a try_files, which is the line everybody forgets")
    void writesFiles() {
        SiteDraft draft = new SiteDraft(List.of("static.gemmi-e.com"), Target.FILES, 0, "/var/www/assets", "",
                "", Security.PLAIN, "", "", false, false, false, RateLimit.NONE);

        String config = draft.toConfig();
        assertTrue(config.contains("root /var/www/assets;"));
        assertTrue(config.contains("try_files $uri $uri/ =404;"));
        assertFalse(config.contains("proxy_pass"));

        Rule rule = NetworkCommands.parseNginx(config).get(0).routes().get(0);
        assertEquals(Rule.Kind.FILES, rule.kind());
        assertEquals("/var/www/assets", rule.target());
    }

    @Test
    @DisplayName("a site forwarding to a pool names the pool and not a port")
    void writesAPool() {
        SiteDraft draft = new SiteDraft(List.of("app.gemmi-e.com"), Target.UPSTREAM, 0, "", "backend_nodes",
                "", Security.PLAIN, "", "", false, false, false, RateLimit.NONE);

        assertEquals("http://backend_nodes", draft.destination());
        assertTrue(draft.toConfig().contains("proxy_pass http://backend_nodes;"));
        assertEquals(-1, NetworkCommands.parseNginx(draft.toConfig()).get(0).routes().get(0).localPort());
    }

    @Test
    @DisplayName("the rate limit points at a zone from the one fixed file, and never at a typed number")
    void writesARateLimit() {
        SiteDraft draft = new SiteDraft(List.of("login.gemmi-e.com"), Target.PORT, 3000, "", "",
                "", Security.PLAIN, "", "", false, false, false, RateLimit.TIGHT);

        assertTrue(draft.toConfig().contains("limit_req zone=kubo_tight burst=20 nodelay;"));

        String zones = SiteDraft.rateLimitZones();
        assertTrue(zones.contains("zone=kubo_tight:10m rate=2r/s;"));
        assertTrue(zones.contains("zone=kubo_strict:10m rate=10r/s;"));
        assertTrue(zones.contains("zone=kubo_gentle:10m rate=30r/s;"));
        assertFalse(zones.contains("kubo_none"), "no zone for no limit");
        assertEquals(zones, SiteDraft.rateLimitZones(), "writing it again changes nothing");
    }

    @Test
    @DisplayName("a domain that is not a domain is refused before anything is written")
    void refusesBadDomains() {
        assertTrue(problemsOf("gemmi-e.com; rm -rf /").stream().anyMatch(p -> p.startsWith("Not a domain")));
        assertTrue(problemsOf("gemmi-e.com {").stream().anyMatch(p -> p.startsWith("Not a domain")));
        assertTrue(problemsOf("gemmi e.com").stream().anyMatch(p -> p.startsWith("Not a domain")));
        assertTrue(problemsOf("$(whoami).com").stream().anyMatch(p -> p.startsWith("Not a domain")));
        assertTrue(problemsOf("-leading-hyphen.com").stream().anyMatch(p -> p.startsWith("Not a domain")));
        assertTrue(problemsOf("trailing-.com").stream().anyMatch(p -> p.startsWith("Not a domain")));
        assertTrue(problemsOf("../../etc/passwd").stream().anyMatch(p -> p.startsWith("Not a domain")));
        assertTrue(problemsOf("*.gemmi-e.com").stream().anyMatch(p -> p.startsWith("Not a domain")),
                "wildcards are for somebody editing the file by hand");
    }

    private static List<String> problemsOf(String domain) {
        return new SiteDraft(List.of(domain), Target.PORT, 3000, "", "", "", Security.PLAIN, "", "", false, false,
                false, RateLimit.NONE).problems();
    }

    @Test
    @DisplayName("a draft with no domain at all is not ready")
    void needsADomain() {
        assertFalse(SiteDraft.empty().ready());
        assertTrue(SiteDraft.empty().problems().get(0).contains("A domain is needed"));
    }

    @Test
    @DisplayName("a port outside the range of a port is refused")
    void refusesBadPorts() {
        assertFalse(toPort(0).ready());
        assertFalse(toPort(70000).ready());
        assertFalse(toPort(-1).ready());
        assertTrue(toPort(1).ready());
        assertTrue(toPort(65535).ready());
    }

    @Test
    @DisplayName("a directory with anything a shell reads in it is refused")
    void refusesBadDirectories() {
        assertFalse(files("var/www").ready(), "an absolute path or nothing");
        assertFalse(files("/var/www/a b").ready());
        assertFalse(files("/var/www; cat /etc/shadow").ready());
        assertFalse(files("").ready());
        assertTrue(files("/var/www/assets").ready());
    }

    private static SiteDraft files(String root) {
        return new SiteDraft(List.of("static.gemmi-e.com"), Target.FILES, 0, root, "", "", Security.PLAIN, "", "",
                false, false, false, RateLimit.NONE);
    }

    @Test
    @DisplayName("HTTPS without both certificate files is refused, because nginx would refuse to start")
    void refusesHttpsWithoutACertificate() {
        SiteDraft missing = new SiteDraft(List.of("api.gemmi-e.com"), Target.PORT, 8080, "", "",
                "", Security.HTTPS, "", "", false, false, false, RateLimit.NONE);

        assertFalse(missing.ready());
        assertEquals(2, missing.problems().size(), "the certificate and the key");
    }

    @Test
    @DisplayName("a websocket on a directory of files is refused rather than quietly ignored")
    void refusesAWebsocketOnFiles() {
        SiteDraft draft = new SiteDraft(List.of("static.gemmi-e.com"), Target.FILES, 0, "/var/www/assets", "",
                "", Security.PLAIN, "", "", true, false, false, RateLimit.NONE);

        assertFalse(draft.ready());
        assertTrue(draft.problems().get(0).contains("websocket"));
    }

    @Test
    @DisplayName("the file is named after what it answers to, not after a filing choice")
    void namesTheFileAfterTheDomain() {
        assertEquals("api.gemmi-e.com", toPort(8080).fileName());
        assertEquals("", SiteDraft.empty().fileName());
    }

    @Test
    @DisplayName("the same domain written twice is written once")
    void doesNotRepeatADomain() {
        SiteDraft draft = new SiteDraft(List.of("gemmi-e.com", "gemmi-e.com", "www.gemmi-e.com"), Target.PORT,
                3000, "", "", "", Security.PLAIN, "", "", false, false, false, RateLimit.NONE);

        assertTrue(draft.toConfig().contains("server_name gemmi-e.com www.gemmi-e.com;"));
    }

    @Test
    @DisplayName("nothing that reaches the file can carry a line of its own into it")
    void nothingEscapesIntoTheFile() {
        SiteDraft draft = new SiteDraft(List.of("api.gemmi-e.com"), Target.PORT, 8080, "", "", "", Security.PLAIN,
                "", "", true, true, false, RateLimit.GENTLE);

        // Every server block opens and closes, so nothing wrote a stray brace.
        String config = draft.toConfig();
        assertEquals(config.chars().filter(c -> c == '{').count(),
                config.chars().filter(c -> c == '}').count());
        assertEquals(1, NetworkCommands.parseNginx(config).size());
    }

    @Test
    @DisplayName("the summary says what the site will do before anybody agrees to it")
    void summarises() {
        SiteDraft draft = new SiteDraft(List.of("api.gemmi-e.com"), Target.PORT, 8080, "", "", "", Security.PLAIN,
                "", "", true, true, false, RateLimit.STRICT);

        String summary = draft.summary();
        assertTrue(summary.contains("api.gemmi-e.com"));
        assertTrue(summary.contains("port 8080"));
        assertTrue(summary.contains("websockets"));
        assertTrue(summary.contains("compression"));
        assertTrue(summary.contains("10 requests a second per address"));
    }
    private static SiteDraft named(String domain) {
        return new SiteDraft(List.of(domain), Target.PORT, 0, "", "", "", Security.PLAIN, "", "", false, false,
                false, RateLimit.NONE);
    }

    @Test
    @DisplayName("a single page app hands every unknown path back to index.html")
    void writesASinglePageApp() {
        SiteDraft draft = named("app.gemmi-e.com").with(SiteDraft.Preset.SPA);
        SiteDraft ready = new SiteDraft(draft.serverNames(), draft.target(), 0, "/var/www/app", "", "",
                draft.security(), "", "", draft.websocket(), draft.gzip(), draft.singlePage(), draft.rateLimit());

        String config = ready.toConfig();
        assertTrue(config.contains("try_files $uri $uri/ /index.html;"),
                "without this a refresh on any route but the first is a 404");
        assertFalse(config.contains("=404"));
        assertTrue(config.contains("root /var/www/app;"));
        assertTrue(ready.ready());
    }

    @Test
    @DisplayName("a plain static site answers an unknown path as not found")
    void writesAStaticSite() {
        SiteDraft draft = named("docs.gemmi-e.com").with(SiteDraft.Preset.STATIC);

        assertEquals(Target.FILES, draft.target());
        assertFalse(draft.singlePage());
        assertTrue(draft.gzip());
    }

    @Test
    @DisplayName("the node preset asks for the websocket headers and keeps the port")
    void theNodePresetKeepsThePort() {
        SiteDraft draft = toPort(3000).with(SiteDraft.Preset.NODE);

        assertEquals(Target.PORT, draft.target());
        assertEquals(3000, draft.port(), "a preset changes the shape, not the answers already given");
        assertTrue(draft.websocket());
        assertTrue(draft.toConfig().contains("proxy_set_header Upgrade $http_upgrade;"));
    }

    @Test
    @DisplayName("a PHP site hands .php to the FPM socket, and refuses to hand anything to a dotfile")
    void writesPhp() {
        SiteDraft preset = named("blog.gemmi-e.com").with(SiteDraft.Preset.PHP);
        assertEquals(SiteDraft.DEFAULT_PHP_SOCKET, preset.socket());

        SiteDraft draft = new SiteDraft(List.of("blog.gemmi-e.com"), Target.PHP, 0, "/var/www/blog", "",
                "/run/php/php8.2-fpm.sock", Security.PLAIN, "", "", false, true, false, RateLimit.NONE);
        String config = draft.toConfig();

        assertTrue(draft.ready());
        assertTrue(config.contains("fastcgi_pass unix:/run/php/php8.2-fpm.sock;"));
        assertTrue(config.contains("include fastcgi_params;"), "on every install, unlike the Debian snippet");
        assertTrue(config.contains("try_files $uri $uri/ /index.php?$query_string;"));
        assertTrue(config.contains("index index.php index.html;"));
        assertTrue(config.contains("deny all;"), ".env and .git are not for visitors");

        // It still parses, every brace closes, and the root comes back out.
        assertEquals(config.chars().filter(c -> c == '{').count(), config.chars().filter(c -> c == '}').count());
        assertEquals("/var/www/blog", NetworkCommands.parseNginx(config).get(0).root());
    }

    @Test
    @DisplayName("PHP without a socket, or a websocket on files, is refused")
    void refusesShapesThatDoNotFit() {
        SiteDraft noSocket = new SiteDraft(List.of("blog.gemmi-e.com"), Target.PHP, 0, "/var/www/blog", "",
                "", Security.PLAIN, "", "", false, false, false, RateLimit.NONE);
        assertTrue(noSocket.problems().stream().anyMatch(p -> p.contains("PHP-FPM socket")));

        SiteDraft socketOnPhp = new SiteDraft(List.of("blog.gemmi-e.com"), Target.PHP, 0, "/var/www/blog", "",
                "/run/php/php-fpm.sock", Security.PLAIN, "", "", true, false, false, RateLimit.NONE);
        assertFalse(socketOnPhp.ready(), "files on disk have no websocket to carry");

        SiteDraft spaOnAPort = new SiteDraft(List.of("api.gemmi-e.com"), Target.PORT, 3000, "", "", "",
                Security.PLAIN, "", "", false, false, true, RateLimit.NONE);
        assertFalse(spaOnAPort.ready(), "index.html is for a site served off disk");
    }

    @Test
    @DisplayName("a draft that is ready reads back as itself, through the parser the screen uses")
    void readsBackAsItself() {
        assertTrue(toPort(8080).readsBack());
        assertTrue(toPort(8080).with(SiteDraft.Preset.NODE).readsBack());
        assertTrue(files("/var/www/assets").readsBack());
        assertTrue(new SiteDraft(List.of("api.gemmi-e.com"), Target.PORT, 8080, "", "", "", Security.HTTPS,
                "/etc/ssl/a.pem", "/etc/ssl/a.key", true, true, false, RateLimit.STRICT).readsBack());
        assertTrue(new SiteDraft(List.of("blog.gemmi-e.com"), Target.PHP, 0, "/var/www/blog", "",
                "/run/php/php-fpm.sock", Security.PLAIN, "", "", false, false, false, RateLimit.NONE).readsBack());
    }

    @Test
    @DisplayName("a draft that is not ready never claims to read back")
    void doesNotClaimWhatItDidNotCheck() {
        assertFalse(SiteDraft.empty().readsBack());
        assertFalse(toPort(0).readsBack());
    }
}
