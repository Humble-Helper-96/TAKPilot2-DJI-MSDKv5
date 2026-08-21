package com.dji.sdk.sample.tak

import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.battery.BatteryLedsInfo
import dji.sdk.keyvalue.value.flightcontroller.LEDsSettings
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import com.taklite.util.AppLog

/**
 * The aircraft's exterior lights: the MOTOR LEDs and the BEACON, controlled separately.
 *
 * ⚠ DJI'S FIELD NAMES DO NOT MATCH THE LIGHTS. This map was measured on a Matrice 4TD on
 * 2026-08-20 by writing one member at a time and watching the airframe, after two guesses
 * from the names were wrong:
 *
 * | SDK member                          | The light it actually drives          |
 * |-------------------------------------|---------------------------------------|
 * | `navigationLEDsOn` + `aircraftLed`  | THE WHITE BEACON — not the nav lights |
 * | `frontLEDsOn`                       | front motor LEDs, steady red          |
 * | `rearLEDsOn`, `statusIndicatorLEDsOn` | rear motor LEDs, flashing red/green |
 *
 * `navigationLEDsOn` is the trap: it reads like the position lights and it is the beacon. A
 * control wired from that name alone would do the opposite of its label, and the pilot would
 * only find out in the dark. Trust the aircraft, never the documentation (safety rule 10).
 *
 * `BeaconKey.KeyIsBeaconOpened` — the key that SHOULD be the beacon — is refused by this
 * airframe outright: the write is rejected and the read returns null. Measured, not assumed.
 *
 * ⚠ The two beacon members move together, so it is not yet known whether one alone is enough.
 * They are written as a pair until someone separates them on hardware.
 *
 * ⚠ [motorLedsOn] AND [beaconOn] ARE THE AIRCRAFT'S ANSWER, NEVER OUR REQUEST (safety rule
 * 4). Every write is followed by a read-back and the buttons render from what comes back, so
 * a refused write cannot paint a control that claims the lights changed when they did not.
 *
 * ⚠ These are WRITES TO THE FLIGHT CONTROLLER, thus on an explicit button press only, never
 * on a timer (safety rule 3).
 */
object AircraftLights {

    private const val TAG = "AircraftLights"

    /**
     * The motor LEDs — front steady red plus the rear flashing pair. True when any is on,
     * false when all are off, NULL when the aircraft has not answered. Null is its own state
     * on purpose: the button shows unknown rather than guessing off.
     *
     * ⚠ This group includes the STATUS INDICATOR, the rear flashing red/green that is how the
     * airframe reports its own health at a glance. Turning the motor LEDs off turns that
     * signal off with them — the operator's decision (2026-08-20), because a pilot going dark
     * needs the aircraft dark, and a status light that stayed lit would defeat the point.
     */
    @Volatile
    var motorLedsOn: Boolean? = null
        private set

    /** The white beacon. Same null-is-unknown rule as [motorLedsOn]. */
    @Volatile
    var beaconOn: Boolean? = null
        private set

    private val ledsKey: DJIKey<LEDsSettings>
        get() = KeyTools.createKey(FlightControllerKey.KeyLEDsSettings)

    private val batteryLedsKey: DJIKey<BatteryLedsInfo>
        get() = KeyTools.createKey(BatteryKey.KeyBatteryLEDsEnabled)

    /**
     * The last full value each key reported.
     *
     * Held because a write carries every member at once: without the others, changing the
     * motor LEDs would silently clear the beacon and vice versa. Preserving them is not an
     * optimisation — it is the difference between changing one light and changing all of them.
     */
    @Volatile
    private var lastLeds: LEDsSettings? = null

    @Volatile
    private var lastBatteryLed: Boolean? = null

    /**
     * Asks the aircraft what its lights are doing and updates both states.
     *
     * Reading is free of the write rule — rule 3 forbids timed WRITES, not polling a state.
     */
    fun refresh(onDone: (() -> Unit)? = null) {
        val leds = runCatching { KeyManager.getInstance().getValue(ledsKey) }.getOrNull()
        val batt = runCatching { KeyManager.getInstance().getValue(batteryLedsKey) }.getOrNull()
        if (leds != null) lastLeds = leds
        if (batt != null) lastBatteryLed = batt.batteryLed

        val motors = listOfNotNull(
            leds?.frontLEDsOn, leds?.rearLEDsOn, leds?.statusIndicatorLEDsOn,
        )
        motorLedsOn = if (motors.isEmpty()) null else motors.any { it }
        beaconOn = leds?.navigationLEDsOn
        AppLog.v(TAG, "lights read-back: motors=$motorLedsOn beacon=$beaconOn " +
            "leds=$leds batteryLeds=$batt")
        onDone?.invoke()
    }

    /**
     * Turns the MOTOR LEDs on or off, preserving the beacon.
     *
     * @param onResult true only when the aircraft CONFIRMED the new state on read-back. False
     * means the pilot must be told the aircraft did not change the lights.
     */
    fun setMotorLeds(on: Boolean, onResult: (Boolean) -> Unit) {
        // READ FIRST. A write carries every member, so the half we are not changing has to be
        // a value the AIRCRAFT gave us — never a guess. See [requireState].
        val prev = requireState() ?: run { onResult(false); return }
        AppLog.i(TAG, "motor LEDs: asking for ${if (on) "ON" else "OFF"} " +
            "(beacon stays ${prev.navigationLEDsOn})")
        // Built BY NAME. The positional constructor is avoided on purpose: the declared field
        // order is (front, statusIndicator, rear, navigation), a constructor is not
        // contractually bound to that order, and getting it wrong would move a different
        // light — silently, and visible only on the airframe.
        val settings = LEDsSettings().apply {
            frontLEDsOn = on
            rearLEDsOn = on
            statusIndicatorLEDsOn = on
            navigationLEDsOn = prev.navigationLEDsOn          // the beacon, untouched
        }
        writeLeds(settings, "motors") { onResult(motorLedsOn == on) }
    }

    /**
     * Turns the BEACON on or off, preserving the motor LEDs.
     *
     * Writes both beacon members — `navigationLEDsOn` and the battery key's `aircraftLed` —
     * because they were only ever observed moving together.
     */
    fun setBeacon(on: Boolean, onResult: (Boolean) -> Unit) {
        val prev = requireState() ?: run { onResult(false); return }
        AppLog.i(TAG, "beacon: asking for ${if (on) "ON" else "OFF"}")
        val settings = LEDsSettings().apply {
            frontLEDsOn = prev.frontLEDsOn
            rearLEDsOn = prev.rearLEDsOn
            statusIndicatorLEDsOn = prev.statusIndicatorLEDsOn
            navigationLEDsOn = on
        }

        var ledsDone = false
        var battDone = false
        fun finish() {
            if (!ledsDone || !battDone) return
            // The read-back is the answer, not the callbacks. Either write can be refused
            // while the other succeeds, which would leave the two halves disagreeing.
            refresh { onResult(beaconOn == on) }
        }

        KeyManager.getInstance().setValue(ledsKey, settings,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.i(TAG, "KeyLEDsSettings(beacon=$on): OK")
                    ledsDone = true; finish()
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "KeyLEDsSettings(beacon) refused: ${error.description()}")
                    ledsDone = true; finish()
                }
            })

        // batteryLed is left as the aircraft last reported it: it is the battery's own level
        // display, not part of the beacon, and was only bundled here because the two share a
        // key. aircraftLed is the beacon half.
        val batt = BatteryLedsInfo(on, lastBatteryLed ?: true)
        KeyManager.getInstance().setValue(batteryLedsKey, batt,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.i(TAG, "KeyBatteryLEDsEnabled(aircraftLed=$on): OK")
                    battDone = true; finish()
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "KeyBatteryLEDsEnabled refused: ${error.description()}")
                    battDone = true; finish()
                }
            })
    }

    /**
     * The aircraft's CURRENT light state, re-read right now, or null if it will not say.
     *
     * ⚠ A NULL HERE MUST REFUSE THE WRITE. Every write carries all four members, so a missing
     * value used to fall back to the value being requested — which changed the very light the
     * caller was trying to preserve. Found on the bench 2026-08-20: the pill came up grey
     * (state unknown, the aircraft had not answered yet) and the first tap turned the beacon
     * on as well as the motor LEDs. Refusing is the honest answer; guessing moves a light the
     * pilot did not ask for, in the dark, on an aircraft they may be trying to hide.
     */
    private fun requireState(): LEDsSettings? {
        refresh()
        val s = lastLeds
        if (s == null) AppLog.w(TAG, "lights: the aircraft has not reported its lights — " +
            "refusing the write rather than guessing the half being preserved")
        return s
    }

    /** One write path for the LEDs key, so the read-back rule is applied the same way twice. */
    private fun writeLeds(settings: LEDsSettings, what: String, verify: () -> Unit) {
        KeyManager.getInstance().setValue(ledsKey, settings,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.i(TAG, "KeyLEDsSettings($what): OK")
                    refresh(verify)
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "KeyLEDsSettings($what) refused: ${error.description()}")
                    refresh(verify)
                }
            })
    }
}
