package com.kubo.connection.ssh;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshOutputLimitTest {

    @Test
    void shortOutputIsKeptWhole() throws IOException {
        String text = SshConnection.readLimited(new ByteArrayInputStream("hello\n".getBytes(StandardCharsets.UTF_8)), 1024);

        assertEquals("hello\n", text);
    }

    @Test
    void longOutputKeepsItsBeginningAndSaysWhatWasLeftOut() throws IOException {
        byte[] output = new byte[3 * 1024 * 1024];
        java.util.Arrays.fill(output, (byte) 'x');

        String text = SshConnection.readLimited(new ByteArrayInputStream(output), 1024 * 1024);

        assertTrue(text.startsWith("x".repeat(1024 * 1024)));
        assertTrue(text.endsWith("[Kubo kept the first 1 MiB of " + output.length + " bytes]"));
    }
}
