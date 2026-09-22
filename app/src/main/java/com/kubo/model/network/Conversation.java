package com.kubo.model.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Connections between the same two ends, counted once.
 *
 * <p>An application holding a pool of connections to Redis opens twenty five sockets and the raw list prints
 * twenty five lines that differ only by an ephemeral port number nobody will ever care about. What the reader
 * wants is the sentence underneath: this thing talks to that thing, this much.
 *
 * @param count how many sockets are open between these two ends right now
 */
public record Conversation(String remoteAddress, String remotePlace, int servicePort, String serviceName,
                           String process, boolean inbound, boolean fromTheInternet, int count) {

    public Conversation {
        remoteAddress = remoteAddress == null ? "" : remoteAddress;
        remotePlace = remotePlace == null ? "" : remotePlace;
        serviceName = serviceName == null ? "" : serviceName;
        process = process == null ? "" : process;
    }

    /**
     * Groups connections by the two ends and the service, busiest first.
     *
     * <p>The ephemeral port is deliberately not part of what makes two connections the same: it is the one
     * thing that differs between every socket of a pool, and keeping it would defeat the whole grouping.
     */
    public static List<Conversation> group(List<Connection> connections, Naming naming) {
        Map<String, Conversation> byPair = new LinkedHashMap<>();
        for (Connection connection : connections) {
            String remote = connection.remoteAddress();
            int service = connection.servicePort();
            String key = remote + "|" + service + "|" + connection.process() + "|" + connection.inbound();
            Conversation existing = byPair.get(key);
            if (existing == null) {
                byPair.put(key, new Conversation(remote, naming.place(remote), service,
                        PortNames.of(service), connection.process(), connection.inbound(),
                        connection.fromTheInternet(), 1));
            } else {
                byPair.put(key, new Conversation(existing.remoteAddress(), existing.remotePlace(),
                        existing.servicePort(), existing.serviceName(), existing.process(), existing.inbound(),
                        existing.fromTheInternet(), existing.count() + 1));
            }
        }
        List<Conversation> conversations = new ArrayList<>(byPair.values());
        conversations.sort(Comparator.comparing(Conversation::fromTheInternet).reversed()
                .thenComparing(Comparator.comparingInt(Conversation::count).reversed())
                .thenComparing(Conversation::remoteAddress));
        return List.copyOf(conversations);
    }

    /** "6379 Redis" or "6379". */
    public String service() {
        return serviceName.isEmpty() ? String.valueOf(servicePort) : servicePort + " " + serviceName;
    }

    /** The other end in words: its address, and where that address is when it is known. */
    public String remote() {
        return remotePlace.isEmpty() ? remoteAddress : remoteAddress + "  ·  " + remotePlace;
    }

    public boolean named() {
        return !process.isBlank();
    }
}
