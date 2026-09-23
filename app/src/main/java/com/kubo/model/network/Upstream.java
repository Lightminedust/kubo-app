package com.kubo.model.network;

import java.util.List;

/**
 * A named pool of servers Nginx spreads requests across.
 *
 * <p>An application that outgrew one process runs on three ports, and the site forwards to a name rather than to
 * a port. Without this the screen showed {@code proxy_pass http://backend} and stopped there, which names the
 * pool and hides every machine in it.
 *
 * @param method how requests are shared out, empty when the file says nothing and Nginx uses each server in turn
 */
public record Upstream(String name, String method, List<Member> servers) {

    /**
     * One server in the pool.
     *
     * @param weight how many requests this one takes for each one its neighbours take, 1 unless the file says
     * @param down   true when the file marks it as out of service, so nothing is sent to it
     */
    public record Member(String address, int weight, boolean down, boolean backup) {

        public Member {
            address = address == null ? "" : address.strip();
            weight = weight < 1 ? 1 : weight;
        }

        /** What this member is doing, in a word. */
        public String state() {
            if (down) {
                return "OUT OF SERVICE";
            }
            return backup ? "BACKUP" : "IN USE";
        }
    }

    public Upstream {
        name = name == null ? "" : name.strip();
        method = method == null ? "" : method.strip();
        servers = List.copyOf(servers == null ? List.of() : servers);
    }

    /** How requests are shared, in words a reader can act on. */
    public String sharing() {
        return switch (method) {
            case "least_conn" -> "to whichever has the fewest open connections";
            case "ip_hash" -> "the same visitor always to the same server";
            case "random" -> "at random";
            case "" -> "each server in turn";
            default -> method;
        };
    }

    /** Servers that are taking requests right now, as the file has it. */
    public long working() {
        return servers.stream().filter(member -> !member.down()).count();
    }
}
