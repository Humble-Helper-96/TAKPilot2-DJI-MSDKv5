package com.dji.sdk.sample.tak

import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraSDCardState
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import com.taklite.util.AppLog

/**
 * Where the camera will actually write, for the home screen's pre-flight readout.
 *
 * ⚠ THIS EXISTS BECAUSE OF A REAL SILENT FAILURE. On the Autel sibling the camera was pointed
 * at internal memory, recording refused to start, and NOTHING on the flight screen said why —
 * an installed SD card is not enough if the camera is not writing to it. A pilot who cannot
 * see the storage target before take-off finds out afterwards, when the recording they needed
 * does not exist.
 *
 * So the readout is deliberately blunt about the one case that loses footage: RED means "you
 * will get no recording" — the target is internal memory, or the card is full, missing or
 * unusable. Green means a verified card. Amber means the camera has not answered yet, which
 * is its own state and must never be painted as "fine".
 *
 * ⚠ READS USE THE TWO-ARGUMENT getValue (safety rule 4). The one-argument form reads MSDK's
 * local cache and answers null for ever on a cold start.
 */
object AircraftStorage {

    private const val TAG = "AircraftStorage"
    private val MAIN_CAM = ComponentIndexType.LEFT_OR_MAIN

    /** What the camera says it will write to, or null before it answers. */
    @Volatile
    var location: CameraStorageLocation? = null
        private set

    /** The card's own health, or null before the camera answers. */
    @Volatile
    var sdState: CameraSDCardState? = null
        private set

    /** Free space on the card in MB, or null if unreported. */
    @Volatile
    var sdFreeMb: Int? = null
        private set

    /** True only when the camera will write to a card that is genuinely usable. */
    val willRecord: Boolean
        get() = location == CameraStorageLocation.SDCARD && sdState == CameraSDCardState.NORMAL

    /** True when the target is internal memory — the case that silently loses a recording. */
    val recordingToInternal: Boolean
        get() = location == CameraStorageLocation.INTERNAL ||
            location == CameraStorageLocation.INTERNAL_SSD

    fun refresh(onDone: (() -> Unit)? = null) {
        var left = 3
        fun step() {
            left--
            if (left == 0) {
                AppLog.v(TAG, "storage read-back: location=$location sd=$sdState free=$sdFreeMb")
                onDone?.invoke()
            }
        }

        KeyManager.getInstance().getValue(
            KeyTools.createKey(CameraKey.KeyCameraStorageLocation, MAIN_CAM),
            object : CommonCallbacks.CompletionCallbackWithParam<CameraStorageLocation> {
                override fun onSuccess(value: CameraStorageLocation?) { location = value; step() }
                override fun onFailure(error: IDJIError) { step() }
            })

        KeyManager.getInstance().getValue(
            KeyTools.createKey(CameraKey.KeyCameraSDCardState, MAIN_CAM),
            object : CommonCallbacks.CompletionCallbackWithParam<CameraSDCardState> {
                override fun onSuccess(value: CameraSDCardState?) { sdState = value; step() }
                override fun onFailure(error: IDJIError) { step() }
            })

        KeyManager.getInstance().getValue(
            KeyTools.createKey(CameraKey.KeySDCardRemainSpace, MAIN_CAM),
            object : CommonCallbacks.CompletionCallbackWithParam<Int> {
                override fun onSuccess(value: Int?) { sdFreeMb = value; step() }
                override fun onFailure(error: IDJIError) { step() }
            })
    }

    /** The pilot-facing line. Short, because it sits on a card row. */
    fun label(): String = when {
        recordingToInternal ->
            "RECORDING TO INTERNAL MEMORY" + (freeLabel()?.let { " · $it FREE" } ?: "")
        location == CameraStorageLocation.SDCARD && sdState == CameraSDCardState.NORMAL ->
            "SD CARD" + (freeLabel()?.let { " · $it FREE" } ?: "")
        location == CameraStorageLocation.SDCARD ->
            "SD CARD: " + (sdState?.name?.replace('_', ' ') ?: "—")
        else -> "STORAGE: —"
    }

    private fun freeLabel(): String? {
        val mb = sdFreeMb ?: return null
        return if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "$mb MB"
    }
}
