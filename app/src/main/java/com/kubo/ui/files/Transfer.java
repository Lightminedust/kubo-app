package com.kubo.ui.files;

import com.kubo.files.LocalScan;
import com.kubo.files.UploadCommands.Conflict;
import com.kubo.model.files.RemoteEntry;
import com.kubo.ui.scope.Format;

import java.nio.file.Path;

/**
 * One upload or download and how far it went. The worker thread updates the bytes and state; the UI thread reads
 * them to show progress, speed and time left.
 */
final class Transfer {

    enum State { READING, WAITING, SENDING, CONFLICT, DONE, FAILED, CANCELLED }

    final boolean download;
    // Upload: what is sent. Download: the local folder receiving it.
    final Path local;
    // Upload: the folder receiving it. Download: what is fetched.
    final RemoteEntry remote;
    String name;
    volatile State state = State.WAITING;
    volatile long sent;
    volatile boolean cancelled;
    LocalScan scan;
    long total = -1;
    // The total is a folder size measured beforehand, not the exact number of bytes that will travel.
    boolean estimated;
    Conflict conflict = Conflict.REFUSE;
    String message = "";
    String destination;
    Path saved;
    double finishedAt = -1;

    private long lastNanos;
    private long lastSent;
    private double speed;

    Transfer(boolean download, Path local, RemoteEntry remote, String name) {
        this.download = download;
        this.local = local;
        this.remote = remote;
        this.name = name;
    }

    boolean running() {
        return state == State.READING || state == State.WAITING || state == State.SENDING;
    }

    /**
     * The folder this lands in, for the user.
     */
    String where() {
        return download ? "TO " + local : "INTO " + remote.path();
    }

    /**
     * Resets the counters when bytes start travelling.
     */
    void start(long nanos) {
        sent = 0;
        lastSent = 0;
        lastNanos = nanos;
        speed = 0;
        state = State.SENDING;
    }

    /**
     * Updates the smoothed speed, at most four times a second so it does not jitter.
     */
    void measureSpeed(long nanos) {
        double seconds = (nanos - lastNanos) / 1e9;
        if (seconds >= 0.25 && lastNanos > 0) {
            double instant = (sent - lastSent) / seconds;
            speed = speed <= 0 ? instant : speed * 0.7 + instant * 0.3;
            lastSent = sent;
            lastNanos = nanos;
        }
    }

    double speed() {
        return speed;
    }

    /**
     * Sets a speed directly; for previews.
     */
    void setSpeed(double bytesPerSecond) {
        speed = bytesPerSecond;
    }

    /**
     * 0 to 1; an estimated total never reads as finished before the end.
     */
    double fraction() {
        return total > 0 ? Math.min(estimated ? 0.99 : 1, (double) sent / total) : 0;
    }

    String sizeText() {
        String approx = estimated ? "~" : "";
        if (total < 0) {
            return state == State.SENDING ? Format.bytes(sent) : "";
        }
        return state == State.SENDING
                ? Format.bytes(sent) + " / " + approx + Format.bytes(total)
                : approx + Format.bytes(total);
    }

    String statusText() {
        return switch (state) {
            case READING -> download ? "MEASURING IT ON THE SERVER…" : "READING IT ON THIS COMPUTER…";
            case WAITING -> "WAITING  ·  " + where();
            case SENDING -> {
                String approx = estimated ? "~" : "";
                String left = speed > 1 && total > sent
                        ? "  ·  " + approx + duration((total - sent) / speed) + " LEFT" : "";
                yield (total > 0 ? Math.round(fraction() * 100) + " %  ·  " : "")
                        + Format.bytes((long) speed) + "/S" + left + "  ·  " + where();
            }
            default -> message;
        };
    }

    static String duration(double seconds) {
        long total = Math.max(0, Math.round(seconds));
        if (total < 60) {
            return total + " S";
        }
        if (total < 3600) {
            return total / 60 + " MIN " + total % 60 + " S";
        }
        return total / 3600 + " H " + total % 3600 / 60 + " MIN";
    }
}
