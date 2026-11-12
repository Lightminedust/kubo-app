package com.kubo.ui;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WindowAreaTest {
    @Test void autoHideKeepsTopRightCornerInsideKubo() {
        Rectangle2D screen = new Rectangle2D(0, 0, 1920, 1080);
        Rectangle2D expanded = WindowArea.expanded(screen, screen, true);
        assertEquals(new Rectangle2D(0, 0, 1920, 1078), expanded);
        assertTrue(expanded.contains(1919, 0), "The screen corner must not click through to another window");
    }

    @Test void pinnedTaskbarIsNeverCovered() {
        Rectangle2D screen = new Rectangle2D(0, 0, 1920, 1080);
        assertEquals(new Rectangle2D(0, 0, 1920, 1040),
                WindowArea.expanded(screen, new Rectangle2D(0, 0, 1920, 1040), true));
        assertEquals(new Rectangle2D(48, 0, 1872, 1078),
                WindowArea.expanded(screen, new Rectangle2D(48, 0, 1872, 1080), true));
    }

    @Test void monitorAboveAndLeftRetainsItsCoordinates() {
        Rectangle2D screen = new Rectangle2D(-1920, -1080, 1920, 1080);
        Rectangle2D expanded = WindowArea.expanded(screen, screen, true);
        assertEquals(new Rectangle2D(-1920, -1080, 1920, 1078), expanded);
        assertTrue(expanded.contains(-1, -1080));
    }

    @Test void otherDesktopsUseTheirWorkArea() {
        Rectangle2D screen = new Rectangle2D(0, 0, 1440, 900);
        Rectangle2D work = new Rectangle2D(0, 24, 1440, 876);
        assertEquals(work, WindowArea.expanded(screen, work, false));
    }

    @Test void largestOverlapSelectsTheMonitorContainingMostOfTheWindow() {
        Rectangle2D window = new Rectangle2D(-100, 100, 1000, 600);
        assertTrue(WindowArea.overlap(window, new Rectangle2D(0, 0, 1920, 1080))
                > WindowArea.overlap(window, new Rectangle2D(-1920, 0, 1920, 1080)));
    }
}
