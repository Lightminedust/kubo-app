package com.kubo.ui.scope;

import com.kubo.model.server.ListeningPort;
import com.kubo.model.server.ListeningPort.Exposure;
import com.kubo.model.server.ListeningPort.Protocol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One service as the user thinks of it: the same port and protocol with the same exposure,
 * even when it listens on both IPv4 and IPv6.
 */
record PortGroup(int port, Protocol protocol, Exposure exposure, List<String> addresses, String process) {

    static List<PortGroup> of(List<ListeningPort> ports) {
        Map<String, List<ListeningPort>> grouped = new LinkedHashMap<>();
        for (ListeningPort port : ports) {
            String key = port.exposure() + "/" + port.protocol() + "/" + port.port();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(port);
        }

        List<PortGroup> groups = new ArrayList<>();
        for (List<ListeningPort> members : grouped.values()) {
            ListeningPort first = members.get(0);
            String process = members.stream()
                    .map(ListeningPort::processName)
                    .filter(name -> !name.isEmpty())
                    .findFirst()
                    .orElse("");
            groups.add(new PortGroup(first.port(), first.protocol(), first.exposure(),
                    members.stream().map(ListeningPort::bindAddress).distinct().toList(), process));
        }
        // Most exposed first, then by port number.
        groups.sort(Comparator.comparing((PortGroup group) -> -group.exposure().ordinal())
                .thenComparingInt(PortGroup::port));
        return List.copyOf(groups);
    }

    String name() {
        return process.isEmpty() ? protocol.name() : process.toUpperCase();
    }
}
