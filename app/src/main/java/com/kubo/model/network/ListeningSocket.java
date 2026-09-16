package com.kubo.model.network;

import com.kubo.model.server.ListeningPort;

import java.util.Objects;

/**
 * A socket waiting for connections, together with the process that holds it.
 *
 * @param pid the owning process, or {@link #UNKNOWN_PID} when {@code ss} could not name it, which happens for
 *            other users' processes when Kubo is not root
 */
public record ListeningSocket(ListeningPort listening, int pid) {

    public static final int UNKNOWN_PID = -1;

    public ListeningSocket {
        Objects.requireNonNull(listening, "listening");
    }

    public int port() {
        return listening.port();
    }

    public String bindAddress() {
        return listening.bindAddress();
    }

    public ListeningPort.Protocol protocol() {
        return listening.protocol();
    }

    public String processName() {
        return listening.processName();
    }

    public ListeningPort.Exposure exposure() {
        return listening.exposure();
    }

    public boolean hasPid() {
        return pid > 0;
    }
}
