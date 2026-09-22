package com.kubo.model.network;

import com.kubo.model.server.ListeningPort;

import java.util.Objects;

/**
 * One connection that is open right now, in either direction.
 *
 * <p>The screen listed the ports a server answers on and stopped there. A listening port says a door exists; this
 * says somebody is standing in it. They are different questions, and the second one is the one asked during an
 * incident.
 *
 * @param inbound true when the local end is the listening side, so the remote end is the one that called
 * @param process empty when {@code ss} could not name it, which happens for other users without root
 * @param pid     {@link #UNKNOWN_PID} when unknown
 */
public record Connection(ListeningPort.Protocol protocol, String localAddress, int localPort,
                         String remoteAddress, int remotePort, String process, int pid, boolean inbound) {

    public static final int UNKNOWN_PID = -1;

    public Connection {
        Objects.requireNonNull(protocol, "protocol");
        localAddress = localAddress == null ? "" : localAddress;
        remoteAddress = remoteAddress == null ? "" : remoteAddress;
        process = process == null ? "" : process;
        if (localPort <= 0 || localPort > 65535 || remotePort <= 0 || remotePort > 65535) {
            throw new IllegalArgumentException("ports must be between 1 and 65535");
        }
    }

    /** The port that identifies the service: the local one for a call coming in, the remote one for a call going out. */
    public int servicePort() {
        return inbound ? localPort : remotePort;
    }

    /** How far away the other end is. A public remote address on an inbound connection came off the internet. */
    public ListeningPort.Exposure remoteExposure() {
        return ListeningPort.exposureOf(remoteAddress);
    }

    public boolean fromTheInternet() {
        return inbound && remoteExposure() == ListeningPort.Exposure.PUBLIC;
    }

    public boolean named() {
        return !process.isBlank();
    }
}
