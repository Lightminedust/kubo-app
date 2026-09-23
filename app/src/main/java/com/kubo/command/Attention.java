package com.kubo.command;

/**
 * Which screen the operator is actually looking at.
 *
 * <p>Every screen in Kubo talks to the server down one SSH connection, and that connection runs one command at
 * a time. A screen left open behind another one does not merely waste a little bandwidth: its poll takes the
 * connection, and whatever the operator just clicked on waits behind it. With four windows open and the scope
 * polling underneath, opening a card could sit in a queue for seconds.
 *
 * <p>The rule is the simple one: the window in front asks, the others wait. A screen polls only while it holds
 * attention, and takes it when its window is focused.
 *
 * <p>Nothing here stops a screen from acting on a click. A click is the operator asking for something now, and
 * it happens whichever window it came from; only the repeating reads are held back.
 */
public final class Attention {

    /** Empty means nobody has claimed anything yet, in which case every screen may read. */
    private static volatile String active = "";

    private Attention() {
    }

    /**
     * Says this screen is the one in front. Called when its window is focused.
     */
    public static void claim(String scope) {
        active = scope == null ? "" : scope;
    }

    /**
     * True when this screen is the one in front, so its repeating reads may run.
     *
     * <p>Before any window has claimed anything, everything is allowed: a screen that never reads because nobody
     * told it that it was in front would be a far worse bug than one extra command.
     */
    public static boolean holds(String scope) {
        String holder = active;
        return holder.isEmpty() || holder.equals(scope);
    }

    /** The screen in front, or an empty string while nothing has claimed it. */
    public static String active() {
        return active;
    }
}
