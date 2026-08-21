package com.dji.sdk.sample.tak

import android.content.Context
import com.taklite.util.AppLog
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType
import dji.v5.manager.aircraft.perception.data.ObstacleData
import dji.v5.manager.aircraft.perception.listener.ObstacleDataListener

/**
 * Obstacle-avoidance state, cached process-wide. The DJI counterpart of the Autel port's
 * `AutelAvoidance`, deliberately the same shape so the ports stay readable side by side.
 *
 * v5 port notes:
 * - v4's per-face VisionDetectionState callbacks are gone. v5's PerceptionManager pushes ONE
 *   [ObstacleData] with a horizontal distance ring (List<Int>, one entry per
 *   `horizontalAngleInterval` degrees) plus single up/down distances.
 * - **Units are MILLIMETRES.** The uxsdk RadarWidgetModel divides these by 1000 to get
 *   metres; this file does the same in exactly one place ([mmToMeters]).
 * - The horizontal ring is folded into four [Face] quadrants so the edge view keeps its v4
 *   shape. Ring index 0 is assumed to be the aircraft NOSE, increasing clockwise —
 *   ⚠ VERIFY ON THE BENCH with a wall on a known side before trusting left/right.
 * - v4's three avoidance switches collapse to one in v5: [ObstacleAvoidanceType]
 *   (BRAKE/BYPASS/CLOSE). RTH avoidance and landing protection are aircraft-firmware
 *   behavior in v5 with no public switch; their fields stay null and the Pre-Flight toggles
 *   for them are inert on this airframe.
 *
 * Absence must not read as safety: a ring that never reports means "this aircraft cannot
 * see that way", NOT "that way is clear" — [sensingAircraft] stays false until real data
 * arrives, and the display stays hidden.
 */
object DjiObstacleState {
    /** Avoidance settings, enforcement and warnings. ALWAYS logged. */
    private const val TAG = "TP2Obstacle"

    /** The repeating per-sample distance readout. Its own tag so the Debug screen can hide
     *  the volume WITHOUT hiding the settings lines above. */
    private const val RANGE_TAG = "TP2ObstacleRange"

    /** Horizontal quadrants relative to the airframe. Replaces v4's VisionSensorPosition. */
    enum class Face { NOSE, RIGHT, TAIL, LEFT }

    /** Per-face nearest obstacle in METRES. Absent = that face has not reported, which is
     *  not the same as clear. */
    @Volatile
    var faces: Map<Face, Float> = emptyMap()
        private set

    /** True once any real obstacle data has arrived — i.e. this airframe has sensors and
     *  they are alive. */
    @Volatile
    var sensingAircraft = false
        private set

    /** Notified whenever [faces] changes (on DJI's callback thread — marshal it yourself). */
    @Volatile
    var onChanged: (() -> Unit)? = null

    // ---- Live avoidance state, as the AIRCRAFT reports it ----
    // Null means "not read yet", NOT "off". The difference matters in front of a pilot.
    @Volatile var collisionAvoidance: Boolean? = null; private set
    /** No public v5 switch — always null; kept so the Pre-Flight screen renders "unknown"
     *  rather than a lie. */
    @Volatile var rthAvoidance: Boolean? = null; private set
    @Volatile var landingProtection: Boolean? = null; private set

    private val obstacleListener = ObstacleDataListener { data -> onObstacleData(data) }
    @Volatile private var listenerArmed = false

    /** Wired from [DjiSdkBridge] on every (re)connect. */
    fun onProductConnected(context: Context) {
        runCatching {
            if (!listenerArmed) {
                listenerArmed = true
                PerceptionManager.getInstance().addObstacleDataListener(obstacleListener)
            }
            AppLog.i(TAG, "obstacle-data listener armed")
        }.onFailure { AppLog.w(TAG, "obstacle-data listener failed: ${it.message}") }

        readSwitches()
        applyAtConnect(context)
    }

    fun onProductDisconnected() {
        faces = emptyMap()
        sensingAircraft = false
        collisionAvoidance = null; rthAvoidance = null; landingProtection = null
        appliedForThisConnect = false
        lastLoggedNear = -1f
        runCatching { onChanged?.invoke() }
    }

    private fun mmToMeters(mm: Int?): Float? =
        mm?.takeIf { it > 0 }?.let { it / 1000f }

    /**
     * One push of the full obstacle picture. The horizontal ring is folded to the nearest
     * reading per quadrant; a quadrant with no valid reading DROPS from the map (an absent
     * face must never keep a stale distance alive).
     */
    private fun onObstacleData(data: ObstacleData?) {
        data ?: return
        val ring: List<Int> = data.horizontalObstacleDistance ?: emptyList()
        val next = HashMap<Face, Float>(4)
        if (ring.isNotEmpty()) {
            val n = ring.size
            fun quadrantMin(centerFrac: Double): Float? {
                // Quadrant = center ± 1/8 of the ring, wrapping.
                val half = n / 8
                val center = (centerFrac * n).toInt()
                var min: Int? = null
                for (off in -half..half) {
                    val v = ring[((center + off) % n + n) % n]
                    if (v > 0 && (min == null || v < min!!)) min = v
                }
                return mmToMeters(min)
            }
            // Index 0 = NOSE, clockwise — bench-verify before trusting left/right.
            quadrantMin(0.00)?.let { next[Face.NOSE] = it }
            quadrantMin(0.25)?.let { next[Face.RIGHT] = it }
            quadrantMin(0.50)?.let { next[Face.TAIL] = it }
            quadrantMin(0.75)?.let { next[Face.LEFT] = it }
        }
        if (ring.isNotEmpty() || data.upwardObstacleDistance != null) sensingAircraft = true
        faces = next
        next.entries.minByOrNull { it.value }?.let { logNearIfNotable(it.key, it.value) }
        // Up/down are logged only, not drawn — same policy as v4 (their calibration against
        // a known distance has not been done on this airframe).
        mmToMeters(data.upwardObstacleDistance)?.let { up ->
            if (up <= LOG_NEAR_M) AppLog.i(RANGE_TAG, "obstacle UP ${"%.1f".format(up)}m")
        }
        runCatching { onChanged?.invoke() }
    }

    /** Nearest obstacle on any face in metres, or null if nothing is reporting. */
    fun nearestMeters(): Float? = faces.values.minOrNull()

    // ---- Logging, biased toward what matters ----
    @Volatile private var lastLoggedNear = -1f
    @Volatile private var lastNearLogMs = 0L

    private fun logNearIfNotable(face: Face, m: Float) {
        if (m > LOG_NEAR_M) return
        val now = android.os.SystemClock.elapsedRealtime()
        // Log on meaningful movement or on a slow heartbeat, so a steady hover near a wall
        // does not fill the log while a genuine approach still gets sampled.
        if (now - lastNearLogMs < NEAR_MIN_GAP_MS && kotlin.math.abs(m - lastLoggedNear) < 0.5f) return
        lastNearLogMs = now
        lastLoggedNear = m
        AppLog.i(RANGE_TAG, "obstacle $face ${"%.1f".format(m)}m")
    }

    /** Below this a reading is worth a log line. 15 m, matching the Autel port's threshold. */
    private const val LOG_NEAR_M = 15f
    private const val NEAR_MIN_GAP_MS = 500L

    // ---- Pre-Flight's saved intent, enforced on every connect ----

    private const val PREFS = "takpilot2_avoid"
    private const val KEY_SYSTEM = "avoid_system"
    private const val KEY_RTH = "avoid_rth"
    private const val KEY_LANDING = "avoid_landing"

    /** Defaults are ON. An install nobody has configured must err toward protection. */
    fun savedSystem(c: Context) = prefs(c).getBoolean(KEY_SYSTEM, true)
    fun savedRth(c: Context) = prefs(c).getBoolean(KEY_RTH, true)
    fun savedLanding(c: Context) = prefs(c).getBoolean(KEY_LANDING, true)

    fun saveIntent(c: Context, system: Boolean, rth: Boolean, landing: Boolean) {
        prefs(c).edit().putBoolean(KEY_SYSTEM, system).putBoolean(KEY_RTH, rth)
            .putBoolean(KEY_LANDING, landing).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Reads what the aircraft currently has, so the pilot can be shown the real state and
     *  so [applyAtConnect] only writes a switch that is actually wrong. */
    private fun readSwitches() {
        runCatching {
            PerceptionManager.getInstance().getObstacleAvoidanceType(
                object : CommonCallbacks.CompletionCallbackWithParam<ObstacleAvoidanceType> {
                    override fun onSuccess(type: ObstacleAvoidanceType?) {
                        collisionAvoidance = when (type) {
                            ObstacleAvoidanceType.BRAKE, ObstacleAvoidanceType.BYPASS -> true
                            ObstacleAvoidanceType.CLOSE -> false
                            else -> null
                        }
                        AppLog.i(TAG, "obstacleAvoidanceType = $type")
                    }

                    override fun onFailure(error: IDJIError) {
                        // Common and harmless: airframes without the feature reject the getter.
                        AppLog.i(TAG, "obstacleAvoidanceType unavailable: ${error.description()}")
                    }
                })
        }
        AppLog.i(TAG, "rthAvoidance/landingProtection: no public v5 switch — " +
            "firmware-managed on this airframe, shown as unknown")
    }

    @Volatile private var appliedForThisConnect = false

    /**
     * Enforces the Pre-Flight selection on the aircraft, once per connect.
     *
     * Same doctrine as v4/Autel: leaving "whatever the DJI app last set" is not neutral, it
     * is UNKNOWN. Enforcement is only safe BECAUSE the state is visible on the flight screen.
     * Deferred by [APPLY_DELAY_MS] so the getter above has answered — only correct what is
     * wrong. NEVER rewrites a safety switch on an aircraft that is already flying.
     */
    private fun applyAtConnect(context: Context) {
        if (appliedForThisConnect) return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (appliedForThisConnect) return@postDelayed
            // Read from the bridge-independent keys: this fires at product connect, usually
            // before the pilot reaches the flight screen and its bridge, so nothing has
            // necessarily subscribed to these keys yet. CLAUDE.md rule 4: the one-argument
            // getValue(key) answers from MSDK's local CACHE only, and a key nothing has
            // fetched or listened to is absent from it — it returns null FOR EVER, which
            // getOrDefault(false) used to read as "not flying". Use the two-argument
            // getValue(key, callback) form, which actually queries the aircraft.
            readFlightState { flying, motorsOn ->
                if (flying || motorsOn) {
                    AppLog.w(TAG, "aircraft is flying/armed — SKIPPING avoidance enforcement this connect")
                    return@readFlightState
                }
                appliedForThisConnect = true
                val desired = savedSystem(context)
                if (collisionAvoidance == desired) {
                    AppLog.i(TAG, "collisionAvoidance already $desired — no write")
                    return@readFlightState
                }
                val type = if (desired) ObstacleAvoidanceType.BRAKE else ObstacleAvoidanceType.CLOSE
                AppLog.i(TAG, "enforcing obstacleAvoidanceType -> $type (aircraft had $collisionAvoidance)")
                runCatching {
                    PerceptionManager.getInstance().setObstacleAvoidanceType(
                        type,
                        object : CommonCallbacks.CompletionCallback {
                            override fun onSuccess() {
                                AppLog.i(TAG, "set obstacleAvoidanceType=$type: OK")
                                collisionAvoidance = desired
                            }

                            override fun onFailure(error: IDJIError) {
                                AppLog.i(TAG, "set obstacleAvoidanceType=$type: ${error.description()}")
                            }
                        })
                }.onFailure { AppLog.w(TAG, "set obstacleAvoidanceType threw: ${it.message}") }
            }
        }, APPLY_DELAY_MS)
    }

    /**
     * Reads KeyIsFlying and KeyAreMotorsOn with the two-argument (aircraft-querying) form of
     * getValue, then calls [onResult] once both have answered. A read that fails or never
     * answers resolves FAIL-SAFE — as flying/armed — so a dead key can only ever make this
     * gate MORE cautious, never let a write through it should have blocked.
     */
    private fun readFlightState(onResult: (flying: Boolean, motorsOn: Boolean) -> Unit) {
        var flying = true
        var motorsOn = true
        val remaining = java.util.concurrent.atomic.AtomicInteger(2)
        fun oneDone() { if (remaining.decrementAndGet() == 0) onResult(flying, motorsOn) }

        runCatching {
            dji.v5.manager.KeyManager.getInstance().getValue(
                dji.sdk.keyvalue.key.KeyTools.createKey(
                    dji.sdk.keyvalue.key.FlightControllerKey.KeyIsFlying),
                object : CommonCallbacks.CompletionCallbackWithParam<Boolean> {
                    override fun onSuccess(v: Boolean?) { flying = v == true; oneDone() }
                    override fun onFailure(error: IDJIError) {
                        AppLog.w(TAG, "KeyIsFlying read failed, assuming flying: ${error.description()}")
                        oneDone()
                    }
                })
        }.onFailure { AppLog.w(TAG, "KeyIsFlying getValue threw, assuming flying: ${it.message}"); oneDone() }

        runCatching {
            dji.v5.manager.KeyManager.getInstance().getValue(
                dji.sdk.keyvalue.key.KeyTools.createKey(
                    dji.sdk.keyvalue.key.FlightControllerKey.KeyAreMotorsOn),
                object : CommonCallbacks.CompletionCallbackWithParam<Boolean> {
                    override fun onSuccess(v: Boolean?) { motorsOn = v == true; oneDone() }
                    override fun onFailure(error: IDJIError) {
                        AppLog.w(TAG, "KeyAreMotorsOn read failed, assuming motors on: ${error.description()}")
                        oneDone()
                    }
                })
        }.onFailure { AppLog.w(TAG, "KeyAreMotorsOn getValue threw, assuming motors on: ${it.message}"); oneDone() }
    }

    /** Long enough for the getter to answer before enforcement compares against it. */
    private const val APPLY_DELAY_MS = 4500L
}
