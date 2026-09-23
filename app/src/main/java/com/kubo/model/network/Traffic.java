package com.kubo.model.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every conversation one service is having, gathered under it.
 *
 * <p>{@link Conversation} already folds a pool of sockets between the same two ends into one line. On a busy
 * server that still leaves a flat list: nine addresses on 443, four containers on 6379, three on 5432, in one
 * column, in no order a reader can hold. The question being asked is almost never "what is 45.135.232.17 doing"
 * but "who is on 443", so that is what this is: one entry per service, the addresses folded inside it.
 *
 * @param parties the other ends, busiest first
 */
public record Traffic(int servicePort, String serviceName, String process, boolean inbound,
                      List<Conversation> parties) {

    public Traffic {
        serviceName = serviceName == null ? "" : serviceName;
        process = process == null ? "" : process;
        parties = List.copyOf(parties == null ? List.of() : parties);
    }

    /**
     * Groups conversations by the service they are on, the program holding it and the direction.
     *
     * <p>Direction is part of the key on purpose. A server that answers on 5432 and also calls out to another
     * server's 5432 is doing two different things, and putting them in one group would say it was doing one.
     */
    public static List<Traffic> group(List<Conversation> conversations) {
        Map<String, List<Conversation>> byService = new LinkedHashMap<>();
        for (Conversation conversation : conversations) {
            String key = conversation.servicePort() + "|" + conversation.process() + "|" + conversation.inbound();
            byService.computeIfAbsent(key, any -> new ArrayList<>()).add(conversation);
        }

        List<Traffic> traffic = new ArrayList<>();
        for (List<Conversation> parties : byService.values()) {
            Conversation first = parties.get(0);
            List<Conversation> sorted = new ArrayList<>(parties);
            sorted.sort(Comparator.comparingInt(Conversation::count).reversed()
                    .thenComparing(Conversation::remoteAddress));
            traffic.add(new Traffic(first.servicePort(), first.serviceName(), first.process(), first.inbound(),
                    sorted));
        }
        // What came off the internet first, then whatever is busiest. On an incident the first question is who
        // got in, and it should not be below a container pool that happens to hold more sockets.
        traffic.sort(Comparator.comparing(Traffic::fromTheInternet).reversed()
                .thenComparing(Comparator.comparingInt(Traffic::sockets).reversed())
                .thenComparingInt(Traffic::servicePort));
        return List.copyOf(traffic);
    }

    /** "443 HTTPS", or the port alone when Kubo has no name for it. */
    public String service() {
        return serviceName.isEmpty() ? String.valueOf(servicePort) : servicePort + " " + serviceName;
    }

    /** How many sockets are open across every party. */
    public int sockets() {
        return parties.stream().mapToInt(Conversation::count).sum();
    }

    /** True when at least one of these conversations came off the internet. */
    public boolean fromTheInternet() {
        return parties.stream().anyMatch(Conversation::fromTheInternet);
    }

    public boolean named() {
        return !process.isBlank();
    }

    /**
     * The whole group in one sentence: how many connections, which way the call went, and where to.
     *
     * <p>A group with one other end names it. Saying "out to one place" and making the reader open the row to
     * find out which place would be a click asking for something the line had room for.
     */
    public String sentence() {
        int sockets = sockets();
        String count = sockets == 1 ? "1 connection" : sockets + " connections";
        String where;
        if (parties.size() == 1) {
            Conversation only = parties.get(0);
            where = only.remotePlace().isEmpty() ? only.remoteAddress() : only.remotePlace();
        } else {
            where = parties.size() + " places";
        }
        return inbound ? count + " from " + where : count + " out to " + where;
    }
}
