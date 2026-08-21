package com.dji.sdk.sample.tak

import org.junit.Assert.assertEquals
import org.junit.Test

/** What little remains after the ladder's retirement — see ZoomLadder's class doc. */
class ZoomLadderTest {

    @Test
    fun theFloorIsTheWideFraming() {
        assertEquals(1.0, ZoomLadder.MIN, 0.0)
    }

    @Test
    fun labelsHaveNoDecimals() {
        assertEquals("1X", ZoomLadder.label(1.0))
        assertEquals("28X", ZoomLadder.label(28.0))
    }
}
