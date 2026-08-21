package com.dji.sdk.sample.takpilot2

import android.app.Application
import android.content.Context
import com.cySdkyc.clx.Helper
import com.dji.sdk.sample.tak.FlightPathLogger
import com.dji.sdk.sample.tak.TakBridgeHolder
import com.dji.sdk.sample.tak.TakDropMarkers
import com.dji.sdk.sample.tak.TakMissionManager
import com.taklite.util.AppLog

class TAKPilot2Application : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // DJI security component. Must install before the SDK starts.
        Helper.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // AppLog holds appContext as a lateinit, and nothing called init() until now. The
        // Log.* half of AppLog worked without it, thus the gap stayed hidden; the file half
        // did not. The Debug screen reads the log file on a timer and crashed the process on
        // open (bench, 2026-08-19). Initialise before anything logs.
        AppLog.init(this)
        TakBridgeHolder.init(this)
        // CodeReview-v1.0.0 R1-R3: these three singletons each store appContext only in
        // init(), and nothing called init() on any of them. Each failed silently: no flight
        // record (R1), every install shared the "E419" callsign and lost dropped pins on
        // restart (R2), and feed membership never survived a restart (R3).
        FlightPathLogger.init(this)
        FlightPathLogger.sweepOrphans()
        TakDropMarkers.init(this)
        TakMissionManager.init(this)
    }
}
