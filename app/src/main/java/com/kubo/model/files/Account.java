package com.kubo.model.files;

import com.kubo.model.files.PermissionMode.Right;
import com.kubo.model.files.PermissionMode.Who;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The connected user as the server sees them, to tell in advance which actions the server will allow.
 * These checks only mirror the permission bits (no ACL, no sudo): the server keeps the last word.
 */
public record Account(int uid, String name, Set<String> groups, String home) {

    public Account {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(home, "home");
        groups = Set.copyOf(Objects.requireNonNull(groups, "groups"));
        if (uid < 0) {
            throw new IllegalArgumentException("uid cannot be negative");
        }
        if (name.isBlank() || !home.startsWith("/")) {
            throw new IllegalArgumentException("name cannot be blank and home must be absolute");
        }
    }

    public boolean isRoot() {
        return uid == 0;
    }

    /**
     * True when the entry belongs to this user; find prints the numeric id for owners it cannot name.
     */
    public boolean owns(RemoteEntry entry) {
        return entry.owner().equals(name) || entry.owner().equals(String.valueOf(uid));
    }

    public boolean canRead(RemoteEntry entry) {
        return allows(entry, Right.READ);
    }

    public boolean canWrite(RemoteEntry entry) {
        return allows(entry, Right.WRITE);
    }

    /**
     * Execute a file, or enter a folder.
     */
    public boolean canExecute(RemoteEntry entry) {
        return allows(entry, Right.EXECUTE);
    }

    /**
     * Only the owner, or root, may change a mode, whatever the bits say.
     */
    public boolean canChangeMode(RemoteEntry entry) {
        return isRoot() || owns(entry);
    }

    public boolean canCreateIn(RemoteEntry folder) {
        return canWrite(folder) && canExecute(folder);
    }

    /**
     * Deleting, renaming or moving an entry changes its folder, not the entry itself. In a sticky folder
     * such as /tmp, only the owner of the entry or of the folder may do it.
     */
    public boolean canRemove(RemoteEntry entry, RemoteEntry folder) {
        if (isRoot()) {
            return true;
        }
        if (!canCreateIn(folder)) {
            return false;
        }
        boolean sticky = PermissionMode.parseSymbolic(folder.permissions())
                .map(mode -> mode.hasSpecial(PermissionMode.STICKY))
                .orElse(false);
        return !sticky || owns(entry) || owns(folder);
    }

    /**
     * Which rights apply to this user: the owner's, the group's or everyone else's. Only one class
     * applies, even when another would grant more.
     */
    public Who classOf(RemoteEntry entry) {
        if (owns(entry)) {
            return Who.OWNER;
        }
        return groups.contains(entry.group()) ? Who.GROUP : Who.OTHERS;
    }

    private boolean allows(RemoteEntry entry, Right right) {
        if (isRoot()) {
            return true;
        }
        Optional<PermissionMode> mode = PermissionMode.parseSymbolic(entry.permissions());
        // A mode not read yet, like the server root's: let the server decide.
        return mode.isEmpty() || mode.get().has(classOf(entry), right);
    }
}
