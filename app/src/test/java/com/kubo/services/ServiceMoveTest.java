package com.kubo.services;

import com.kubo.model.services.Action;
import com.kubo.model.services.BootState;
import com.kubo.model.services.DropIn;
import com.kubo.model.services.Move;
import com.kubo.model.services.ServiceUnit;
import com.kubo.model.services.Standing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Moving a service from one standing to another, and the one file it is given of its own.
 *
 * <p>A standing is what two separate answers add up to, so a move is a short sequence of ordinary verbs and
 * which verbs depends on where the service starts. These pin that sequence, and pin that a move is only
 * offered when it really lands where it says.
 */
class ServiceMoveTest {

    private static ServiceUnit unit(String active, String sub, BootState boot, String type) {
        return new ServiceUnit("nginx.service", "", "loaded", active, sub, boot, type, null);
    }

    private static final ServiceUnit RUNNING = unit("active", "running", BootState.ENABLED, "simple");
    private static final ServiceUnit LEAVING = unit("active", "running", BootState.DISABLED, "simple");
    private static final ServiceUnit STOPPED = unit("inactive", "dead", BootState.ENABLED, "simple");
    private static final ServiceUnit ASLEEP = unit("inactive", "dead", BootState.DISABLED, "simple");

    @Test
    @DisplayName("the standings each service really sits in, before any of it is moved")
    void theGroundTruth() {
        assertEquals(Standing.RUNNING, RUNNING.standing());
        assertEquals(Standing.WILL_NOT_RETURN, LEAVING.standing());
        assertEquals(Standing.NOT_RUNNING, STOPPED.standing());
        assertEquals(Standing.IDLE, ASLEEP.standing());
    }

    @Test
    @DisplayName("a service that will not come back is put right by enabling it, and nothing else")
    void oneStepIsOneStep() {
        assertEquals(List.of(Action.ENABLE), Move.stepsTo(Standing.RUNNING, LEAVING));
        assertEquals(List.of(Action.DISABLE), Move.stepsTo(Standing.WILL_NOT_RETURN, RUNNING));
        assertEquals(List.of(Action.START), Move.stepsTo(Standing.RUNNING, STOPPED));
    }

    @Test
    @DisplayName("a move that needs two verbs gets two, in the order that works")
    void twoStepsWhereTwoAreNeeded() {
        assertEquals(List.of(Action.START, Action.ENABLE), Move.stepsTo(Standing.RUNNING, ASLEEP));
        assertEquals(List.of(Action.STOP, Action.DISABLE), Move.stepsTo(Standing.IDLE, RUNNING));
        assertEquals(List.of(Action.ENABLE), Move.stepsTo(Standing.NOT_RUNNING, ASLEEP),
                "it is already stopped, so stopping it is not one of the steps");
        assertEquals(List.of(Action.START), Move.stepsTo(Standing.WILL_NOT_RETURN, ASLEEP),
                "it is already disabled, so disabling it is not one of the steps either");
    }

    @Test
    @DisplayName("nothing is ever moved into failed: a failure happens, it is not asked for")
    void failureIsNotADestination() {
        assertTrue(Move.stepsTo(Standing.FAILED, RUNNING).isEmpty());
        for (ServiceUnit any : List.of(RUNNING, LEAVING, STOPPED, ASLEEP)) {
            assertFalse(Move.targets(any).contains(Standing.FAILED), any.standing().name());
        }
    }

    @Test
    @DisplayName("where a service already is, is not offered as somewhere to move it")
    void nowhereIsAMove() {
        assertFalse(Move.targets(RUNNING).contains(Standing.RUNNING));
        assertEquals(List.of(Standing.WILL_NOT_RETURN, Standing.NOT_RUNNING, Standing.IDLE),
                Move.targets(RUNNING));
    }

    @Test
    @DisplayName("a one-shot task is offered no move at all, because none of these verbs would move it")
    void aMoveIsOnlyOfferedWhenItLands() {
        ServiceUnit oneShot = unit("inactive", "dead", BootState.DISABLED, "oneshot");
        assertEquals(Standing.IDLE, oneShot.standing());

        // It runs and exits. Started, it is idle again a moment later; enabled, it is an idle task that is
        // enabled, which is still idle. There is no sequence of these five verbs that takes it anywhere, so
        // the screen offers none — rather than offering one and leaving the card where it was.
        assertFalse(Move.lands(Standing.RUNNING, oneShot));
        assertFalse(Move.lands(Standing.NOT_RUNNING, oneShot));
        assertFalse(Move.lands(Standing.WILL_NOT_RETURN, oneShot));
        assertEquals(List.of(), Move.targets(oneShot));
    }

    @Test
    @DisplayName("the steps are judged by where they would really leave it, not by what they are called")
    void thePredictionIsTheTest() {
        assertEquals(Standing.RUNNING, Move.after(Move.stepsTo(Standing.RUNNING, ASLEEP), ASLEEP).standing());
        assertEquals(Standing.IDLE, Move.after(Move.stepsTo(Standing.IDLE, RUNNING), RUNNING).standing());
    }

    // The one file a service is given of its own

    @Test
    @DisplayName("a setting is added under the section when the file has not got it yet")
    void aSettingIsAdded() {
        assertEquals("[Service]\nRestart=always\n", DropIn.with("", "Restart", "always"));
        assertEquals("[Service]\nRestart=always\nMemoryMax=512M\n",
                DropIn.with("[Service]\nMemoryMax=512M", "Restart", "always"));
    }

    @Test
    @DisplayName("a setting already there is replaced in place, not added a second time")
    void aSettingIsReplaced() {
        String once = DropIn.with("[Service]\nRestart=no\nMemoryMax=512M\n", "Restart", "always");

        assertEquals("[Service]\nRestart=always\nMemoryMax=512M\n", once);
        assertEquals(1, once.lines().filter(line -> line.startsWith("Restart=")).count(),
                "several changes to one service are one file at the end, not a pile of them");
    }

    @Test
    @DisplayName("everything the file already said is still there afterwards, to the line")
    void nothingElseIsTouched() {
        String kept = DropIn.with("[Service]\n# put here by hand\nEnvironment=\"A=1\"\nRestart=no\n",
                "Restart", "always");

        assertTrue(kept.contains("# put here by hand"), kept);
        assertTrue(kept.contains("Environment=\"A=1\""), kept);
        assertTrue(kept.contains("Restart=always"), kept);
        assertFalse(kept.contains("Restart=no"), kept);
    }

    @Test
    @DisplayName("a setting is read back and taken away again by name")
    void readAndRemoved() {
        String text = "[Service]\nRestart = on-failure\nRestartSec=5s\n";

        assertEquals("on-failure", DropIn.valueOf(text, "Restart"), "however it was spaced");
        assertNull(DropIn.valueOf(text, "MemoryMax"));
        assertFalse(DropIn.without(text, "Restart").contains("Restart ="));
        assertTrue(DropIn.without(text, "Restart").contains("RestartSec=5s"), "a longer name is not the same one");
    }

    @Test
    @DisplayName("a file that says nothing is known for what it is, so it can be removed instead of saved")
    void anEmptyFileIsRecognised() {
        assertTrue(DropIn.saysNothing(""));
        assertTrue(DropIn.saysNothing("[Service]"));
        assertTrue(DropIn.saysNothing("[Service]\n\n# nothing but a note\n"));
        assertFalse(DropIn.saysNothing("[Service]\nRestart=always"));
    }
}
