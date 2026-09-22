package com.kubo.model.network;

import java.util.List;
import java.util.Optional;

/**
 * Where the server sits on the network: its interfaces, its routes and the resolvers it asks.
 *
 * <p>Kubo does not ask an outside service for the public address. The server contacting a third party would break
 * the one promise the whole product is sold on. What is shown instead is what the machine itself reports, plus the
 * address this very SSH session came in on, which is the address that demonstrably works from outside.
 *
 * @param reachedAt the address the operator connected to, empty when it is not known
 */
public record Identity(List<Link> links, List<Route> routes, Resolvers resolvers, String reachedAt) {

    public Identity {
        links = List.copyOf(links == null ? List.of() : links);
        routes = List.copyOf(routes == null ? List.of() : routes);
        resolvers = resolvers == null ? Resolvers.none() : resolvers;
        reachedAt = reachedAt == null ? "" : reachedAt;
    }

    public static Identity empty() {
        return new Identity(List.of(), List.of(), Resolvers.none(), "");
    }

    /** The route everything leaves by, when there is one. */
    public Optional<Route> defaultRoute() {
        return routes.stream().filter(Route::isDefault).filter(route -> !route.ipv6()).findFirst();
    }

    /** Every link except loopback, which is never what anyone is looking for here. */
    public List<Link> realLinks() {
        return links.stream().filter(link -> !link.loopback()).toList();
    }

    /** Every address on this machine the internet could reach, if nothing filters it. */
    public List<String> publicAddresses() {
        return links.stream()
                .flatMap(link -> link.reachable().stream())
                .map(Link.Address::address)
                .distinct()
                .toList();
    }
}
