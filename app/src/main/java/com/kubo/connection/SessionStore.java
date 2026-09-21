package com.kubo.connection;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.kubo.connection.SavedSession.Auth;
import com.kubo.connection.ssh.SshTarget;
import com.kubo.platform.Host;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The saved sessions, in {@code .kubo/sessions.json} under the user's home folder. Written whole to a temporary
 * file then moved into place, so the file is never half-written. Methods block when a secret is involved — the
 * system is asked to encrypt it — so call them off the UI thread.
 */
public final class SessionStore {

    public static final Path FILE = Path.of(System.getProperty("user.home"), ".kubo", "sessions.json");

    /**
     * How secrets are encrypted for storage.
     */
    public interface Protection {

        boolean available();

        /**
         * One sentence for the connection screen saying who can read a remembered secret, or why none is kept.
         * Shown to the user, so it has to be true of this system rather than of Kubo in general.
         */
        default String explanation() {
            return "";
        }

        String protect(char[] secret) throws IOException;

        char[] unprotect(String protectedSecret) throws IOException;
    }

    private record Document(List<SavedSession> sessions) {
    }

    private final Path file;
    private final Protection protection;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public SessionStore(Path file, Protection protection, Clock clock) {
        this.file = Objects.requireNonNull(file, "file");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The user's own store, secrets protected by whatever this system offers — and not stored at all where it
     * offers nothing.
     */
    public static SessionStore standard() {
        Protection protection = Host.isWindows() ? new WindowsSecrets() : new NoSecrets();
        return new SessionStore(FILE, protection, Clock.systemUTC());
    }

    public boolean canRememberSecrets() {
        return protection.available();
    }

    /**
     * What to tell the user about where a remembered secret lives on this system.
     */
    public String protectionNote() {
        return protection.explanation();
    }

    /**
     * Saved sessions, most recently used first. A file that cannot be read is set aside as "sessions.json.broken"
     * instead of being overwritten by the next save.
     */
    public synchronized List<SavedSession> list() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            Document document = mapper.readValue(file.toFile(), Document.class);
            List<SavedSession> sessions = new ArrayList<>(document.sessions() == null ? List.of() : document.sessions());
            sessions.removeIf(Objects::isNull);
            sessions.sort(Comparator.comparingLong(SavedSession::lastUsedMillis).reversed());
            return List.copyOf(sessions);
        } catch (IOException | RuntimeException e) {
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // Left where it is; listing stays empty.
            }
            return List.of();
        }
    }

    /**
     * Saves or updates the session used for a successful connection.
     *
     * @param keyFile        the key's path, null for a password session
     * @param rememberSecret whether the user asked to remember the password or passphrase; when false any secret
     *                       saved before is erased
     * @param secret         the secret typed now, or null to keep the one already saved
     * @return the session as saved
     * @throws IOException when the file cannot be written or the secret cannot be protected; nothing is changed
     */
    public synchronized SavedSession remember(SshTarget target, Auth auth, String keyFile, boolean rememberSecret,
                                              char[] secret) throws IOException {
        List<SavedSession> sessions = new ArrayList<>(list());
        SavedSession existing = sessions.stream().filter(session -> session.sameAs(target, auth, keyFile)).findFirst()
                .orElse(null);
        String protectedSecret = "";
        if (rememberSecret) {
            if (secret != null && secret.length > 0) {
                protectedSecret = protection.protect(secret);
            } else if (existing != null) {
                protectedSecret = existing.protectedSecret();
            }
        }
        long now = clock.millis();
        SavedSession saved = existing != null
                ? existing.withSecret(protectedSecret, now)
                : new SavedSession(UUID.randomUUID().toString(), target.username(), target.host(), target.port(), auth,
                keyFile, protectedSecret, now);
        sessions.remove(existing);
        sessions.add(0, saved);
        write(sessions);
        return saved;
    }

    public synchronized void forgetSecret(String id) throws IOException {
        List<SavedSession> sessions = new ArrayList<>(list());
        sessions.replaceAll(session -> session.id().equals(id) ? session.withSecret("", session.lastUsedMillis()) : session);
        write(sessions);
    }

    public synchronized void delete(String id) throws IOException {
        List<SavedSession> sessions = new ArrayList<>(list());
        sessions.removeIf(session -> session.id().equals(id));
        write(sessions);
    }

    /**
     * Decrypts a remembered secret; the caller wipes it after use.
     */
    public char[] secret(SavedSession session) throws IOException {
        if (!session.remembersSecret()) {
            throw new IOException("No secret remembered for " + session.target());
        }
        return protection.unprotect(session.protectedSecret());
    }

    private void write(List<SavedSession> sessions) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "sessions", ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), new Document(sessions));
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
