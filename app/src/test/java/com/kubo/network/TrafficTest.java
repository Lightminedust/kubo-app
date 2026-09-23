package com.kubo.network;

import com.kubo.model.network.Conversation;
import com.kubo.model.network.Naming;
import com.kubo.model.network.Traffic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrafficTest {

    private static Conversation from(String address, Naming.Place where, int port, String service,
                                     String process, boolean inbound, boolean internet, int count) {
        return new Conversation(address, "", where, port, service, process, inbound, internet, count);
    }

    @Test
    void addressesOnTheSameServiceBecomeOneEntry() {
        List<Traffic> traffic = Traffic.group(List.of(
                from("45.135.232.17", Naming.Place.INTERNET, 443, "HTTPS", "nginx", true, true, 2),
                from("86.15.2.9", Naming.Place.INTERNET, 443, "HTTPS", "nginx", true, true, 5),
                from("91.0.0.4", Naming.Place.INTERNET, 443, "HTTPS", "nginx", true, true, 1)));

        assertEquals(1, traffic.size());
        Traffic https = traffic.get(0);
        assertEquals("443 HTTPS", https.service());
        assertEquals(3, https.parties().size());
        assertEquals(8, https.sockets());
    }

    @Test
    void thePartiesAreSortedBusiestFirst() {
        List<Traffic> traffic = Traffic.group(List.of(
                from("45.135.232.17", Naming.Place.INTERNET, 443, "HTTPS", "nginx", true, true, 2),
                from("86.15.2.9", Naming.Place.INTERNET, 443, "HTTPS", "nginx", true, true, 5)));

        assertEquals("86.15.2.9", traffic.get(0).parties().get(0).remoteAddress());
    }

    @Test
    void thesameportInBothDirectionsIsTwoEntries() {
        List<Traffic> traffic = Traffic.group(List.of(
                from("10.0.0.7", Naming.Place.PRIVATE, 5432, "PostgreSQL", "postgres", true, false, 1),
                from("10.0.0.8", Naming.Place.PRIVATE, 5432, "PostgreSQL", "node", false, false, 3)));

        assertEquals(2, traffic.size());
    }

    @Test
    void whatCameOffTheInternetComesFirstHoweverQuietItIs() {
        List<Traffic> traffic = Traffic.group(List.of(
                from("172.19.0.2", Naming.Place.DOCKER, 6379, "Redis", "node", false, false, 25),
                from("86.15.2.9", Naming.Place.INTERNET, 22, "SSH", "sshd", true, true, 1)));

        assertEquals(22, traffic.get(0).servicePort());
        assertTrue(traffic.get(0).fromTheInternet());
        assertFalse(traffic.get(1).fromTheInternet());
    }

    @Test
    void theSentenceCountsSocketsAndPlaces() {
        Traffic one = Traffic.group(List.of(
                from("45.135.232.17", Naming.Place.INTERNET, 443, "HTTPS", "nginx", true, true, 2),
                from("86.15.2.9", Naming.Place.INTERNET, 443, "HTTPS", "nginx", true, true, 5))).get(0);
        assertEquals("7 connections from 2 places", one.sentence());

        Traffic out = Traffic.group(List.of(
                new Conversation("172.19.0.2", "Docker app_default", Naming.Place.DOCKER, 6379, "Redis",
                        "node", false, false, 14))).get(0);
        assertEquals("14 connections out to Docker app_default", out.sentence(),
                "one other end is named, rather than making the reader open the row to find out which");

        Traffic unplaced = Traffic.group(List.of(
                from("86.15.2.9", Naming.Place.INTERNET, 22, "SSH", "sshd", true, true, 1))).get(0);
        assertEquals("1 connection from 86.15.2.9", unplaced.sentence(),
                "an address nothing is known about is still better than the word place");
    }

    @Test
    void aServiceWithNoNamedProgramSaysSo() {
        Traffic traffic = Traffic.group(List.of(
                from("172.19.0.2", Naming.Place.DOCKER, 6379, "Redis", "", false, false, 3))).get(0);

        assertFalse(traffic.named());
        assertEquals("6379 Redis", traffic.service());
    }

    @Test
    void aPortKuboHasNoNameForIsTheNumberAlone() {
        Traffic traffic = Traffic.group(List.of(
                from("10.0.0.5", Naming.Place.PRIVATE, 47311, "", "node", false, false, 1))).get(0);

        assertEquals("47311", traffic.service());
    }

    @Test
    void groupingNothingGivesNothing() {
        assertTrue(Traffic.group(List.of()).isEmpty());
    }

    @Test
    void theFilterLooksAtTheAddressThePortTheServiceAndTheProgram() {
        Conversation conversation = new Conversation("172.19.0.2", "Docker app_default", Naming.Place.DOCKER,
                6379, "Redis", "node", false, false, 3);

        assertTrue(conversation.matches(""));
        assertTrue(conversation.matches("172.19"));
        assertTrue(conversation.matches("app_default"));
        assertTrue(conversation.matches("redis"));
        assertTrue(conversation.matches("node"));
        assertTrue(conversation.matches("6379"));
        assertFalse(conversation.matches("nginx"));
    }

    @Test
    void theMarkNamesThePlaceInOneWord() {
        assertEquals("INTERNET", from("1.1.1.1", Naming.Place.INTERNET, 53, "DNS", "", false, false, 1).mark());
        assertEquals("DOCKER", from("172.19.0.2", Naming.Place.DOCKER, 6379, "Redis", "", false, false, 1).mark());
        assertEquals("THIS SERVER",
                from("127.0.0.1", Naming.Place.LOOPBACK, 6379, "Redis", "", false, false, 1).mark());
        assertEquals("PRIVATE", from("10.0.0.7", Naming.Place.PRIVATE, 5432, "", "", false, false, 1).mark());
    }
}
