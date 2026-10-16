package com.kubo.model.server;

import com.kubo.model.server.ListeningPort.Exposure;
import com.kubo.model.server.ListeningPort.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ListeningPortTest {

    private static ListeningPort port(String bindAddress) {
        return new ListeningPort(Protocol.TCP, bindAddress, 8080, "");
    }

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({
            // Loopback
            "127.0.0.1,           LOCAL",
            "127.0.0.53%lo,       LOCAL",
            "::1,                 LOCAL",
            "[::1],               LOCAL",
            "::ffff:127.0.0.1,    LOCAL",
            // Private networks
            "10.0.0.5,            INTERNAL",
            "172.16.0.1,          INTERNAL",
            "172.17.0.1,          INTERNAL",
            "172.31.255.254,      INTERNAL",
            "192.168.1.10,        INTERNAL",
            "169.254.1.1,         INTERNAL",
            "[fe80::1]%eth0,      INTERNAL",
            "fd12:3456::1,        INTERNAL",
            // All interfaces
            "0.0.0.0,             PUBLIC",
            "*,                   PUBLIC",
            "::,                  PUBLIC",
            "[::],                PUBLIC",
            // Public addresses
            "203.0.113.42,     PUBLIC",
            "172.32.0.1,          PUBLIC",
            "2a02:c207::1,        PUBLIC",
    })
    void exposureFollowsBindAddress(String bindAddress, Exposure expected) {
        assertEquals(expected, port(bindAddress).exposure());
    }

    @ParameterizedTest
    @ValueSource(strings = {"999.1.1.1", "not-an-address", "1.2.3", "gg::1"})
    void unparseableAddressIsTreatedAsPublic(String bindAddress) {
        assertEquals(Exposure.PUBLIC, port(bindAddress).exposure());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(NullPointerException.class, () -> new ListeningPort(null, "0.0.0.0", 22, ""));
        assertThrows(NullPointerException.class, () -> new ListeningPort(Protocol.TCP, null, 22, ""));
        assertThrows(NullPointerException.class, () -> new ListeningPort(Protocol.TCP, "0.0.0.0", 22, null));
        assertThrows(IllegalArgumentException.class, () -> new ListeningPort(Protocol.TCP, " ", 22, ""));
        assertThrows(IllegalArgumentException.class, () -> new ListeningPort(Protocol.TCP, "0.0.0.0", 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new ListeningPort(Protocol.UDP, "0.0.0.0", 65536, ""));
    }
}
