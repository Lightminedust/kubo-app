package com.kubo.network;

import com.kubo.model.network.Firewall;
import com.kubo.model.network.FirewallRule;
import com.kubo.model.network.ListeningSocket;
import com.kubo.model.network.PmApp;
import com.kubo.model.network.Vhost;
import com.kubo.model.server.ListeningPort.Exposure;
import com.kubo.model.server.ListeningPort.Protocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkCommandsTest {

    @Test
    void socketsAreReadWithTheirProcessAndPid() {
        String output = """
                tcp   LISTEN 0 4096   127.0.0.1:3000  0.0.0.0:*  users:(("node",pid=1234,fd=20))
                tcp   LISTEN 0 511    0.0.0.0:80      0.0.0.0:*  users:(("nginx",pid=999,fd=6),("nginx",pid=1000,fd=6))
                tcp   LISTEN 0 4096   [::]:443        [::]:*     users:(("nginx",pid=999,fd=7))
                udp   UNCONN 0 0      127.0.0.53%lo:53 0.0.0.0:* users:(("systemd-resolve",pid=500,fd=13))
                tcp   LISTEN 0 4096   0.0.0.0:27017   0.0.0.0:*
                nonsense
                """;

        List<ListeningSocket> sockets = NetworkCommands.parseSockets(output);

        assertEquals(5, sockets.size());
        ListeningSocket node = sockets.get(0);
        assertEquals(3000, node.port());
        assertEquals(1234, node.pid());
        assertEquals("node", node.processName());
        assertEquals(Exposure.LOCAL, node.exposure());

        assertEquals(Exposure.PUBLIC, sockets.get(1).exposure());
        assertEquals(999, sockets.get(1).pid());
        assertEquals(Protocol.UDP, sockets.get(3).protocol());
        // A socket ss could not attribute keeps no PID.
        assertFalse(sockets.get(4).hasPid());
        assertEquals(ListeningSocket.UNKNOWN_PID, sockets.get(4).pid());
    }

    @Test
    void pm2ApplicationsAreRead() {
        String output = """
                [{"name":"gemmie-api","pm_id":0,"pid":1234,"monit":{"memory":157286400,"cpu":2.5},
                  "pm2_env":{"status":"online","pm_uptime":1757942400000,"restart_time":3}},
                 {"name":"worker","pm_id":1,"pid":0,"monit":{"memory":0,"cpu":0},
                  "pm2_env":{"status":"errored","pm_uptime":0,"restart_time":41}}]
                """;

        List<PmApp> apps = NetworkCommands.parsePm2(output);

        assertEquals(2, apps.size());
        assertEquals("gemmie-api", apps.get(0).name());
        assertEquals(1234, apps.get(0).pid());
        assertTrue(apps.get(0).isOnline());
        assertEquals(157286400L, apps.get(0).memoryBytes());
        assertTrue(apps.get(1).hasFailed());
        assertEquals(41, apps.get(1).restarts());
    }

    @Test
    void anAbsentPm2GivesNoApplications() {
        assertTrue(NetworkCommands.parsePm2("[]").isEmpty());
        assertTrue(NetworkCommands.parsePm2("pm2: command not found").isEmpty());
        assertTrue(NetworkCommands.parsePm2("[not json").isEmpty());
    }

    @Test
    void firewallRulesAreReadWithTheirPorts() {
        String output = """
                Status: active

                     To                         Action      From
                     --                         ------      ----
                [ 1] 22/tcp                     ALLOW IN    Anywhere
                [ 2] 80,443/tcp                 ALLOW IN    Anywhere
                [ 3] 27017                      DENY IN     Anywhere
                [ 4] 22/tcp (v6)                ALLOW IN    Anywhere (v6)
                """;

        Firewall firewall = NetworkCommands.parseFirewall(output);

        assertTrue(firewall.isEnforcing());
        assertEquals(4, firewall.rules().size());
        assertEquals(List.of(22), firewall.rules().get(0).ports());
        assertEquals(List.of(80, 443), firewall.rules().get(1).ports());
        assertEquals(FirewallRule.Action.DENY, firewall.rules().get(2).action());
        assertTrue(firewall.rules().get(3).v6());
    }

    @Test
    void aFirewallThatIsOffOrUnreadableProtectsNothing() {
        assertEquals(Firewall.State.INACTIVE, NetworkCommands.parseFirewall("Status: inactive").state());
        assertEquals(Firewall.State.UNREADABLE,
                NetworkCommands.parseFirewall("ERROR: You need to be root to run this script").state());
        assertFalse(NetworkCommands.parseFirewall("Status: inactive").blocks(27017));
        assertFalse(NetworkCommands.parseFirewall("nonsense").blocks(27017));
    }

    @Test
    void anActivefirewallBlocksWhatItDoesNotOpen() {
        Firewall firewall = NetworkCommands.parseFirewall("""
                Status: active
                [ 1] 22/tcp                     ALLOW IN    Anywhere
                [ 2] 27017                      DENY IN     Anywhere
                """);

        assertFalse(firewall.blocks(22));
        assertTrue(firewall.blocks(27017));
        // No rule at all means the default incoming policy denies it.
        assertTrue(firewall.blocks(3000));
    }

    @Test
    void rulesAreReadWhateverSpacingUfwUses() {
        Firewall tight = NetworkCommands.parseFirewall("Status: active\n[ 1] 80,443/tcp ALLOW IN Anywhere\n");

        FirewallRule rule = tight.rules().get(0);
        assertEquals(FirewallRule.Action.ALLOW, rule.action());
        assertEquals(List.of(80, 443), rule.ports());
        assertEquals("Anywhere", rule.from());
        assertTrue(rule.opensToTheWorld(443));
        assertFalse(tight.blocks(443));
    }

    @Test
    void ruleDestinationsGiveTheirPorts() {
        assertEquals(List.of(22), NetworkCommands.portsIn("22/tcp"));
        assertEquals(List.of(80, 443), NetworkCommands.portsIn("80,443/tcp"));
        assertEquals(List.of(22), NetworkCommands.portsIn("192.168.1.5 22/tcp"));
        assertEquals(List.of(), NetworkCommands.portsIn("Anywhere"));
        assertEquals(List.of(6000, 6001, 6002), NetworkCommands.portsIn("6000:6002/tcp"));
        // A range too wide to list is not expanded.
        assertEquals(List.of(), NetworkCommands.portsIn("1:65535/tcp"));
    }

    @Test
    void nginxServerBlocksAreReadWithTheirProxyTargets() {
        String output = """
                # configuration file /etc/nginx/nginx.conf:
                http {
                    upstream backend {
                        server 10.0.0.5:9000;
                    }
                    server {
                        listen 80;
                        server_name gemmie.rw www.gemmie.rw;
                        return 301 https://$host$request_uri;
                    }
                    server {
                        listen 443 ssl;
                        server_name gemmie.rw;
                        ssl_certificate /etc/letsencrypt/live/gemmie.rw/fullchain.pem;
                        location / {
                            proxy_pass http://127.0.0.1:3000;
                        }
                        location /api {
                            proxy_pass http://127.0.0.1:4000/;
                        }
                    }
                }
                """;

        List<Vhost> vhosts = NetworkCommands.parseNginx(output);

        assertEquals(2, vhosts.size());
        Vhost plain = vhosts.get(0);
        assertEquals(List.of("gemmie.rw", "www.gemmie.rw"), plain.serverNames());
        assertEquals(List.of(80), plain.listenPorts());
        assertFalse(plain.forwards());

        Vhost secure = vhosts.get(1);
        assertTrue(secure.ssl());
        assertEquals("https://gemmie.rw", secure.url());
        assertEquals(List.of(443), secure.listenPorts());
        assertEquals(2, secure.proxyTargets().size());
        assertEquals(3000, secure.proxyTargets().get(0).port());
        assertTrue(secure.proxyTargets().get(0).isLocalPort());
        assertEquals(4000, secure.proxyTargets().get(1).port());
        assertTrue(secure.certificate().endsWith("fullchain.pem"));
    }

    @Test
    void proxyTargetsAreReadFromTheirUrl() {
        assertEquals(3000, NetworkCommands.target("http://127.0.0.1:3000").port());
        assertEquals(3000, NetworkCommands.target("http://127.0.0.1:3000/api/").port());
        assertEquals(80, NetworkCommands.target("http://backend").port());
        assertFalse(NetworkCommands.target("http://10.0.0.5:9000").isLocalPort());
        assertTrue(NetworkCommands.target("http://localhost:8080").isLocalPort());
    }

    @Test
    void listenDirectivesGiveTheirPort() {
        assertEquals(443, NetworkCommands.portOfListen("443"));
        assertEquals(443, NetworkCommands.portOfListen("[::]:443"));
        assertEquals(8080, NetworkCommands.portOfListen("127.0.0.1:8080"));
    }

    @Test
    void firewallActionsAreFixedTemplatesOverValidatedPorts() {
        assertEquals("ufw allow 8080/tcp", NetworkCommands.ufwAllow(8080, Protocol.TCP));
        assertEquals("ufw allow 53/udp", NetworkCommands.ufwAllow(53, Protocol.UDP));
        assertEquals("ufw --force delete allow 8080/tcp", NetworkCommands.ufwDeleteAllow(8080, Protocol.TCP));
        assertEquals("ufw deny 27017/tcp", NetworkCommands.ufwDeny(27017, Protocol.TCP));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.ufwAllow(0, Protocol.TCP));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.ufwAllow(70000, Protocol.TCP));
    }

    @Test
    void processActionsQuoteTheirNameAndRefuseDangerousPids() {
        assertEquals("pm2 restart 'gemmie-api'", NetworkCommands.pm2Restart("gemmie-api"));
        // The name is stripped, then quoted whole, so a shell metacharacter can never leave the argument.
        assertEquals("pm2 stop 'a'\\''; rm -rf /;'", NetworkCommands.pm2Stop("a'; rm -rf /; "));
        assertTrue(NetworkCommands.pm2Logs("api", 50).endsWith("--lines 50 --nostream"));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.pm2Restart(" "));

        assertEquals("kill -9 4321", NetworkCommands.kill(4321));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.kill(1));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.kill(0));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.kill(-5));
    }

    @Test
    void reloadingNginxOnlyHappensAfterItValidates() {
        assertEquals("nginx -t && systemctl reload nginx", NetworkCommands.NGINX_RELOAD);
    }
}
