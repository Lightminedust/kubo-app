package com.kubo.connection;

import com.kubo.platform.Host;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Encrypts secrets with Windows DPAPI for the current Windows account: only this user, logged into this
 * computer, can decrypt them. Kubo holds no key of its own. PowerShell does the work; secrets travel through
 * its standard input, never its command line. Calls block for about a second: keep them off the UI thread.
 */
public final class WindowsSecrets implements SessionStore.Protection {

    private static final String PROTECT = "Add-Type -AssemblyName System.Security; "
            + "$d = [Convert]::FromBase64String([Console]::In.ReadToEnd().Trim()); "
            + "$p = [Security.Cryptography.ProtectedData]::Protect($d, $null, 'CurrentUser'); "
            + "[Console]::Out.Write([Convert]::ToBase64String($p))";
    private static final String UNPROTECT = "Add-Type -AssemblyName System.Security; "
            + "$d = [Convert]::FromBase64String([Console]::In.ReadToEnd().Trim()); "
            + "$p = [Security.Cryptography.ProtectedData]::Unprotect($d, $null, 'CurrentUser'); "
            + "[Console]::Out.Write([Convert]::ToBase64String($p))";

    @Override
    public boolean available() {
        return Host.isWindows();
    }

    @Override
    public String explanation() {
        return "Encrypted by Windows for your account only (DPAPI). Anyone using your open Windows session "
                + "could connect with it.";
    }

    @Override
    public String protect(char[] secret) throws IOException {
        byte[] bytes = utf8(secret);
        byte[] encoded = Base64.getEncoder().encode(bytes);
        try {
            byte[] result = powershell(PROTECT, encoded);
            return new String(result, StandardCharsets.US_ASCII).strip();
        } finally {
            Arrays.fill(bytes, (byte) 0);
            Arrays.fill(encoded, (byte) 0);
        }
    }

    @Override
    public char[] unprotect(String protectedSecret) throws IOException {
        byte[] result = powershell(UNPROTECT, protectedSecret.getBytes(StandardCharsets.US_ASCII));
        byte[] bytes = null;
        try {
            bytes = Base64.getDecoder().decode(new String(result, StandardCharsets.US_ASCII).strip());
            CharBuffer chars = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
            char[] secret = Arrays.copyOf(chars.array(), chars.limit());
            Arrays.fill(chars.array(), '\0');
            return secret;
        } catch (IllegalArgumentException e) {
            throw new IOException("Windows returned an unreadable secret", e);
        } finally {
            Arrays.fill(result, (byte) 0);
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    private byte[] powershell(String script, byte[] input) throws IOException {
        if (!available()) {
            throw new IOException("Saving secrets needs Windows");
        }
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(input);
        }
        try {
            return collect(process, TimeUnit.SECONDS.toMillis(20));
        } catch (IOException e) {
            throw new IOException("Windows could not " + (script == PROTECT ? "encrypt" : "decrypt") + " the secret", e);
        }
    }

    /**
     * Waits for a process and returns everything it wrote, giving up after {@code timeoutMillis}.
     *
     * <p>The output is read on another thread. Reading it here would block until the process closes its output,
     * so a PowerShell that hung without closing it would be waited on for ever, and the timeout would never be
     * reached. A process that runs out of time is killed, which also closes its output and frees the reader.
     *
     * @throws IOException when the process runs out of time, fails, or is interrupted
     */
    static byte[] collect(Process process, long timeoutMillis) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream stdout = process.getInputStream()) {
                stdout.transferTo(output);
            } catch (IOException ignored) {
                // The process was killed under us; what was read is thrown away below.
            }
        }, "kubo-dpapi-output");
        reader.setDaemon(true);
        reader.start();
        try {
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("No answer after " + timeoutMillis + " ms");
            }
            // The process has exited, so its output is closed and the reader is about to finish.
            reader.join(TimeUnit.SECONDS.toMillis(2));
            if (reader.isAlive()) {
                throw new IOException("The output never closed");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Exit code " + process.exitValue());
            }
            return output.toByteArray();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Interrupted", e);
        }
    }

    private static byte[] utf8(char[] secret) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret));
        byte[] bytes = Arrays.copyOf(buffer.array(), buffer.limit());
        Arrays.fill(buffer.array(), (byte) 0);
        return bytes;
    }
}
