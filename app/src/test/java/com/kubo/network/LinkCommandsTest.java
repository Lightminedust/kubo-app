package com.kubo.network;

import com.kubo.model.network.Link;
import com.kubo.model.network.Resolvers;
import com.kubo.model.network.Route;
import com.kubo.model.server.ListeningPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the interfaces, the routes and the resolvers off a server.
 *
 * <p>The samples are what a real VPS prints. Both shapes of every reading are covered, because the plain one is
 * the only thing an older iproute2 gives and a fallback nobody tested is a fallback that does not work.
 */
class LinkCommandsTest {

    private static final String ADDR_JSON = """
            [{"ifindex":1,"ifname":"lo","flags":["LOOPBACK","UP","LOWER_UP"],"mtu":65536,"operstate":"UNKNOWN",
              "address":"00:00:00:00:00:00","addr_info":[
                {"family":"inet","local":"127.0.0.1","prefixlen":8,"scope":"host"},
                {"family":"inet6","local":"::1","prefixlen":128,"scope":"host"}]},
             {"ifindex":2,"ifname":"eth0","flags":["BROADCAST","MULTICAST","UP","LOWER_UP"],"mtu":1500,
              "operstate":"UP","address":"96:00:02:d1:4f:aa","addr_info":[
                {"family":"inet","local":"173.212.254.237","prefixlen":24,"scope":"global"},
                {"family":"inet6","local":"2a02:c207:2044:1::1","prefixlen":64,"scope":"global"}]},
             {"ifindex":3,"ifname":"docker0","flags":["NO-CARRIER","BROADCAST","MULTICAST","UP"],"mtu":1500,
              "operstate":"DOWN","address":"02:42:1b:9c:11:02","addr_info":[
                {"family":"inet","local":"172.17.0.1","prefixlen":16,"scope":"global"}]}]
            """;

    @Test
    @DisplayName("interfaces, addresses, state, MAC and MTU come out of the JSON")
    void readsTheJson() {
        List<Link> links = LinkCommands.parseLinksJson(ADDR_JSON);

        assertEquals(List.of("lo", "eth0", "docker0"), links.stream().map(Link::name).toList());
        Link eth0 = links.get(1);
        assertEquals("UP", eth0.state());
        assertEquals("96:00:02:d1:4f:aa", eth0.mac());
        assertEquals(1500, eth0.mtu());
        assertEquals(List.of("173.212.254.237/24", "2a02:c207:2044:1::1/64"),
                eth0.addresses().stream().map(Link.Address::cidr).toList());
        assertTrue(eth0.addresses().get(1).ipv6());
    }

    @Test
    @DisplayName("an address is judged the same way a listening port's bind address is")
    void addressesAreClassified() {
        List<Link> links = LinkCommands.parseLinksJson(ADDR_JSON);

        assertEquals(ListeningPort.Exposure.LOCAL, links.get(0).addresses().get(0).exposure());
        assertEquals(ListeningPort.Exposure.PUBLIC, links.get(1).addresses().get(0).exposure());
        // The Docker bridge is private, so it must not be counted as reachable from outside.
        assertEquals(ListeningPort.Exposure.INTERNAL, links.get(2).addresses().get(0).exposure());
        assertEquals(List.of(), links.get(2).reachable());
    }

    @Test
    @DisplayName("loopback is up even though the kernel calls its state unknown")
    void loopbackCounts() {
        Link lo = LinkCommands.parseLinksJson(ADDR_JSON).get(0);
        assertTrue(lo.loopback());
        assertTrue(lo.up());
        assertFalse(LinkCommands.parseLinksJson(ADDR_JSON).get(2).up());
    }

    @Test
    @DisplayName("the plain output of an older iproute2 gives the same addresses")
    void readsThePlainOutput() {
        String output = """
                1: lo    inet 127.0.0.1/8 scope host lo\\       valid_lft forever preferred_lft forever
                2: eth0    inet 173.212.254.237/24 brd 173.212.254.255 scope global eth0\\       valid_lft forever
                2: eth0    inet6 2a02:c207:2044:1::1/64 scope global \\       valid_lft forever
                1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN mode DEFAULT group default
                2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc fq_codel state UP mode DEFAULT \
                group default qlen 1000 link/ether 96:00:02:d1:4f:aa brd ff:ff:ff:ff:ff:ff
                """;

        List<Link> links = LinkCommands.parseLinksText(output);

        assertEquals(List.of("lo", "eth0"), links.stream().map(Link::name).toList());
        Link eth0 = links.get(1);
        assertEquals(List.of("173.212.254.237/24", "2a02:c207:2044:1::1/64"),
                eth0.addresses().stream().map(Link.Address::cidr).toList());
        assertEquals("UP", eth0.state());
        assertEquals("96:00:02:d1:4f:aa", eth0.mac());
        assertEquals(1500, eth0.mtu());
    }

    @Test
    @DisplayName("an interface that is up with no address still appears")
    void unconfiguredLinksAreNotDropped() {
        String output = "3: eth1: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc fq state UP mode DEFAULT";

        List<Link> links = LinkCommands.parseLinksText(output);

        assertEquals(1, links.size());
        assertEquals("eth1", links.get(0).name());
        assertEquals(List.of(), links.get(0).addresses());
    }

    @Test
    @DisplayName("output that is not the JSON that was asked for gives nothing, not a wrong answer")
    void refusesAnythingElse() {
        assertEquals(List.of(), LinkCommands.parseLinksJson("ip: unrecognised option '-j'"));
        assertEquals(List.of(), LinkCommands.parseLinksJson(""));
        assertEquals(List.of(), LinkCommands.parseRoutesJson("Command 'ip' not found"));
    }

    @Test
    @DisplayName("the default route names the gateway and the interface everything leaves by")
    void readsRoutes() {
        String json = """
                [{"dst":"default","gateway":"173.212.254.1","dev":"eth0","metric":100},
                 {"dst":"172.17.0.0/16","dev":"docker0","prefsrc":"172.17.0.1"}]
                """;

        List<Route> routes = LinkCommands.parseRoutesJson(json);

        assertEquals(2, routes.size());
        assertTrue(routes.get(0).isDefault());
        assertEquals("173.212.254.1", routes.get(0).gateway());
        assertEquals("eth0", routes.get(0).device());
        assertEquals(100, routes.get(0).metric());
        assertFalse(routes.get(1).isDefault());
        assertEquals("172.17.0.1", routes.get(1).source());
        assertEquals(Route.NO_METRIC, routes.get(1).metric());
    }

    @Test
    @DisplayName("the plain routing table reads the same")
    void readsRoutesPlainly() {
        String output = """
                default via 173.212.254.1 dev eth0 proto static metric 100
                172.17.0.0/16 dev docker0 proto kernel scope link src 172.17.0.1
                """;

        List<Route> routes = LinkCommands.parseRoutesText(output);

        assertEquals("173.212.254.1", routes.get(0).gateway());
        assertEquals("eth0", routes.get(0).device());
        assertEquals(100, routes.get(0).metric());
        assertEquals("172.17.0.1", routes.get(1).source());
    }

    @Test
    @DisplayName("resolved is believed over the file, and its own stub is not reported as a resolver")
    void prefersResolved() {
        String output = """
                Global
                       Protocols: -LLMNR -mDNS -DNSOverTLS
                Link 2 (eth0)
                    Current Scopes: DNS
                       DNS Servers: 1.1.1.1 8.8.8.8
                        DNS Domain: example.com
                ---
                nameserver 127.0.0.53
                options edns0
                """;

        Resolvers resolvers = LinkCommands.parseResolvers(output);

        assertEquals(List.of("1.1.1.1", "8.8.8.8"), resolvers.servers());
        assertEquals(List.of("example.com"), resolvers.searchDomains());
        assertEquals(Resolvers.RESOLVECTL, resolvers.source());
    }

    @Test
    @DisplayName("a wrapped list of servers is read to the end")
    void readsWrappedLists() {
        String output = """
                Link 2 (eth0)
                       DNS Servers: 1.1.1.1
                                    8.8.8.8
                                    9.9.9.9
                ---
                """;

        assertEquals(List.of("1.1.1.1", "8.8.8.8", "9.9.9.9"), LinkCommands.parseResolvers(output).servers());
    }

    @Test
    @DisplayName("without resolved, the file answers")
    void fallsBackToTheFile() {
        String output = """
                ---
                # Managed by man:resolvconf(8)
                search lan example.com
                nameserver 192.168.1.1
                nameserver 9.9.9.9
                """;

        Resolvers resolvers = LinkCommands.parseResolvers(output);

        assertEquals(List.of("192.168.1.1", "9.9.9.9"), resolvers.servers());
        assertEquals(List.of("lan", "example.com"), resolvers.searchDomains());
        assertEquals(Resolvers.RESOLV_CONF, resolvers.source());
    }

    @Test
    @DisplayName("a machine with no resolver at all says so rather than inventing one")
    void noResolvers() {
        assertFalse(LinkCommands.parseResolvers("---").known());
        assertEquals(List.of(), LinkCommands.parseResolvers("").servers());
    }
}
