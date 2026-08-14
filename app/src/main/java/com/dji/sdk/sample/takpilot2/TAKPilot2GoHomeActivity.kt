package com.dji.sdk.sample.takpilot2

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dji.sdk.sample.R
import com.dji.sdk.sample.tak.DjiSdkBridge

/**
 * Phase 1 placeholder home screen. Shows SDK registration and product
 * connection state. Phase 2 replaces this with the ported v4 home
 * screen (activity_takpilot2go_home).
 */
class TAKPilot2GoHomeActivity : AppCompatActivity(), DjiSdkBridge.Listener {

    private lateinit var regText: TextView
    private lateinit var productText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_takpilot2go_home)
        regText = findViewById(R.id.homeRegState)
        productText = findViewById(R.id.homeProductState)
    }

    override fun onStart() {
        super.onStart()
        DjiSdkBridge.addListener(this)
    }

    override fun onStop() {
        super.onStop()
        DjiSdkBridge.removeListener(this)
    }

    override fun onRegStateChanged(state: DjiSdkBridge.RegState, detail: String) {
        runOnUiThread { regText.text = "SDK: $state — $detail" }
    }

    override fun onProductConnectionChanged(connected: Boolean, productId: Int) {
        runOnUiThread {
            productText.text = if (connected) "Aircraft: connected" else "Aircraft: not connected"
        }
    }
}
