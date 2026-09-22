package com.kubo.network;

import com.kubo.files.ShellQuote;
import com.kubo.model.network.Certificate;
import com.kubo.model.network.Jail;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The two things standing between a server and the internet that Kubo could not see: fail2ban, and the TLS
 * certificates.
 *
 * <p>Reading fail2ban needs root on most installs, because its socket is root-owned. Banning and unbanning always
 * do. Every address these commands act on is checked against {@link #ADDRESS} first and quoted after, so nothing
 * a user types can become a second command.
 */
public final class GuardCommands {

    /** An IPv4 or IPv6 literal, optionally with a prefix. Nothing else ever reaches a command. */
    private static final Pattern ADDRESS = Pattern.compile("^[0-9a-fA-F:.]{2,45}(/\\d{1,3})?$");

    /** A jail name as fail2ban writes them. */
    private static final Pattern JAIL = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    public static final String PRESENT = "command -v fail2ban-client >/dev/null 2>&1 && echo YES || echo NO";

    /** The jail list, then the full status of every jail, in one round trip. */
    private static final String JAILS_ONLY =
            "LC_ALL=C fail2ban-client status 2>&1; "
            + "for J in $(LC_ALL=C fail2ban-client status 2>/dev/null "
            + "| sed -n 's/.*Jail list:\\s*//p' | tr ',' ' '); do "
            + "echo \"=== $J\"; LC_ALL=C fail2ban-client status \"$J\" 2>&1; done";

    /** Whether fail2ban is there, and everything it knows, in one round trip. */
    public static final String WATCH = "echo '@@present'; " + PRESENT + "; echo '@@jails'; " + JAILS_ONLY;

    /** True when the answer to {@link #WATCH} says fail2ban is installed. */
    public static boolean installed(String output) {
        return LinkCommands.section(output, "present").strip().equals("YES");
    }

    private GuardCommands() {
    }

    public static String ban(String jail, String address) {
        return "LC_ALL=C fail2ban-client set " + ShellQuote.quote(checkedJail(jail))
                + " banip " + ShellQuote.quote(checkedAddress(address));
    }

    public static String unban(String jail, String address) {
        return "LC_ALL=C fail2ban-client set " + ShellQuote.quote(checkedJail(jail))
                + " unbanip " + ShellQuote.quote(checkedAddress(address));
    }

    /**
     * @throws IllegalArgumentException when the text is not an address
     */
    public static String checkedAddress(String address) {
        if (address == null || !ADDRESS.matcher(address.strip()).matches()) {
            throw new IllegalArgumentException("Not an IP address: " + address);
        }
        return address.strip();
    }

    /**
     * @throws IllegalArgumentException when the text is not a jail name
     */
    public static String checkedJail(String jail) {
        if (jail == null || !JAIL.matcher(jail.strip()).matches()) {
            throw new IllegalArgumentException("Not a jail name: " + jail);
        }
        return jail.strip();
    }

    /**
     * Jails from the output of {@link #WATCH}.
     *
     * <p>fail2ban prints a tree of bulleted lines whose labels have changed wording between versions, so each
     * value is found by the word that has stayed put rather than by its position.
     */
    public static List<Jail> parseJails(String output) {
        List<Jail> jails = new ArrayList<>();
        String[] blocks = output.split("(?m)^=== ");
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            int newline = block.indexOf('\n');
            if (newline < 0) {
                continue;
            }
            String name = block.substring(0, newline).strip();
            if (!JAIL.matcher(name).matches()) {
                continue;
            }
            jails.add(new Jail(name, number(block, "Currently failed:"), number(block, "Total failed:"),
                    number(block, "Total banned:"), addresses(block)));
        }
        return jails;
    }

    private static int number(String block, String label) {
        for (String line : block.split("\\R")) {
            int at = line.indexOf(label);
            if (at >= 0) {
                try {
                    return Integer.parseInt(line.substring(at + label.length()).strip());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static List<String> addresses(String block) {
        Set<String> banned = new LinkedHashSet<>();
        for (String line : block.split("\\R")) {
            int at = line.indexOf("Banned IP list:");
            if (at < 0) {
                continue;
            }
            for (String word : line.substring(at + "Banned IP list:".length()).strip().split("\\s+")) {
                if (ADDRESS.matcher(word).matches()) {
                    banned.add(word);
                }
            }
        }
        return List.copyOf(banned);
    }

    // Certificates

    /**
     * Reads the expiry of every certificate given, in one round trip.
     *
     * <p>Each file is announced before it is read so that a file that cannot be read still appears in the answer,
     * with the reason. A certificate Kubo silently dropped would read on screen as a site with no certificate.
     */
    public static String certificates(List<String> files) {
        StringBuilder command = new StringBuilder();
        for (String file : files) {
            String quoted = ShellQuote.quote(file);
            command.append("echo \"=== ").append(file.replace("\"", "")).append('"').append("; ")
                    .append("openssl x509 -noout -enddate -subject -issuer -in ").append(quoted)
                    .append(" 2>&1; ");
        }
        return command.toString();
    }

    private static final DateTimeFormatter OPENSSL_DATE =
            DateTimeFormatter.ofPattern("MMM ppd HH:mm:ss yyyy zzz", Locale.ROOT);

    /**
     * Certificates from the output of {@link #certificates(List)}.
     */
    public static List<Certificate> parseCertificates(String output) {
        List<Certificate> certificates = new ArrayList<>();
        String[] blocks = output.split("(?m)^=== ");
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            int newline = block.indexOf('\n');
            if (newline < 0) {
                continue;
            }
            String file = block.substring(0, newline).strip();
            String body = block.substring(newline + 1);
            String expiry = after(body, "notAfter=");
            if (expiry.isEmpty()) {
                certificates.add(Certificate.unreadable(file, firstLine(body)));
                continue;
            }
            try {
                Instant notAfter = ZonedDateTime.parse(expiry, OPENSSL_DATE).toInstant();
                certificates.add(new Certificate(file, notAfter, after(body, "subject="), after(body, "issuer="), ""));
            } catch (DateTimeParseException e) {
                certificates.add(Certificate.unreadable(file, "Could not read the expiry date: " + expiry));
            }
        }
        return certificates;
    }

    private static String after(String body, String label) {
        for (String line : body.split("\\R")) {
            String text = line.strip();
            if (text.startsWith(label)) {
                return text.substring(label.length()).strip();
            }
        }
        return "";
    }

    private static String firstLine(String text) {
        return text.strip().lines().findFirst().orElse("The file could not be read.");
    }
}
