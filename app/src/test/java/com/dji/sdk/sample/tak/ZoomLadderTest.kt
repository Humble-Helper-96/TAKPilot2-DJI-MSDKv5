package com.dji.sdk.sample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rungs are the CAMERA'S published gears, not the Autel sibling's numbers. If a value here
 * is changed to something the camera did not name, the camera clamps it and the button then
 * shows a magnification the camera is not at — which is the defect these tests exist to stop.
 */
class ZoomLadderTest {

    @Test
    fun theRungsAreTheCamerasOwnGears() {
        // Reported by the aircraft on 2026-08-20:
        // ZoomRatiosRange{isContinuous=false, gears=[1, 3, 7, 14, 28, 56, 112]}
        // 56 and 112 are deliberately absent — the operator does not want zoom above 28.
        assertEquals(
            listOf(1.0, 3.0, 7.0, 14.0, 28.0),
            ZoomLadder.RUNGS.toList(),
        )
    }

    @Test
    fun twoTimesIsNotARungBecauseTheCameraCannotDoIt() {
        // The bug this ladder replaces: the button asked for 2.0 and the camera held 3.0.
        assertFalse(ZoomLadder.RUNGS.any { it == 2.0 })
    }

    @Test
    fun stepsUpThroughEveryRung() {
        assertEquals(3.0, ZoomLadder.next(1.0, +1), 0.0)
        assertEquals(7.0, ZoomLadder.next(3.0, +1), 0.0)
        assertEquals(14.0, ZoomLadder.next(7.0, +1), 0.0)
        assertEquals(28.0, ZoomLadder.next(14.0, +1), 0.0)
    }

    @Test
    fun stepsDownThroughEveryRung() {
        assertEquals(14.0, ZoomLadder.next(28.0, -1), 0.0)
        assertEquals(3.0, ZoomLadder.next(7.0, -1), 0.0)
        assertEquals(1.0, ZoomLadder.next(3.0, -1), 0.0)
    }

    @Test
    fun theEndsHold() {
        assertEquals(28.0, ZoomLadder.next(28.0, +1), 0.0)
        assertEquals(1.0, ZoomLadder.next(1.0, -1), 0.0)
    }

    @Test
    fun aCameraLeftBetweenRungsStillMovesOneLevel() {
        // DJI Pilot 2 can leave the camera anywhere. 5x is on no rung.
        assertEquals(7.0, ZoomLadder.next(5.0, +1), 0.0)
        assertEquals(3.0, ZoomLadder.next(5.0, -1), 0.0)
    }

    @Test
    fun aCameraAboveOurCeilingStepsBackIntoTheLadder() {
        // 56x and 112x are real gears this application does not offer. If something else left
        // the camera there, stepping DOWN must bring it back to a rung we do offer.
        assertEquals(28.0, ZoomLadder.next(56.0, -1), 0.0)
        assertEquals(28.0, ZoomLadder.next(112.0, -1), 0.0)
    }

    @Test
    fun oneButtonWrapsFromTheTopBackToWide() {
        assertEquals(3.0, ZoomLadder.stepUpOrWrap(1.0), 0.0)
        assertEquals(28.0, ZoomLadder.stepUpOrWrap(14.0), 0.0)
        assertEquals(1.0, ZoomLadder.stepUpOrWrap(28.0), 0.0)
        // Above the ceiling it must also come home, not sit there.
        assertEquals(1.0, ZoomLadder.stepUpOrWrap(56.0), 0.0)
    }

    @Test
    fun onlyOneTimesIsTheWideCamera() {
        assertTrue(ZoomLadder.isWide(1.0))
        assertFalse(ZoomLadder.isWide(3.0))
        assertFalse(ZoomLadder.isWide(28.0))
    }

    @Test
    fun labelsHaveNoTrailingDecimal() {
        assertEquals("1X", ZoomLadder.label(1.0))
        assertEquals("28X", ZoomLadder.label(28.0))
    }
}
