package com.dji.sdk.sample.takpilot2

import android.app.Application
import android.content.Context
import com.cySdkyc.clx.Helper
import com.dji.sdk.sample.tak.TakBridgeHolder
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
    }
}
