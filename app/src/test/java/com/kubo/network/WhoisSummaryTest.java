package com.kubo.network;

import com.kubo.model.network.WhoisSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Three lines out of forty, from whichever registry answered. */
class WhoisSummaryTest {

    @Test
    @DisplayName("RIPE writes its keys in lower case with hyphens")
    void readsRipe() {
        WhoisSummary summary = WhoisSummary.parse("""
                % This is the RIPE Database query service.
                inetnum:        159.41.0.0 - 159.41.255.255
                netname:        HETZNER-CLOUD
                descr:          Hetzner Cloud
                country:        DE
                org-name:       Hetzner Online GmbH
                """);

        assertEquals("DE", summary.country());
        assertEquals("Germany", summary.countryName());
        assertEquals("Hetzner Online GmbH", summary.owner(), "the organisation before the description");
        assertEquals("159.41.0.0 - 159.41.255.255", summary.block());
        assertEquals("Germany  ·  Hetzner Online GmbH  ·  159.41.0.0 - 159.41.255.255", summary.line());
    }

    @Test
    @DisplayName("ARIN writes them capitalised, and CIDR is preferred to the range")
    void readsArin() {
        WhoisSummary summary = WhoisSummary.parse("""
                NetRange:       45.79.0.0 - 45.79.255.255
                CIDR:           45.79.0.0/16
                NetName:        LINODE-US
                OrgName:        Akamai Technologies, Inc.
                Country:        US
                """);

        assertEquals("United States", summary.countryName());
        assertEquals("Akamai Technologies, Inc.", summary.owner());
        assertEquals("45.79.0.0/16", summary.block());
    }

    @Test
    @DisplayName("the loopback range is said to be reserved, which is what the raw answer buries")
    void readsReserved() {
        WhoisSummary summary = WhoisSummary.parse("""
                NetRange:       127.0.0.0 - 127.255.255.255
                CIDR:           127.0.0.0/8
                NetName:        SPECIAL-IPV4-LOOPBACK-IANA-RESERVED
                NetType:        IANA Special Use
                """);

        assertTrue(summary.reserved());
        assertEquals("a reserved range: no one owns it on the internet", summary.line());
    }

    @Test
    @DisplayName("an answer with nothing usable says so rather than printing an empty line")
    void survivesNothing() {
        assertTrue(WhoisSummary.parse("").empty());
        assertTrue(WhoisSummary.parse(null).empty());
        assertTrue(WhoisSummary.parse("% no entries found").line().contains("the answer is below"));
    }

    @Test
    @DisplayName("a country code nobody knows is shown as the code")
    void keepsAnUnknownCode() {
        WhoisSummary summary = WhoisSummary.parse("country: ZZ");

        assertEquals("ZZ", summary.country());
        assertFalse(summary.countryName().isBlank());
    }
}
