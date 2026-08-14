package com.dji.sdk.sample.tak

import android.content.Context
import android.util.Log
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns SDK registration and the product connection state.
 *
 * Rule (from the v4 tree): this bridge is the ONE owner of the
 * SDKManagerCallback. Consumers subscribe to this bridge, never to the
 * SDK. The same rule applies to every other SDK listener in the app.
 */
object DjiSdkBridge {

    private const val TAG = "DjiSdkBridge"

    enum class RegState { NOT_STARTED, INITIALIZING, REGISTERED, FAILED }

    interface Listener {
        fun onRegStateChanged(state: RegState, detail: String)
        fun onProductConnectionChanged(connected: Boolean, productId: Int)
    }

    @Volatile
    var regState: RegState = RegState.NOT_STARTED
        private set

    @Volatile
    var regDetail: String = ""
        private set

    @Volatile
    var productConnected: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<Listener>()
    private var started = false

    fun addListener(l: Listener) {
        listeners.add(l)
        l.onRegStateChanged(regState, regDetail)
        l.onProductConnectionChanged(productConnected, -1)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** Call once from Application.onCreate. Safe to call again. */
    fun start(appContext: Context) {
        if (started) return
        started = true
        setRegState(RegState.INITIALIZING, "SDK init")
        SDKManager.getInstance().init(appContext, object : SDKManagerCallback {
            override fun onRegisterSuccess() {
                setRegState(RegState.REGISTERED, "Register success")
            }

            override fun onRegisterFailure(error: IDJIError?) {
                setRegState(RegState.FAILED, "Register failure: ${error?.description() ?: error}")
            }

            override fun onProductConnect(productId: Int) {
                setProductConnected(true, productId)
            }

            override fun onProductDisconnect(productId: Int) {
                setProductConnected(false, productId)
            }

            override fun onProductChanged(productId: Int) {
                Log.i(TAG, "Product changed: $productId")
            }

            override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                Log.i(TAG, "Init process: $event $totalProcess")
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    // Registration is a two-step handshake in v5.
                    SDKManager.getInstance().registerApp()
                }
            }

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                Log.i(TAG, "DB download: $current/$total")
            }
        })
    }

    private fun setRegState(state: RegState, detail: String) {
        regState = state
        regDetail = detail
        Log.i(TAG, "Reg state: $state ($detail)")
        listeners.forEach { it.onRegStateChanged(state, detail) }
    }

    private fun setProductConnected(connected: Boolean, productId: Int) {
        productConnected = connected
        Log.i(TAG, "Product connected=$connected id=$productId")
        listeners.forEach { it.onProductConnectionChanged(connected, productId) }
    }
}
