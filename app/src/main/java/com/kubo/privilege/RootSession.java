package com.kubo.privilege;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;

import java.util.Objects;

/**
 * The one root state of a Kubo session: how this account can become root, the sudo password once the user has
 * given it, and whether root is in force right now.
 *
 * <p>Every screen shares this, so the password is asked for once rather than window by window, and every window can
 * show that it is running as root. Kubo opens one server at a time, which is why the state that the window frames
 * read is held statically; the object itself is passed to the screens that need to run something.
 */
public final class RootSession {

    private static final ReadOnlyBooleanWrapper ELEVATED = new ReadOnlyBooleanWrapper(false);

    private final Privilege privilege;

    public RootSession(RootAccess access) {
        this.privilege = new Privilege(Objects.requireNonNull(access, "access"));
        refresh();
    }

    /**
     * True while Kubo is holding root for this session, watched by every window frame.
     */
    public static ReadOnlyBooleanProperty elevatedProperty() {
        return ELEVATED.getReadOnlyProperty();
    }

    /**
     * The name to show: Kubo's wordmark, marked when root is in force.
     */
    public static String wordmark() {
        return ELEVATED.get() ? "Kubo_root" : "Kubo_";
    }

    public Privilege privilege() {
        return privilege;
    }

    public RootAccess access() {
        return privilege.access();
    }

    /**
     * True when this account can become root at all.
     */
    public boolean isPossible() {
        return privilege.available();
    }

    /**
     * True when Kubo can run a root command right now, without asking for anything.
     */
    public boolean isElevated() {
        return privilege.available() && !privilege.needsPassword();
    }

    /**
     * True when becoming root still needs the user's sudo password.
     */
    public boolean needsPassword() {
        return privilege.needsPassword();
    }

    /**
     * Called once the password has been accepted, so every window can show it.
     */
    public void refresh() {
        boolean elevated = isElevated();
        if (ELEVATED.get() != elevated) {
            if (javafx.application.Platform.isFxApplicationThread()) {
                ELEVATED.set(elevated);
            } else {
                javafx.application.Platform.runLater(() -> ELEVATED.set(elevated));
            }
        }
    }

    /**
     * Forgets the sudo password and leaves root mode. The account keeps whatever access it had.
     */
    public void drop() {
        privilege.wipe();
        refresh();
    }

    /**
     * Wipes the password and clears the shared state; call when the connection closes.
     */
    public void close() {
        privilege.wipe();
        if (javafx.application.Platform.isFxApplicationThread()) {
            ELEVATED.set(false);
        } else {
            javafx.application.Platform.runLater(() -> ELEVATED.set(false));
        }
    }

    /**
     * Why root is not available, worded for the user; empty when it is.
     */
    public String unavailableReason() {
        return switch (access()) {
            case ROOT, SUDO_NOPASSWD, SUDO_PASSWORD -> "";
            case NONE -> "This account cannot become root on this server: it is not root and it is not in the "
                    + "sudo, wheel or admin group.";
        };
    }
}
