package com.kubo.model.network;

import java.util.ArrayList;
import java.util.List;

/**
 * The Nginx site files: the ones that exist, and the ones that are switched on.
 *
 * <p>Debian and its family keep every site in {@code sites-available} and symlink the live ones into
 * {@code sites-enabled}. A site that exists and is off is invisible to {@code nginx -T}, so it never appeared on
 * this screen at all, which is exactly the file somebody is looking for when a site stopped answering.
 */
public record NginxSites(List<String> available, List<String> enabled) {

    public NginxSites {
        available = List.copyOf(available == null ? List.of() : available);
        enabled = List.copyOf(enabled == null ? List.of() : enabled);
    }

    public static NginxSites none() {
        return new NginxSites(List.of(), List.of());
    }

    public boolean isEnabled(String site) {
        return enabled.contains(site);
    }

    /** Sites that exist but are switched off. */
    public List<String> disabled() {
        List<String> off = new ArrayList<>();
        for (String site : available) {
            if (!enabled.contains(site)) {
                off.add(site);
            }
        }
        return List.copyOf(off);
    }

    /**
     * Enabled links whose file is gone from sites-available.
     *
     * <p>A dangling link stops Nginx from starting at all, and the error it prints names the link rather than
     * the missing file.
     */
    public List<String> dangling() {
        List<String> broken = new ArrayList<>();
        for (String site : enabled) {
            if (!available.contains(site)) {
                broken.add(site);
            }
        }
        return List.copyOf(broken);
    }

    public boolean empty() {
        return available.isEmpty() && enabled.isEmpty();
    }
}
