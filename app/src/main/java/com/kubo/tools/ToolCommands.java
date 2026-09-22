package com.kubo.tools;

import com.kubo.model.tools.PackageManager;
import com.kubo.model.tools.Tool;

import java.util.EnumSet;
import java.util.Set;

/**
 * Finding out which helper tools a server has, and installing one when the operator asks for it.
 *
 * <p>Nothing here is built from anything typed. The package names are fields of {@link Tool} and the install
 * sentence is one of six fixed shapes, so the worst a bad answer from the server can do is leave a tool
 * missing.
 */
public final class ToolCommands {

    /** Which tools are there and which package manager drives this machine, in one round trip. */
    public static final String DETECT = detect();

    private ToolCommands() {
    }

    private static String detect() {
        StringBuilder command = new StringBuilder("echo '@@tools'; ");
        for (Tool tool : Tool.values()) {
            command.append("command -v ").append(tool.binary())
                    .append(" >/dev/null 2>&1 && printf '%s\\n' ").append(tool.binary()).append("; ");
        }
        command.append("echo '@@manager'; ");
        for (PackageManager manager : PackageManager.values()) {
            if (manager.known()) {
                command.append("command -v ").append(manager.binary())
                        .append(" >/dev/null 2>&1 && printf '%s\\n' ").append(manager.binary()).append("; ");
            }
        }
        // Always ends well: the exit code of the last lookup says nothing about whether the answer is usable.
        return command.append("true").toString();
    }

    /**
     * The tools present, from the answer to {@link #DETECT}.
     */
    public static Set<Tool> parseTools(String output) {
        Set<Tool> found = EnumSet.noneOf(Tool.class);
        String block = com.kubo.network.LinkCommands.section(output, "tools");
        for (String line : block.split("\\R")) {
            String name = line.strip();
            for (Tool tool : Tool.values()) {
                if (tool.binary().equals(name)) {
                    found.add(tool);
                }
            }
        }
        return found;
    }

    /**
     * The package manager, from the answer to {@link #DETECT}.
     *
     * <p>The first one found wins, in the order of the enum, which puts the native manager of each family
     * ahead of anything installed beside it.
     */
    public static PackageManager parseManager(String output) {
        String block = com.kubo.network.LinkCommands.section(output, "manager");
        for (PackageManager manager : PackageManager.values()) {
            if (!manager.known()) {
                continue;
            }
            for (String line : block.split("\\R")) {
                if (line.strip().equals(manager.binary())) {
                    return manager;
                }
            }
        }
        return PackageManager.NONE;
    }

    /**
     * The command that installs one tool.
     *
     * @throws IllegalArgumentException when Kubo knows no package for this tool on this manager
     */
    public static String install(Tool tool, PackageManager manager) {
        String packageName = tool.packageFor(manager);
        if (packageName.isEmpty()) {
            throw new IllegalArgumentException("Kubo does not know which package carries " + tool.binary()
                    + " on this system");
        }
        return manager.install(packageName);
    }
}
