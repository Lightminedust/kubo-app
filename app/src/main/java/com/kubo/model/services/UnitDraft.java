package com.kubo.model.services;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A service a person is about to create, and the file it will be written as.
 *
 * <p>Everything that decides what the file says is here rather than in the screen, so what will be written
 * can be judged — and shown — before anything is sent. The screen shows exactly this text, and exactly this
 * text is what goes to the server.
 *
 * <p>Nothing here is a command. It is a configuration file, and it travels on standard input, so the shell
 * never sees any of it. What still has to be right is the name, because that becomes a path, and the quoting
 * of a path inside {@code ExecStart}, because systemd splits that line on spaces itself.
 */
public record UnitDraft(String name, String description, String command, String arguments,
                        String workingDirectory, String user, String restart, int restartSeconds,
                        boolean atBoot) {

    /** A user name systemd and the system both take, the only kind written into User=. */
    private static final Pattern USER = Pattern.compile("^[a-z_][a-z0-9_-]{0,31}\\$?$");

    /** What systemd allows in a unit name, and what may therefore become a file name under /etc. */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.@-]{0,63}$");

    /** The restart policies worth offering, in the words systemd uses for them. */
    public static final String[] POLICIES = {"no", "on-failure", "always"};

    public UnitDraft {
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description.trim();
        command = command == null ? "" : command.trim();
        arguments = arguments == null ? "" : arguments.trim();
        workingDirectory = workingDirectory == null ? "" : workingDirectory.trim();
        user = user == null ? "" : user.trim();
        restart = restart == null || restart.isBlank() ? "on-failure" : restart.trim();
        restartSeconds = Math.max(0, Math.min(restartSeconds, 3600));
    }

    /** A draft whose command takes no arguments, or has them written into it. */
    public UnitDraft(String name, String description, String command, String workingDirectory, String user,
                     String restart, int restartSeconds, boolean atBoot) {
        this(name, description, command, "", workingDirectory, user, restart, restartSeconds, atBoot);
    }

    /** An empty draft, which is where the screen starts. */
    public static UnitDraft blank() {
        return new UnitDraft("", "", "", "", "", "on-failure", 5, true);
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name.trim()).matches() && !name.trim().endsWith(".service");
    }

    /** The name the file will have, which is the name plus what systemd expects at the end of it. */
    public String unitName() {
        return name + ".service";
    }

    /**
     * What is still wrong with this draft, in the words the screen shows. Empty means it can be written.
     */
    public List<String> problems() {
        List<String> found = new ArrayList<>();
        if (name.isEmpty()) {
            found.add("it needs a name");
        } else if (!validName(name)) {
            found.add("a name holds letters, digits and _ . @ - only, and kubo adds .service itself");
        }
        if (command.isEmpty()) {
            found.add("it needs something to run");
        } else if (!command.startsWith("/")) {
            found.add("what it runs needs its full path, starting with /: systemd gives a service no PATH");
        }
        if (!workingDirectory.isEmpty() && !workingDirectory.startsWith("/")) {
            found.add("where it runs needs its full path, starting with /");
        }
        if (!user.isEmpty() && !USER.matcher(user).matches()) {
            found.add("that is not a user name the system takes");
        }
        // Each of these becomes one line of the file: a line break would write a second setting into it.
        for (String field : new String[]{description, command, arguments, workingDirectory}) {
            if (field.contains("\n") || field.contains("\r")) {
                found.add("each field stays on one line");
                break;
            }
        }
        return found;
    }

    public boolean ready() {
        return problems().isEmpty();
    }

    /** The file, exactly as it will be written. */
    public String text() {
        StringBuilder unit = new StringBuilder();
        unit.append("# Written by Kubo. Edit it here or with systemctl edit --full.\n");
        unit.append("[Unit]\n");
        unit.append("Description=").append(description.isEmpty() ? name : description).append('\n');
        // Nearly everything wants the network up first, and nothing is harmed by waiting for it.
        unit.append("After=network-online.target\n");
        unit.append("Wants=network-online.target\n\n");

        unit.append("[Service]\n");
        unit.append("Type=simple\n");
        unit.append("ExecStart=").append(quoted(command))
                .append(arguments.isEmpty() ? "" : " " + arguments).append('\n');
        if (!workingDirectory.isEmpty()) {
            unit.append("WorkingDirectory=").append(quoted(workingDirectory)).append('\n');
        }
        if (!user.isEmpty()) {
            unit.append("User=").append(user).append('\n');
        }
        unit.append("Restart=").append(restart).append('\n');
        if (!restart.equals("no")) {
            unit.append("RestartSec=").append(restartSeconds).append("s\n");
        }
        unit.append('\n');

        unit.append("[Install]\n");
        unit.append("WantedBy=multi-user.target\n");
        return unit.toString();
    }

    /**
     * A path as systemd will read it.
     *
     * <p>systemd splits {@code ExecStart} on spaces itself, so a path with a space in it has to be quoted or
     * it becomes a command and an argument. A path without one is left alone, because quoting everything
     * makes a file nobody wants to read.
     */
    static String quoted(String path) {
        return path.contains(" ") ? "\"" + path.replace("\"", "\\\"") + "\"" : path;
    }
}
