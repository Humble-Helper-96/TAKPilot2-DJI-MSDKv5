package com.dji.sdk.sample.tak

import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import com.taklite.util.AppLog

/**
 * The camera's OWN field of view, from its reported focal length — the calibration knob's
 * replacement, and the first correct FOV above 1x.
 *
 * MEASURED ON THE BENCH, 2026-08-20, stepping the ladder with the probe build (vc61):
 *
 *   KeyCameraHybridZoomFocalLength  700 / 1680 / 3360 / 6720 at rungs 3/7/14/28
 *   KeyCameraOpticalZoomFocalLength 240 while the WIDE camera is live
 *   KeyThermalFocalLength 52, KeyCameraIRFocalLength 527, constant
 *
 * The unit is the 35mm-EQUIVALENT focal length (x10 on hybrid/optical/ir; x1 on thermal), and
 * the hybrid value is LIVE — it includes the current zoom. Note 700 at "3X": the camera's real
 * gear is 2.917x, which dividing a base FOV by the nominal rung could never know. The
 * equivalence convention is full-frame DIAGONAL (43.27mm), confirmed against DJI's own spec:
 * 24mm equiv -> 84 deg diagonal, the published wide FOV; 52.7mm -> ~45 deg, the published
 * thermal FOV.
 *
 * So:  dfov = 2*atan(43.27 / (2*f35))   and the horizontal follows from the live picture
 * shape ([TakBridgeHolder.setVideoAspect]), the same identity the one-knob calibration uses.
 *
 * `KeyRetrieveLensFOV` — the key that SHOULD answer this directly — is refused for every lens
 * on this airframe (measured, 14 refusals per lens). The focal-length route is the one the
 * aircraft actually serves.
 *
 * ⚠ ASYNC READS ONLY (safety rule 4): the one-argument getValue reads a cache that is empty
 * on a cold start. And the answer is fed through a sanity gate — a camera that reports
 * something absurd must not send every marker to infinity.
 */
object CameraFov {

    private const val TAG = "CameraFov"
    private val MAIN_CAM = ComponentIndexType.LEFT_OR_MAIN

    /** Full-frame diagonal, mm — the reference the 35mm equivalence is defined against. */
    private const val FF_DIAGONAL_MM = 43.27

    /** The wide camera's 35mm-equivalent focal length. MEASURED (optical=240 while wide is
     *  live), and it matches DJI's published 24mm; used directly because the wide lens is
     *  fixed and the hybrid key follows the ZOOM lens, which idles wherever it was left. */
    private const val WIDE_F35_MM = 24.0

    /**
     * Refreshes [TakBridgeHolder]'s camera-reported FOV for the live source.
     *
     * @param irLive  true when the INFRARED camera is the live stream source.
     * @param zoomRatio the ladder's read-back — only consulted to decide wide vs zoom lens.
     */
    fun refresh(irLive: Boolean, zoomRatio: Double) {
        when {
            irLive -> {
                KeyManager.getInstance().getValue(
                    KeyTools.createCameraKey(
                        CameraKey.KeyCameraIRFocalLength, MAIN_CAM,
                        CameraLensType.CAMERA_LENS_THERMAL),
                    object : CommonCallbacks.CompletionCallbackWithParam<Int> {
                        override fun onSuccess(value: Int?) {
                            adopt(value?.let { it / 10.0 }, "thermal")
                        }

                        override fun onFailure(error: IDJIError) {
                            AppLog.w(TAG, "thermal focal read refused: ${error.description()}")
                        }
                    })
            }

            zoomRatio > 1.0 -> {
                KeyManager.getInstance().getValue(
                    KeyTools.createCameraKey(
                        CameraKey.KeyCameraHybridZoomFocalLength, MAIN_CAM,
                        CameraLensType.CAMERA_LENS_ZOOM),
                    object : CommonCallbacks.CompletionCallbackWithParam<Int> {
                        override fun onSuccess(value: Int?) {
                            adopt(value?.let { it / 10.0 }, "zoom")
                        }

                        override fun onFailure(error: IDJIError) {
                            AppLog.w(TAG, "hybrid focal read refused: ${error.description()}")
                        }
                    })
            }

            else -> adopt(WIDE_F35_MM, "wide")
        }
    }

    private fun adopt(f35mm: Double?, lens: String) {
        if (f35mm == null || !f35mm.isFinite() || f35mm < 4.0 || f35mm > 2000.0) {
            AppLog.w(TAG, "$lens focal length implausible ($f35mm mm) — keeping the previous FOV")
            return
        }
        val dfov = 2.0 * Math.toDegrees(Math.atan(FF_DIAGONAL_MM / (2.0 * f35mm)))
        TakBridgeHolder.setCameraFov(dfov)
        AppLog.i(TAG, "camera FOV adopted: $lens f35=${"%.1f".format(f35mm)}mm -> " +
            "dfov=${"%.1f".format(dfov)} deg (h=${"%.1f".format(TakBridgeHolder.currentHFov())})")
    }
}
