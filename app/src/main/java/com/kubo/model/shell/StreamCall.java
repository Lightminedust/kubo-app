package com.kubo.model.shell;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A typed command that will never finish on its own, because following a log is the whole point of it.
 *
 * <p>In the console these are a trap. The field locks for as long as they run, which is forever; the output
 * pushes everything else out of a list that keeps a few hundred lines; and the moment somebody switches to
 * another screen it is gone from view although it is still running. So a command that follows gets a window
 * of its own, where it can run as long as it likes without holding anything.
 */
public record StreamCall(String command, String title) {

    /** Programs that follow by default, named by their first two words. */
    private static final Set<String> ALWAYS = Set.of("pm2 logs", "pm2 log", "pm2 monit");

    /** Programs that follow only when asked, and whose subcommand does not matter. */
    private static final Set<String> PLAIN_FOLLOWERS = Set.of("tail", "journalctl", "dmesg");

    /**
     * Programs where only one subcommand follows. Without this, "docker ps -f status=running" reads as a
     * stream because of a -f that means filter, and the screen opens on a command that ends immediately.
     */
    private static final Set<String> SUBCOMMAND_FOLLOWERS = Set.of("docker logs", "docker compose logs",
            "docker-compose logs", "podman logs", "kubectl logs", "nomad logs", "flyctl logs", "fly logs",
            "heroku logs", "pm2 logs");

    private static final Set<String> FOLLOW_FLAGS =
            Set.of("-f", "-F", "--follow", "-w", "--wait", "--follow=true");

    public StreamCall {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(title, "title");
    }

    /** The command as typed, if it follows a log, or empty for anything that ends by itself. */
    public static Optional<StreamCall> of(String typed) {
        String text = typed == null ? "" : typed.strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        List<String> words = List.of(text.split("\\s+"));
        int at = words.get(0).equals("sudo") || words.get(0).equals("doas") ? 1 : 0;
        if (at >= words.size()) {
            return Optional.empty();
        }
        String program = basename(words.get(at)).toLowerCase(Locale.ROOT);
        String pair = at + 1 < words.size()
                ? program + " " + words.get(at + 1).toLowerCase(Locale.ROOT) : program;
        String triple = at + 2 < words.size() ? pair + " " + words.get(at + 2).toLowerCase(Locale.ROOT) : pair;

        if (ALWAYS.contains(pair)) {
            return found(text);
        }
        boolean named = PLAIN_FOLLOWERS.contains(program)
                || SUBCOMMAND_FOLLOWERS.contains(pair) || SUBCOMMAND_FOLLOWERS.contains(triple);
        if (!named) {
            return Optional.empty();
        }
        for (int i = at + 1; i < words.size(); i++) {
            if (follows(words.get(i))) {
                return found(text);
            }
        }
        return Optional.empty();
    }

    private static boolean follows(String word) {
        if (FOLLOW_FLAGS.contains(word) || FOLLOW_FLAGS.contains(word.toLowerCase(Locale.ROOT))) {
            return true;
        }
        // Bundled short options, "tail -fn 100". Letters only, so a value like -f=x or a path is not one.
        return word.length() > 1 && word.charAt(0) == '-' && word.charAt(1) != '-'
                && word.chars().skip(1).allMatch(Character::isLetter)
                && word.indexOf('f') > 0;
    }

    private static Optional<StreamCall> found(String command) {
        return Optional.of(new StreamCall(command, shorten(command)));
    }

    /** Short enough for a title bar, long enough to tell two of them apart. */
    private static String shorten(String command) {
        return command.length() <= 64 ? command : command.substring(0, 61) + "…";
    }

    private static String basename(String word) {
        int slash = word.lastIndexOf('/');
        return slash < 0 ? word : word.substring(slash + 1);
    }
}
