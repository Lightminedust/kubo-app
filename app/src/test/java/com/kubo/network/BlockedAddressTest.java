package com.kubo.network;

import com.kubo.model.network.BlockedAddress;
import com.kubo.model.network.BlockedAddress.Source;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The journal of what this server has shut out, and what it did to deserve it. */
class BlockedAddressTest {

    private static final String NEWLINE = String.valueOf((char) 10);

    /**
     * Exactly what sqlite3 prints for the two queries, taken from a database built on fail2ban's own schema.
     */
    private static final String REAL = String.join(NEWLINE,
            "@@current",
            "91.0.0.4|sshd|1758699600|600|1",
            "45.135.232.17|sshd|1758696400|600|11",
            "@@history",
            "45.135.232.17|nginx-bad|1758699900|3600|2|1",
            "91.0.0.4|sshd|1758699600|600|1|1",
            "45.135.232.17|sshd|1758696400|600|11|2");

    @Test
    void readsWhoIsBannedNowAndWhoHasBeenBefore() {
        List<BlockedAddress> all = GuardCommands.parseHistory(REAL);

        assertEquals(5, all.size());
        BlockedAddress now = all.get(0);
        assertEquals("91.0.0.4", now.address());
        assertEquals("sshd", now.jail());
        assertTrue(now.active(), "the first section is what is banned at this moment");
        assertEquals(Instant.ofEpochSecond(1758699600), now.bannedAt());
        // Banned at that moment for ten minutes.
        assertEquals(Instant.ofEpochSecond(1758700200), now.until());
        assertFalse(all.get(2).active(), "the second section is everything that ever was");
    }

    @Test
    void countsHowManyTimesAnAddressHasComeBack() {
        List<BlockedAddress> all = GuardCommands.parseHistory(REAL);
        BlockedAddress persistent = all.stream().filter(one -> one.times() == 11).findFirst().orElseThrow();

        // Eleven bans is a different thing from one, and is the whole reason for reading the database.
        assertEquals("45.135.232.17", persistent.address());
        assertTrue(persistent.repeatOffender());
        assertFalse(all.stream().filter(one -> one.address().equals("91.0.0.4"))
                .findFirst().orElseThrow().repeatOffender());
    }

    @Test
    void oneAddressInTwoJailsIsOneRow() {
        // The same address failing on SSH and on the web is two rows in fail2ban's books and one decision here.
        List<BlockedAddress> merged = BlockedAddress.merge(GuardCommands.parseHistory(REAL));

        assertEquals(2, merged.size());
        BlockedAddress both = merged.stream().filter(one -> one.address().equals("45.135.232.17"))
                .findFirst().orElseThrow();
        assertTrue(both.jail().contains("sshd") && both.jail().contains("nginx-bad"), both.jail());
        assertEquals(11, both.times(), "the largest count is the true one");
        assertTrue(both.active(), "banned in one jail is banned");
    }

    @Test
    void aFirewallRuleOutlastsABanAndSaysSo() {
        BlockedAddress ban = new BlockedAddress("1.2.3.4", "sshd", Source.FAIL2BAN,
                Instant.ofEpochSecond(1000), Instant.ofEpochSecond(1600), 1, 0, true);
        BlockedAddress rule = new BlockedAddress("1.2.3.4", "", Source.FIREWALL, null, null, 1, 0, true);

        BlockedAddress merged = BlockedAddress.merge(List.of(ban, rule)).get(0);

        assertEquals(Source.FIREWALL, merged.source(), "a rule stays until somebody takes it away");
    }

    @Test
    void theOnesThatKeepComingBackAreFirst() {
        List<BlockedAddress> sorted = new java.util.ArrayList<>(
                BlockedAddress.merge(GuardCommands.parseHistory(REAL)));
        sorted.sort(BlockedAddress.byPersistence());

        // A list of two hundred sorted by address says nothing; this way the first screen is the useful one.
        assertEquals("45.135.232.17", sorted.get(0).address());
    }

    @Test
    void addressesSortTheWayAnybodyExpects() {
        List<BlockedAddress> sorted = new java.util.ArrayList<>(List.of(
                one("100.0.0.1"), one("9.0.0.1"), one("45.135.232.17")));
        sorted.sort(BlockedAddress.byAddress());

        // Sorted as plain text, 9.0.0.1 lands after 100.0.0.1 and ranges end up scattered.
        assertEquals(List.of("9.0.0.1", "45.135.232.17", "100.0.0.1"),
                sorted.stream().map(BlockedAddress::address).toList());
    }

    @Test
    void addressesFromOnePlaceAreSeenAsOneThing() {
        List<Map.Entry<String, List<BlockedAddress>>> ranges = BlockedAddress.neighbourhoods(List.of(
                one("45.135.232.17"), one("45.135.232.18"), one("45.135.232.90"), one("8.8.8.8")));

        // Twenty addresses from one range is one place trying twenty ways.
        assertEquals("45.135.232.0/24", ranges.get(0).getKey());
        assertEquals(3, ranges.get(0).getValue().size());
        assertEquals("8.8.8.0/24", ranges.get(1).getKey());
    }

    @Test
    void aMachineThatCannotBeAskedSaysSoRatherThanLookingEmpty() {
        assertEquals(List.of(), GuardCommands.parseHistory("@@nosqlite"));
        assertTrue(GuardCommands.historyProblem("@@nosqlite").contains("sqlite3 is not installed"));
        assertTrue(GuardCommands.historyProblem("@@nodb").contains("turn on ROOT"));
        assertEquals("", GuardCommands.historyProblem(REAL));
        assertEquals(List.of(), GuardCommands.parseHistory(null));
        assertEquals(List.of(), GuardCommands.parseHistory("nonsense" + NEWLINE + "a|b"));
    }

    @Test
    void theCommandReadsWithoutWritingAndSurvivesAMissingDatabase() {
        assertTrue(GuardCommands.HISTORY.contains("-readonly"), "nothing here changes fail2ban's own books");
        assertTrue(GuardCommands.HISTORY.contains("@@nosqlite"));
        assertTrue(GuardCommands.HISTORY.contains("@@nodb"));
        assertTrue(GuardCommands.HISTORY.contains("FROM bips"), "what is banned at this moment");
        assertTrue(GuardCommands.HISTORY.contains("FROM bans"), "and everything that ever was");
    }

    private static BlockedAddress one(String address) {
        return new BlockedAddress(address, "sshd", Source.FAIL2BAN, Instant.ofEpochSecond(1000), null, 1, 0, true);
    }

    @Test
    void whatFail2banAnswersWithoutADatabaseIsStillTheFloor() {
        // A machine with no sqlite3 reports nothing from parseHistory, and the screen used to show "Nothing is
        // blocked" on a server with six addresses banned that fail2ban-client was naming perfectly well. That
        // was the worse of the two wrong answers: it looked like a server nobody was attacking.
        assertEquals(List.of(), GuardCommands.parseHistory("@@nosqlite"));

        // What the jails give has no date and no count, and merges under whatever the database adds later.
        BlockedAddress fromJail = new BlockedAddress("187.251.123.104", "sshd", Source.FAIL2BAN,
                null, null, 1, 0, true);
        BlockedAddress fromDatabase = new BlockedAddress("187.251.123.104", "sshd", Source.FAIL2BAN,
                Instant.ofEpochSecond(1758696400), Instant.ofEpochSecond(1758697000), 9, 0, true);

        BlockedAddress merged = BlockedAddress.merge(List.of(fromJail, fromDatabase)).get(0);

        assertEquals(1, BlockedAddress.merge(List.of(fromJail, fromDatabase)).size());
        assertEquals(9, merged.times(), "the count the database knows wins over the jail's bare name");
        assertEquals(Instant.ofEpochSecond(1758696400), merged.bannedAt());
        assertTrue(merged.active());

        // And on its own, a jail row is a usable row: an address, banned now, by fail2ban.
        assertTrue(fromJail.active());
        assertNull(fromJail.bannedAt());
        assertFalse(fromJail.repeatOffender());
    }
}
