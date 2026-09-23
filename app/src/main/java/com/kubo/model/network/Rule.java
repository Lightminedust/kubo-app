package com.kubo.model.network;

/**
 * One {@code location} inside an Nginx server block: a path a visitor asks for, and what answers it.
 *
 * <p>A domain is almost never one thing. {@code /} goes to the site, {@code /api} to something else entirely,
 * {@code /ws} holds a socket open, {@code /static} is read straight off the disk. The screen used to show one
 * destination per domain, which on any real server is the destination of the first rule and nothing more.
 *
 * <p>Called a rule rather than a route because {@link Route} is already the kernel's routing table, and the two
 * have nothing to do with each other.
 *
 * @param path      what the visitor asks for, as written in the file: "/", "/api/v1", "~ \.php$"
 * @param target    where it goes: a proxy destination, a directory, a URL to redirect to, or empty
 * @param websocket true when this location carries the headers a websocket needs to survive the proxy
 */
public record Rule(String path, Kind kind, String target, boolean websocket) {

    /** What answers a path. */
    public enum Kind {
        /** Forwarded to something else, usually a program on a local port. */
        PROXY("forwards to"),
        /** Read off the disk. */
        FILES("serves files from"),
        /** Answered with a redirect. */
        REDIRECT("redirects to"),
        /** Answered by Nginx itself with a status and nothing else. */
        ANSWER("answers"),
        /** Nothing in the block says where this goes, so it falls back to whatever the server block does. */
        UNSET("no destination of its own");

        private final String verb;

        Kind(String verb) {
            this.verb = verb;
        }

        /** The words between the path and the target, so a row reads as a sentence. */
        public String verb() {
            return verb;
        }
    }

    public Rule {
        path = path == null ? "" : path.strip();
        target = target == null ? "" : target.strip();
        kind = kind == null ? Kind.UNSET : kind;
    }

    /** True for the rule that answers everything no other rule claimed. */
    public boolean catchAll() {
        return path.equals("/");
    }

    /** The whole rule as one sentence. */
    public String sentence() {
        return target.isEmpty() ? kind.verb() : kind.verb() + " " + target;
    }

    /**
     * The port this rule forwards to, or -1 when it does not forward to a local port.
     *
     * <p>So a rule can be matched against something that is actually listening: a location pointing at 127.0.0.1
     * on a port nothing answers on is a 502 waiting to be reported as "the site is down".
     */
    public int localPort() {
        if (kind != Kind.PROXY) {
            return -1;
        }
        Vhost.ProxyTarget parsed = com.kubo.network.NetworkCommands.target(target);
        return parsed.isLocalPort() ? parsed.port() : -1;
    }
}
