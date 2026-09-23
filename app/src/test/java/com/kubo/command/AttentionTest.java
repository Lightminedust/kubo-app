package com.kubo.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttentionTest {

    @AfterEach
    void forget() {
        Attention.claim("");
    }

    @Test
    void everyScreenMayReadBeforeAnyWindowHasClaimed() {
        assertTrue(Attention.holds("NETWORK"));
        assertTrue(Attention.holds("DOCKER"));
        assertEquals("", Attention.active());
    }

    @Test
    void onlyTheScreenInFrontMayRead() {
        Attention.claim("NETWORK");

        assertTrue(Attention.holds("NETWORK"));
        assertFalse(Attention.holds("DOCKER"));
        assertFalse(Attention.holds("SCOPE"));
    }

    @Test
    void theLastWindowFocusedIsTheOneInFront() {
        Attention.claim("NETWORK");
        Attention.claim("REDIS");

        assertEquals("REDIS", Attention.active());
        assertFalse(Attention.holds("NETWORK"));
    }

    @Test
    void claimingNothingOpensItBackUpToEveryScreen() {
        Attention.claim("REDIS");
        Attention.claim(null);

        assertEquals("", Attention.active());
        assertTrue(Attention.holds("SERVICES"));
    }
}
