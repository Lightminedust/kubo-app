package com.kubo.model.redis;

import java.util.List;

/**
 * The dashboard figures read from {@code redis-cli info}.
 *
 * @param usedMemory     human memory in use, e.g. "32.7M"
 * @param peakMemory     human peak memory
 * @param connectedClients how many clients are connected
 * @param opsPerSec      commands per second right now
 * @param keyspace       the databases that hold keys
 */
public record RedisInfo(String version, long uptimeSeconds, String usedMemory, String peakMemory,
                        int connectedClients, long opsPerSec, List<Db> keyspace) {

    /**
     * One database and how many keys it holds.
     */
    public record Db(int index, long keys, long expires) {
    }

    public RedisInfo {
        version = version == null ? "" : version;
        usedMemory = usedMemory == null ? "—" : usedMemory;
        peakMemory = peakMemory == null ? "—" : peakMemory;
        keyspace = List.copyOf(keyspace == null ? List.of() : keyspace);
    }

    public long totalKeys() {
        return keyspace.stream().mapToLong(Db::keys).sum();
    }
}
