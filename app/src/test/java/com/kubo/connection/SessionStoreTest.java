package com.kubo.connection;

import com.kubo.connection.SavedSession.Auth;
import com.kubo.connection.ssh.SshTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStoreTest {

    private static final SshTarget VPS = SshTarget.parse("xana@203.0.113.42");
    private static final String KEY = "C:\\Users\\xana\\keys\\vps.ppk";

    /**
     * Reversible and visibly different from the secret, so the tests can check nothing is stored in clear.
     */
    private static final SessionStore.Protection FAKE = new SessionStore.Protection() {
        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String protect(char[] secret) {
            return "sealed:" + new StringBuilder(new String(secret)).reverse();
        }

        @Override
        public char[] unprotect(String sealed) {
            return new StringBuilder(sealed.substring("sealed:".length())).reverse().toString().toCharArray();
        }
    };

    @TempDir
    Path temp;

    private long now = 1_000;

    private SessionStore store() {
        return new SessionStore(temp.resolve("sessions.json"), FAKE, new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(now);
            }
        });
    }

    @Test
    void keySessionRemembersThePathButNotThePassphraseByDefault() throws IOException {
        SavedSession saved = store().remember(VPS, Auth.KEY, KEY, false, "hunter2".toCharArray());

        assertEquals(KEY, saved.keyFile());
        assertFalse(saved.remembersSecret());
        String json = Files.readString(temp.resolve("sessions.json"));
        assertTrue(json.contains("vps.ppk"));
        assertFalse(json.contains("hunter2"));
        assertFalse(json.contains("2retnuh"));
    }

    @Test
    void secretIsKeptOnlyProtectedAndOnlyWhenAsked() throws IOException {
        SessionStore store = store();
        SavedSession saved = store.remember(VPS, Auth.PASSWORD, null, true, "hunter2".toCharArray());

        String json = Files.readString(temp.resolve("sessions.json"));
        assertFalse(json.contains("hunter2"));
        assertTrue(saved.remembersSecret());
        assertArrayEquals("hunter2".toCharArray(), store.secret(store.list().get(0)));
    }

    @Test
    void connectingAgainUpdatesTheSameEntry() throws IOException {
        SessionStore store = store();
        SavedSession first = store.remember(VPS, Auth.KEY, KEY, false, null);
        now = 5_000;
        SavedSession second = store.remember(VPS, Auth.KEY, KEY, false, null);

        assertEquals(1, store.list().size());
        assertEquals(first.id(), second.id());
        assertEquals(5_000, store.list().get(0).lastUsedMillis());
    }

    @Test
    void differentKeyOrMethodIsAnotherSession() throws IOException {
        SessionStore store = store();
        store.remember(VPS, Auth.KEY, KEY, false, null);
        store.remember(VPS, Auth.KEY, "C:\\other.ppk", false, null);
        store.remember(VPS, Auth.PASSWORD, null, false, null);

        assertEquals(3, store.list().size());
    }

    @Test
    void mostRecentComesFirst() throws IOException {
        SessionStore store = store();
        store.remember(VPS, Auth.KEY, KEY, false, null);
        now = 9_000;
        store.remember(SshTarget.parse("deploy@203.0.113.10:2222"), Auth.PASSWORD, null, false, null);

        List<SavedSession> sessions = store.list();
        assertEquals("deploy", sessions.get(0).username());
        assertEquals(2222, sessions.get(0).port());
    }

    @Test
    void keepingTheRememberedSecretNeedsNoRetyping() throws IOException {
        SessionStore store = store();
        store.remember(VPS, Auth.PASSWORD, null, true, "hunter2".toCharArray());
        store.remember(VPS, Auth.PASSWORD, null, true, null);

        assertArrayEquals("hunter2".toCharArray(), store.secret(store.list().get(0)));
    }

    @Test
    void notAskingAnymoreErasesTheSecret() throws IOException {
        SessionStore store = store();
        store.remember(VPS, Auth.PASSWORD, null, true, "hunter2".toCharArray());
        store.remember(VPS, Auth.PASSWORD, null, false, null);

        assertFalse(store.list().get(0).remembersSecret());
        assertThrows(IOException.class, () -> store.secret(store.list().get(0)));
    }

    @Test
    void secretCanBeForgottenAndSessionDeleted() throws IOException {
        SessionStore store = store();
        SavedSession saved = store.remember(VPS, Auth.PASSWORD, null, true, "hunter2".toCharArray());

        store.forgetSecret(saved.id());
        assertFalse(store.list().get(0).remembersSecret());
        store.delete(saved.id());
        assertTrue(store.list().isEmpty());
    }

    @Test
    void brokenFileIsSetAsideNotOverwritten() throws IOException {
        Files.writeString(temp.resolve("sessions.json"), "{ not json");

        assertTrue(store().list().isEmpty());
        assertTrue(Files.exists(temp.resolve("sessions.json.broken")));
    }

    @Test
    void keySessionWithoutKeyIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new SavedSession("id", "xana", "host", 22, Auth.KEY, "", "", 0));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsProtectsAndRestoresASecret() throws IOException {
        WindowsSecrets secrets = new WindowsSecrets();

        String sealed = secrets.protect("pässwörd €".toCharArray());

        assertNotEquals("pässwörd €", sealed);
        assertArrayEquals("pässwörd €".toCharArray(), secrets.unprotect(sealed));
    }
}
