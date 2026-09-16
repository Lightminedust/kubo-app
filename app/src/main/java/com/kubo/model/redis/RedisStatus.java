package com.kubo.model.redis;

/**
 * Where Redis was found and whether it can be used.
 *
 * @param where    a short label of the endpoint, e.g. "host" or a container name
 * @param version  the Redis version, filled once the info is read
 */
public record RedisStatus(Availability availability, Location location, String where, String version) {

    public enum Availability {
        /**
         * Redis answered PING.
         */
        USABLE,
        /**
         * Redis is there but needs a password Kubo does not have.
         */
        NEEDS_AUTH,
        /**
         * No Redis found on the host nor in a running container.
         */
        NOT_FOUND
    }

    public enum Location { HOST, DOCKER }

    public RedisStatus {
        where = where == null ? "" : where;
        version = version == null ? "" : version;
    }

    public boolean usable() {
        return availability == Availability.USABLE;
    }

    public RedisStatus withVersion(String version) {
        return new RedisStatus(availability, location, where, version);
    }
}
