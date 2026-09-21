package com.kubo.connection;

import com.kubo.platform.Host;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Waiting on the PowerShell that encrypts and decrypts saved passwords.
 *
 * <p>These run real processes, because the failure they guard against only exists with one: a process that keeps
 * its output open. Reading that output to the end used to come before the timeout, so the timeout was never
 * reached and the connection screen waited for ever.
 */
class WindowsSecretsTest {

    private static Process start(List<String> windows, List<String> unix) throws IOException {
        return new ProcessBuilder(Host.isWindows() ? windows : unix)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    @Test
    @DisplayName("a process that never closes its output is killed at the timeout instead of waited on for ever")
    void aHungProcessIsStoppedOnTime() throws IOException {
        // Both keep their output open for thirty seconds.
        Process hung = start(List.of("ping", "-n", "30", "127.0.0.1"), List.of("sleep", "30"));
        long started = System.nanoTime();

        IOException error = assertThrows(IOException.class, () -> WindowsSecrets.collect(hung, 1_000));

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertTrue(elapsedMillis < 10_000, "gave up after " + elapsedMillis + " ms, not after about one second");
        assertTrue(error.getMessage().contains("No answer"), error.getMessage());
        assertFalse(hung.isAlive(), "the process has to be killed, not left running");
    }

    @Test
    @DisplayName("a process that answers in time is read to the end")
    void anAnswerIsReadCompletely() throws IOException {
        Process answering = start(List.of("cmd", "/c", "echo", "sealed"), List.of("echo", "sealed"));

        byte[] output = WindowsSecrets.collect(answering, 10_000);

        assertEquals("sealed", new String(output, StandardCharsets.US_ASCII).strip());
    }

    @Test
    @DisplayName("a process that fails is reported as a failure, not taken as an empty answer")
    void aFailureIsNotAnEmptyAnswer() throws IOException {
        Process failing = start(List.of("cmd", "/c", "exit", "3"), List.of("sh", "-c", "exit 3"));

        IOException error = assertThrows(IOException.class, () -> WindowsSecrets.collect(failing, 10_000));

        assertTrue(error.getMessage().contains("3"), error.getMessage());
    }
}
