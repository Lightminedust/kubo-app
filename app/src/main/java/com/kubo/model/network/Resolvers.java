package com.kubo.model.network;

import java.util.List;
import java.util.Objects;

/**
 * The DNS servers this machine asks, and where that answer came from.
 *
 * <p>Which file to believe is not obvious. On a server running systemd-resolved, {@code /etc/resolv.conf} holds
 * 127.0.0.53, the local stub, and reading only that file would report the stub as the resolver and hide the real
 * ones. So {@code resolvectl} is asked first and the file is the fallback.
 *
 * @param source where the answer was read, shown on screen so the reader can check it themselves
 */
public record Resolvers(List<String> servers, List<String> searchDomains, String source) {

    public static final String RESOLVECTL = "resolvectl";
    public static final String RESOLV_CONF = "/etc/resolv.conf";

    public Resolvers {
        servers = List.copyOf(servers == null ? List.of() : servers);
        searchDomains = List.copyOf(searchDomains == null ? List.of() : searchDomains);
        source = source == null ? "" : source;
        Objects.requireNonNull(servers, "servers");
    }

    public static Resolvers none() {
        return new Resolvers(List.of(), List.of(), "");
    }

    public boolean known() {
        return !servers.isEmpty();
    }
}
