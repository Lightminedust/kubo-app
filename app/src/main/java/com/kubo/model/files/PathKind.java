package com.kubo.model.files;

import java.util.List;
import java.util.Locale;

/**
 * What a path field is asking for, so a picker can open in the right place and refuse the wrong thing.
 *
 * <p>A certificate path typed from memory is how a site ends up refusing to start. The shape of each of these is
 * known well enough to say where they usually live and what they are called, and that is all this says: it
 * decides what can be chosen, not what is shown. Everything in a folder is shown, so the reader can tell they
 * are in the right one.
 */
public enum PathKind {

    /** A folder, such as the one a site serves its files from. */
    FOLDER("FOLDER", "/var/www", List.of("/var/www", "/srv", "/home", "/opt", "/"), List.of()),

    /**
     * A certificate. Let's Encrypt keeps each one under its own folder in {@code /etc/letsencrypt/live}, as a
     * link to the real file in {@code archive}, which is why a link is accepted as readily as a file.
     */
    CERTIFICATE("CERTIFICATE", "/etc/letsencrypt/live",
            List.of("/etc/letsencrypt/live", "/etc/ssl/certs", "/etc/nginx"), List.of(".pem", ".crt", ".cer")),

    /** The private key that goes with a certificate. */
    PRIVATE_KEY("PRIVATE KEY", "/etc/letsencrypt/live",
            List.of("/etc/letsencrypt/live", "/etc/ssl/private", "/etc/nginx"), List.of(".pem", ".key")),

    /**
     * A PHP-FPM socket. Not a file at all: a socket is its own kind of entry, and {@code php-fpm.sock} is
     * usually a link to the one with the version in its name.
     */
    SOCKET("SOCKET", "/run/php", List.of("/run/php", "/run", "/var/run"), List.of(".sock"));

    private final String label;
    private final String start;
    private final List<String> places;
    private final List<String> endings;

    PathKind(String label, String start, List<String> places, List<String> endings) {
        this.label = label;
        this.start = start;
        this.places = places;
        this.endings = endings;
    }

    /** What it is called on a button: CHOOSE THIS CERTIFICATE. */
    public String label() {
        return label;
    }

    /** Where a picker opens when the field says nothing yet. */
    public String start() {
        return start;
    }

    /** The shortcuts at the top of a picker: where these usually are. */
    public List<String> places() {
        return places;
    }

    public boolean wantsFolder() {
        return this == FOLDER;
    }

    /**
     * Whether this entry is one of these.
     *
     * <p>By its kind and its name. Nothing is opened to check: reading a private key to find out whether it is
     * one would be the wrong thing for a picker to do.
     */
    public boolean accepts(RemoteEntry entry) {
        if (entry == null) {
            return false;
        }
        if (this == FOLDER) {
            return entry.isDirectory();
        }
        boolean kindFits = switch (this) {
            case SOCKET -> entry.type() == RemoteEntry.Type.OTHER || entry.type() == RemoteEntry.Type.LINK;
            default -> entry.type() == RemoteEntry.Type.FILE || entry.type() == RemoteEntry.Type.LINK;
        };
        if (!kindFits) {
            return false;
        }
        String name = entry.name().toLowerCase(Locale.ROOT);
        if (endings.stream().noneMatch(name::endsWith)) {
            return false;
        }
        // A certificate and its key are both .pem more often than not. A name that says which is believed; a
        // name that says neither, such as site.pem, is offered as both, and its first line settles it once it
        // is picked. See judge().
        return switch (this) {
            case CERTIFICATE -> !saysKey(name);
            case PRIVATE_KEY -> !saysCertificate(name);
            default -> true;
        };
    }

    private static boolean saysKey(String name) {
        return name.endsWith(".key") || name.contains("key") || name.contains("priv");
    }

    private static boolean saysCertificate(String name) {
        return name.endsWith(".crt") || name.endsWith(".cer") || name.contains("cert") || name.contains("chain");
    }

    /** True for the kinds whose files say what they are in their first line, so a picker should look. */
    public boolean readsFirstLine() {
        return this == CERTIFICATE || this == PRIVATE_KEY;
    }

    /** What the first line of a picked file says about it. */
    public enum Verdict {
        /** It is what was asked for. */
        FITS,
        /** It is something else: a key picked as a certificate, a certificate picked as a key. */
        WRONG,
        /** A key locked by a passphrase, which Nginx would ask for at every start. */
        LOCKED,
        /** The line could not be read, or the file is not PEM at all. */
        UNKNOWN
    }

    /**
     * Judges a file by its PEM armour, the {@code -----BEGIN …-----} line every certificate and key starts with.
     *
     * <p>That line names the thing and nothing else. It holds none of the key, which is why it is the one line
     * Kubo is willing to read out of a private key file, and why only that line is ever asked for.
     *
     * @param armour the first {@code -----BEGIN …-----} line of the file, or empty when there was none
     */
    public Verdict judge(String armour) {
        String line = armour == null ? "" : armour.strip().toUpperCase(Locale.ROOT);
        if (!line.startsWith("-----BEGIN ")) {
            return Verdict.UNKNOWN;
        }
        boolean key = line.contains("PRIVATE KEY");
        boolean certificate = line.contains("CERTIFICATE") && !line.contains("REQUEST");
        return switch (this) {
            case CERTIFICATE -> certificate ? Verdict.FITS : Verdict.WRONG;
            case PRIVATE_KEY -> line.contains("ENCRYPTED") ? Verdict.LOCKED : key ? Verdict.FITS : Verdict.WRONG;
            default -> Verdict.UNKNOWN;
        };
    }

    /** What a verdict means, in the words the picker shows. */
    public String explain(Verdict verdict, String armour) {
        String what = armour == null ? "" : armour.strip().replace("-----BEGIN ", "").replace("-----", "")
                .toLowerCase(Locale.ROOT);
        return switch (verdict) {
            case FITS -> "its first line says " + what;
            case WRONG -> "its first line says " + what + ", not a " + label.toLowerCase(Locale.ROOT);
            case LOCKED -> "locked by a passphrase: Nginx would stop and ask for it at every start";
            case UNKNOWN -> "its first line could not be read, so the name is all Kubo has to go on";
        };
    }

    /**
     * A word about one entry that fits, when there is something worth knowing: of the three certificates Let's
     * Encrypt writes, only one is the one Nginx should be given. Empty when there is nothing to say.
     */
    public String advice(RemoteEntry entry) {
        if (!readsFirstLine() || entry == null) {
            return "";
        }
        String name = entry.name().toLowerCase(Locale.ROOT);
        if (this == CERTIFICATE) {
            switch (name) {
                case "fullchain.pem" -> {
                    return "the one Nginx wants";
                }
                case "cert.pem" -> {
                    return "missing the intermediates";
                }
                case "chain.pem" -> {
                    return "the intermediates only";
                }
                default -> {
                    // Said below when the name says nothing either way.
                }
            }
        }
        return name.endsWith(".pem") && !saysKey(name) && !saysCertificate(name)
                ? "the name does not say which" : "";
    }

    /**
     * The key that goes with a certificate, when the certificate is laid out the way Let's Encrypt lays them
     * out, or empty when there is no way to know.
     *
     * <p>Picking the certificate and then walking back to the same folder for its key is a step nobody needs to
     * take for the most common certificate on a small server.
     */
    public static String keyBeside(String certificate) {
        if (certificate == null) {
            return "";
        }
        for (String name : List.of("/fullchain.pem", "/cert.pem", "/chain.pem")) {
            if (certificate.endsWith(name)) {
                return certificate.substring(0, certificate.length() - name.length()) + "/privkey.pem";
            }
        }
        return "";
    }
}
