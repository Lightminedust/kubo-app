package com.kubo.model.network;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole journal of what this server has shut out, as a file somebody can keep or hand on.
 *
 * <p>A table on a screen answers what is happening now. A file answers the other questions: whether last
 * week's wave came from the same place as this one, whether the range somebody blocked by hand is still
 * trying, what to give a hosting provider when reporting abuse. None of that survives a window being closed.
 *
 * <p>Addresses and counts only. No log line and no request is in it: fail2ban's own matches can carry the
 * path somebody asked for and the credentials they tried, and a file meant to be sent to a third party is the
 * last place either belongs.
 */
public final class BlockedExport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BlockedExport() {
    }

    /**
     * @param target which server this is, for a file that will be read away from the screen
     * @param now    when it is being written, which is what "still banned" is relative to
     */
    public static Map<String, Object> of(String target, List<BlockedAddress> blocked, Instant now) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("kubo", "blocked addresses");
        root.put("note", "Addresses and counts only. No log line and no request is in this file: what a "
                + "blocked address asked for can carry a path or a credential, and this is meant to be shared.");
        root.put("target", target);
        root.put("exportedAt", now.toString());

        List<BlockedAddress> merged = BlockedAddress.merge(blocked);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("addresses", merged.size());
        totals.put("blockedNow", merged.stream().filter(BlockedAddress::active).count());
        totals.put("repeatOffenders", merged.stream().filter(BlockedAddress::repeatOffender).count());
        totals.put("byFail2ban", merged.stream()
                .filter(one -> one.source() == BlockedAddress.Source.FAIL2BAN).count());
        totals.put("byFirewall", merged.stream()
                .filter(one -> one.source() == BlockedAddress.Source.FIREWALL).count());
        root.put("totals", totals);

        List<Map<String, Object>> rows = new ArrayList<>();
        List<BlockedAddress> ordered = new ArrayList<>(merged);
        ordered.sort(BlockedAddress.byPersistence());
        for (BlockedAddress one : ordered) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("address", one.address());
            row.put("blockedBy", one.source().label());
            row.put("jail", one.jail());
            row.put("blockedNow", one.active());
            row.put("times", one.times());
            row.put("failures", one.failures());
            row.put("bannedAt", one.bannedAt() == null ? null : one.bannedAt().toString());
            row.put("until", one.until() == null ? null : one.until().toString());
            row.put("range", one.neighbourhood());
            rows.add(row);
        }
        root.put("addresses", rows);

        // The ranges, because twenty addresses from one place is one thing worth seeing as one thing.
        List<Map<String, Object>> ranges = new ArrayList<>();
        for (Map.Entry<String, List<BlockedAddress>> entry : BlockedAddress.neighbourhoods(merged)) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            Map<String, Object> range = new LinkedHashMap<>();
            range.put("range", entry.getKey());
            range.put("addresses", entry.getValue().size());
            range.put("times", entry.getValue().stream().mapToInt(BlockedAddress::times).sum());
            range.put("members", entry.getValue().stream().map(BlockedAddress::address).sorted().toList());
            ranges.add(range);
        }
        root.put("ranges", ranges);
        return root;
    }

    /** The export as text, indented to be read by a person as well as a program. */
    public static String json(String target, List<BlockedAddress> blocked, Instant now) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(of(target, blocked, now));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A map of strings and numbers could not be written as JSON", e);
        }
    }

    public static void write(String target, List<BlockedAddress> blocked, Path file, Instant now)
            throws IOException {
        Files.writeString(file, json(target, blocked, now), StandardCharsets.UTF_8);
    }
}
