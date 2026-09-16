package com.kubo.files;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Names from the server made safe for Windows, which refuses more characters and names than Linux.
 */
public final class LocalNames {

    private static final String FORBIDDEN = "<>:\"/\\|?*";
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private LocalNames() {
    }

    /**
     * Replaces what Windows refuses by "_": forbidden characters, control characters, a trailing dot or space,
     * device names such as "con" or "aux.txt".
     */
    public static String sanitize(String name) {
        StringBuilder safe = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            safe.append(c < 0x20 || FORBIDDEN.indexOf(c) >= 0 ? '_' : c);
        }
        int end = safe.length();
        while (end > 0 && (safe.charAt(end - 1) == '.' || safe.charAt(end - 1) == ' ')) {
            safe.setCharAt(--end, '_');
        }
        String result = safe.toString();
        if (result.isEmpty()) {
            return "_";
        }
        int dot = result.indexOf('.');
        String stem = (dot < 0 ? result : result.substring(0, dot)).toUpperCase(Locale.ROOT);
        return RESERVED.contains(stem) ? "_" + result : result;
    }

    /**
     * {@code name} if nothing in the folder has it, otherwise "name (2).ext", "name (3).ext"…
     */
    public static String freeName(Path folder, String name, boolean directory) {
        if (!Files.exists(folder.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
            return name;
        }
        String[] parts = UploadCommands.stemAndExtension(name, directory);
        for (int i = 2; ; i++) {
            String candidate = parts[0] + " (" + i + ")" + parts[1];
            if (!Files.exists(folder.resolve(candidate), LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
    }
}
