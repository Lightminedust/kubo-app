package com.kubo.connection.ssh;

/**
 * Why a connection could not be opened, with a message meant for the user.
 */
public final class SshConnectionException extends Exception {

    public enum Reason {
        KEY_UNREADABLE,
        UNREACHABLE,
        HOST_KEY_REJECTED,
        HOST_KEY_CHANGED,
        AUTH_FAILED
    }

    private final Reason reason;

    public SshConnectionException(Reason reason, String message) {
        this(reason, message, null);
    }

    public SshConnectionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
