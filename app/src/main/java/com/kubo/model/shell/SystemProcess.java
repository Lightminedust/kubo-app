package com.kubo.model.shell;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * One process on the server, asked for as columns rather than read off a drawn screen.
 *
 * <p>htop and top draw a terminal screen: they position the cursor, colour cells, and redraw parts of lines
 * in place several times a second. In a console that shows lines of text the result is the drawing
 * instructions themselves, which is what anybody who has typed htop into Kubo has seen. They also never end,
 * so the console sits locked until somebody remembers to stop them.
 *
 * <p>ps answers the same question with the columns named and nothing drawn. Every field here is asked for by
 * name, so the order is Kubo's rather than the tool's, and the process id comes with it, which is what a
 * button on a row needs.
 */
public record SystemProcess(int pid, int parent, String user, double cpu, double memoryShare, long resident,
                            String state, Duration up, String command) {

    /**
     * What Kubo asks for, in this order.
     *
     * <p>{@code args} is last because it is the only field that can hold spaces, so everything before it
     * splits cleanly and the rest of the line is the command whatever is in it. LC_ALL=C so the numbers come
     * back with dots wherever the server thinks it is.
     */
    public static final String COMMAND =
            "LC_ALL=C ps -eo pid=,ppid=,user=,pcpu=,pmem=,rss=,stat=,etimes=,args=";

    /** How many columns come before the command. */
    private static final int FIELDS = 8;

    public SystemProcess {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(up, "up");
        Objects.requireNonNull(command, "command");
    }

    /** True for one that is using the processor right now rather than waiting. */
    public boolean running() {
        return !state.isEmpty() && state.charAt(0) == 'R';
    }

    /** True for one that has ended and is waiting for its parent to notice. */
    public boolean zombie() {
        return !state.isEmpty() && state.charAt(0) == 'Z';
    }

    /**
     * Languages that are never the answer to "what is this process".
     *
     * <p>A server running five things under node has five processes all called node. The name worth showing
     * is the file they were told to run, which is the argument after the interpreter.
     */
    private static final java.util.Set<String> INTERPRETERS = java.util.Set.of(
            "node", "nodejs", "python", "python2", "python3", "ruby", "perl", "php", "java", "sh", "bash",
            "zsh", "dash", "env", "sudo", "deno", "bun");

    /** The program, without the path it was started from or the arguments after it. */
    public String program() {
        return basename(word(0));
    }

    /**
     * The short name for this process, as somebody would say it out loud.
     *
     * <p>The program, unless the program is an interpreter, in which case the file it was given. Kernel
     * threads keep their brackets, because "[kworker/1:1H]" is the name of that thing and stripping the
     * brackets would make it look like a program somebody started.
     */
    public String name() {
        String first = word(0);
        if (first.startsWith("[")) {
            return first.endsWith("]") ? first : first + "]";
        }
        String program = basename(first);
        if (!INTERPRETERS.contains(program.toLowerCase(java.util.Locale.ROOT))) {
            return program;
        }
        for (int at = 1; ; at++) {
            String next = word(at);
            if (next.isEmpty()) {
                return program;
            }
            // Options and their values are not the thing being run.
            if (next.startsWith("-")) {
                continue;
            }
            String named = basename(next);
            return named.isEmpty() ? program : named;
        }
    }

    /** The nth space separated word of the command, or empty past the end. */
    private String word(int at) {
        String[] words = command.strip().split("\s+");
        return at < words.length ? words[at] : "";
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        // sshd rewrites its own first word to "sshd:" so ps shows what the session is doing. The colon is
        // punctuation for that sentence, not part of a name.
        return name.endsWith(":") ? name.substring(0, name.length() - 1) : name;
    }

    /**
     * Everything ps answered with, heaviest first.
     *
     * <p>Sorted here rather than by ps. {@code --sort} is one of the flags that is not on every ps there is,
     * and a server whose ps does not take it would answer with nothing at all rather than with a different
     * order.
     */
    public static List<SystemProcess> parse(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        List<SystemProcess> found = new ArrayList<>();
        for (String line : output.lines().toList()) {
            one(line).ifPresent(found::add);
        }
        found.sort(Comparator.comparingDouble(SystemProcess::cpu).reversed()
                .thenComparing(Comparator.comparingDouble(SystemProcess::memoryShare).reversed()));
        return List.copyOf(found);
    }

    private static java.util.Optional<SystemProcess> one(String line) {
        String text = line.strip();
        if (text.isEmpty()) {
            return java.util.Optional.empty();
        }
        String[] parts = text.split("\\s+", FIELDS + 1);
        if (parts.length < FIELDS) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new SystemProcess(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    parts[2],
                    Double.parseDouble(parts[3]),
                    Double.parseDouble(parts[4]),
                    Long.parseLong(parts[5]) * 1024,
                    parts[6],
                    Duration.ofSeconds(Long.parseLong(parts[7])),
                    parts.length > FIELDS ? parts[FIELDS] : parts[6]));
        } catch (NumberFormatException notAProcess) {
            // A header somebody's ps printed anyway, or a line that is not a process at all.
            return java.util.Optional.empty();
        }
    }
}
