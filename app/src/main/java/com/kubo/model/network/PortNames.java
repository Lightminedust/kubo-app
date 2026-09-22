package com.kubo.model.network;

import java.util.Map;

/**
 * What a port number usually means.
 *
 * <p>6379 is Redis to somebody who has met Redis, and a number to everybody else. The screen shows both: the
 * name so it can be read, the number because the name is a convention and not a fact. Nothing is invented for a
 * port nobody agreed on, which is most of them.
 */
public final class PortNames {

    private static final Map<Integer, String> KNOWN = Map.ofEntries(
            Map.entry(21, "FTP"),
            Map.entry(22, "SSH"),
            Map.entry(25, "SMTP"),
            Map.entry(53, "DNS"),
            Map.entry(80, "HTTP"),
            Map.entry(110, "POP3"),
            Map.entry(123, "NTP"),
            Map.entry(143, "IMAP"),
            Map.entry(443, "HTTPS"),
            Map.entry(465, "SMTP"),
            Map.entry(587, "SMTP"),
            Map.entry(993, "IMAP"),
            Map.entry(1883, "MQTT"),
            Map.entry(2375, "Docker"),
            Map.entry(2376, "Docker"),
            Map.entry(3000, "web app"),
            Map.entry(3306, "MySQL"),
            Map.entry(5432, "PostgreSQL"),
            Map.entry(5672, "RabbitMQ"),
            Map.entry(6379, "Redis"),
            Map.entry(8080, "web app"),
            Map.entry(8443, "HTTPS"),
            Map.entry(9000, "web app"),
            Map.entry(9090, "Cockpit"),
            Map.entry(9200, "Elasticsearch"),
            Map.entry(11211, "Memcached"),
            Map.entry(27017, "MongoDB"));

    private PortNames() {
    }

    /**
     * The usual name of a port, or an empty string when it has none worth showing.
     */
    public static String of(int port) {
        return KNOWN.getOrDefault(port, "");
    }

    /** "6379 Redis", or just "6379" when nothing is known about it. */
    public static String describe(int port) {
        String name = of(port);
        return name.isEmpty() ? String.valueOf(port) : port + " " + name;
    }
}
