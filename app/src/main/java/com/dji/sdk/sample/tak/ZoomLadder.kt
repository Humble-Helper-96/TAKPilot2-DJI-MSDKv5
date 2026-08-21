package com.dji.sdk.sample.tak

/**
 * Zoom display helpers — all that remains of the ladder.
 *
 * ⚠ THIS FILE ONCE HELD A TEN-RUNG TAP LADDER WITH HYBRID DISPLAY CROPS, and before that a
 * five-gear one. Both are gone (operator, 2026-08-20, over one long bench afternoon): the
 * right dial reaches the camera's zoom through DJI's firmware on this aircraft, smoothly and
 * continuously, so every app-side stepping scheme was a fight with the hardware. The final
 * design needs no ladder at all — the dial zooms 1x-28x, the pill snaps to 1x, the display
 * follows the camera's own ratio. See `onZoomTapped` and `CameraZoomFollow` for the story.
 *
 * NO ANDROID OR SDK IMPORTS, ON PURPOSE — [ZoomLadderTest] pins what little is left.
 */
object ZoomLadder {

    /** The bottom of the range: 1x, the wide framing. The snap-to-1X pill's target. */
    const val MIN = 1.0

    /** The label for a whole-number ratio, without a trailing ".0" — "1X", "28X". Fractional
     *  ratios are formatted by the caller ("4.6X"); this is only the clean-integer case. */
    fun label(ratio: Double): String = "${ratio.toInt()}X"
}
