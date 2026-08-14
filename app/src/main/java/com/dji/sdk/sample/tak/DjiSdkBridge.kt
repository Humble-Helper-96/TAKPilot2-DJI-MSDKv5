package com.dji.sdk.sample.tak

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.taklite.util.AppLog
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.diagnostic.DJIDeviceHealthInfo
import dji.v5.manager.diagnostic.DJIDeviceHealthInfoChangeListener
import dji.v5.manager.diagnostic.DeviceHealthManager
import dji.v5.manager.interfaces.SDKManagerCallback
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registers the app with the DJI SDK (MSDK v5) directly from TAKPilot2's own
 * launcher activity.
 *
 * v5 notes versus the v4 original this file ports:
 * - Registration is a two-step handshake: SDKManager.init() fires
 *   onInitProcess, and registerApp() is called at INITIALIZE_COMPLETE.
 * - There is no startConnectionToProduct(): the product connection starts by
 *   itself after registration. onProductConnect/Disconnect arrive on the same
 *   SDKManagerCallback.
 * - AppActivationManager and UserAccountManager do not exist in v5. The
 *   activation/login flow is gone with them.
 * - Aircraft health/diagnostics come from DeviceHealthManager, not from a
 *   product callback.
 *
 * Rule (unchanged from v4): this bridge is the ONE owner of the SDK manager
 * callback and the health listener. Consumers read this bridge, never the SDK.
 */
object DjiSdkBridge {

    private const val TAG = "DjiSdkBridge"

    /** Aircraft health/readiness. Its own tag so it greps cleanly, and — importantly — one that
     *  is NOT in AppLog's TAK_TAGS, so it stays in the file when TAK logging is filtered off. */
    private const val DIAG_TAG = "TP2Diag"
    const val PERMISSION_REQUEST_CODE = 1001

    private val REQUIRED_PERMISSIONS: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.VIBRATE,
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECORD_AUDIO
            )
        } else {
            arrayOf(
                Manifest.permission.VIBRATE,
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECORD_AUDIO
            )
        }

    private val isRegistrationStarted = AtomicBoolean(false)
    private var activityRef: WeakReference<Activity>? = null

    /** True after onRegisterSuccess. The home screen polls this. */
    @Volatile
    var isRegistered: Boolean = false
        private set

    /** True while an aircraft is connected. The home screen polls this. */
    @Volatile
    var isProductConnected: Boolean = false
        private set

    fun missingPermissions(context: Context): Array<String> =
        REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    fun hasMissingPermissions(context: Context): Boolean = missingPermissions(context).isNotEmpty()

    /** Kick off the runtime permission dialog; result lands in the activity's
     *  onRequestPermissionsResult(PERMISSION_REQUEST_CODE, ...) — call [registerAndConnect]
     *  again once everything is granted. */
    fun requestMissingPermissions(activity: Activity) {
        ActivityCompat.requestPermissions(activity, missingPermissions(activity), PERMISSION_REQUEST_CODE)
    }

    /**
     * Initializes the SDK and registers the app. Safe to call more than once
     * (e.g. from onCreate and again from onRequestPermissionsResult) — only
     * the first call for the lifetime of the process does anything.
     */
    fun registerAndConnect(activity: Activity) {
        activityRef = WeakReference(activity)

        if (hasMissingPermissions(activity)) {
            AppLog.w(TAG, "registerAndConnect: permissions not yet granted, deferring")
            return
        }
        if (!isRegistrationStarted.compareAndSet(false, true)) {
            return
        }

        AppLog.i(TAG, "Initializing DJI MSDK v5")
        val appContext = activity.applicationContext
        SDKManager.getInstance().init(appContext, object : SDKManagerCallback {
            override fun onRegisterSuccess() {
                AppLog.i(TAG, "DJI SDK registration succeeded")
                isRegistered = true
            }

            override fun onRegisterFailure(error: IDJIError?) {
                AppLog.e(TAG, "DJI SDK registration failed: ${error?.description()}")
                // Allow a retry (e.g. no network yet on cold boot) on the next call.
                isRegistrationStarted.set(false)
            }

            override fun onProductConnect(productId: Int) {
                AppLog.d(TAG, "onProductConnect: $productId")
                isProductConnected = true
                subscribeDiagnostics()
                // Obstacle sensing is armed here, process-wide, NOT on the flight screen:
                // avoidance settings are enforced pre-flight, which happens while the pilot
                // is still on the home screen. Self-limits to airframes that have sensors.
                DjiObstacleState.onProductConnected(appContext)
            }

            override fun onProductDisconnect(productId: Int) {
                AppLog.d(TAG, "onProductDisconnect")
                isProductConnected = false
                lastDiagnostics = ""
                // Stale faults from a disconnected aircraft must not stay on the pilot's screen.
                diagnostics = emptyList()
                runCatching { onDiagnostics?.invoke(emptyList()) }
                // Same reasoning, and more urgent: a stale obstacle distance reads as a live
                // clearance measurement. It must go the instant the aircraft does.
                DjiObstacleState.onProductDisconnected()
            }

            override fun onProductChanged(productId: Int) {
                AppLog.d(TAG, "onProductChanged: $productId")
            }

            override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                AppLog.d(TAG, "onInitProcess: $event")
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    AppLog.i(TAG, "Registering app with the DJI SDK")
                    SDKManager.getInstance().registerApp()
                }
            }

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                if (total > 0) {
                    AppLog.d(TAG, "Fly-zone DB load progress: ${100 * current / total}%")
                }
            }
        })
    }

    /**
     * Subscribes to the aircraft's own health/diagnostics stream — DJI's answer to "why won't it
     * do the thing", the same content DJI's app prints as banner text (compass error, IMU
     * calibration required, restricted zone, battery fault, and the rest).
     *
     * Ported from the v4 motors-won't-start lesson: the app configured the aircraft, logged
     * every call as OK, and then had nothing to say about why the aircraft declined to arm,
     * because it never asked.
     *
     * Logged under [DIAG_TAG], deliberately NOT a TAK tag: the readiness picture must survive the
     * Debug screen's "TAK logging off" filter.
     *
     * Deduped against the last rendered set: the listener re-fires with the same content, so
     * logging unconditionally would bury the log in repeats of one steady condition.
     */
    private var lastDiagnostics = ""

    /**
     * Current aircraft faults/warnings in pilot-readable form, newest snapshot wins. Empty when
     * the aircraft is happy or nothing is connected. Read this on screen entry — the listener
     * only fires on CHANGE, so a screen opened while a fault is already standing would otherwise
     * show nothing.
     */
    @Volatile
    var diagnostics: List<String> = emptyList()
        private set

    /** Notified (on DJI's callback thread — marshal to the UI yourself) whenever [diagnostics]
     *  changes. Single slot: the flight screen owns it while it's up. */
    @Volatile
    var onDiagnostics: ((List<String>) -> Unit)? = null

    private val healthListener = DJIDeviceHealthInfoChangeListener { list ->
        onHealthInfos(list.orEmpty())
    }
    private val hasHealthListener = AtomicBoolean(false)

    private fun subscribeDiagnostics() {
        runCatching {
            if (hasHealthListener.compareAndSet(false, true)) {
                DeviceHealthManager.getInstance()
                    .addDJIDeviceHealthInfoChangeListener(healthListener)
            }
            // The listener only fires on change; render whatever is already standing.
            onHealthInfos(DeviceHealthManager.getInstance().currentDJIDeviceHealthInfos.orEmpty())
            AppLog.i(DIAG_TAG, "diagnostics subscription active")
        }.onFailure {
            AppLog.w(DIAG_TAG, "could not subscribe to diagnostics: ${it.message}")
        }
    }

    private fun onHealthInfos(items: List<DJIDeviceHealthInfo>) {
        val rendered = if (items.isEmpty()) "none" else items.joinToString(" | ") {
            "[${it.componentId()}/${it.informationCode()}/${it.warningLevel()}] " +
                "${it.title()}${it.description()?.let { d -> " -> $d" } ?: ""}"
        }
        if (rendered == lastDiagnostics) return
        lastDiagnostics = rendered
        AppLog.i(DIAG_TAG, "aircraft diagnostics: $rendered")
        // Distinct: the same condition can be reported once per affected component, and a
        // pilot does not need to read it twice.
        val readable = items.mapNotNull { d ->
            humanReason(d.title())?.let { r ->
                val fix = humanReason(d.description())
                if (fix.isNullOrEmpty()) r else "$r — $fix"
            }
        }.distinct()
        diagnostics = readable
        runCatching { onDiagnostics?.invoke(readable) }
    }

    /**
     * DJI hands back an untranslated enum token instead of a sentence for conditions it has no
     * localized string for. Showing a pilot SCREAMING_SNAKE is worse than not showing it, so
     * tokens get turned back into words. Anything already containing lowercase is a real
     * message and passes through untouched.
     */
    private fun humanReason(reason: String?): String? {
        val raw = reason?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (!raw.matches(Regex("[A-Z0-9_]{4,}"))) return raw
        return raw.split('_')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.lowercase().replaceFirstChar { c -> c.uppercase() } }
    }
}
