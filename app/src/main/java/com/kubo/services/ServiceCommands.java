package com.kubo.services;

import com.kubo.files.ShellQuote;
import com.kubo.model.services.Action;
import com.kubo.model.services.BootState;
import com.kubo.model.services.Dependencies;
import com.kubo.model.services.Resource;
import com.kubo.model.services.Signal;
import com.kubo.model.services.ServiceUnit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What Kubo asks systemd, and how it reads the answers.
 *
 * <p>Every command is a fixed template, and every one is prefixed with {@code LC_ALL=C}: systemd translates
 * part of its output, and a server set to another language would otherwise be read wrong rather than not at
 * all. All of the reading works as an ordinary user; only the acting on it needs root.
 */
public final class ServiceCommands {

    /** systemd puts this directory there when, and only when, it is the init system. */
    public static final String PRESENT = "[ -d /run/systemd/system ] && echo YES || echo NO";

    /** What is loaded now: unit, load state, active state, sub state, description. */
    public static final String UNITS =
            "LC_ALL=C systemctl list-units --type=service --all --plain --no-legend --no-pager";

    /** What each unit does at boot: unit, state, and on newer systemd a vendor preset that is ignored here. */
    public static final String UNIT_FILES =
            "LC_ALL=C systemctl list-unit-files --type=service --plain --no-legend --no-pager";

    /** How long each unit held up the last boot, longest first. */
    public static final String BLAME = "LC_ALL=C systemd-analyze blame --no-pager 2>/dev/null";

    /** The total of the last boot. */
    public static final String BOOT_TIME = "LC_ALL=C systemd-analyze time --no-pager 2>/dev/null";

    private ServiceCommands() {
    }

    /** Everything systemd knows about one unit, as {@code key=value} lines. */
    public static String show(String unit) {
        return "LC_ALL=C systemctl show " + ShellQuote.quote(unit) + " --no-pager";
    }

    /** The unit file as it is really on disk, drop-ins included: what this service actually runs. */
    public static String cat(String unit) {
        return "LC_ALL=C systemctl cat " + ShellQuote.quote(unit) + " --no-pager 2>&1";
    }

    /** The type of several units at once, to tell a finished one-shot task from a service that should be up. */
    public static String types(List<String> units) {
        return properties(units, "Id", "Type");
    }

    /**
     * What each of several units requires, wants, and starts after. Only the relations that are services
     * themselves are of any use on the map: the targets, sockets and mounts around them are systemd's own
     * plumbing, and they would bury the few links a person is actually looking for.
     */
    public static String relations(List<String> units) {
        return properties(units, "Id", "Requires", "Wants", "After");
    }

    private static String properties(List<String> units, String... names) {
        StringBuilder command = new StringBuilder("LC_ALL=C systemctl show");
        for (String name : names) {
            command.append(" --property=").append(name);
        }
        command.append(" --no-pager");
        for (String unit : units) {
            command.append(' ').append(ShellQuote.quote(unit));
        }
        return command.toString();
    }

    /**
     * The last lines of one unit's journal. Reading it needs no root on most servers, and where it does the
     * command simply fails and says so; Kubo does not quietly elevate a reading.
     */
    public static String journal(String unit, int lines) {
        return "LC_ALL=C journalctl --unit " + ShellQuote.quote(unit)
                + " --lines " + Math.max(1, Math.min(lines, 2000)) + " --no-pager --output=short-iso 2>&1";
    }

    /**
     * One of the five verbs, on one unit. The verb comes from {@link Action}, so it is one of five fixed
     * strings and never anything a user typed; only the unit name is a value, and it is quoted.
     */
    public static String act(Action action, String unit) {
        return "LC_ALL=C systemctl " + action.verb() + " " + ShellQuote.quote(unit);
    }

    /** The shape of a unit name. Anything else never reaches a path or a command. */
    private static final java.util.regex.Pattern UNIT_NAME =
            java.util.regex.Pattern.compile("^[A-Za-z0-9:_.@\\-]{1,255}\\.service$");

    /**
     * Checks a name is one systemd could have given.
     *
     * <p>Every name Kubo uses comes from systemd's own listing, so this should never fire. It is here because
     * the next few commands build a path out of it, and a path built out of something unchecked is exactly
     * the kind of thing that turns out to matter later.
     *
     * @throws IllegalArgumentException when the name is not the shape of a unit name
     */
    public static String checked(String unit) {
        if (unit == null || !UNIT_NAME.matcher(unit).matches()) {
            throw new IllegalArgumentException("Not the name of a service unit: " + unit);
        }
        return unit;
    }

    /** Where a service Kubo creates is written. */
    public static String unitPath(String unit) {
        return "/etc/systemd/system/" + checked(unit);
    }

    /**
     * Writes a new unit from whatever arrives on standard input, and refuses to write over one that is
     * already there.
     *
     * <p>The guard is in the command, not only in the screen: {@code cat >} truncates whatever it opens, and
     * a screen that checked a moment earlier is not the same thing as a check that cannot be raced.
     */
    public static String createUnit(String unit) {
        String path = ShellQuote.quote(unitPath(unit));
        return "test ! -e " + path + " && cat > " + path + " && chmod 0644 " + path;
    }

    /** Whether a unit of that name is already on the machine, wherever it came from. */
    public static String unitExists(String unit) {
        return "test -e " + ShellQuote.quote(unitPath(unit))
                + " && echo YES || LC_ALL=C systemctl cat " + ShellQuote.quote(unit)
                + " >/dev/null 2>&1 && echo YES || echo NO";
    }

    /** Where a service's own settings live, which is never the file its package shipped. */
    public static String dropInDirectory(String unit) {
        return "/etc/systemd/system/" + checked(unit) + ".d";
    }

    /** The one file in there Kubo writes, and the only one it will ever remove. */
    public static String dropInFile(String unit) {
        return dropInDirectory(unit) + "/kubo.conf";
    }

    /** Reads back what Kubo has already put there, or nothing at all. */
    public static String readDropIn(String unit) {
        return "cat " + ShellQuote.quote(dropInFile(unit)) + " 2>/dev/null";
    }

    /**
     * Writes that file from whatever arrives on standard input.
     *
     * <p>The content never appears in the command, which is what keeps it out of the process list and out of
     * sudo's log however long or however strange it is.
     */
    public static String writeDropIn(String unit) {
        return "mkdir -p " + ShellQuote.quote(dropInDirectory(unit))
                + " && cat > " + ShellQuote.quote(dropInFile(unit))
                + " && chmod 0644 " + ShellQuote.quote(dropInFile(unit));
    }

    /**
     * Takes a unit file away.
     *
     * <p>The path is built here from a checked name and a fixed directory, never from anything the user
     * typed: this command can only ever reach one file in /etc/systemd/system, and never the file a package
     * installed elsewhere. Whether this particular unit may be deleted at all is
     * {@link com.kubo.model.services.Removal}'s question, asked before this is ever run.
     */
    public static String removeUnit(String unit) {
        return "rm -f " + ShellQuote.quote(unitPath(unit));
    }

    /**
     * Clears what systemd still remembers of a unit that failed.
     *
     * <p>Without this a deleted service keeps a failed entry in the listing, which is a service that does not
     * exist being reported as broken. It is allowed to fail: there is nothing to clear when it never failed.
     */
    public static String resetFailed(String unit) {
        return "LC_ALL=C systemctl reset-failed " + ShellQuote.quote(unit) + " 2>/dev/null ; true";
    }

    /** Takes Kubo's file away again, and the directory with it when nothing else is left in it. */
    public static String removeDropIn(String unit) {
        return "rm -f " + ShellQuote.quote(dropInFile(unit))
                + " ; rmdir " + ShellQuote.quote(dropInDirectory(unit)) + " 2>/dev/null ; true";
    }

    /** systemd does not notice a file changing on its own. */
    public static final String DAEMON_RELOAD = "LC_ALL=C systemctl daemon-reload";

    /**
     * Puts a limit on a running service.
     *
     * <p>The property is one of two fixed strings. The value is typed by a person, so it is checked against
     * the shape systemd takes before it is allowed anywhere near this, and quoted on the way.
     *
     * @throws IllegalArgumentException when the value is not one systemd would accept
     */
    public static String setLimit(Resource resource, String value, String unit) {
        String wanted = value == null ? "" : value.trim();
        if (!resource.accepts(wanted)) {
            throw new IllegalArgumentException(resource.label() + " does not take " + wanted
                    + ". " + resource.meaning());
        }
        return "LC_ALL=C systemctl set-property " + ShellQuote.quote(unit) + " "
                + ShellQuote.quote(resource.property() + "=" + wanted);
    }

    /**
     * Sends one signal, either to the one process that is the service or to everything in its group.
     *
     * <p>{@code --kill-who} rather than the newer {@code --kill-whom}: the old spelling is still taken by
     * every systemd in the field, and the new one is not there before 252.
     */
    public static String signal(Signal signal, boolean everything, String unit) {
        return "LC_ALL=C systemctl kill --signal=" + signal.signalName()
                + " --kill-who=" + (everything ? "all" : "main") + " " + ShellQuote.quote(unit);
    }

    // Reading the answers

    /**
     * {@code UNIT LOAD ACTIVE SUB DESCRIPTION}, separated by runs of spaces. The description is whatever is
     * left, so a description with spaces in it stays whole.
     */
    public static List<ServiceUnit> parseUnits(String output) {
        List<ServiceUnit> units = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String[] parts = line.trim().split("\\s+", 5);
            if (parts.length < 4 || !parts[0].endsWith(".service")) {
                continue;
            }
            units.add(ServiceUnit.of(parts[0], parts.length == 5 ? parts[4] : "", parts[1], parts[2], parts[3]));
        }
        return units;
    }

    /** {@code UNIT STATE [PRESET]}. */
    public static Map<String, BootState> parseUnitFiles(String output) {
        Map<String, BootState> states = new HashMap<>();
        for (String line : output.split("\\R")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2 || !parts[0].endsWith(".service")) {
                continue;
            }
            states.put(parts[0], BootState.of(parts[1]));
        }
        return states;
    }

    /** {@code 8.123s postgresql.service}, one per line, already sorted by systemd. */
    public static Map<String, Duration> parseBlame(String output) {
        Map<String, Duration> times = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            int space = trimmed.lastIndexOf(' ');
            if (space < 0 || !trimmed.endsWith(".service")) {
                continue;
            }
            Duration duration = parseDuration(trimmed.substring(0, space));
            if (duration != null) {
                times.put(trimmed.substring(space + 1), duration);
            }
        }
        return times;
    }

    /**
     * {@code Startup finished in 3.4s (kernel) + 18.9s (userspace) = 22.3s}. The total is what follows the
     * last equals sign; the userspace part is the one a person can do anything about.
     */
    public static Duration parseTotalBoot(String output) {
        for (String line : output.split("\\R")) {
            int equals = line.lastIndexOf('=');
            if (line.contains("Startup finished") && equals >= 0) {
                return parseDuration(line.substring(equals + 1));
            }
        }
        return null;
    }

    public static Duration parseUserspace(String output) {
        for (String line : output.split("\\R")) {
            int mark = line.indexOf("(userspace)");
            if (mark < 0) {
                continue;
            }
            int start = line.lastIndexOf('+', mark);
            return parseDuration(line.substring(start < 0 ? "Startup finished in".length() : start + 1, mark));
        }
        return null;
    }

    /** {@code key=value} lines, the form {@code systemctl show} answers in. */
    public static Map<String, String> parseShow(String output) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                properties.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        return properties;
    }

    /**
     * Asked about several units at once, systemd answers one block per unit. A blank line ends a block, and so
     * does a second {@code Id}: not every version of systemd puts a blank line between them.
     */
    public static List<Map<String, String>> parseBlocks(String output) {
        List<Map<String, String>> blocks = new ArrayList<>();
        Map<String, String> current = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                if (!current.isEmpty()) {
                    blocks.add(current);
                    current = new LinkedHashMap<>();
                }
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = line.substring(0, equals);
            if (key.equals("Id") && current.containsKey("Id")) {
                blocks.add(current);
                current = new LinkedHashMap<>();
            }
            current.put(key, line.substring(equals + 1));
        }
        if (!current.isEmpty()) {
            blocks.add(current);
        }
        return blocks;
    }

    /** The Id/Type pairs of {@link #types(List)}. */
    public static Map<String, String> parseTypes(String output) {
        Map<String, String> types = new LinkedHashMap<>();
        for (Map<String, String> block : parseBlocks(output)) {
            String id = block.get("Id");
            String type = block.get("Type");
            if (id != null && type != null) {
                types.put(id.trim(), type.trim());
            }
        }
        return types;
    }

    /**
     * The answers of {@link #relations(List)}, kept to the services and never pointing a unit at itself.
     */
    public static Dependencies parseRelations(String output) {
        Map<String, List<String>> needs = new LinkedHashMap<>();
        for (Map<String, String> block : parseBlocks(output)) {
            String id = block.get("Id");
            if (id == null) {
                continue;
            }
            id = id.trim();
            LinkedHashSet<String> mine = new LinkedHashSet<>();
            for (String key : List.of("Requires", "Wants", "After")) {
                for (String name : block.getOrDefault(key, "").trim().split("\\s+")) {
                    if (name.endsWith(".service") && !name.equals(id)) {
                        mine.add(name);
                    }
                }
            }
            needs.put(id, List.copyOf(mine));
        }
        return Dependencies.of(needs);
    }

    /**
     * systemd writes durations as a run of pieces: {@code 1min 2.345s}, {@code 512ms}, {@code 45us}. Anything
     * it could not measure is left out rather than counted as zero.
     */
    public static Duration parseDuration(String text) {
        double millis = 0;
        boolean found = false;
        for (String piece : text.trim().split("\\s+")) {
            int end = 0;
            while (end < piece.length() && (Character.isDigit(piece.charAt(end)) || piece.charAt(end) == '.')) {
                end++;
            }
            if (end == 0 || end == piece.length()) {
                continue;
            }
            double value;
            try {
                value = Double.parseDouble(piece.substring(0, end));
            } catch (NumberFormatException e) {
                continue;
            }
            double scale = switch (piece.substring(end)) {
                case "h" -> 3_600_000;
                case "min" -> 60_000;
                case "s" -> 1_000;
                case "ms" -> 1;
                case "us" -> 0.001;
                default -> 0;
            };
            if (scale > 0) {
                millis += value * scale;
                found = true;
            }
        }
        return found ? Duration.ofNanos(Math.round(millis * 1_000_000)) : null;
    }
}
