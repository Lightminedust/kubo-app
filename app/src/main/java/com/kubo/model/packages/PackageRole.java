package com.kubo.model.packages;

import java.util.Locale;
import java.util.Set;

/**
 * What a package is for, which is the question someone actually asks when looking at a thousand of them.
 *
 * <p>dpkg sections are too many and too fine to read at a glance, so they are folded into a handful of roles: what
 * the machine needs to run, what other packages depend on, what serves the outside, what builds software, what a
 * person actually runs, and what is only text.
 */
public enum PackageRole {

    SYSTEM("SYSTEM", "What the machine needs to boot and run"),
    LIBRARIES("LIBRARIES", "Shared code that other packages depend on"),
    SERVICES("SERVICES", "Things that listen and serve"),
    DEVELOPMENT("DEVELOPMENT", "Compilers, headers and source tools"),
    APPLICATIONS("APPLICATIONS", "Programs meant to be run"),
    DOCUMENTATION("DOCS & DATA", "Manuals, fonts and translations");

    private static final Set<String> SYSTEM_SECTIONS = Set.of("admin", "base", "kernel", "init", "shells",
            "metapackages", "misc", "oldlibs");
    private static final Set<String> SERVICE_SECTIONS = Set.of("web", "httpd", "database", "net", "mail", "news",
            "comm");
    private static final Set<String> DEVELOPMENT_SECTIONS = Set.of("devel", "libdevel", "vcs", "interpreters",
            "javascript", "java", "perl", "python", "ruby", "php", "golang", "rust", "haskell", "lisp", "ocaml");
    private static final Set<String> DOCUMENTATION_SECTIONS = Set.of("doc", "fonts", "localization", "text",
            "translations");

    private final String title;
    private final String note;

    PackageRole(String title, String note) {
        this.title = title;
        this.note = note;
    }

    public String title() {
        return title;
    }

    public String note() {
        return note;
    }

    /**
     * Works out what a package is for, from what dpkg says about it.
     */
    public static PackageRole of(PackageEntry entry) {
        String family = entry.family().toLowerCase(Locale.ROOT);
        String priority = entry.priority().toLowerCase(Locale.ROOT);
        // Anything the machine cannot boot without is system, whatever section it was filed under.
        if (entry.essential() || priority.equals("required") || priority.equals("important")) {
            return SYSTEM;
        }
        if (family.equals("libs") || family.startsWith("lib") && !family.equals("libdevel")) {
            return LIBRARIES;
        }
        if (DEVELOPMENT_SECTIONS.contains(family)) {
            return DEVELOPMENT;
        }
        if (SERVICE_SECTIONS.contains(family)) {
            return SERVICES;
        }
        if (DOCUMENTATION_SECTIONS.contains(family)) {
            return DOCUMENTATION;
        }
        if (SYSTEM_SECTIONS.contains(family)) {
            return SYSTEM;
        }
        return APPLICATIONS;
    }
}
