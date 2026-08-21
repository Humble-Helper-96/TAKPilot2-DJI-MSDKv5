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
 * The aircraft's EXTERIOR LIGHTS: the navigation LEDs and the beacon, CONTROLLED SEPARATELY.
 *
 * They were one dark/lit toggle for a few hours on 2026-08-20 and the operator split them the
 * same day: a tap works the navigation lights (the red and green LEDs near each motor), a
 * touch-and-hold works the beacon. The two serve different purposes in the air — the beacon
 * is the anti-collision strobe others see, the navigation LEDs say which way the aircraft
 * faces — so a pilot needs to kill one without losing the other.
 *
 * The SDK holds them on two keys, which is what makes the split clean:
 *  - [FlightControllerKey.KeyLEDsSettings] carries four booleans — front, rear, navigation
 *    and status-indicator LEDs. The arm LEDs are the front/rear/navigation members.
 *  - [FlightControllerKey.KeyIsBeaconOpened] is the beacon, separately.
 *
 * ⚠ [navOn] AND [beaconOn] ARE THE AIRCRAFT'S ANSWER, NEVER OUR REQUEST (safety rule 4, and the Autel
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
     * The navigation LEDs: true when the aircraft reports any of them on, false when all are
     * off, NULL when it has not answered. Null is its own state on purpose — the button shows
     * unknown rather than guessing off, per the UI convention.
     */
    @Volatile
    var navOn: Boolean? = null
        private set

    /** The beacon, independently. Same null-is-unknown rule as [navOn]. */
    @Volatile
    var beaconOn: Boolean? = null
        private set

    private val ledsKey: DJIKey<LEDsSettings>
        get() = KeyTools.createKey(FlightControllerKey.KeyLEDsSettings)

    private val beaconKey: DJIKey<Boolean>
        get() = KeyTools.createKey(BeaconKey.KeyIsBeaconOpened)

    /**
     * Asks the aircraft what its lights are doing and updates [navOn] and [beaconOn].
     *
     * Call on flight-screen entry and after any write. Reading is free of the write rule —
     * rule 3 forbids timed WRITES, not polling a state.
     */
    fun refresh(onDone: (() -> Unit)? = null) {
        val leds = runCatching { KeyManager.getInstance().getValue(ledsKey) }.getOrNull()
        val beacon = runCatching { KeyManager.getInstance().getValue(beaconKey) }.getOrNull()
        navOn = computeNav(leds)
        beaconOn = beacon
        AppLog.v(TAG, "lights read-back: nav=$navOn beacon=$beaconOn (raw leds=$leds)")
        onDone?.invoke()
    }

    /**
     * The nav lights are ON when ANY member is on. An unknown member does not count as off —
     * if the aircraft will not say, the answer is unknown, not "probably dark".
     */
    private fun computeNav(leds: LEDsSettings?): Boolean? {
        val members = listOfNotNull(
            leds?.frontLEDsOn, leds?.rearLEDsOn,
            leds?.navigationLEDsOn, leds?.statusIndicatorLEDsOn,
        )
        if (members.isEmpty()) return null
        return members.any { it }
    }

    /**
     * Turns the NAVIGATION LEDs on or off. The beacon is untouched — see [setBeacon].
     *
     * @param onResult true only when the aircraft CONFIRMED the new state on read-back. False
     * means the pilot must be told the aircraft did not change the lights.
     */
    fun setNav(on: Boolean, onResult: (Boolean) -> Unit) {
        AppLog.i(TAG, "nav lights: asking for ${if (on) "ON" else "OFF"}")
        KeyManager.getInstance().setValue(ledsKey, LEDsSettings(on, on, on, on),
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.i(TAG, "KeyLEDsSettings(on=$on): OK")
                    // The read-back is the answer, not this callback (safety rule 4).
                    refresh { onResult(navOn == on) }
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "KeyLEDsSettings refused: ${error.description()}")
                    refresh { onResult(false) }
                }
            })
    }

    /** Turns the BEACON on or off. The navigation LEDs are untouched — see [setNav]. */
    fun setBeacon(on: Boolean, onResult: (Boolean) -> Unit) {
        AppLog.i(TAG, "beacon: asking for ${if (on) "ON" else "OFF"}")
        KeyManager.getInstance().setValue(beaconKey, on,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.i(TAG, "KeyIsBeaconOpened($on): OK")
                    refresh { onResult(beaconOn == on) }
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "KeyIsBeaconOpened refused: ${error.description()}")
                    refresh { onResult(false) }
                }
            })
    }
}
