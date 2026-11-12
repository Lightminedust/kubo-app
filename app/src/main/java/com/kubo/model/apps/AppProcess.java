package com.kubo.model.apps;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * One process a process manager keeps running: one PM2 instance, or one supervisor program.
 *
 * @param app        the application it belongs to; the instances of a PM2 cluster share it
 * @param id         PM2's pm_id, or -1 for supervisor, which names its programs instead
 * @param instance   which instance of its application this is, from 0
 * @param status     the manager's own word: "online", "stopped", "errored", "RUNNING", "FATAL"…
 * @param pid        the running process, or 0 when there is none
 * @param cpu        percent of one core
 * @param memory     resident memory, in bytes
 * @param since      when it was last started, or null when the manager does not say
 * @param mode       "fork" or "cluster" for PM2, "program" for supervisor
 * @param script     what it runs, empty when unknown
 * @param cwd        where it runs from, empty when unknown
 * @param outLog     where its output goes, empty when unknown
 * @param errLog     where its errors go, empty when unknown
 * @param envPort    the PORT its environment gives it, or 0
 * @param version    the version its package.json declares, empty when unknown
 */
public record AppProcess(Manager manager, String app, int id, int instance, String status, int pid, double cpu,
                         long memory, Instant since, int restarts, String mode, String script, String cwd,
                         String interpreter, String outLog, String errLog, int envPort, String version) {

    public AppProcess {
        Objects.requireNonNull(manager, "manager");
        app = app == null ? "" : app;
        status = status == null ? "" : status;
        mode = mode == null ? "" : mode;
        script = script == null ? "" : script;
        cwd = cwd == null ? "" : cwd;
        interpreter = interpreter == null ? "" : interpreter;
        outLog = outLog == null ? "" : outLog;
        errLog = errLog == null ? "" : errLog;
        version = version == null ? "" : version;
    }

    /** Up and taking work. */
    public boolean online() {
        String word = status.toLowerCase(Locale.ROOT);
        return word.equals("online") || word.equals("running");
    }

    /** Stopped on its own, crashing, or given up on, rather than stopped by someone. */
    public boolean failed() {
        String word = status.toLowerCase(Locale.ROOT);
        return word.startsWith("error") || word.equals("fatal") || word.equals("backoff") || word.equals("exited");
    }

    /** On its way up or down. */
    public boolean moving() {
        String word = status.toLowerCase(Locale.ROOT);
        return word.equals("launching") || word.equals("starting") || word.equals("stopping")
                || word.equals("waiting restart") || word.equals("one-launch-status");
    }

    public Duration uptime(Instant now) {
        return since == null || !online() || since.isAfter(now) ? Duration.ZERO : Duration.between(since, now);
    }
}
