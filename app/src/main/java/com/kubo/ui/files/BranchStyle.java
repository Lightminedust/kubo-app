package com.kubo.ui.files;

import com.kubo.model.files.RemoteEntry;
import com.kubo.ui.Fibers;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.Locale;
import java.util.Set;

/**
 * The look shared by the file manager: colours by kind of entry, fonts, dot sizes and the small maths used to draw
 * fibers and particles.
 */
final class BranchStyle {

    static final Color BACKGROUND = Color.web("#050508");
    static final Color FOLDER = Color.web("#9d4dff");
    static final Color CODE = Color.web("#6ee0c8");
    static final Color MEDIA = Color.web("#e5484d");
    static final Color ARCHIVE = Color.web("#5b6fd6");
    static final Color OTHER = Color.web("#8a8fb0");
    static final Color LINK = Color.web("#ffb347");
    // Everything coming from the user's own computer.
    static final Color LOCAL = Color.web("#35e8ff");
    static final Color TEXT = Color.web("#e6e6ec");
    static final Color FOLDER_TEXT = Color.web("#c9c9d4");
    static final Color FILE_TEXT = Color.web("#8d8d9b");
    static final Color MUTED = Color.web("#6e6e7d");
    static final Color FAINT = Color.web("#1c1c24");
    static final Color ALERT = Color.web("#ff2d55");
    static final Color DENIED_TEXT = Color.web("#ff6b81");
    static final Color[] PROBE_COLORS = {FOLDER, MEDIA, ARCHIVE, CODE};

    static final Font SMALL_FONT = Font.font("Segoe UI", 9);
    static final Font CHIP_FONT = Font.font("Segoe UI", FontWeight.SEMI_BOLD, 9);
    static final Font HEADER_FONT = Font.font("Segoe UI", FontWeight.SEMI_BOLD, 10.5);
    static final Font TOOLTIP_FONT = Font.font("Segoe UI", 11);

    static final double TAU = Fibers.TAU;

    private static final Set<String> CODE_EXTENSIONS = Set.of("java", "kt", "kts", "py", "js", "mjs", "cjs", "ts",
            "tsx", "jsx", "vue", "go", "rs", "c", "h", "cpp", "cs", "php", "rb", "sh", "bash", "zsh", "json", "yml",
            "yaml", "xml", "toml", "ini", "conf", "cfg", "env", "md", "txt", "log", "csv", "html", "css", "scss",
            "sql", "properties", "gradle", "lock", "service", "map", "d.ts");
    private static final Set<String> MEDIA_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "svg", "ico",
            "bmp", "mp4", "mkv", "mov", "avi", "webm", "mp3", "wav", "flac", "ogg", "pdf", "woff", "woff2", "ttf");
    private static final Set<String> ARCHIVE_EXTENSIONS = Set.of("zip", "tar", "gz", "tgz", "xz", "bz2", "7z", "rar",
            "jar", "war", "deb", "rpm", "iso", "img", "bin", "so", "exe", "dll", "db", "sqlite", "dump", "bak", "node");

    private BranchStyle() {
    }

    static Color color(RemoteEntry entry) {
        return switch (entry.type()) {
            case DIRECTORY -> FOLDER;
            case LINK -> LINK;
            case OTHER -> OTHER;
            case FILE -> {
                String extension = entry.extension();
                String name = entry.name().toLowerCase(Locale.ROOT);
                if (CODE_EXTENSIONS.contains(extension) || name.equals("dockerfile") || name.equals("makefile")) {
                    yield CODE;
                }
                if (MEDIA_EXTENSIONS.contains(extension)) {
                    yield MEDIA;
                }
                if (ARCHIVE_EXTENSIONS.contains(extension)) {
                    yield ARCHIVE;
                }
                yield OTHER;
            }
        };
    }

    /**
     * Folders by entry count, files by weight; both logarithmic so one huge item does not dwarf the rest.
     */
    static double radius(RemoteEntry entry) {
        return switch (entry.type()) {
            case DIRECTORY -> entry.childCount() < 0
                    ? 4
                    : 3.2 + Math.min(10.5, 1.9 * Math.log(1 + entry.childCount()) / Math.log(2));
            case LINK -> 3.5;
            default -> 1.8 + Math.min(10, Math.log10(1 + entry.sizeBytes()));
        };
    }

    /**
     * A point on the fiber between two nodes; {@code jitter} bends it so parallel strands stay apart.
     */
    static double[] bezier(double x1, double y1, double x2, double y2, double jitter, double t) {
        return Fibers.bezier(x1, y1, x2, y2, jitter, t);
    }

    static double easeOutBack(double t) {
        return Fibers.easeOutBack(t);
    }

    /**
     * A stable pseudo-random number in [0, 1] for a text, so particles and strands keep their place from frame to frame.
     */
    static double hash(String text, int salt) {
        return Fibers.hash(text, salt);
    }

    static double clamp(double value, double min, double max) {
        return Fibers.clamp(value, min, max);
    }
}
