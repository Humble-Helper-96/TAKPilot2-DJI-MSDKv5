package com.dji.sdk.sample.tak

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog
import java.io.File
import java.util.UUID

/**
 * Silent TAK reconnect from saved enrollment — no UI, no password re-entry. Used two ways:
 *  - [attemptOnAppLaunch]: fired once from the home screen (the launcher activity) so a
 *    configured server is connected, with channels pulled, before the pilot even opens
 *    Pre-Flight Setup.
 *  - [toggle]: the flight-screen TAK icon's on/off tap (connect if saved creds exist and we're
 *    not connected; disconnect otherwise).
 *
 * Shares the same SharedPreferences keys as [TakConnectActivity].
 */
object TakAutoConnect {
    private const val TAG = "TakAutoConnect"
    private const val PREFS = "takpilot2_tak"
    private const val KEY_HOST = "host"
    private const val KEY_COT_PORT = "cot_port"
    private const val KEY_USERNAME = "username"
    private const val KEY_CALLSIGN = "callsign"
    private const val KEY_UID = "uid"
    private const val KEY_TRUSTSTORE = "truststore_path"
    private const val KEY_CLIENTCERT = "clientcert_path"
    private const val KEY_CAMERA_POINT = "camera_point"
    private const val KEY_LOGGED_OUT = "logged_out"

    @Volatile private var attemptedThisProcess = false

    fun attemptOnAppLaunch(context: Context) {
        if (attemptedThisProcess) return
        attemptedThisProcess = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (TakManager.getInstance().isConnected) return
        if (prefs.getBoolean(KEY_LOGGED_OUT, false)) {
            AppLog.i(TAG, "user logged out — skipping auto-connect")
            return
        }
        if (!hasSavedCerts(prefs)) {
            AppLog.i(TAG, "no saved enrollment — skipping auto-connect")
            return
        }
        AppLog.i(TAG, "auto-connecting to saved TAK server on app launch")
        reconnect(context.applicationContext)
    }

    /** Flight-screen TAK icon tap: connect if we can, disconnect if we're up. */
    fun toggle(context: Context, onResult: (ok: Boolean, msg: String) -> Unit) {
        if (TakManager.getInstance().isConnected) {
            AppLog.i(TAG, "TAK icon tap — disconnecting")
            runCatching { TakManager.getInstance().disconnect() }
            runCatching { TakForegroundService.stop(context.applicationContext) }
            onResult(true, "TAK disconnected")
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!hasSavedCerts(prefs)) {
            onResult(false, "No saved TAK enrollment — set up the server in Pre-Flight Setup first")
            return
        }
        AppLog.i(TAG, "TAK icon tap — reconnecting")
        // R33: report what actually happened. This used to claim "Reconnecting to TAK…" even
        // when reconnect() had refused the request, so a pilot tapping a second time was told
        // it was working while nothing new had started.
        if (reconnect(context.applicationContext)) {
            onResult(true, "Reconnecting to TAK…")
        } else {
            onResult(false, "Already reconnecting to TAK…")
        }
    }

    fun hasSavedCerts(prefs: android.content.SharedPreferences): Boolean {
        val ts = prefs.getString(KEY_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CLIENTCERT, "") ?: ""
        return ts.isNotEmpty() && cc.isNotEmpty() && File(ts).exists() && File(cc).exists()
    }

    /**
     * True while a connect attempt is on its worker thread. R33: without this a second TAK-icon
     * tap spawned a PARALLEL connect, and TakManager.connect() is not reentrant — it disconnects
     * and reassigns its client field with no lock, so two racing calls can orphan a live
     * TakClient socket thread that keeps publishing PLI for the same uid with nothing left
     * holding a reference to stop it.
     */
    @Volatile private var connecting = false

    /**
     * Connect using saved certs + saved server settings.
     *
     * @return true if an attempt was actually started. False means nothing is in flight —
     * either the enrollment is unusable or an attempt is already running — which the caller
     * needs in order to tell the pilot something true.
     */
    fun reconnect(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, "") ?: ""
        val username = prefs.getString(KEY_USERNAME, "") ?: ""
        val cotPort = prefs.getInt(KEY_COT_PORT, 8089)
        val ts = prefs.getString(KEY_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CLIENTCERT, "") ?: ""
        val callsign = prefs.getString(KEY_CALLSIGN, "sUAS") ?: "sUAS"
        var uid = prefs.getString(KEY_UID, "") ?: ""
        if (uid.isEmpty()) {
            uid = "TAKPilot2-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_UID, uid).apply()
        }
        if (host.isEmpty() || ts.isEmpty() || cc.isEmpty()) {
            AppLog.w(TAG, "reconnect requested but saved enrollment is incomplete")
            return false
        }
        if (connecting) {
            AppLog.i(TAG, "reconnect already in flight — ignoring this request")
            return false
        }
        connecting = true
        val droneUid = "$uid-DRONE"
        Thread {
            // R33: the body used to be bare. An exception on a plain Thread reaches Android's
            // default uncaught handler, which KILLS THE PROCESS — so a bad cert file or an
            // unreachable host could take the whole flight screen down mid-flight, from a
            // background thread, for a failure that only ever needed a log line.
            try {
                TakManager.getInstance().connect(
                    uid, callsign, "Cyan", "Team Member",
                    host, cotPort, ts, "atakatak", cc, "atakatak",
                )
                TakBridgeHolder.start(droneUid, callsign)
                TakBridgeHolder.setCameraPointEnabled(prefs.getBoolean(KEY_CAMERA_POINT, false))
                TakForegroundService.start(context, callsign)
                // R24: this line used to read "connected to …". connect() is fire-and-forget —
                // it only starts TakClient's socket thread — so the log asserted a connection
                // that may never have happened, which is misleading in exactly the log someone
                // reads to find out why TAK is not working. The flight screen's TAK dot polls
                // the real state.
                AppLog.i(TAG, "connecting to $host:$cotPort as $callsign (socket result follows)")
                // The channel auto-pull is gone with channel selection (2026-08-15): the feature
                // silently destroyed markers. See TakManager.
            } catch (t: Throwable) {
                AppLog.e(TAG, "TAK reconnect failed: ${t.message}", t)
            } finally {
                connecting = false
            }
        }.start()
        return true
    }
}
