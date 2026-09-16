package com.kubo.monitor.parse;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionsTest {

    @Test
    void scriptPrintsAMarkerBeforeEachCommand() {
        assertEquals("echo @@host; hostname; echo @@load; cat /proc/loadavg",
                Sections.script("host", "hostname", "load", "cat /proc/loadavg"));
    }

    @Test
    void scriptRejectsUnpairedArguments() {
        assertThrows(IllegalArgumentException.class, () -> Sections.script("host"));
        assertThrows(IllegalArgumentException.class, Sections::script);
    }

    @Test
    void splitGroupsLinesUnderTheirMarker() {
        Map<String, List<String>> sections = Sections.split("""
                ignored before any marker
                @@host
                vps-kubo

                @@load
                0.42 0.30 0.25 3/287 12345
                @@empty
                """);

        assertEquals(List.of("vps-kubo"), sections.get("host"));
        assertEquals(List.of("0.42 0.30 0.25 3/287 12345"), sections.get("load"));
        assertTrue(sections.get("empty").isEmpty());
        assertEquals(3, sections.size());
    }

    @Test
    void splitHandlesWindowsLineEndings() {
        Map<String, List<String>> sections = Sections.split("@@host\r\nvps-kubo\r\n");

        assertEquals(List.of("vps-kubo"), sections.get("host"));
    }

    @Test
    void firstLineFailsOnMissingSection() {
        Map<String, List<String>> sections = Sections.split("@@host\n");

        assertThrows(IllegalArgumentException.class, () -> Sections.firstLine(sections, "host"));
        assertThrows(IllegalArgumentException.class, () -> Sections.firstLine(sections, "missing"));
    }

    @Test
    void keyValuesKeepsTheFirstWordOfEachValue() {
        Map<String, String> values = Sections.keyValues(List.of(
                "MemTotal:        8000000 kB",
                "HugePages_Total:       0",
                "no colon here"));

        assertEquals(Map.of("MemTotal", "8000000", "HugePages_Total", "0"), values);
    }
}
