package com.kubo.model.network;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Something about the network that deserves the user's attention, worded plainly enough to act on.
 */
public record Risk(Level level, int port, String title, String detail) {

    public enum Level {
        /** Reachable from the internet and holding data or administration: act now. */
        ALERT,
        /** Worth knowing, but not necessarily wrong. */
        NOTE
    }

    /**
     * Ports whose services are never meant to face the internet. Exposing one of these usually means the service
     * was started without binding it to loopback.
     */
    private static final Map<Integer, String> SENSITIVE = Map.ofEntries(
            Map.entry(27017, "MongoDB"), Map.entry(27018, "MongoDB"),
            Map.entry(3306, "MySQL / MariaDB"), Map.entry(5432, "PostgreSQL"),
            Map.entry(6379, "Redis"), Map.entry(11211, "Memcached"),
            Map.entry(9200, "Elasticsearch"), Map.entry(9300, "Elasticsearch"),
            Map.entry(5672, "RabbitMQ"), Map.entry(15672, "RabbitMQ management"),
            Map.entry(2375, "Docker API, unencrypted"), Map.entry(2376, "Docker API"),
            Map.entry(9042, "Cassandra"), Map.entry(7474, "Neo4j"), Map.entry(8086, "InfluxDB"),
            Map.entry(5984, "CouchDB"), Map.entry(1433, "SQL Server"), Map.entry(1521, "Oracle"));

    public Risk {
        Objects.requireNonNull(level, "level");
        title = title == null ? "" : title;
        detail = detail == null ? "" : detail;
    }

    /**
     * Judges one endpoint. Returns nothing when there is nothing worth saying.
     */
    public static java.util.Optional<Risk> of(Endpoint endpoint) {
        if (!endpoint.isOpenToTheWorld()) {
            return java.util.Optional.empty();
        }
        int port = endpoint.port();
        String service = SENSITIVE.get(port);
        if (service != null) {
            return java.util.Optional.of(new Risk(Level.ALERT, port,
                    service + " is open to the internet on port " + port,
                    "It listens on " + endpoint.socket().bindAddress() + ", which means every interface, and no "
                            + "firewall rule stops it. Bind it to 127.0.0.1 instead, or close the port below."));
        }
        if (port == 22) {
            return java.util.Optional.of(new Risk(Level.NOTE, port,
                    "SSH is reachable from the internet",
                    "That is how you are connected right now. Keep it on keys rather than a password."));
        }
        if (port == 80 || port == 443 || port == 8080 || port == 8443) {
            return java.util.Optional.empty();
        }
        String owner = endpoint.owner().label();
        return java.util.Optional.of(new Risk(Level.NOTE, port,
                "Port " + port + " is open to the internet",
                (owner.isBlank() ? "A process" : owner) + " listens on every interface and no firewall rule "
                        + "stops it. If it is only used by this server, bind it to 127.0.0.1."));
    }

    /**
     * True when this port carries a service that should never face the internet.
     */
    public static boolean isSensitive(int port) {
        return SENSITIVE.containsKey(port);
    }

    public static String serviceOn(int port) {
        return SENSITIVE.getOrDefault(port, "");
    }

    public String label() {
        return level == Level.ALERT ? "ALERT" : "NOTE";
    }

    public String shortTitle() {
        return title.toUpperCase(Locale.ROOT);
    }
}
