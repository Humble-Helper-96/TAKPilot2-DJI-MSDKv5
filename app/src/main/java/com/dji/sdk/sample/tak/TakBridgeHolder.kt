package com.dji.sdk.sample.tak

/** Process-wide holder so the bridge survives screen navigation. */
object TakBridgeHolder {
    // Application context, wired from TAKPilot2Application.onCreate. The v5 bridge needs a
    // Context (prefs, DTED lookups, battery service) and v4's DJISampleApplication.getInstance()
    // is gone.
    private lateinit var appContext: android.content.Context

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    // Declared first: the FOV properties below initialise from these, and Kotlin resolves
    // object properties in declaration order.
    const val DEFAULT_HFOV = 73.0
    const val DEFAULT_VFOV = 45.0
    const val MIN_FOV = 5.0
    const val MAX_FOV = 170.0

    private var bridge: DroneTakBridge? = null
    // Remembered so it survives bridge restarts (reconnect) and a start-before-connect order.
    private var videoUrl: String? = null

    // ---- Aim calibration (V32, audit 2026-08-20; the Autel sibling's design) ----------------
    const val DEFAULT_PITCH_OFFSET = 0.0
    const val DEFAULT_BEARING_OFFSET = 0.0
    /** These correct MOUNT TOLERANCE, not gross error. A pitch offset beyond this would mean
     *  something mechanically wrong that calibration must not paper over. */
    const val MAX_PITCH_OFFSET = 15.0

    @Volatile private var pitchOffset: Double = DEFAULT_PITCH_OFFSET
    @Volatile private var bearingOffset: Double = DEFAULT_BEARING_OFFSET

    /** Sets the aim calibration. Clamped — a mistyped value is refused rather than quietly
     *  aimed at the horizon. */
    fun setAimOffsets(pitchDeg: Double, bearingDeg: Double) {
        pitchOffset = pitchDeg.coerceIn(-MAX_PITCH_OFFSET, MAX_PITCH_OFFSET)
        bearingOffset = ((bearingDeg % 360.0) + 540.0) % 360.0 - 180.0   // normalise to ±180
    }

    val currentPitchOffset: Double get() = pitchOffset
    val currentBearingOffset: Double get() = bearingOffset
    private var cameraPointEnabled = false
    private var zoomFactor: Double = 1.0

    // Calibrated camera field of view (degrees at 1x). Defaults are the published Mini 2 specs
    // (83 deg diagonal on a 16:9 frame); the real lens is whatever it is, which is what the 6D-D
    // calibration measures. Held here so the published FOV cone and the AR projection always
    // read the same numbers.
    /** 16:9, the shape of the visible-camera stream. Only used before the first frame. */
    private const val FALLBACK_ASPECT = 16.0 / 9.0

    private var hFovBase: Double = DEFAULT_HFOV

    /**
     * The live video's width/height. Fed from [com.dji.sdk.sample.takpilot2.FpvTextureView],
     * which already computes it to letterbox the frame, so the two can never disagree about
     * the shape of the picture.
     *
     * It matters more than it looks: the thermal camera is 640x512 (5:4), not 16:9, so the
     * vertical field changes the moment the pilot touches IR.
     */
    @Volatile private var videoAspect: Double = FALLBACK_ASPECT

    fun setVideoAspect(aspect: Double) {
        if (aspect.isFinite() && aspect > 0.0) videoAspect = aspect
    }

    fun start(droneUid: String, droneCallsign: String) {
        // finalizeFlight=false: this is a RESTART, and the flight is the same flight. The
        // replacement bridge continues the open GPX session instead of splitting it (V33).
        bridge?.stop(finalizeFlight = false)
        bridge = DroneTakBridge(appContext, droneUid, droneCallsign).also {
            it.videoUrl = videoUrl
            it.cameraPointEnabled = cameraPointEnabled
            it.zoomFactor = zoomFactor
            it.start()
        }
    }

    fun stop() {
        bridge?.stop()
        bridge = null
    }

    /** Advertise (or clear) the video URL in the drone CoT. Null/empty removes it. */
    fun setVideoUrl(url: String?) {
        videoUrl = url?.takeIf { it.isNotBlank() }
        bridge?.videoUrl = videoUrl
    }

    /** Enable/disable the live camera slant-point (sensor point of interest) marker. */
    fun setCameraPointEnabled(enabled: Boolean) {
        cameraPointEnabled = enabled
        bridge?.cameraPointEnabled = enabled
    }

    val isCameraPointEnabled: Boolean get() = cameraPointEnabled

    /**
     * Current digital zoom (1.0 = none), set by the flight screen's zoom control.
     *
     * Held here rather than only in the Activity because TWO things depend on it and must not
     * disagree: the FOV cone published to other TAK clients, and the AR overlay's projection.
     * Zooming crops the camera's angular width, so a fixed 1x FOV puts every AR marker at
     * roughly half its correct offset from centre at 2x — which is exactly how this surfaced.
     */
    fun setZoomFactor(factor: Double) {
        zoomFactor = if (factor.isFinite() && factor >= 1.0) factor else 1.0
        bridge?.zoomFactor = zoomFactor
    }

    val currentZoomFactor: Double get() = zoomFactor

    /**
     * Set the calibrated 1x field of view. Clamped to sane bounds so a mis-tap can't drive the
     * projection somewhere absurd — an FOV near zero sends every marker to infinity.
     */
    /**
     * Sets the calibrated 1x HORIZONTAL field of view. THERE IS NO VERTICAL SETTER.
     *
     * ⚠ THE TWO AXES ARE NOT INDEPENDENT. For any rectilinear camera they are bound by
     *
     *     tan(hFov/2) / tan(vFov/2) == frameWidth / frameHeight
     *
     * so the vertical is whatever that identity says it is. This tree carried SEPARATE H and V
     * knobs until 2026-08-20, tied by nothing, which let a pilot calibrate the pair into a
     * shape no real lens has — and because the projection uses both, the result is an overlay
     * that is wrong in a way more tuning cannot fix. Deriving the vertical means the two axes
     * cannot drift apart and a camera mode change re-derives it for free.
     */
    /**
     * The DIAGONAL field the camera itself reported for the live lens AT ITS CURRENT ZOOM,
     * or null when the camera has not answered. See [com.dji.sdk.sample.tak.CameraFov] for
     * the measurement and the units. Preferred over the calibrated base wherever present:
     * the aircraft's answer beats the pilot's estimate, and it is the only source that is
     * right above 1x — the tele lens is a different focal length, and dividing the wide
     * base by the nominal rung was wrong twice over (the real gear at "3X" is 2.917x).
     */
    @Volatile private var cameraDFovDeg: Double? = null

    fun setCameraFov(dfovDeg: Double) {
        // ⚠ NOT MIN_FOV. That bound guards the calibrated BASE, where 5 deg would mean a
        // mistyped value. A camera-reported field INCLUDES the zoom, and at 28x the true
        // diagonal is 3.7 deg — the first bench pass used MIN_FOV here, rejected the correct
        // answer, and silently fell back to the known-wrong division at exactly the rung
        // where the camera's answer matters most. The floor only has to reject nonsense.
        cameraDFovDeg = dfovDeg.takeIf { it.isFinite() && it in 0.5..MAX_FOV * 2 }
    }

    fun clearCameraFov() { cameraDFovDeg = null }

    /**
     * The CURRENT horizontal field, zoom included: the camera's own answer when it has given
     * one, else the calibrated base narrowed by the nominal zoom factor. The vertical always
     * follows through [vFovFor], so the axes cannot disagree whichever source wins.
     */
    fun currentHFov(): Double {
        val d = cameraDFovDeg ?: return Double.NaN
        // Diagonal -> horizontal under the live aspect: tanH = tanD * w/sqrt(w*w+h*h) —
        // then narrowed by the display crop, which zooms exactly as a real gear does.
        val a = videoAspect
        val f = a / Math.sqrt(a * a + 1.0)
        val h = 2.0 * Math.toDegrees(Math.atan(Math.tan(Math.toRadians(d / 2.0)) * f))
        return cropFov(h)
    }

    /**
     * The hybrid ladder's display crop, 1.0 = none. The camera reports the FOV of its GEAR;
     * the crop is this application's own narrowing on top, so the effective field is
     * tan(eff/2) = tan(gear/2) / crop — the same identity a real zoom obeys. Fed from the
     * flight screen beside FpvTextureView.setDigitalCrop, so the picture and the geometry
     * cannot disagree about the crop.
     */
    @Volatile private var digitalCrop: Double = 1.0

    fun setDigitalCrop(crop: Double) {
        digitalCrop = crop.takeIf { it.isFinite() && it >= 1.0 } ?: 1.0
    }

    fun cropFov(deg: Double): Double {
        val c = digitalCrop
        if (c <= 1.0 || !deg.isFinite()) return deg
        return 2.0 * Math.toDegrees(Math.atan(Math.tan(Math.toRadians(deg / 2.0)) / c))
    }

    val hasCameraFov: Boolean get() = cameraDFovDeg != null

    fun setHFovBase(hDeg: Double) {
        hFovBase = hDeg.coerceIn(MIN_FOV, MAX_FOV)
    }

    val currentHFovBase: Double get() = hFovBase

    /** The vertical that pairs with the calibrated horizontal under the LIVE video aspect. */
    val currentVFovBase: Double get() = vFovFor(hFovBase)

    /** The vertical that pairs with [hDeg]. Shared so the published `<sensor>` cone and the AR
     *  projection derive it exactly one way. */
    fun vFovFor(hDeg: Double): Double {
        val aspect = videoAspect.takeIf { it > 0.0 } ?: FALLBACK_ASPECT
        return 2.0 * Math.toDegrees(Math.atan(Math.tan(Math.toRadians(hDeg / 2.0)) / aspect))
    }


    val isRunning: Boolean get() = bridge != null

    /** Ground point the camera is currently aimed at (for the drop-marker-at-look-point hot key),
     *  or null if the bridge isn't running / GPS+gimbal aren't ready. */
    fun lookPoint(): Triple<Double, Double, Double>? = bridge?.lookPoint()

    /** See [DroneTakBridge.lookRangeMeters]. Null with no bridge, or no ground intersection. */
    fun lookRangeMeters(): Double? = bridge?.lookRangeMeters()

    /** Latest telemetry snapshot for the on-screen HUD, or null if the bridge isn't running. */
    fun hud(): DroneTakBridge.Hud? = bridge?.hud()

    /** See [DroneTakBridge.photoInProgress]. False when the bridge isn't running — with no camera
     *  state to consult, a caller waiting on this must proceed rather than block forever. */
    fun photoInProgress(): Boolean = bridge?.photoInProgress() ?: false

    /** Camera bearing + pitch for the AR overlay's projection — the same model that places
     *  dropped markers, see [DroneTakBridge.cameraPose]. Null until GPS/gimbal are ready. */
    fun cameraPose(): DroneTakBridge.CameraPose? = bridge?.cameraPose()

    /** See [DroneTakBridge.isOwnPublishedUid]. False when the bridge isn't running — nothing is
     *  being published then, so nothing coming back can be ours. */
    fun isOwnPublishedUid(candidate: String?): Boolean =
        bridge?.isOwnPublishedUid(candidate) ?: false
}
