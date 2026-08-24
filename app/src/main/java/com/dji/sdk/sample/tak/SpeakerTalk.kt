package com.dji.sdk.sample.tak

import android.os.Handler
import android.os.HandlerThread
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.common.recorder.AudioRecordHandler
import dji.v5.common.recorder.EncodedDataCallback
import dji.v5.common.recorder.OpusEncoder
import dji.v5.common.recorder.SourceDataCallback
import dji.v5.manager.aircraft.megaphone.MegaphoneManager
import dji.v5.manager.aircraft.megaphone.WorkMode
import com.taklite.util.AppLog

/**
 * PUSH-TO-TALK through the AS1 speaker: the controller's microphone, live, out of the aircraft.
 *
 * The pipeline is DJI's own, the same one their `MegaphoneVM` sample uses:
 * mic -> [AudioRecordHandler] -> [OpusEncoder] -> `sendRealTimeDataToMegaphone`.
 *
 * ⚠ **PUSH TO TALK, NEVER A TOGGLE, AND THAT IS A SAFETY DECISION** (operator, 2026-08-23). A
 * toggle can leave a HOT MICROPHONE broadcasting from an aircraft over a public area with nobody
 * aware of it — the pilot's own conversation, on a loudspeaker, at whatever range the aircraft
 * happens to be. Every route in and out of this object is therefore a hold: the transmission
 * lives only as long as a finger or a button is down. [stop] is idempotent and safe to call from
 * anywhere, and it is called from the panel closing, the screen pausing and the screen dying, so
 * no path through the application can leave the microphone open.
 *
 * ⚠ THE MICROPHONE IS A SINGLETON. [AudioRecordHandler] is one instance for the process, so a
 * session that did not release cleanly makes the NEXT one fail with the microphone unavailable.
 * [forceRelease] therefore tears down the recorder and the encoder before every start, whatever
 * state this object believes it is in.
 *
 * ⚠ The work mode is left in [WorkMode.REAL_TIME] after a talk. Putting it back would be a write
 * to the payload for no pilot-visible gain, and the next SOUND press sets what it needs anyway.
 */
object SpeakerTalk {

    private const val TAG = "SpeakerTalk"

    @Volatile
    private var recorder: AudioRecordHandler? = null

    @Volatile
    private var encoder: OpusEncoder? = null

    /** True from the moment the pilot presses until the moment the microphone is released. */
    @Volatile
    var talking = false
        private set

    /** Told whenever [talking] changes, so the panel can paint the TALK control. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    private var thread: HandlerThread? = null
    private var io: Handler? = null

    private var micFrames = 0
    private var sentFrames = 0

    /**
     * Opens the live channel and starts sending.
     *
     * @param onStarted true when the aircraft is actually taking audio. False means the pilot
     * must be told, because a push-to-talk that silently does nothing is worse than one that
     * refuses — the pilot keeps talking to a speaker that is not on.
     */
    fun start(onStarted: (Boolean) -> Unit) {
        if (talking) { onStarted(true); return }
        forceRelease()
        talking = true
        notifyChanged()
        micFrames = 0
        sentFrames = 0
        startIoThread()
        AppLog.i(TAG, "talk: opening the live channel")
        MegaphoneManager.getInstance().setWorkMode(WorkMode.REAL_TIME,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() = openTransmission(onStarted)
                override fun onFailure(error: IDJIError) {
                    // Not fatal on its own: the payload may already be in real-time mode. The
                    // transmission open below is the step that decides.
                    AppLog.w(TAG, "talk: work mode refused (${error.description()}) — " +
                        "opening anyway")
                    openTransmission(onStarted)
                }
            })
    }

    private fun openTransmission(onStarted: (Boolean) -> Unit) {
        MegaphoneManager.getInstance().startRealTimeTransmission(
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    // The pilot may have let go during the round trip. Do not open a microphone
                    // nobody is holding.
                    if (!talking) { AppLog.i(TAG, "talk: released before it opened"); stop(); return }
                    try {
                        buildPipeline()
                        recorder?.start()
                        encoder?.start()
                        AppLog.i(TAG, "talk: LIVE")
                        onStarted(true)
                    } catch (t: Throwable) {
                        AppLog.w(TAG, "talk: the audio pipeline failed: ${t.message}")
                        stop()
                        onStarted(false)
                    }
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "talk: the aircraft refused the live channel: " +
                        "${error.description()}")
                    stop()
                    onStarted(false)
                }
            })
    }

    private fun buildPipeline() {
        val rec = AudioRecordHandler.getInstance()
        rec.init()
        val enc = OpusEncoder()
        enc.config(rec.audioConfig)
        AppLog.i(TAG, "talk: rate=${rec.audioConfig?.audioSampleRate} " +
            "ch=${rec.audioConfig?.audioChannelCount} buf=${rec.audioConfig?.bufferSize}")
        enc.setEncodedDataCallback(EncodedDataCallback { data, size ->
            // ⚠ THE GUARD IS INSIDE THE CALLBACK. Frames already queued keep arriving for a
            // moment after the pilot lets go, and without this test they would be sent after
            // the release — audio out of the aircraft that nobody is holding a button for.
            if (!talking) return@EncodedDataCallback
            sentFrames++
            io?.post {
                if (!talking) return@post
                MegaphoneManager.getInstance().sendRealTimeDataToMegaphone(data, size,
                    object : CommonCallbacks.CompletionCallback {
                        override fun onSuccess() {}
                        override fun onFailure(error: IDJIError) {}
                    })
            }
        })
        rec.setDataCallBack(SourceDataCallback { data, _ ->
            micFrames++
            enc.putData(data)
        })
        recorder = rec
        encoder = enc
    }

    /**
     * Closes the microphone and the live channel. Safe to call at any time, from any state.
     *
     * ⚠ THE ORDER MATTERS. The microphone is released FIRST, so nothing more can be encoded,
     * and only then is the end-of-file appended to tell the payload the talk is over. The other
     * order leaves a window where frames are still being produced after the payload has been
     * told there are none coming.
     */
    fun stop() {
        if (!talking && recorder == null && encoder == null) return
        val wasTalking = talking
        talking = false
        AppLog.i(TAG, "talk: stopping (mic frames=$micFrames sent=$sentFrames)")
        forceRelease()
        if (wasTalking) {
            MegaphoneManager.getInstance().appendEOFToRealTimeData(
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() { AppLog.i(TAG, "talk: closed") }
                    override fun onFailure(error: IDJIError) {
                        AppLog.w(TAG, "talk: close refused: ${error.description()}")
                    }
                })
        }
        stopIoThread()
        notifyChanged()
    }

    /** Tears the microphone down whatever state this object believes it is in. */
    private fun forceRelease() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        runCatching { encoder?.release() }
        // The singleton as well, in case our own references were already dropped.
        runCatching { AudioRecordHandler.getInstance().stop() }
        runCatching { AudioRecordHandler.getInstance().release() }
        recorder = null
        encoder = null
    }

    private fun startIoThread() {
        if (thread != null) return
        thread = HandlerThread("speaker-talk").also {
            it.start()
            io = Handler(it.looper)
        }
    }

    private fun stopIoThread() {
        runCatching { thread?.quitSafely() }
        thread = null
        io = null
    }

    private fun notifyChanged() {
        val cb = onChanged ?: return
        Handler(android.os.Looper.getMainLooper()).post { cb() }
    }
}
