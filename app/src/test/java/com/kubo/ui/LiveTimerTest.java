package com.kubo.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How often the shared frame clock lets a canvas draw.
 */
class LiveTimerTest {

    private static final long MS = 1_000_000;

    /** Counts the frames drawn during one second of pulses at {@code hz}, each pulse late by up to {@code jitter}. */
    private static int framesInOneSecond(double hz, double maxFps, long jitter) {
        long last = -1;
        int frames = 0;
        double step = 1e9 / hz;
        for (int pulse = 0; pulse < hz; pulse++) {
            // Real pulses never arrive exactly on time; alternate early and late.
            long now = Math.round(pulse * step) + (pulse % 2 == 0 ? 0 : jitter);
            if (LiveTimer.due(now, last, maxFps)) {
                last = now;
                frames++;
            }
        }
        return frames;
    }

    @Test
    @DisplayName("with no cap, every pulse draws")
    void noCapDrawsEveryPulse() {
        assertEquals(60, framesInOneSecond(60, LiveTimer.EVERY_PULSE, 0));
    }

    @Test
    @DisplayName("the very first frame always draws, whatever the cap")
    void theFirstFrameIsNeverHeldBack() {
        assertTrue(LiveTimer.due(123 * MS, -1, 30));
    }

    @Test
    @DisplayName("capped at 30 on a 60 Hz pulse, it draws on every second pulse — half the work")
    void thirtyOnSixtyIsEveryOtherPulse() {
        assertEquals(30, framesInOneSecond(60, LiveTimer.AMBIENT, 0));
    }

    @Test
    @DisplayName("a pulse that arrives a little early still counts, so the rate does not sag to one in three")
    void jitterDoesNotDropTheRate() {
        // Without the tolerance, a pulse 1 ms early would miss its turn and the next frame would wait for a third
        // pulse: 20 frames a second instead of 30.
        int frames = framesInOneSecond(60, LiveTimer.AMBIENT, -MS);
        assertTrue(frames >= 29, "drew " + frames + " frames");
    }

    @Test
    @DisplayName("on a fast screen the cap holds, instead of drawing as often as the screen refreshes")
    void aFastScreenIsCapped() {
        int frames = framesInOneSecond(144, LiveTimer.AMBIENT, 0);
        assertTrue(frames <= 36, "drew " + frames + " frames on a 144 Hz pulse");
    }

    @Test
    @DisplayName("too soon after the last frame, nothing is drawn")
    void tooSoonIsSkipped() {
        assertFalse(LiveTimer.due(10 * MS, 0, 30));
        assertTrue(LiveTimer.due(34 * MS, 0, 30));
    }
}
