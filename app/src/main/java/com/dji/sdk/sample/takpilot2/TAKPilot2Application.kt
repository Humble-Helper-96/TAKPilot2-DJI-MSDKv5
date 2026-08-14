package com.dji.sdk.sample.takpilot2

import android.app.Application
import android.content.Context
import com.cySdkyc.clx.Helper
import com.dji.sdk.sample.tak.DjiSdkBridge

class TAKPilot2Application : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // DJI security component. Must install before the SDK starts.
        Helper.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        DjiSdkBridge.start(this)
    }
}
