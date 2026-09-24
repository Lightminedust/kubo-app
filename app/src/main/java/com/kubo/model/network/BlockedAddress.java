package com.kubo.model.network;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One address this server has shut out, and what it did to deserve it.
 *
 * <p>The screen could say which addresses are banned and nothing else. That answers the least interesting
 * question. What somebody actually wants to know, looking at a list of two hundred, is which of them keep
 * coming back, when the last wave was, and whether it is one machine or a whole range from one place.
 *
 * <p>Two things can block an address here and they are not the same. fail2ban bans it for a while because it
 * failed to log in too often, and lets it go afterwards. A firewall rule blocks it until somebody removes the
 * rule. Both end up on this list, marked for which they are.
 *
 * @param times      how many times this address has been banned, ever; 1 for a first offence
 * @param failures   how many attempts fail2ban counted, 0 when it did not say
 * @param bannedAt   when the most recent ban started, null when nothing recorded it
 * @param until      when it is let go, null for a block with no end
 */
public record BlockedAddress(String address, String jail, Source source, Instant bannedAt, Instant until,
                             int times, int failures, boolean active) {

    /** What is holding the address out. */
    public enum Source {
        /** fail2ban, which counted failures and will let go by itself. */
        FAIL2BAN("FAIL2BAN"),
        /** A firewall rule, which stays until somebody takes it away. */
        FIREWALL("FIREWALL");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public BlockedAddress {
        Objects.requireNonNull(address, "address");
        jail = jail == null ? "" : jail;
        source = source == null ? Source.FAIL2BAN : source;
    }

    /** How long is left, or null when nothing says. */
    public Duration remaining(Instant now) {
        return until == null || !until.isAfter(now) ? null : Duration.between(now, until);
    }

    /** True when this address has been shut out before and came back anyway. */
    public boolean repeatOffender() {
        return times > 1;
    }

    /**
     * How the rows are sorted by default: the ones that keep coming back first, then the most recent.
     *
     * <p>A list of two hundred sorted by address says nothing. Sorted this way, the first screen holds the
     * handful worth doing something permanent about.
     */
    public static Comparator<BlockedAddress> byPersistence() {
        return Comparator.comparingInt(BlockedAddress::times).reversed()
                .thenComparing(BlockedAddress::failures, Comparator.reverseOrder())
                .thenComparing(blocked -> blocked.bannedAt() == null ? Instant.EPOCH : blocked.bannedAt(),
                        Comparator.reverseOrder())
                .thenComparing(BlockedAddress::address);
    }

    public static Comparator<BlockedAddress> byNewest() {
        return Comparator.comparing((BlockedAddress blocked) ->
                        blocked.bannedAt() == null ? Instant.EPOCH : blocked.bannedAt(),
                Comparator.reverseOrder()).thenComparing(BlockedAddress::address);
    }

    public static Comparator<BlockedAddress> byAddress() {
        return Comparator.comparing(BlockedAddress::sortableAddress).thenComparing(BlockedAddress::address);
    }

    /**
     * The address in a form that sorts the way somebody expects.
     *
     * <p>Sorted as text, 9.0.0.1 lands after 100.0.0.1 and the ranges that belong together end up scattered.
     * Each part is padded so that they do not.
     */
    public String sortableAddress() {
        if (!address.contains(".") || address.contains(":")) {
            return address;
        }
        StringBuilder padded = new StringBuilder();
        for (String part : address.split("\\.")) {
            padded.append(String.format("%3s", part).replace(' ', '0'));
        }
        return padded.toString();
    }

    /** The first three parts of an IPv4 address: what the machines from one place share. */
    public String neighbourhood() {
        String[] parts = address.split("\\.");
        return parts.length == 4 ? parts[0] + "." + parts[1] + "." + parts[2] + ".0/24" : address;
    }

    /**
     * Addresses grouped by the range they sit in, largest group first.
     *
     * <p>Twenty addresses from one /24 is one place trying twenty ways, and is worth seeing as one thing.
     */
    public static List<Map.Entry<String, List<BlockedAddress>>> neighbourhoods(List<BlockedAddress> blocked) {
        Map<String, List<BlockedAddress>> byRange = new LinkedHashMap<>();
        for (BlockedAddress one : blocked) {
            byRange.computeIfAbsent(one.neighbourhood(), any -> new ArrayList<>()).add(one);
        }
        List<Map.Entry<String, List<BlockedAddress>>> ordered = new ArrayList<>(byRange.entrySet());
        ordered.sort(Comparator.comparingInt((Map.Entry<String, List<BlockedAddress>> entry) ->
                entry.getValue().size()).reversed().thenComparing(Map.Entry::getKey));
        return List.copyOf(ordered);
    }

    /**
     * Folds the same address seen in several jails into one row.
     *
     * <p>One address failing on SSH and on the web appears twice in fail2ban's own books. It is one address
     * and one decision, so it is one row, keeping the earliest ban, the largest counts and every jail name.
     */
    public static List<BlockedAddress> merge(List<BlockedAddress> blocked) {
        Map<String, BlockedAddress> byAddress = new LinkedHashMap<>();
        for (BlockedAddress one : blocked) {
            byAddress.merge(one.address(), one, BlockedAddress::combine);
        }
        return List.copyOf(byAddress.values());
    }

    private static BlockedAddress combine(BlockedAddress first, BlockedAddress second) {
        String jails = first.jail().equals(second.jail()) || second.jail().isEmpty() ? first.jail()
                : first.jail().isEmpty() ? second.jail() : first.jail() + ", " + second.jail();
        Instant when = first.bannedAt() == null ? second.bannedAt()
                : second.bannedAt() == null || first.bannedAt().isAfter(second.bannedAt())
                        ? first.bannedAt() : second.bannedAt();
        Instant end = first.until() == null ? second.until()
                : second.until() == null || first.until().isAfter(second.until()) ? first.until()
                        : second.until();
        return new BlockedAddress(first.address(), jails,
                // A firewall rule outlasts a fail2ban ban, so that is what the row is really saying.
                first.source() == Source.FIREWALL || second.source() == Source.FIREWALL
                        ? Source.FIREWALL : Source.FAIL2BAN,
                when, end,
                Math.max(first.times(), second.times()),
                Math.max(first.failures(), second.failures()),
                first.active() || second.active());
    }
}
