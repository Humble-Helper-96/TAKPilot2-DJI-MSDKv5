package com.dji.sdk.sample.tak

import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.BeaconKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.flightcontroller.LEDsSettings
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import com.taklite.util.AppLog

/**
 * The aircraft's EXTERIOR LIGHTS, as one dark/lit toggle.
 *
 * "Exterior" here is the whole set the operator named (2026-08-20): the beacon AND the red
 * and green LEDs near each motor, not one of them. A pilot going covert needs the aircraft
 * dark, and a control that only killed the beacon while the arm LEDs still shone would be a
 * control that lies about what it did.
 *
 * The SDK splits this over two keys, which is why this object exists rather than a pair of
 * call sites:
 *  - [FlightControllerKey.KeyLEDsSettings] carries four booleans — front, rear, navigation
 *    and status-indicator LEDs. The arm LEDs are the front/rear/navigation members.
 *  - [FlightControllerKey.KeyIsBeaconOpened] is the beacon, separately.
 *
 * ⚠ [isDark] IS THE AIRCRAFT'S ANSWER, NEVER OUR REQUEST (safety rule 4, and the Autel
 * sibling's design). Every write is followed by a read-back, and the button renders from what
 * comes back. A refused write that painted the icon anyway would tell a pilot they are dark
 * when the aircraft is lit — the exact failure the read-back rule exists to prevent.
 *
 * ⚠ These are WRITES TO THE FLIGHT CONTROLLER, thus they happen on an explicit button press
 * only, never on a timer (safety rule 3).
 */
object AircraftLights {

    private const val TAG = "AircraftLights"

    /**
     * True when the aircraft reports every exterior light off, false when any is on, and NULL
     * when it has not answered yet. Null is its own state on purpose: the button shows unknown
     * rather than guessing off, per the UI convention.
     */
    @Volatile
    var isDark: Boolean? = null
        private set

    private val ledsKey: DJIKey<LEDsSettings>
        get() = KeyTools.createKey(FlightControllerKey.KeyLEDsSettings)

    private val beaconKey: DJIKey<Boolean>
        get() = KeyTools.createKey(BeaconKey.KeyIsBeaconOpened)

    /**
     * Asks the aircraft what its lights are doing and updates [isDark].
     *
     * Call on flight-screen entry and after any write. Reading is free of the write rule —
     * rule 3 forbids timed WRITES, not polling a state.
     */
    fun refresh(onDone: (() -> Unit)? = null) {
        val leds = runCatching { KeyManager.getInstance().getValue(ledsKey) }.getOrNull()
        val beacon = runCatching { KeyManager.getInstance().getValue(beaconKey) }.getOrNull()
        isDark = computeDark(leds, beacon)
        AppLog.v(TAG, "lights read-back: leds=$leds beacon=$beacon -> isDark=$isDark")
        onDone?.invoke()
    }

    /**
     * Dark is EVERY light off. An unknown member does not count as off — if the aircraft will
     * not say, the answer is unknown, not "probably dark".
     */
    private fun computeDark(leds: LEDsSettings?, beacon: Boolean?): Boolean? {
        if (leds == null && beacon == null) return null
        val members = listOfNotNull(
            leds?.frontLEDsOn, leds?.rearLEDsOn,
            leds?.navigationLEDsOn, leds?.statusIndicatorLEDsOn, beacon,
        )
        if (members.isEmpty()) return null
        return members.none { it }
    }

    /**
     * Turns every exterior light off (dark = true) or back on.
     *
     * @param onResult true only when the aircraft CONFIRMED the new state on read-back. A
     * false here means the pilot must be told the aircraft did not change the lights.
     */
    fun setAllOff(dark: Boolean, onResult: (Boolean) -> Unit) {
        val on = !dark
        AppLog.i(TAG, "lights: asking for ${if (dark) "DARK" else "LIT"}")
        val settings = LEDsSettings(on, on, on, on)

        var ledsDone = false
        var beaconDone = false
        fun finish() {
            if (!ledsDone || !beaconDone) return
            // The read-back is the answer, not the two callbacks above. Either write can be
            // refused while the other succeeds, which would leave the aircraft half-dark.
            refresh { onResult(isDark == dark) }
        }

        KeyManager.getInstance().setValue(ledsKey, settings,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.i(TAG, "KeyLEDsSettings(on=$on): OK")
                    ledsDone = true; finish()
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "KeyLEDsSettings refused: ${error.description()}")
                    ledsDone = true; finish()
                }
            })

        KeyManager.getInstance().setValue(beaconKey, on,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.i(TAG, "KeyIsBeaconOpened($on): OK")
                    beaconDone = true; finish()
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "KeyIsBeaconOpened refused: ${error.description()}")
                    beaconDone = true; finish()
                }
            })
    }
}
