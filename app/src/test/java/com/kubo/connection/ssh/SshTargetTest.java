package com.kubo.connection.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SshTargetTest {

    @Test
    void parseUsesDefaultPortWhenMissing() {
        SshTarget target = SshTarget.parse("root@1.2.3.4");

        assertEquals(new SshTarget("root", "1.2.3.4", SshTarget.DEFAULT_PORT), target);
    }

    @Test
    void parseReadsExplicitPort() {
        SshTarget target = SshTarget.parse("root@1.2.3.4:2222");

        assertEquals(new SshTarget("root", "1.2.3.4", 2222), target);
    }

    @Test
    void parseAcceptsHostname() {
        SshTarget target = SshTarget.parse("deploy@vps.example.com:22");

        assertEquals(new SshTarget("deploy", "vps.example.com", 22), target);
    }

    @Test
    void parseIgnoresSurroundingWhitespace() {
        SshTarget target = SshTarget.parse("  root@1.2.3.4:2222  ");

        assertEquals(new SshTarget("root", "1.2.3.4", 2222), target);
    }

    @Test
    void parseReadsBracketedIpv6WithPort() {
        SshTarget target = SshTarget.parse("root@[::1]:2222");

        assertEquals(new SshTarget("root", "::1", 2222), target);
    }

    @Test
    void parseReadsBracketedIpv6WithoutPort() {
        SshTarget target = SshTarget.parse("root@[2001:db8::1]");

        assertEquals(new SshTarget("root", "2001:db8::1", SshTarget.DEFAULT_PORT), target);
    }

    @Test
    void parseTreatsBareIpv6AsHostWithDefaultPort() {
        SshTarget target = SshTarget.parse("root@2001:db8::1");

        assertEquals(new SshTarget("root", "2001:db8::1", SshTarget.DEFAULT_PORT), target);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "1.2.3.4",           // missing user
            "@1.2.3.4",          // empty user
            "root@",             // missing host
            "root@:22",          // empty host
            "root@host:",        // empty port
            "root@host:abc",     // non-numeric port
            "root@host:0",       // port below range
            "root@host:70000",   // port above range
            "root@host:-22",     // negative port
            "ro ot@host",        // whitespace in user
            "root@ho st",        // whitespace in host
            "root@[::1",         // unclosed bracket
            "root@[::1]22",      // missing colon after bracket
            "root@[]:22"         // empty bracketed host
    })
    void parseRejectsInvalidTarget(String value) {
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse(value));
    }

    @Test
    void parseRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse(null));
    }

    @Test
    void constructorRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new SshTarget(null, "host", 22));
        assertThrows(IllegalArgumentException.class, () -> new SshTarget("root", null, 22));
        assertThrows(IllegalArgumentException.class, () -> new SshTarget("root", "host", 65536));
    }

    @Test
    void toStringFormatsIpv4() {
        assertEquals("root@1.2.3.4:22", SshTarget.parse("root@1.2.3.4").toString());
    }

    @Test
    void toStringBracketsIpv6() {
        assertEquals("root@[::1]:2222", SshTarget.parse("root@[::1]:2222").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"root@1.2.3.4", "root@1.2.3.4:2222", "root@[::1]:2222", "root@2001:db8::1"})
    void parseOfToStringGivesSameTarget(String value) {
        SshTarget target = SshTarget.parse(value);

        assertEquals(target, SshTarget.parse(target.toString()));
    }
}
