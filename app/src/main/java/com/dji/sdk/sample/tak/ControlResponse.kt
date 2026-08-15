package com.dji.sdk.sample.tak

import android.content.Context
import com.taklite.util.AppLog
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager

/**
 * How fast the controller drives the camera: one FIXED number per response mode, written to the
 * aircraft at connect and on an explicit change.
 *
 * Ported from the MSDKv4 sibling 2026-08-14 (conformance V4). The behaviour is the sibling's;
 * only the SDK call changed — v4's `gimbal.setControllerMaxSpeed(Axis.PITCH, n)` becomes v5's
 * `GimbalKey.KeyPitchControlMaxSpeed`, an Integer key that can get, set and listen.
 *
 * ⚠ WHY FIXED, AND NOT A MULTIPLIER. The sibling once read the gimbal's current speed and
 * multiplied it by 1.5. That compounded, because the value it read was the one it had written
 * last time, and the 2026-08-12 logs caught it climbing 22 → 33 → 49 → 73 → 100 until it pinned
 * at the ceiling. The camera handled differently on every flight and the pilot had no way to
 * know which speed they had. A fixed target is the whole point: the same selection gives the
 * same feel every time, whatever the last session or the DJI app left behind.
 *
 * ⚠ THE VALUES ARE A STARTING POINT, NOT A MEASUREMENT, AND THEY ARE THE MINI 2's. They came
 * from the sibling, whose gimbal reports 1..100. **Nothing here has been felt on an M4T.** The
 * read-back line in Pre-Flight reports what the aircraft actually took, and that is the number
 * the first real adjustment starts from. Two named constants, so they are easy to move.
 *
 * ⚠ NOT CLAMPED THE WAY THE SIBLING CLAMPS. v4 asked the gimbal for its
 * PITCH_CONTROLLER_MAX_SPEED capability range and clamped into it. v5 exposes the value as a
 * key and does not publish a range alongside it, so there is nothing to clamp against. The
 * write is sent as-is and the READ-BACK is what proves what the aircraft took — if the M4T
 * reports a different scale, the read-back will show it and these constants move. That is the
 * same discipline, arrived at differently: trust the aircraft's answer, never the request.
 */
object ControlResponse {

    private const val TAG = "TP2Control"
    private const val PREFS = "takpilot2_tak"
    private const val KEY_MODE = "control_response_precision"

    /** Gimbal pitch speed for each mode. Mini 2 scale (1..100) — unverified on the M4T. */
    private const val NORMAL_PITCH_SPEED = 35
    private const val PRECISION_PITCH_SPEED = 15

    enum class Mode(val label: String, val pitchSpeed: Int) {
        NORMAL("Normal", NORMAL_PITCH_SPEED),
        PRECISION("Precision", PRECISION_PITCH_SPEED),
    }

    /** What the AIRCRAFT reports holding, read back after a write. Null until one lands. */
    @Volatile
    var aircraftPitchSpeed: Int? = null
        private set

    fun saved(context: Context): Mode =
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_MODE, false)
        ) Mode.PRECISION else Mode.NORMAL

    fun save(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MODE, mode == Mode.PRECISION).apply()
        AppLog.i(TAG, "control response set to ${mode.label}")
    }

    /**
     * Pushes the saved mode to the gimbal, then reads back what it took.
     *
     * Safe to call with no aircraft — the key write fails and is logged. Called at connect and
     * whenever the pilot changes the setting. **Never on a timer:** this is a write to the
     * flight hardware, and safety rule 3 applies to it as much as to the flight limits.
     */
    fun apply(context: Context, onDone: (() -> Unit)? = null) {
        val mode = saved(context)
        val key = KeyTools.createKey(GimbalKey.KeyPitchControlMaxSpeed, ComponentIndexType.LEFT_OR_MAIN)
        runCatching {
            KeyManager.getInstance().setValue(key, mode.pitchSpeed,
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        AppLog.i(TAG, "gimbal pitch speed set to ${mode.pitchSpeed} (${mode.label})")
                        readBack(onDone)
                    }

                    override fun onFailure(error: IDJIError) {
                        // Not fatal: the read-back still runs, so the Pre-Flight line shows what
                        // the aircraft holds rather than going blank on a refused write.
                        AppLog.w(TAG, "gimbal pitch speed set to ${mode.pitchSpeed} failed: " +
                            error.description())
                        readBack(onDone)
                    }
                })
        }.onFailure {
            AppLog.w(TAG, "gimbal pitch-speed write threw: ${it.message}")
            onDone?.invoke()
        }
    }

    /** The answer, not the request. Safety rule 4: a success callback is not proof. */
    private fun readBack(onDone: (() -> Unit)?) {
        val key = KeyTools.createKey(GimbalKey.KeyPitchControlMaxSpeed, ComponentIndexType.LEFT_OR_MAIN)
        runCatching {
            KeyManager.getInstance().getValue(key,
                object : CommonCallbacks.CompletionCallbackWithParam<Int> {
                    override fun onSuccess(value: Int?) {
                        aircraftPitchSpeed = value
                        AppLog.i(TAG, "aircraft gimbal pitch speed is now: $value")
                        onDone?.invoke()
                    }

                    override fun onFailure(error: IDJIError) {
                        AppLog.w(TAG, "gimbal pitch-speed read-back failed: ${error.description()}")
                        onDone?.invoke()
                    }
                })
        }.onFailure {
            AppLog.w(TAG, "gimbal pitch-speed read-back threw: ${it.message}")
            onDone?.invoke()
        }
    }
}
