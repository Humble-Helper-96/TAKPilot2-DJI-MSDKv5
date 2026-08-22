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

    /**
     * The runtime permissions the pilot must grant before the SDK can register.
     *
     * This list holds DANGEROUS permissions only. Do not add a normal (install-time)
     * permission here, such as INTERNET, VIBRATE, BLUETOOTH or the WiFi-state permissions.
     * The system grants a normal permission at install time when the manifest declares it,
     * and `requestPermissions` cannot grant one. If a normal permission is in this list and
     * the manifest does not declare it, `checkSelfPermission` reports it denied for ever.
     * The gate then never opens, `registerAndConnect` is never called, and the SDK never
     * registers — with no dialog, no log line and no error.
     *
     * That defect was live on the bench on 2026-08-19: `VIBRATE` was in this list but was
     * not in the manifest, so registration never started. The V1 reference tree
     * (`Org_TAKPilot2-source-V1`, `DJIMainActivity.permissionArray`) keeps normal
     * permissions out of its gate for this reason. Keep them out of this one.
     */
    private val REQUIRED_PERMISSIONS: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECORD_AUDIO
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
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

    /**
     * The permissions from [REQUIRED_PERMISSIONS] that this APK actually declares.
     *
     * R43: the doc above explains why an undeclared permission in the gate is fatal and silent —
     * `checkSelfPermission` reports it denied for ever, `requestPermissions` cannot grant it,
     * so the gate never opens and the SDK never registers. That has already happened once
     * (VIBRATE, 2026-08-19) and was one dependency change away from happening again, because
     * two of the entries below were only in the merged manifest by courtesy of the DJI AAR.
     *
     * Declaring them (see AndroidManifest) fixes today's instance. This makes the CLASS of bug
     * impossible: a permission this APK does not declare is dropped from the gate and logged
     * loudly, so the worst case becomes "registered, with a capability possibly missing and a
     * warning in the log" instead of "never registered, in silence".
     */
    private fun declaredRequiredPermissions(context: Context): List<String> {
        val declared = runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.toSet()
        }.getOrNull()
        // Could not read our own package info — assume the list is right rather than
        // suppressing the gate entirely on a lookup failure.
        if (declared == null) return REQUIRED_PERMISSIONS.toList()
        val (present, absent) = REQUIRED_PERMISSIONS.partition { it in declared }
        if (absent.isNotEmpty()) {
            AppLog.e(TAG, "PERMISSION GATE MISMATCH — requested but NOT DECLARED in the " +
                "manifest: ${absent.joinToString()}. Dropping them from the gate so " +
                "registration can still proceed; declare them in AndroidManifest.xml.")
        }
        return present
    }

    fun missingPermissions(context: Context): Array<String> =
        declaredRequiredPermissions(context).filter {
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
        // ⚠ WORST FIRST. The banner shows the worst fault and counts the rest as "+N"
        // (specification §4.8), thus the order of this list decides what the pilot reads. The
        // aircraft does NOT send them worst-first: on the bench it led with a NOTICE about the
        // memory card while two CAUTIONs sat behind it (2026-08-19). sortedByDescending is
        // stable, so faults of equal severity keep the aircraft's own order.
        val readable = items.sortedByDescending { severityRank(it.warningLevel()?.toString()) }
            .mapNotNull { d ->
            val code = d.informationCode()?.toString()
            // Not in front of the pilot if the OEM does not put it there either. The full list
            // is already in the log line above, thus nothing is lost to a post-flight read.
            if (isOemHidden(code)) return@mapNotNull null
            // A verified English line for this exact code wins outright. For these faults the
            // aircraft sends Chinese in BOTH its fields, thus there is no readable text to
            // keep and the "never re-word" rule protects nothing — it only leaves the pilot
            // with a warning they cannot read. The code stays on the line, so the aircraft's
            // own wording is always one lookup away.
            FAULT_ENGLISH[code]?.let { return@mapNotNull "$it ($code)" }
            humanReason(d.title())?.let { r ->
                val fix = humanReason(d.description())
                val text = when {
                    fix.isNullOrEmpty() -> r
                    // ⚠ The Matrice 4T puts the SAME SENTENCE in title and description, so
                    // "$r — $fix" printed everything twice and doubled the length of a banner
                    // that already covers the video (bench, 2026-08-19). Sometimes the two are
                    // equal apart from case and punctuation; sometimes the description is the
                    // title PLUS the fault code in brackets.
                    //
                    // Keep the longer of the two when one contains the other, thus the code is
                    // never lost and the repeat is never printed. This removes repetition only
                    // — it is not the filtering that §4.8 forbids. When the two really do say
                    // different things, both still print.
                    repeats(r, fix) -> if (fix.length >= r.length) fix else r
                    else -> "$r — $fix"
                }
                // Not translated, and not English. Keep the aircraft's own words — that rule
                // still holds for anything not in the table — but append the code, so an
                // unreadable warning is at least a warning the pilot can look up.
                if (code != null && hasCjk(text) && !text.contains(code)) "$text ($code)" else text
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
    /**
     * English for aircraft faults that the Matrice 4T reports ONLY in Chinese.
     *
     * The aircraft localises most of its health messages, but not all of them: a few arrive
     * with Chinese in both `title()` and `description()` whatever the controller's language
     * is. A warning a pilot cannot read is worse than useless on a banner that covers the
     * live video, so those get an English line here.
     *
     * RULES FOR THIS TABLE:
     * - A code goes in ONLY after it has been seen on the bench and the Chinese has been read
     *   in full. Do not add a code from documentation, and do not guess a translation.
     * - Keep the English short. It shares a banner with everything else the aircraft is
     *   saying.
     * - Anything not listed stays in the aircraft's own words, with its code appended. That
     *   is the default and it must stay the default — this table is the exception.
     * - The code prints beside the English, thus the original is always one lookup away and a
     *   post-flight reader can check the wording against the flight record.
     *
     * Seen on the bench 2026-08-19, Matrice 4T on firmware as delivered:
     * - 0x1900D004 一致性检查：一致性检查不通过 — literally "consistency check: consistency
     *   check did not pass".
     * - 0x1AFC0140 存在炸机日志，拉取并清理炸机日志后在进行飞测 — literally "crash logs are
     *   present; pull and clear the crash logs before flight testing". 炸机 is the standard
     *   term for a crashed aircraft, and 飞测 is flight test.
     */
    private val FAULT_ENGLISH: Map<String, String> = mapOf(
        "0x1900D004" to "Consistency check failed",
        "0x1AFC0140" to "Crash logs stored on the aircraft. Download and clear them before flight test",
    )

    /** True when the text holds Chinese characters, thus it was never localised. */
    private fun hasCjk(s: String): Boolean = s.any { it.code in 0x4E00..0x9FFF }

    /**
     * Fault codes that DJI Pilot 2 does NOT put in front of a pilot, and neither do we
     * (operator, 2026-08-19).
     *
     * ⚠ THIS IS A DELIBERATE EXCEPTION TO FlightWarnings' "nothing is filtered out on the
     * pilot's behalf" RULE. That rule was written after a sibling application hid "Cannot
     * takeoff in a no-fly zone" through two flights, so weakening it is not free. Read that
     * rule before you add a code here, and do not add one to reduce clutter.
     *
     * The list is MEASURED, not guessed. On 2026-08-13 the controller's HMS log recorded 134
     * alarms across 33 distinct codes; Pilot 2's Error Records screen displayed 9 of those
     * codes and its flight screen displayed none at all. These are the 24 it withheld. The
     * comparison and the screenshots are in
     * `DJI/v5/pilot2-reference/hms-error-records-2026-08-13/`.
     *
     * Most are a module-enumeration burst that fires on every power-on — the 1649xx, 164Cxx,
     * 1641xx, 1643xx families — which is what made the banner noisy on a screen where it
     * covers live video.
     *
     * ⚠ Suppressed is not unrecorded. Every fault the aircraft reports is still written to the
     * log in full, above, BEFORE this filter runs, thus a post-flight reader loses nothing and
     * this list can be re-judged from real data.
     *
     * ⚠ Three of these were on the bench banner when the list was made, including
     * `1AFC0140`, the stored-crash-log caution that sent the operator to DJI support that same
     * day. Suppressing it is the operator's decision and it is reversible: delete the line.
     */
    private val OEM_HIDDEN_CODES: Set<String> = setOf(
        // Module enumeration burst, every power-on.
        "16200601", "16411004", "16411205", "16411302",
        "16430014", "16430016", "16430018", "1643001a",
        "16493400", "16493500", "16493600", "16493700", "16493800", "16493900",
        "164c04be", "164c04fe", "164c0680", "164c06c0", "164c0700",
        "1a0100f0",
        // Informational, not a reason the aircraft will not fly.
        "19000703",  // memory card not recommended
        "1b080003",  // "Remote ID functionality normal" — reports that something is FINE
        // Cautions DJI files in its records screen and never raises in flight.
        "1900d004",  // consistency check failed
        "1afc0140",  // stored crash logs — delete this line to put it back on the banner
    )

    /** Normalised for lookup: the aircraft reports "0x1AFC0140", the set holds "1afc0140". */
    private fun isOemHidden(code: String?): Boolean =
        code != null && OEM_HIDDEN_CODES.contains(code.removePrefix("0x").removePrefix("0X").lowercase())

    /**
     * Severity as a number, so the fault list can be ordered worst-first.
     *
     * Matched on the NAME and not on the enum, because an unknown level must not crash and
     * must not silently sort to the top. An unrecognised name ranks 0 and lands last — it is
     * still on the banner behind the "+N", never dropped.
     */
    private fun severityRank(level: String?): Int = when (level?.uppercase()) {
        "SERIOUS", "FATAL" -> 4
        "WARNING" -> 3
        "CAUTION" -> 2
        "NOTICE" -> 1
        else -> 0
    }

    /**
     * True when one of the two strings only repeats the other, so the banner must print one.
     *
     * Compared on letters and digits alone: the aircraft varies case and punctuation between
     * the title and the description of the same fault, and adds the fault code in brackets to
     * one of them. Both of those are repetition, not a second fact.
     */
    private fun repeats(a: String, b: String): Boolean {
        val na = a.filter { it.isLetterOrDigit() }.lowercase()
        val nb = b.filter { it.isLetterOrDigit() }.lowercase()
        if (na.isEmpty() || nb.isEmpty()) return true
        return na.contains(nb) || nb.contains(na)
    }

    private fun humanReason(reason: String?): String? {
        val raw = reason?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (!raw.matches(Regex("[A-Z0-9_]{4,}"))) return raw
        return raw.split('_')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.lowercase().replaceFirstChar { c -> c.uppercase() } }
    }
}
