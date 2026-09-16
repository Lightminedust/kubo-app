package com.kubo.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchCameraTest {

    @Test
    void screenAndWorldCoordinatesAreInverse() {
        BranchCamera camera = new BranchCamera();
        camera.camX = 120;
        camera.camY = -40;
        camera.zoom = 0.5;

        assertEquals(333, camera.sx(camera.worldX(333)), 1e-9);
        assertEquals(77, camera.sy(camera.worldY(77)), 1e-9);
    }

    @Test
    void zoomKeepsThePointUnderTheCursorStill() {
        BranchCamera camera = new BranchCamera();
        camera.camX = 50;
        camera.camY = 10;
        double worldX = camera.worldX(400);
        double worldY = camera.worldY(300);

        camera.zoomAround(2, 400, 300, false);

        assertEquals(2, camera.zoom, 1e-9);
        assertEquals(worldX, camera.worldX(400), 1e-9);
        assertEquals(worldY, camera.worldY(300), 1e-9);
    }

    @Test
    void zoomStaysWithinItsLimits() {
        BranchCamera camera = new BranchCamera();

        camera.zoomAround(1000, 0, 0, false);
        assertEquals(BranchCamera.MAX_ZOOM, camera.zoom, 1e-9);
        camera.zoomAround(1e-6, 0, 0, false);
        assertEquals(BranchCamera.MIN_ZOOM, camera.zoom, 1e-9);
    }

    @Test
    void aThrowGlidesThenSlowsDown() {
        BranchCamera camera = new BranchCamera();
        camera.press(0, 0, 0);
        camera.drag(-100, 0, 20_000_000);
        camera.release(25_000_000);
        double start = camera.camX;

        camera.step(0.05);
        double first = camera.camX - start;
        camera.step(0.05);
        double second = camera.camX - start - first;

        assertTrue(first > 0);
        assertTrue(second > 0 && second < first);
    }

    @Test
    void aHandThatStoppedBeforeLettingGoDoesNotThrow() {
        BranchCamera camera = new BranchCamera();
        camera.press(0, 0, 0);
        camera.drag(-100, 0, 20_000_000);
        camera.release(500_000_000);
        double x = camera.camX;

        camera.step(0.1);

        assertEquals(x, camera.camX, 1e-9);
    }

    @Test
    void focusPutsTheColumnRightOfCentreAndTheRowMidHeight() {
        BranchCamera camera = new BranchCamera();

        camera.focus(990, 240, 1000, 700, true);

        assertEquals(1000 * 0.55, camera.sx(990), 1e-6);
        assertEquals(BranchCamera.HEADER_HEIGHT + (700 - BranchCamera.HEADER_HEIGHT) / 2, camera.sy(240), 1e-6);
    }

    @Test
    void fitNeverShrinksNamesBelowReadable() {
        BranchCamera camera = new BranchCamera();

        camera.fit(new double[]{0, 100_000, 0, 100_000}, 1000, 700);

        assertEquals(BranchCamera.FIT_MIN_ZOOM, camera.targetZoom, 1e-9);
    }
}
