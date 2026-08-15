package com.dji.sdk.sample.tak

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.flightcontroller.FlightMode
import dji.sdk.keyvalue.value.flightcontroller.GPSSignalLevel
import dji.sdk.keyvalue.value.flightcontroller.WindWarning
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import kotlin.math.sqrt

/**
 * DroneTakBridge — MSDK v5 telemetry -> TAK air-track PLI.
 *
 * The v4 original cached each component's setStateCallback() push and built a
 * PLI on a 2 s timer. v5 replaces the component callbacks with KeyManager
 * listens; the shape stays the same: every listen writes an @Volatile cache,
 * and [pushOnce] reads the caches on the same 2 s tick.
 *
 * Listener ownership rule (unchanged): this bridge is the only client of
 * these keys. Consumers read the bridge's caches ([hud], [cameraPose],
 * [lookPoint]) and never register their own listens.
 */
class DroneTakBridge(
    private val appContext: Context,
    private val fallbackUid: String,
    private val droneCallsign: String,
    private val intervalMs: Long = 2000L,
) {
    private val tak = TakManager.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    // Prefer the real aircraft serial (stable per-aircraft so ATAK associates the sensor
    // cone correctly). Fetched async on start(); falls back to the provided uid until it
    // resolves.
    @Volatile private var droneUid: String = fallbackUid

    /** Optional RTSP/stream url to advertise (rides the OPERATOR marker — see pushPilotPli). */
    @Volatile
    var videoUrl: String? = null

    /** When true, also push the camera slant point (sensor point of interest). */
    @Volatile
    var cameraPointEnabled: Boolean = false

    private val spiUid: String get() = "$droneUid-SPI"

    // Sensor FOV cone state, refreshed when camera-point is enabled; embedded in the
    // drone PLI so ATAK/taklite draw the cone natively. -1 = omit.
    @Volatile private var sensorFov = -1.0
    @Volatile private var sensorVfov = -1.0

    /** Current digital zoom (1.0 = none). Set by the flight screen's zoom control via
     *  [TakBridgeHolder]; narrows both the published FOV cone and the AR projection. */
    @Volatile var zoomFactor: Double = 1.0
    @Volatile private var sensorAzimuth = -1.0
    @Volatile private var sensorElevation = 0.0
    @Volatile private var sensorRange = -1.0

    // ------------------------------------------------------------------
    // Cached key values. Each field is one KeyManager listen. v5 has no
    // frame object; the caches ARE the frame.
    // ------------------------------------------------------------------
    @Volatile private var lastLocation: LocationCoordinate3D? = null
    /** elapsedRealtime of the last location push, 0 = none. The drone CoT is only
     *  published while this is recent — see the freshness gate in [pushOnce]. */
    @Volatile private var lastStateMs = 0L
    @Volatile private var lastVelocity: Velocity3D? = null
    @Volatile private var lastHeading: Double? = null
    @Volatile private var lastFlightMode: FlightMode? = null
    @Volatile private var lastIsFlying = false
    @Volatile private var lastMotorsOn = false
    @Volatile private var lastSatCount = 0
    @Volatile private var lastGpsLevel: GPSSignalLevel? = null
    @Volatile private var lastHomeLocation: LocationCoordinate2D? = null
    @Volatile private var lastHomeSet = false
    @Volatile private var lastFlightTimeSec = 0
    @Volatile private var lastWind: WindWarning? = null
    @Volatile private var lastBatteryPct = 0
    @Volatile private var lastBatteryMaxMah = 0
    @Volatile private var lastBatteryRemainMah = 0
    @Volatile private var lastVoltageMv = 0
    @Volatile private var lastLowBattThreshold: Int? = null
    @Volatile private var lastSeriousBattThreshold: Int? = null
    // RC-to-aircraft link quality, 0-100 — the "controller signal strength" a pilot cares
    // about (distinct from downlink/video quality).
    @Volatile private var lastUplinkQuality: Int? = null
    /** AIRCRAFT-to-RC link quality, 0-100 — the direction the VIDEO actually travels. Kept
     *  from v4's FPV-artifacting lesson: uplink says nothing about the link frames are lost on. */
    @Volatile private var lastDownlinkQuality: Int? = null
    @Volatile private var lastGimbalAttitude: Attitude? = null
    @Volatile private var lastGimbalYawRel: Double? = null
    @Volatile private var lastIsRecording = false
    @Volatile private var lastIsShootingPhoto = false
    @Volatile private var lastRthHeight: Int? = null
    @Volatile private var lastRemainingFlightSec: Int? = null
    @Volatile private var lastIsoName: String? = null
    @Volatile private var lastShutterName: String? = null

    // One-shot guards, same pattern as v4: applied on the first sign of life from the
    // component, which is the reliable "it is actually up" signal.
    @Volatile private var exposureApplied = false
    @Volatile private var controlResponseApplied = false
    @Volatile private var limitsApplied = false
    @Volatile private var gimbalRangeApplied = false

    /** Listen holder cancelled on [stop]. */
    private val sessionHolder = Any()

    /**
     * Listen holder that is NEVER cancelled.
     *
     * The AirLink signal listens live here. On the Autel port, removing the RC info
     * listener at TAK stop killed the RC signal indicator for the life of the process (SDK
     * asymmetry, found in flight 2026-08-06). Whether v5's KeyManager shares the defect is
     * unverified, but keeping these armed costs nothing — they only write @Volatile caches
     * — and the signal bars on the flight screen must never depend on a TAK toggle.
     */
    private val signalHolder = Any()
    @Volatile private var signalListensArmed = false

    private val tick = object : Runnable {
        override fun run() {
            try {
                pushOnce()
            } catch (t: Throwable) {
                AppLog.w(TAG, "telemetry push failed: ${t.message}")
            }
            if (running) handler.postDelayed(this, intervalMs)
        }
    }

    fun start() {
        if (running) return
        running = true

        // New session = new flight = a new takeoff point, so the latched terrain reference from
        // the last one must not carry over (see TerrainAgl).
        TerrainAgl.reset()

        armSessionListens()
        armSignalListensOnce()
        resolveSerial()

        // The CONTROLLER's own position, for the operator marker. Idempotent, and it must be a
        // real requestLocationUpdates — see OperatorLocation for why the cache alone is empty.
        OperatorLocation.start(appContext)

        // The at-limit warnings compare against the SAME configured values that get pushed to
        // the aircraft, read from the same place, so the banner cannot disagree with the limit
        // the aircraft is enforcing.
        FlightWarnings.setLimits(
            FlightLimitsController.ftToM(FlightLimitsController.savedMaxAltitudeFt(appContext))?.toDouble(),
            FlightLimitsController.ftToM(FlightLimitsController.savedMaxRadiusFt(appContext))?.toDouble(),
        )
        // A new session must not inherit the last one's banner state.
        FlightWarnings.reset()

        handler.post(tick)
        AppLog.i(TAG, "DroneTakBridge started ($droneCallsign / $droneUid, every ${intervalMs}ms)")
    }

    private fun armSessionListens() {
        val h = sessionHolder
        FlightControllerKey.KeyAircraftLocation3D.create().listen(h) {
            lastLocation = it
            // Proof of life for the drone CoT — see the freshness gate in pushOnce().
            if (it != null) lastStateMs = android.os.SystemClock.elapsedRealtime()
            if (!limitsApplied && it != null) {
                limitsApplied = true
                FlightLimitsController.applyDefaults(appContext)
            }
        }
        FlightControllerKey.KeyAircraftVelocity.create().listen(h) { lastVelocity = it }
        FlightControllerKey.KeyCompassHeading.create().listen(h) { lastHeading = it }
        FlightControllerKey.KeyFlightMode.create().listen(h) { lastFlightMode = it }
        FlightControllerKey.KeyIsFlying.create().listen(h) { lastIsFlying = it == true }
        FlightControllerKey.KeyAreMotorsOn.create().listen(h) { lastMotorsOn = it == true }
        FlightControllerKey.KeyGPSSatelliteCount.create().listen(h) { lastSatCount = it ?: 0 }
        FlightControllerKey.KeyGPSSignalLevel.create().listen(h) { lastGpsLevel = it }
        FlightControllerKey.KeyHomeLocation.create().listen(h) { lastHomeLocation = it }
        FlightControllerKey.KeyIsHomeLocationSet.create().listen(h) { lastHomeSet = it == true }
        FlightControllerKey.KeyFlightTimeInSeconds.create().listen(h) { lastFlightTimeSec = it ?: 0 }
        FlightControllerKey.KeyWindWarning.create().listen(h) { lastWind = it }
        FlightControllerKey.KeyGoHomeHeight.create().listen(h) {
            lastRthHeight = it?.takeIf { v -> v > 0 }
        }
        // The aircraft's own remaining-flight-time estimate (seconds), from its smart-RTH
        // model — unlike a percent-times-endurance guess, this models actual current draw.
        FlightControllerKey.KeyLowBatteryRTHInfo.create().listen(h) {
            lastRemainingFlightSec = it?.remainingFlightTime?.takeIf { v -> v > 0 }
        }
        FlightControllerKey.KeyLowBatteryWarningThreshold.create().listen(h) {
            lastLowBattThreshold = it
        }
        FlightControllerKey.KeySeriousLowBatteryWarningThreshold.create().listen(h) {
            lastSeriousBattThreshold = it
        }

        BatteryKey.KeyChargeRemainingInPercent.create().listen(h) { lastBatteryPct = it ?: 0 }
        BatteryKey.KeyFullChargeCapacity.create().listen(h) { lastBatteryMaxMah = it ?: 0 }
        BatteryKey.KeyChargeRemaining.create().listen(h) { lastBatteryRemainMah = it ?: 0 }
        BatteryKey.KeyVoltage.create().listen(h) { lastVoltageMv = it ?: 0 }

        GimbalKey.KeyGimbalAttitude.create().listen(h) {
            lastGimbalAttitude = it
            // Same one-shot-per-connect pattern as exposure/limits: first gimbal state is
            // the reliable "gimbal is actually up" signal.
            if (!gimbalRangeApplied && it != null) {
                gimbalRangeApplied = true
                applyPitchRangeExtension()
            }
        }
        GimbalKey.KeyYawRelativeToAircraftHeading.create().listen(h) { lastGimbalYawRel = it }

        // Control response at each connect, so the camera feels the same every flight whatever
        // the last session or the DJI app left in the gimbal. Fires ONCE per connect: this is a
        // write to flight hardware and safety rule 3 forbids putting it on a clock. Matches the
        // MSDKv4 sibling, which applies it from its own gimbal-up signal.
        GimbalKey.KeyConnection.create().listen(h) { connected ->
            if (connected == true && !controlResponseApplied) {
                controlResponseApplied = true
                ControlResponse.apply(appContext)
            } else if (connected != true) {
                controlResponseApplied = false
            }
        }

        CameraKey.KeyIsRecording.create().listen(h) { lastIsRecording = it == true }
        CameraKey.KeyIsShootingPhoto.create().listen(h) { lastIsShootingPhoto = it == true }
        CameraKey.KeyConnection.create().listen(h) { connected ->
            if (connected == true && !exposureApplied) {
                exposureApplied = true
                ExposureController.applyDefaults(appContext)
            }
        }
        // Live exposure readout for the HUD. Enum names, translated by ExposureController.
        CameraKey.KeyISO.create().listen(h) { lastIsoName = it?.name }
        CameraKey.KeyShutterSpeed.create().listen(h) { lastShutterName = it?.name }
    }

    private fun armSignalListensOnce() {
        if (signalListensArmed) return
        signalListensArmed = true
        AirLinkKey.KeyUpLinkQuality.create().listen(signalHolder) { lastUplinkQuality = it }
        // Downlink is the direction video travels — see lastDownlinkQuality's doc for why
        // watching only uplink misled the v4 artifacting investigation.
        AirLinkKey.KeyDownLinkQuality.create().listen(signalHolder) { lastDownlinkQuality = it }
    }

    private fun resolveSerial() {
        KeyManager.getInstance().getValue(
            KeyTools.createKey(FlightControllerKey.KeySerialNumber),
            object : CommonCallbacks.CompletionCallbackWithParam<String> {
                override fun onSuccess(serial: String?) {
                    if (!serial.isNullOrBlank()) {
                        droneUid = serial
                        AppLog.i(TAG, "drone uid = aircraft serial $serial")
                    }
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "serial read failed (${error.description()}) — using $fallbackUid")
                }
            },
        )
    }

    /**
     * Lets the gimbal tilt UP past level, not just down — so a pilot can visually acquire
     * air traffic overhead. Ported from v4. Failure is logged and otherwise ignored: this
     * is a nice-to-have, and an aircraft that refuses it should still fly normally.
     *
     * The v4 pitch-SPEED boost is deliberately NOT ported yet: v5 exposes no min/max
     * capability range to clamp against, and an unclamped write to an unknown range on a
     * new airframe is the kind of blind flight-configuration write this project does not
     * do. Revisit at the M4T bench with the real dial response in hand.
     */
    private fun applyPitchRangeExtension() {
        KeyManager.getInstance().setValue(
            KeyTools.createKey(GimbalKey.KeyPitchRangeExtensionEnabled),
            true,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.i(TAG, "gimbal pitch range extended — camera can now look up")
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "gimbal pitch range extension refused: ${error.description()}")
                }
            },
        )
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        OperatorLocation.stop()
        // Close the track now rather than leaving it to the orphan sweep. The sweep is the
        // crash path; a clean stop should produce a complete GPX immediately.
        FlightPathLogger.endSession("bridge stopped")
        // Session listens go; the AirLink signal listens on signalHolder deliberately stay
        // (see signalHolder's doc).
        runCatching { KeyManager.getInstance().cancelListen(sessionHolder) }
        lastLocation = null
        lastVelocity = null
        lastHeading = null
        lastFlightMode = null
        lastIsFlying = false
        lastMotorsOn = false
        lastSatCount = 0
        lastGpsLevel = null
        lastHomeLocation = null
        lastHomeSet = false
        lastFlightTimeSec = 0
        lastWind = null
        lastGimbalAttitude = null
        lastGimbalYawRel = null
        lastIsRecording = false
        lastIsShootingPhoto = false
        lastIsoName = null
        lastShutterName = null
        exposureApplied = false
        limitsApplied = false
        gimbalRangeApplied = false
        AppLog.i(TAG, "DroneTakBridge stopped")
    }

    /**
     * One-line flight-readiness snapshot, logged only when something in it CHANGES.
     * Ported from v4 — answers "why won't it arm" from the aircraft's own state.
     * Logs under [READY_TAG] so it survives the Debug screen's "TAK logging off" filter.
     */
    private var lastReadiness: String? = null

    private fun logReadinessIfChanged() {
        val line = "mode=$lastFlightMode motors=$lastMotorsOn " +
            "flying=$lastIsFlying sats=$lastSatCount " +
            "gps=$lastGpsLevel homeSet=$lastHomeSet"
        if (line != lastReadiness) {
            lastReadiness = line
            AppLog.i(READY_TAG, line)
        }
    }

    private fun isGoingHome(): Boolean = lastFlightMode == FlightMode.GO_HOME

    private fun warningsFrame() = FlightWarnings.Frame(
        flightMode = lastFlightMode,
        gpsLevel = lastGpsLevel,
        wind = lastWind,
        goingHome = isGoingHome(),
        batteryPct = lastBatteryPct,
        lowBatteryThresholdPct = lastLowBattThreshold,
        seriousBatteryThresholdPct = lastSeriousBattThreshold,
        homeSet = lastHomeSet,
    )

    private fun pushOnce() {
        val loc = lastLocation ?: run {
            AppLog.d(TAG, "tick: no aircraft location pushed yet")
            return
        }
        // FRESHNESS, not just presence — an aircraft that stopped talking must stop being
        // reported, or its CoT marker can never stale out on other clients (measured and
        // fixed on the Autel tree first, 2026-08-13).
        if (android.os.SystemClock.elapsedRealtime() - lastStateMs > TELEMETRY_FRESH_MS) {
            AppLog.d(TAG, "tick: telemetry stale — not publishing the aircraft")
            return
        }
        logReadinessIfChanged()

        val lat = loc.latitude
        val lon = loc.longitude
        if (!isValidLat(lat) || !isValidLon(lon)) {
            // No GPS fix yet — skip this tick rather than send a bogus 0,0 marker.
            AppLog.d(TAG, "tick: no valid GPS fix yet (lat=$lat lon=$lon)")
            return
        }
        val hae = loc.altitude

        // Horizontal ground speed from NED velocity components, m/s.
        val vel = lastVelocity
        val speed = if (vel != null) sqrt(vel.x * vel.x + vel.y * vel.y) else 0.0

        // True heading (deg). KeyCompassHeading can be -180..180; normalize to 0..360.
        val heading = (((lastHeading ?: 0.0) % 360.0) + 360.0) % 360.0

        val battery = lastBatteryPct
        val isFlying = lastIsFlying
        val flightTimeSec = lastFlightTimeSec

        // Flight record. FED from the caches, never its own subscription. Deliberately before
        // the TAK publish and outside its connected-check: the record must be written with no
        // server and no network. `hae` is DJI's height above the TAKEOFF point; MSL is passed
        // as NaN until the terrain reference latches so the GPX falls back rather than
        // inventing a datum.
        FlightPathLogger.onTelemetry(
            lat, lon,
            aircraftMsl(hae) ?: Double.NaN, hae,
            speed, heading, battery, lastSatCount,
        )

        // Warning policy, fed from the same caches for the same reason: the banner and the
        // PLI must never disagree about whether the aircraft was flying.
        FlightWarnings.onState(warningsFrame(), isFlying, hae, homeDistanceMeters(lat, lon))

        val gimbalPitch = lastGimbalAttitude?.pitch ?: 0.0
        val gimbalYaw = lastGimbalAttitude?.yaw ?: 0.0

        // Compute the camera look-point + sensor FOV BEFORE the PLI, so the PLI can carry
        // the <sensor> element (ATAK/taklite draw the FOV cone from it).
        if (cameraPointEnabled) {
            pushCameraPoint(lat, lon, hae, heading)
        } else {
            sensorFov = -1.0; sensorVfov = -1.0; sensorAzimuth = -1.0
            sensorElevation = 0.0; sensorRange = -1.0
        }

        AppLog.d(TAG, "tick: lat=$lat lon=$lon hae=$hae hdg=${"%.0f".format(heading)} " +
            "spd=${"%.1f".format(speed)} batt=$battery% flying=$isFlying tak.connected=${tak.isConnected}")

        // north reference = 0: the <sensor azimuth> is an ABSOLUTE true-north bearing.
        // videoUrl rides the OPERATOR marker instead of here — the stream is a screen capture
        // of the controller and keeps running when the aircraft is down, but this drone PLI
        // stops the moment there is no GPS fix (see pushPilotPli).
        tak.sendDronePLI(droneUid, droneCallsign, lat, lon, hae, heading, speed, battery,
            null, spiUid,
            sensorFov, sensorVfov, sensorAzimuth, sensorElevation, sensorRange, 0.0,
            0.0, gimbalPitch, gimbalYaw,
            isFlying, flightTimeSec,
            lastBatteryMaxMah, lastBatteryRemainMah, lastVoltageMv / 1000.0)

        pushPilotPli()
    }

    /**
     * The PILOT's marker — the operator on the ground, at the controller's own position.
     * Returns without sending when there is no fix: no marker is better than a marker in
     * the wrong place.
     */
    private fun pushPilotPli() {
        val fix = OperatorLocation.latest ?: return
        runCatching {
            tak.sendPilotPLI(fix, droneCallsign, "Team Member", pilotBatteryPct(), videoUrl)
        }.onFailure { AppLog.w(TAG, "pilot PLI failed: ${it.message}") }
    }

    /**
     * CONTROLLER battery, not the aircraft's — here the RC Plus 2 itself. Cached for
     * [BATTERY_CACHE_MS]; on failure the LAST GOOD reading is returned, never 100.
     */
    private var batteryPctCache = 100
    private var batteryPctReadAt = 0L
    private fun pilotBatteryPct(): Int {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - batteryPctReadAt < BATTERY_CACHE_MS && batteryPctReadAt != 0L) return batteryPctCache
        runCatching {
            val bm = appContext.getSystemService(android.content.Context.BATTERY_SERVICE)
                as? android.os.BatteryManager ?: return@runCatching
            val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct in 0..100) {
                batteryPctCache = pct
                batteryPctReadAt = now
            }
        }.onFailure { AppLog.w(TAG, "controller battery read failed: ${it.message}") }
        return batteryPctCache
    }

    /**
     * True geographic bearing the camera points along. Prefers KeyYawRelativeToAircraftHeading
     * (heading-stable), falling back to rawYaw + a fixed offset.
     */
    private fun cameraBearing(rawYaw: Double, aircraftHeading: Double): Double {
        val relYaw = lastGimbalYawRel
        return if (relYaw != null && relYaw.isFinite())
            CameraSlantPoint.norm360(aircraftHeading + relYaw)
        else
            CameraSlantPoint.norm360(rawYaw + BEARING_OFFSET_DEG)
    }

    private fun pushCameraPoint(lat: Double, lon: Double, aglMeters: Double, aircraftHeading: Double) {
        val gimbal = lastGimbalAttitude
        if (gimbal == null) {
            AppLog.d(TAG, "SPI skip: gimbal attitude not yet received")
            return
        }
        val pitch = gimbal.pitch
        val yaw = gimbal.yaw
        val bearing = cameraBearing(yaw, aircraftHeading)

        // Slant-range calibration bias — see PITCH_OFFSET_DEG note below.
        val pitchAdj = pitch + PITCH_OFFSET_DEG

        // ABOVE THE HORIZON THERE IS NO LOOK-POINT, SO PUBLISH NOTHING. An absent SPI is
        // honest; a fabricated one is worse than none, because the team will act on it.
        // Threshold matches CameraSlantPoint's own `depression > 1.0` guard.
        if (pitchAdj > -1.0) {
            sensorFov = -1.0; sensorVfov = -1.0; sensorAzimuth = -1.0
            sensorElevation = pitchAdj; sensorRange = -1.0
            AppLog.d(TAG, "SPI suppressed: camera at or above horizon " +
                "(pitch ${"%.1f".format(pitchAdj)}) — no ground intersection to publish")
            return
        }

        val gp = CameraSlantPoint.compute(
            lat, lon, aglMeters, bearing, pitchAdj, ::elevationLookup, aircraftMsl(aglMeters))
        tak.sendCameraPoint(spiUid, droneUid, "$droneCallsign-SPI", gp.lat, gp.lon, gp.rangeMeters)

        // FOV cone: ATAK/taklite draw it natively from the drone PLI's <sensor> element.
        // Zoom-corrected, so the cone narrows when the pilot zooms in.
        sensorFov = hFovDeg(zoomFactor)
        sensorVfov = vFovDeg(zoomFactor)
        sensorAzimuth = bearing
        sensorElevation = pitch
        sensorRange = gp.rangeMeters
        AppLog.d(TAG, "SPI: pitch=$pitch yaw=$yaw heading=${"%.0f".format(aircraftHeading)} " +
            "az=${"%.0f".format(bearing)} alt=$aglMeters range=${Math.round(gp.rangeMeters)}m")
    }

    private fun isValidLat(v: Double) = v.isFinite() && v != 0.0 && v >= -90.0 && v <= 90.0
    private fun isValidLon(v: Double) = v.isFinite() && v != 0.0 && v >= -180.0 && v <= 180.0

    /** Snapshot of cached telemetry for the on-screen HUD. Same shape as the Autel port's
     *  AutelTakBridge.Hud — reads the same caches pushOnce() uses, just without gating on
     *  the 2s CoT-push tick. */
    data class Hud(
        val lat: Double, val lon: Double, val alt: Double,
        val speedMs: Double, val headingDeg: Double, val batteryPct: Int,
        val satCount: Int, val gimbalPitch: Double?, val hasFix: Boolean,
        val homeLat: Double, val homeLon: Double, val homeSet: Boolean,
        val flightTimeSec: Int, val uplinkSignalPct: Int?, val isGoingHome: Boolean,
        val isRecording: Boolean, val liveIso: Int?, val liveShutter: String?,
        /** The AIRCRAFT's own remaining-flight-time estimate, seconds, or null if it isn't
         *  reporting one yet (v5: LowBatteryRTHInfo.remainingFlightTime). */
        val remainingFlightTimeSec: Int?,
        /** AIRCRAFT-to-RC link quality — the direction VIDEO travels. For diagnostics. */
        val downlinkSignalPct: Int? = null,
        /** OcuSync's reported video-link capacity in Mbps. v5 exposes no equivalent of v4's
         *  VideoDataRateCallback; always null here, kept so the HUD shape matches v4. */
        val videoDataRateMbps: Float? = null,
        /**
         * Return-to-home height IN METRES AS THE AIRCRAFT REPORTS IT, or null before it has
         * said. This is the aircraft's answer (KeyGoHomeHeight listen), not the Pre-Flight
         * preference — those can differ, and the difference is the point.
         */
        val rthHeightM: Int? = null,
    )

    /**
     * Whether the camera is still busy taking or writing a still. v5 collapses v4's seven
     * SystemState booleans into KeyIsShootingPhoto. False when no value has arrived: a
     * missing subscription must not deadlock the caller into waiting forever.
     */
    fun photoInProgress(): Boolean = lastIsShootingPhoto

    fun hud(): Hud {
        val loc = lastLocation
        val lat = loc?.latitude ?: Double.NaN
        val lon = loc?.longitude ?: Double.NaN
        val hasFix = isValidLat(lat) && isValidLon(lon)
        val vel = lastVelocity
        val speed = if (vel != null) sqrt(vel.x * vel.x + vel.y * vel.y) else 0.0
        val heading = (((lastHeading ?: 0.0) % 360.0) + 360.0) % 360.0
        val home = lastHomeLocation
        return Hud(
            lat, lon, loc?.altitude ?: 0.0, speed, heading,
            lastBatteryPct,
            lastSatCount,
            lastGimbalAttitude?.pitch, hasFix,
            home?.latitude ?: Double.NaN, home?.longitude ?: Double.NaN,
            lastHomeSet,
            lastFlightTimeSec, lastUplinkQuality,
            isGoingHome(),
            lastIsRecording,
            ExposureController.isoValue(lastIsoName),
            ExposureController.shutterLabel(lastShutterName),
            lastRemainingFlightSec,
            lastDownlinkQuality,
            null,
            lastRthHeight,
        )
    }

    /** Where the camera is pointing: true-north bearing and pitch, both degrees. */
    data class CameraPose(val bearingDeg: Double, val pitchDeg: Double)

    /**
     * True if [candidate] is a uid THIS app publishes — our own aircraft PLI or its sensor
     * point. Neither is a target: drawing the SPI would pin a marker permanently under the
     * crosshair.
     */
    fun isOwnPublishedUid(candidate: String?): Boolean =
        candidate != null && (candidate == droneUid || candidate == spiUid)

    /**
     * Current camera pose, or null until GPS/gimbal state has arrived. Computed from the
     * SAME [cameraBearing] + [PITCH_OFFSET_DEG] model that [lookPoint] uses — one model,
     * one place, so the AR overlay and marker drops cannot disagree.
     */
    fun cameraPose(): CameraPose? {
        val gimbal = lastGimbalAttitude ?: return null
        if (lastLocation == null) return null
        val heading = (((lastHeading ?: 0.0) % 360.0) + 360.0) % 360.0
        return CameraPose(cameraBearing(gimbal.yaw, heading), gimbal.pitch + PITCH_OFFSET_DEG)
    }

    /**
     * One-shot ground point the camera is currently aimed at (for the "drop marker at
     * look-point" flow). Returns (lat, lon, terrain elevation) or null if GPS/gimbal state
     * hasn't arrived yet.
     */
    fun lookPoint(): Triple<Double, Double, Double>? {
        val gimbal = lastGimbalAttitude ?: return null
        val loc = lastLocation ?: return null
        if (!isValidLat(loc.latitude) || !isValidLon(loc.longitude)) return null
        val hae = loc.altitude
        val heading = (((lastHeading ?: 0.0) % 360.0) + 360.0) % 360.0
        val bearing = cameraBearing(gimbal.yaw, heading)
        val gp = CameraSlantPoint.compute(
            loc.latitude, loc.longitude, hae, bearing, gimbal.pitch + PITCH_OFFSET_DEG,
            ::elevationLookup, aircraftMsl(hae),
        )
        return Triple(gp.lat, gp.lon, gp.elevationMeters)
    }

    /**
     * Ground distance from the home point, metres, or NaN when no home point is set yet.
     * Equirectangular approximation — error is centimetres at these ranges.
     */
    private fun homeDistanceMeters(lat: Double, lon: Double): Double {
        val home = lastHomeLocation ?: return Double.NaN
        val hLat = home.latitude
        val hLon = home.longitude
        if (!isValidLat(hLat) || !isValidLon(hLon)) return Double.NaN
        val meanLatRad = Math.toRadians((lat + hLat) / 2.0)
        val dLat = Math.toRadians(lat - hLat) * EARTH_RADIUS_M
        val dLon = Math.toRadians(lon - hLon) * EARTH_RADIUS_M * Math.cos(meanLatRad)
        return sqrt(dLat * dLat + dLon * dLon)
    }

    /** Aircraft altitude above MEAN SEA LEVEL, or null before the takeoff terrain reference
     *  latches. */
    private fun aircraftMsl(heightAboveTakeoff: Double): Double? =
        TerrainAgl.takeoffTerrainElevMsl?.plus(heightAboveTakeoff)

    /** DTED-backed elevation lookup for [CameraSlantPoint], or null if no tile covers the
     *  point. */
    private fun elevationLookup(lat: Double, lon: Double): Double? =
        DtedIndex.elevationAt(appContext, lat, lon)

    companion object {
        private const val TAG = "DroneTakBridge"

        /** How old the last location may be and still be worth publishing as the aircraft's
         *  position. */
        private const val TELEMETRY_FRESH_MS = 5_000L

        /** How long a controller-battery reading is reused before another binder IPC. */
        private const val BATTERY_CACHE_MS = 30_000L

        private const val EARTH_RADIUS_M = 6_371_000.0

        /** Flight-readiness snapshots — survives the "TAK logging off" filter. */
        private const val READY_TAG = "TP2Ready"

        // NOT YET FIELD-CALIBRATED for the Matrice 4T. Only matters when
        // KeyYawRelativeToAircraftHeading is unavailable (cameraBearing() prefers that
        // heading-stable value first). To recalibrate: point the camera at a known compass
        // direction, compare the cone in ATAK/WinTAK against reality, adjust this constant.
        private const val BEARING_OFFSET_DEG = 0.0

        // Slant-range (look-point distance) calibration bias added to gimbal pitch.
        private const val PITCH_OFFSET_DEG = 0.0

        /** Shared so the AR overlay uses the same bearing correction as the cone. */
        fun bearingOffsetDeg() = BEARING_OFFSET_DEG

        /**
         * Camera field of view, corrected for digital zoom, shared with the AR overlay.
         * Digital zoom is a centre crop, so the angular width shrinks non-linearly:
         * effectiveHalfAngle = atan(tan(baseHalfAngle) / zoom).
         */
        fun hFovDeg(zoom: Double = 1.0) = zoomedFov(TakBridgeHolder.currentHFovBase, zoom)
        fun vFovDeg(zoom: Double = 1.0) = zoomedFov(TakBridgeHolder.currentVFovBase, zoom)

        private fun zoomedFov(baseDeg: Double, zoom: Double): Double {
            if (!zoom.isFinite() || zoom <= 1.0) return baseDeg
            val halfRad = Math.toRadians(baseDeg / 2.0)
            return 2.0 * Math.toDegrees(Math.atan(Math.tan(halfRad) / zoom))
        }
    }
}
