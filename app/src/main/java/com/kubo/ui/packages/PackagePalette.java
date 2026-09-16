package com.kubo.ui.packages;

import com.kubo.model.packages.PackageEntry;
import com.kubo.model.packages.PackageRole;
import javafx.scene.paint.Color;

import java.util.Map;

/**
 * The colours the packages screen uses, in one place, so the tree and the legend can never disagree.
 */
final class PackagePalette {

    static final Color TEXT = Color.web("#e6e6ec");
    static final Color DIM = Color.web("#c9c9d4");
    /** A package that only arrived as a dependency. */
    static final Color QUIET = Color.web("#8d8d9b");
    static final Color MUTED = Color.web("#6e6e7d");
    static final Color BACKGROUND = Color.web("#050508");
    static final Color FAINT = Color.web("#1c1c24");
    static final Color ROOT = Color.web("#e6e6ec");
    /** A security update waiting. */
    static final Color ALERT = Color.web("#ff2d55");
    /** An ordinary update waiting. */
    static final Color AMBER = Color.web("#ffb347");
    static final Color LIVE = Color.web("#35e0a1");
    static final Color SCAN = Color.web("#35e8ff");

    private static final Map<PackageRole, Color> ROLES = Map.of(
            PackageRole.SYSTEM, Color.web("#ff3fd8"),
            PackageRole.LIBRARIES, Color.web("#5b6fd6"),
            PackageRole.SERVICES, Color.web("#35e8ff"),
            PackageRole.DEVELOPMENT, Color.web("#35e0a1"),
            PackageRole.APPLICATIONS, Color.web("#c77dff"),
            PackageRole.DOCUMENTATION, Color.web("#8a8fb0"));

    private PackagePalette() {
    }

    static Color of(PackageRole role) {
        return ROLES.getOrDefault(role, MUTED);
    }

    /**
     * A package's own colour: what is wrong with it first, then whether anyone asked for it.
     */
    static Color of(PackageEntry entry, PackageRole role) {
        if (entry.security()) {
            return ALERT;
        }
        if (entry.upgradable()) {
            return AMBER;
        }
        return entry.manual() ? of(role) : QUIET;
    }
}
