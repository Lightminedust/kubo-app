package com.kubo.packages;

import com.kubo.model.packages.RestartNeeds;
import com.kubo.model.packages.RestartNeeds.Kernel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading needrestart, which knows what an upgrade left running on the old copy of a library. */
class RestartNeedsTest {

    /** A real batch answer: a newer kernel installed, two daemons stale, one session. */
    private static final String AFTER_AN_UPGRADE = """
            NEEDRESTART-VER: 3.6
            NEEDRESTART-KCUR: 6.1.0-17-amd64
            NEEDRESTART-KEXP: 6.1.0-18-amd64
            NEEDRESTART-KSTA: 3
            NEEDRESTART-SVC: nginx.service
            NEEDRESTART-SVC: postfix@-.service
            NEEDRESTART-SESS: root @ session-1.scope
            NEEDRESTART-UCSTA: 0
            """;

    @Test
    void namesEveryServiceRunningOnSomethingReplaced() {
        RestartNeeds needs = RestartNeeds.parse(AFTER_AN_UPGRADE);

        assertTrue(needs.available());
        assertEquals(List.of("nginx.service", "postfix@-.service"), needs.services());
        assertEquals(List.of("root @ session-1.scope"), needs.sessions());
        assertFalse(needs.settled());
    }

    @Test
    void tellsAKernelThatNeedsARebootFromOneThatDoesNot() {
        RestartNeeds rebooting = RestartNeeds.parse(AFTER_AN_UPGRADE);

        assertEquals(Kernel.REBOOT_NEEDED, rebooting.kernel());
        assertTrue(rebooting.needsReboot());
        // The one thing no service restart can fix, so it is said rather than mixed in with the buttons.
        assertTrue(rebooting.kernelNote().contains("only a reboot can"), rebooting.kernelNote());
        assertTrue(rebooting.kernelNote().contains("6.1.0-18-amd64"));

        RestartNeeds compatible = RestartNeeds.parse("NEEDRESTART-KSTA: 2\nNEEDRESTART-KCUR: a\n"
                + "NEEDRESTART-KEXP: b");
        assertEquals(Kernel.ABI_COMPATIBLE, compatible.kernel());
        assertFalse(compatible.needsReboot());
        assertTrue(compatible.kernelNote().contains("can wait"), compatible.kernelNote());

        RestartNeeds fine = RestartNeeds.parse("NEEDRESTART-KSTA: 1");
        assertEquals(Kernel.CURRENT, fine.kernel());
        assertEquals("", fine.kernelNote(), "nothing to say about a kernel that is the one installed");
    }

    @Test
    void aServerWithNothingStaleSaysSo() {
        RestartNeeds needs = RestartNeeds.parse("NEEDRESTART-VER: 3.6\nNEEDRESTART-KSTA: 1");

        assertTrue(needs.settled());
        assertEquals("Nothing is running on anything that has been replaced.", needs.headline());
    }

    @Test
    void theHeadlineCountsWhatWasFound() {
        assertTrue(RestartNeeds.parse(AFTER_AN_UPGRADE).headline()
                .startsWith("2 services are running on libraries that have been replaced"));
        assertTrue(RestartNeeds.parse(AFTER_AN_UPGRADE).headline().contains("only a reboot will run it"));
        assertTrue(RestartNeeds.parse("NEEDRESTART-SVC: cron.service").headline()
                .startsWith("1 service is running"));
    }

    @Test
    void aMissingNeedrestartIsSaidRatherThanReadAsAllClear() {
        RestartNeeds absent = RestartNeeds.parse("NEEDRESTART-ABSENT");

        // Printing nothing and having nothing to report look identical, and one of them is a lie.
        assertFalse(absent.available());
        assertFalse(absent.settled());
        assertTrue(absent.headline().contains("cannot tell"), absent.headline());
        assertFalse(RestartNeeds.parse("").available());
        assertFalse(RestartNeeds.parse(null).available());
        assertFalse(RestartNeeds.parse("some other program's output").available());
    }

    @Test
    void theSameServiceNamedTwiceIsOneButton() {
        assertEquals(List.of("nginx.service"),
                RestartNeeds.parse("NEEDRESTART-SVC: nginx.service\nNEEDRESTART-SVC: nginx.service").services());
    }

    @Test
    void theCommandCannotStopAndWaitForAnAnswer() {
        // Without -b needrestart opens a menu, and a session with no terminal waits for it forever.
        assertTrue(AptCommands.NEEDRESTART.contains("needrestart -b"));
        assertTrue(AptCommands.NEEDRESTART.contains("NEEDRESTART-ABSENT"),
                "a machine without it has to say so, because silence reads as all clear");
    }
}
