package com.kubo.model.server;

import com.kubo.model.server.ServerMetrics.LoadAverage;
import com.kubo.model.server.ServerMetrics.MemoryMetrics;
import com.kubo.model.server.ServerMetrics.NetworkMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerMetricsTest {

    private static final LoadAverage LOAD = new LoadAverage(0.5, 0.4, 0.3);
    private static final MemoryMetrics MEMORY = new MemoryMetrics(8_000, 6_000, 2_000, 2_000);
    private static final NetworkMetrics NETWORK = new NetworkMetrics(1_200, 800);

    private static ServerMetrics metrics(double cpuPercent, double stealPercent) {
        return new ServerMetrics(Instant.now(), cpuPercent, stealPercent, LOAD, MEMORY, NETWORK, 3_600, 120);
    }

    @Test
    void memoryUsedIsDerivedFromTotalAndAvailable() {
        MemoryMetrics memory = new MemoryMetrics(8_000, 6_000, 2_000, 500);

        assertEquals(2_000, memory.usedBytes());
        assertEquals(25.0, memory.usagePercentage(), 1e-9);
        assertEquals(1_500, memory.usedSwapBytes());
    }

    @Test
    void memoryWithoutSwapIsValid() {
        MemoryMetrics memory = new MemoryMetrics(8_000, 8_000, 0, 0);

        assertEquals(0, memory.usedSwapBytes());
        assertEquals(0.0, memory.usagePercentage(), 1e-9);
    }

    @Test
    void memoryRejectsImpossibleValues() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryMetrics(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MemoryMetrics(8_000, 9_000, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MemoryMetrics(8_000, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MemoryMetrics(8_000, 6_000, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new MemoryMetrics(8_000, 6_000, 2_000, 3_000));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 42.5, 100})
    void acceptsPercentagesInRange(double percent) {
        assertDoesNotThrow(() -> metrics(percent, percent));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 100.1, Double.NaN})
    void rejectsPercentagesOutOfRange(double percent) {
        assertThrows(IllegalArgumentException.class, () -> metrics(percent, 0));
        assertThrows(IllegalArgumentException.class, () -> metrics(0, percent));
    }

    @Test
    void rejectsOtherImpossibleValues() {
        assertThrows(IllegalArgumentException.class, () -> new LoadAverage(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new NetworkMetrics(-1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerMetrics(Instant.now(), 10, 0, LOAD, MEMORY, NETWORK, -1, 120));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerMetrics(Instant.now(), 10, 0, LOAD, MEMORY, NETWORK, 3_600, -1));
        assertThrows(NullPointerException.class,
                () -> new ServerMetrics(null, 10, 0, LOAD, MEMORY, NETWORK, 3_600, 120));
    }
}
