package com.dji.sdk.sample.tak

/**
 * The fixed zoom levels the button steps through, and the rule for moving between them.
 *
 * A pilot frames a subject at a few known magnifications, not at the values between them. The
 * same judgement the operator made on the Autel sibling (2026-08-15) applies here.
 *
 * ⚠ **THE RUNGS ARE THE CAMERA'S OWN GEARS, NOT THE AUTEL SIBLING'S.** The Autel ladder is
 * 1, 2, 3, 4, 6, 8, 10, 12, 16. This camera cannot do those. It reports
 * `ZoomRatiosRange{isContinuous=false, gears=[1, 3, 7, 14, 28, 56, 112]}` and it CLAMPS
 * anything else: asking for 2.0 on the bench returned 3.0, and the button then displayed a
 * magnification the camera was not at (2026-08-20). Parity of NUMBERS is not available on this
 * hardware; parity of BEHAVIOUR is — one press moves one level, and no value between levels.
 *
 * 56 and 112 are real gears and are deliberately left out: the operator does not want zoom
 * above 28 (2026-08-20). Adding them back is a one-line change, but read the FOV note below
 * first.
 *
 * ⚠ **1x IS THE WIDE CAMERA. EVERY OTHER RUNG IS THE ZOOM CAMERA.** The zoom ratio belongs to
 * the tele path, thus the caller must switch `KeyCameraVideoStreamSource` as well as set the
 * ratio — setting the ratio alone is accepted, held, and invisible. See `onZoomTapped`.
 *
 * ⚠ **THE FIELD OF VIEW IS WRONG ABOVE 1x AND THE AR PROJECTION DEPENDS ON IT.** The tele
 * camera is a different focal length, so treating a rung as a crop of the wide lens's FOV is
 * not correct arithmetic, and MSDK v5 exposes no FOV key on DJI. Markers dropped while zoomed
 * will be misplaced until each lens is measured on the bench. Known and deferred by the
 * operator (2026-08-20).
 *
 * NO ANDROID OR SDK IMPORTS, ON PURPOSE. This is the part with the arithmetic in it, thus the
 * part worth testing, and the flight activity cannot be reached from a unit test. See
 * [ZoomLadderTest].
 */
object ZoomLadder {

    /**
     * The levels, as the ratios the camera takes. Ascending, and every one of them is a gear
     * the camera published — do not add a value the camera did not name.
     */
    val RUNGS = doubleArrayOf(1.0, 3.0, 7.0, 14.0, 28.0)

    /** The lowest rung, which is the wide camera. */
    val MIN = RUNGS.first()

    /** The highest rung this application offers. */
    val MAX = RUNGS.last()

    /** True when this rung is the wide camera rather than the tele camera. */
    fun isWide(ratio: Double): Boolean = ratio <= MIN

    /**
     * The level one step from [current], travelling in [direction] (+1 in, -1 out). Returns
     * [current]'s own end of the ladder when there is nothing further to go to, thus the
     * caller's "did it move?" test doubles as the end stop.
     *
     * STRICTLY GREATER / STRICTLY LESS, not an index step. The camera does not have to be on a
     * rung for this to give a sensible answer: DJI Pilot 2 can leave it anywhere, and a camera
     * sitting at 5x steps up to 7x and down to 3x. Index arithmetic would need a separate
     * "am I on a rung" branch to do the same thing, and would have to decide what to do when
     * the answer was no.
     */
    fun next(current: Double, direction: Int): Double = when {
        direction > 0 -> RUNGS.firstOrNull { it > current } ?: RUNGS.last()
        direction < 0 -> RUNGS.lastOrNull { it < current } ?: RUNGS.first()
        else -> current
    }

    /**
     * The next rung up, wrapping to the bottom at the top of the ladder.
     *
     * The flight screen has ONE zoom button and no rocker, so a tap has to serve both
     * directions. Wrapping at the top means a pilot always gets back to 1x, and gets there by
     * pressing the control they are already pressing.
     */
    fun stepUpOrWrap(current: Double): Double =
        if (current >= MAX) MIN else next(current, +1)

    /** The label for a rung, without a trailing ".0" — "1X", "3X", "28X". */
    fun label(ratio: Double): String = "${ratio.toInt()}X"
}
