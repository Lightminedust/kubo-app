package com.kubo.model.network;

import java.util.List;
import java.util.Locale;

/**
 * What a whois answer says, in one line: whose address it is, in which country, in which block.
 *
 * <p>A whois answer is forty lines in a format that depends on which registry answered: ARIN writes
 * {@code OrgName:} and {@code Country:}, RIPE and APNIC write {@code org-name:} and {@code country:}, LACNIC writes
 * {@code owner:}. What somebody wants before a ban is three of those lines. The raw answer stays on screen under
 * this, because it is the thing that is certainly right.
 *
 * <p>The country comes from here rather than from a GeoIP database, because this answer was already fetched,
 * and a database would be either a licence to accept or every attacker's address sent to somebody else's service.
 *
 * @param country the two letter code the registry gave, empty when it gave none
 * @param owner   the organisation holding the block, empty when none of the usual lines is there
 * @param block   the range the address belongs to, empty when not given
 */
public record WhoisSummary(String country, String owner, String block, boolean reserved) {

    private static final List<String> OWNER_KEYS = List.of("orgname", "org-name", "owner", "descr", "netname");
    private static final List<String> BLOCK_KEYS = List.of("cidr", "inetnum", "inet6num", "netrange", "route");

    public WhoisSummary {
        country = country == null ? "" : country.strip().toUpperCase(Locale.ROOT);
        owner = owner == null ? "" : owner.strip();
        block = block == null ? "" : block.strip();
    }

    /** Reads a whois answer. The first line for each key wins, which is the most specific block. */
    public static WhoisSummary parse(String answer) {
        String country = "";
        String owner = "";
        int ownerRank = Integer.MAX_VALUE;
        String block = "";
        int blockRank = Integer.MAX_VALUE;
        boolean reserved = false;
        for (String line : (answer == null ? "" : answer).split("\\R")) {
            int colon = line.indexOf(':');
            if (colon <= 0 || line.startsWith("%") || line.startsWith("#")) {
                continue;
            }
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (value.isEmpty()) {
                continue;
            }
            if (key.equals("country") && country.isEmpty() && value.length() == 2) {
                country = value;
            }
            int rank = OWNER_KEYS.indexOf(key);
            if (rank >= 0 && rank < ownerRank) {
                owner = value;
                ownerRank = rank;
            }
            rank = BLOCK_KEYS.indexOf(key);
            if (rank >= 0 && rank < blockRank) {
                block = value;
                blockRank = rank;
            }
            if ((key.equals("netname") || key.equals("nettype") || key.equals("comment"))
                    && (value.contains("RESERVED") || value.contains("Special Use") || value.contains("PRIVATE"))) {
                reserved = true;
            }
        }
        return new WhoisSummary(country, owner, block, reserved);
    }

    /** The country in words, from its code: "DE" becomes "Germany". The code alone when Java has no name for it. */
    public String countryName() {
        if (country.length() != 2) {
            return "";
        }
        String name = Locale.of("", country).getDisplayCountry(Locale.ENGLISH);
        return name.isBlank() ? country : name;
    }

    /** True when the answer said nothing this can use. */
    public boolean empty() {
        return country.isEmpty() && owner.isEmpty() && block.isEmpty() && !reserved;
    }

    /** The line shown beside the address: "Germany · Hetzner Online GmbH · 159.41.0.0/16". */
    public String line() {
        if (reserved) {
            return "a reserved range: no one owns it on the internet";
        }
        StringBuilder text = new StringBuilder();
        for (String part : List.of(countryName(), owner, block)) {
            if (part.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append("  ·  ");
            }
            text.append(part);
        }
        return text.length() == 0 ? "whois gave nothing Kubo could read: the answer is below" : text.toString();
    }
}
