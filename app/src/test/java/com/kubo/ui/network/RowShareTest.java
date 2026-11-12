package com.kubo.ui.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** How one row of the route is shared out: nothing short is ever cut for something long. */
class RowShareTest {

    @Test
    void withRoomToSpareEachGetsWhatItNeedsAndAnEvenShareOfTheRest() {
        assertArrayEquals(new double[]{150, 250}, RowShare.share(new double[]{100, 200}, 400), 1e-9);
    }

    @Test
    void shortOfRoomOnlyTheWidestAreCutAndAllToTheSameWidth() {
        // 100 fits under an even share and keeps all of it; the three wider ones share what is left equally.
        assertArrayEquals(new double[]{100, 140, 140, 140},
                RowShare.share(new double[]{100, 160, 170, 145}, 520), 1e-9);
        // With a little more room the 145 fits whole and only the two widest are cut.
        assertArrayEquals(new double[]{100, 157.5, 157.5, 145},
                RowShare.share(new double[]{100, 160, 170, 145}, 560), 1e-9);
    }

    @Test
    void whenEverythingIsTooWideEveryOneGetsTheSameShare() {
        assertArrayEquals(new double[]{100, 100, 100}, RowShare.share(new double[]{300, 200, 400}, 300), 1e-9);
    }

    @Test
    void anEmptyRowIsEmpty() {
        assertEquals(0, RowShare.share(new double[0], 500).length);
    }
}
