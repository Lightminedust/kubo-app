package com.kubo.shell;

import com.kubo.model.shell.Pm2Process;
import com.kubo.model.shell.TableCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("What pm2 is running, read as data")
class Pm2ProcessTest {

    private static final String JLIST = """
            [{"pid":4021,"name":"api","pm_id":0,
              "monit":{"memory":58720256,"cpu":1.4},
              "pm2_env":{"status":"online","pm_uptime":1758801906000,"restart_time":3,
                         "exec_mode":"cluster_mode","instances":2,"namespace":"default"}},
             {"pid":0,"name":"worker","pm_id":1,
              "monit":{"memory":0,"cpu":0},
              "pm2_env":{"status":"stopped","pm_uptime":0,"restart_time":0,
                         "exec_mode":"fork_mode","instances":1,"namespace":"jobs"}}]
            """;

    @Test
    void everyColumnComesOutOfTheJsonRatherThanOutOfTheDrawing() {
        List<Pm2Process> found = Pm2Process.parse(JLIST);

        assertEquals(2, found.size());
        Pm2Process api = found.get(0);
        assertEquals(0, api.id());
        assertEquals("api", api.name());
        assertEquals("cluster ×2", api.mode());
        assertEquals("online", api.status());
        assertEquals(3, api.restarts());
        assertEquals(1.4, api.cpu());
        assertEquals(58_720_256L, api.memory());
        assertEquals("default", api.namespace());
        assertTrue(api.online());
        assertFalse(api.broken());
    }

    @Test
    void oneThatIsNotRunningHasNoUptime() {
        Pm2Process worker = Pm2Process.parse(JLIST).get(1);

        assertEquals("fork", worker.mode(), "one instance is not worth saying twice");
        assertFalse(worker.online());
        assertEquals(null, worker.since());
        assertEquals(java.time.Duration.ZERO, worker.uptime(Instant.now()));
    }

    @Test
    void theBannerPm2PrintsOnAColdStartIsSteppedOver() {
        // The first call after a reboot starts the daemon and says so, in front of the JSON.
        String withBanner = "[PM2] Spawning PM2 daemon with pm2_home=/root/.pm2\n"
                + "[PM2] PM2 Successfully daemonized\n" + JLIST;

        assertEquals(2, Pm2Process.parse(withBanner).size());
    }

    @Test
    void anythingThatIsNotJsonIsNoProcesses() {
        assertEquals(List.of(), Pm2Process.parse("pm2: command not found"));
        assertEquals(List.of(), Pm2Process.parse("{\"not\":\"an array\"}"));
        assertEquals(List.of(), Pm2Process.parse("[  "));
        assertEquals(List.of(), Pm2Process.parse(""));
        assertEquals(List.of(), Pm2Process.parse(null));
    }

    @Test
    void anEmptyListIsAnAnswerToo() {
        assertEquals(List.of(), Pm2Process.parse("[]"), "pm2 installed with nothing to run");
    }

    @Test
    void theSpellingsOfAskingPm2WhatItIsRunning() {
        assertEquals(Optional.of(new TableCall(TableCall.Kind.PM2, false)), TableCall.of("pm2 list"));
        assertEquals(Optional.of(new TableCall(TableCall.Kind.PM2, false)), TableCall.of("pm2 ls"));
        assertEquals(Optional.of(new TableCall(TableCall.Kind.PM2, false)), TableCall.of("pm2 status"));
        assertEquals(Optional.of(new TableCall(TableCall.Kind.PM2, true)), TableCall.of("sudo pm2 list"));
    }

    @Test
    void anythingWithMoreToItIsLeftToTheShell() {
        assertEquals(Optional.empty(), TableCall.of("pm2 list --watch"), "that is asking for something else");
        assertEquals(Optional.empty(), TableCall.of("pm2 list | grep api"));
        assertEquals(Optional.empty(), TableCall.of("pm2 jlist"), "somebody typing that wants the JSON");
        assertEquals(Optional.empty(), TableCall.of("pm2 logs"));
        assertEquals(Optional.empty(), TableCall.of("ls"));
        assertEquals(Optional.empty(), TableCall.of(null));
    }
}
