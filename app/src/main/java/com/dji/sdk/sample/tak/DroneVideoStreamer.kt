package com.dji.sdk.sample.tak

import android.content.Context
import android.media.MediaCodec
import com.taklite.util.AppLog
import com.pedro.rtsp.rtsp.Protocol
import com.pedro.rtsp.rtsp.RtspClient
import com.pedro.rtsp.utils.ConnectCheckerRtsp
import java.nio.ByteBuffer

/**
 * DroneVideoStreamer — RTSP push of the flight screen to a media server (MediaMTX).
 *
 * v5 port: SCREEN-CAPTURE ONLY. MediaProjection mirrors the whole flight screen (FPV +
 * HUD) into [ScreenCaptureEncoder]'s input surface; the H.264 output goes out through the
 * vendored com.pedro.rtsp client (see NOTICE.txt). The v4 aircraft-feed modes (VideoFeeder
 * passthrough and the decode-transcode fallback) are gone with VideoFeeder itself — the
 * keyframe-starvation problem they existed to manage was a Mini 2 behavior, and the
 * pilot-facing LIVE flow has been the screen-capture path since Phase 5 shipped.
 *
 * The [VideoConfig.profile] quality ladder ("low"/"standard"/"high") still selects the
 * encoder's resolution/fps/bitrate via [StreamTranscoder.TranscodeProfile]. "original"
 * (passthrough) no longer exists; a saved "original" profile encodes at "standard".
 */
class DroneVideoStreamer(
    private val context: Context,
    private val config: VideoConfig,
    private val mediaProjection: android.media.projection.MediaProjection? = null,
    // Fired once when a reconnect window (see RECONNECT_MAX_MS) expires without success: the
    // stream and capture/projection have already been torn down internally by the time this
    // fires, so the caller (VideoStreamerHolder) just needs to drop its reference and clean up
    // anything it owns (the foreground service).
    private val onGiveUp: () -> Unit = {},
    private val onStatus: (Boolean, String) -> Unit,
) : ConnectCheckerRtsp {

    data class VideoConfig(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val streamId: String,
        val tcp: Boolean,
        val profile: String = "standard",   // "original" | "low" | "standard" | "high"
        // The outbound codec ("h264" | "h265") — a Pre-Flight choice, see [VideoCodec].
        val codec: String = VideoCodec.H264.prefValue,
    ) {
        val isTranscode: Boolean get() = profile != "original"
        // Transcoded output is published to a "-Low" path (e.g. Feed-A -> Feed-A-Low): it
        // tells the media server this stream is ALREADY reduced/keyframed, so it passes it
        // through to clients instead of running its own transcode on it. Flows through
        // push/advertise/preview URLs alike since they all build on path().
        private fun path(): String = streamId.trim('/') + if (isTranscode) "-Low" else ""
        fun pushUrl(): String = "rtsp://$host:$port/${path()}"
        fun advertiseUrl(): String {
            val cred = if (username.isNotEmpty()) "${enc(username)}:${enc(password)}@" else ""
            val q = if (tcp) "?tcp" else ""
            return "rtsp://$cred$host:$port/${path()}$q"
        }
        /**
         * The URL preview shown in Pre-Flight, with the password masked.
         *
         * An EMPTY password reads "(NO PASSWORD)" rather than the same `***` a real one gets.
         * Masking both identically meant the preview — the one place a pilot would check — could
         * not answer the question it exists to answer, and a blank password looked exactly like a
         * correct one. That mattered because the password really was being erased on every visit
         * to this screen; see the restore line in TakConnectActivity.setupVideoControls.
         */
        fun urlSafe(): String {
            val who = when {
                username.isEmpty() -> ""
                password.isEmpty() -> "$username:(NO PASSWORD)@"
                else -> "$username:***@"
            }
            val q = if (tcp) "?tcp" else ""
            return "rtsp://$who$host:$port/${path()}$q"
        }
        private fun enc(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }

    private val client = RtspClient(this)
    private var screenEncoder: ScreenCaptureEncoder? = null

    @Volatile private var streaming = false
    @Volatile private var paramsSet = false
    @Volatile private var stopped = false
    private var startNs = 0L
    private var frameCount = 0
    private var frameBytesSinceLog = 0L

    // ---- Auto-reconnect with backoff (network drops, server restarts, etc.) ----
    // A dropped connection does NOT tear down the encoder/projection immediately — the capture
    // keeps running (frames are simply not sent) so a transient blip doesn't cost a fresh
    // permission grant. Only if RECONNECT_MAX_MS elapses without a successful reconnect do we
    // give up for real and release everything (see handleConnectionDropped/onGiveUp).
    @Volatile var isReconnecting: Boolean = false
        private set
    private var reconnectStartNs = 0L
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS

    val isStreaming: Boolean get() = streaming

    fun start() {
        stopped = false
        paramsSet = false
        startNs = System.nanoTime()

        client.setLogs(false)
        client.setProtocol(if (config.tcp) Protocol.TCP else Protocol.UDP)
        if (config.username.isNotEmpty()) client.setAuthorization(config.username, config.password)
        client.setOnlyVideo(true)
        // Our own handleConnectionDropped() backoff loop is authoritative on when to give up
        // (RECONNECT_MAX_MS wall-clock, not attempt count) — set this high so the library's own
        // internal reTries counter (decremented by every client.reConnect() call) never becomes
        // the limiting factor first.
        client.setReTries(1000)

        val projection = mediaProjection
        if (projection == null) {
            // The v5 port streams the screen only; there is no aircraft-feed fallback.
            onStatus(false, "Screen-capture permission required — start LIVE from the flight screen")
            return
        }
        // Screen-capture: the encoder produces frames from the composited screen
        // immediately; params-ready connects, and the encoder's own sync frame arms the
        // packetizer.
        val enc = ScreenCaptureEncoder(
            context, projection,
            StreamTranscoder.TranscodeProfile.fromPref(config.profile),
            VideoCodec.fromPref(config.codec),
            onEncoded = { buf, info -> onEncodedFrame(buf, info) },
            onParamsReady = { s, p, v -> onEncoderParamsReady(s, p, v) },
        )
        if (!enc.start()) {
            onStatus(false, "Screen capture failed to start")
            return
        }
        screenEncoder = enc
        AppLog.i(TAG, "start [${config.profile}, ${config.codec}, screen] push=${config.pushUrl()}")
        onStatus(true, "Capturing screen → ${config.urlSafe()}")
    }

    fun stop() {
        if (stopped) return
        stopped = true
        isReconnecting = false
        releaseInternal()
    }

    /** Shared teardown for an explicit [stop] and a give-up-after-timeout. Idempotent-ish via
     *  the [stopped] guard in callers; safe to call once. */
    private fun releaseInternal() {
        screenEncoder?.release()
        screenEncoder = null
        try { client.disconnect() } catch (t: Throwable) { AppLog.w(TAG, "disconnect: ${t.message}") }
        streaming = false
        paramsSet = false
    }

    // ---- Encoder output path (ScreenCaptureEncoder's thread) ----

    private fun onEncoderParamsReady(s: ByteBuffer, p: ByteBuffer, v: ByteBuffer?) {
        if (stopped || paramsSet) return
        paramsSet = true
        try {
            // A non-null VPS is what tells the RTSP library this stream is H.265; it switches
            // the packetiser and the SDP with it. H.264 passes null, same as before.
            client.setVideoInfo(s, p, v)
            AppLog.i(TAG, "encoder params ready — connecting")
            client.connect(config.pushUrl())
        } catch (t: Throwable) {
            AppLog.w(TAG, "transcode connect failed: ${t.message}")
        }
    }

    private fun onEncodedFrame(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (stopped) return
        try {
            client.sendVideo(buf, info)
            countFrame(info.size)
        } catch (t: Throwable) {
            AppLog.w(TAG, "encoded frame push failed: ${t.message}")
        }
    }

    private fun countFrame(size: Int) {
        frameCount++
        frameBytesSinceLog += size
        if (frameCount % 150 == 0) {
            AppLog.v(TAG, "video: $frameCount frames pushed, ${frameBytesSinceLog / 1024}KB in last 150")
            frameBytesSinceLog = 0
        }
    }

    // ---- ConnectCheckerRtsp ----

    override fun onConnectionStartedRtsp(rtspUrl: String) { AppLog.i(TAG, "connecting ${config.urlSafe()}") }
    override fun onConnectionSuccessRtsp() {
        streaming = true
        if (isReconnecting) {
            AppLog.i(TAG, "reconnected after ${(System.nanoTime() - reconnectStartNs) / 1_000_000}ms")
            isReconnecting = false
        }
        onStatus(true, "Streaming → ${config.urlSafe()}")
        // Arm (and re-arm) RootEncoder's one-shot H264Packet.sendKeyFrame flag on EVERY connect.
        // connect() is async, so the first keyframe was sent while RtspSender.running was still
        // false and got discarded; without re-arming, the packetizer drops every P-frame forever
        // ("waiting for keyframe"). Our own encoder answers an IDR request instantly.
        AppLog.i(TAG, "connected — requesting screen-encoder sync frame to arm the packetizer")
        screenEncoder?.requestSyncFrame()
    }
    override fun onConnectionFailedRtsp(reason: String) {
        AppLog.w(TAG, "connection failed: $reason")
        handleConnectionDropped(reason)
    }
    override fun onDisconnectRtsp() {
        AppLog.i(TAG, "disconnected")
        handleConnectionDropped("disconnected")
    }

    /** Entry point for every "the RTSP link just died" event (failed connect attempt, or a
     *  live connection dropping). Drives the backoff loop; see the reconnect fields above. */
    private fun handleConnectionDropped(reason: String) {
        if (stopped) return
        streaming = false
        if (reason.contains("Endpoint malformed") || reason.contains("access denied")) {
            // Not a transient network problem — a config error retrying won't fix. Give up now.
            AppLog.w(TAG, "non-retryable failure ($reason) — giving up immediately")
            giveUp("Stream failed: $reason")
            return
        }
        val now = System.nanoTime()
        if (!isReconnecting) {
            isReconnecting = true
            reconnectStartNs = now
            reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
            AppLog.w(TAG, "video connection lost ($reason) — reconnecting, capture stays live")
            onStatus(false, "Video connection lost — reconnecting…")
        }
        val elapsedMs = (now - reconnectStartNs) / 1_000_000
        if (elapsedMs >= RECONNECT_MAX_MS) {
            AppLog.w(TAG, "no reconnect after ${elapsedMs}ms — giving up, stopping stream + capture")
            giveUp("Video stream failed — stopped after 60s")
            return
        }
        AppLog.i(TAG, "reconnect attempt in ${reconnectDelayMs}ms (elapsed ${elapsedMs}ms, reason=$reason)")
        client.reConnect(reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private fun giveUp(statusMsg: String) {
        stopped = true
        isReconnecting = false
        releaseInternal()
        onStatus(false, statusMsg)
        onGiveUp()
    }
    override fun onAuthErrorRtsp() { streaming = false; onStatus(false, "Stream auth error (check user/pass)") }
    override fun onAuthSuccessRtsp() { AppLog.i(TAG, "auth ok") }
    override fun onNewBitrateRtsp(bitrate: Long) {}

    companion object {
        private const val TAG = "DroneVideoStreamer"
        private const val RECONNECT_MAX_MS = 60_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 2_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
    }
}

object VideoStreamerHolder {
    private var streamer: DroneVideoStreamer? = null
    private var appContext: Context? = null

    /** Notified on every start/stop AND on every connection-state change so UI (e.g. the
     *  flight-screen LIVE pill) reflects real streaming state, not just the start/stop call. */
    @JvmField
    var onStateChanged: Runnable? = null
    private fun notifyState() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onStateChanged?.run() }
    }

    private fun buildConfig(context: Context): DroneVideoStreamer.VideoConfig? {
        val p = context.getSharedPreferences("takpilot2_tak", Context.MODE_PRIVATE)
        val host = p.getString("video_host", "") ?: ""
        val streamId = p.getString("video_streamid", "") ?: ""
        if (host.isEmpty() || streamId.isEmpty()) return null
        return DroneVideoStreamer.VideoConfig(
            host = host,
            port = p.getInt("video_port", 8554),
            username = p.getString("video_user", "") ?: "",
            password = p.getString("video_pass", "") ?: "",
            streamId = streamId,
            tcp = p.getBoolean("video_tcp", true),
            profile = p.getString("video_profile", "standard") ?: "standard",
            codec = p.getString("video_codec", VideoCodec.H264.prefValue)
                ?: VideoCodec.H264.prefValue,
        )
    }

    /** Wraps the caller's onStatus so every status change (incl. the async connect-success
     *  that flips isStreaming true) also refreshes the LIVE pill and advertises the CoT URL. */
    private fun launch(
        context: Context,
        config: DroneVideoStreamer.VideoConfig,
        projection: android.media.projection.MediaProjection?,
        onStatus: (Boolean, String) -> Unit,
    ) {
        appContext = context.applicationContext
        streamer?.stop()
        streamer = DroneVideoStreamer(
            context.applicationContext, config, projection,
            onGiveUp = {
                // Reconnect window expired — DroneVideoStreamer already released its own
                // encoder/transcoder/client; our job is to drop the reference and tear down
                // the foreground service + projection it doesn't own.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    AppLog.w("VideoStreamerHolder", "reconnect window expired — stopping capture")
                    streamer = null
                    TakBridgeHolder.setVideoUrl(null)
                    appContext?.let { ScreenCaptureService.stop(it) }
                    notifyState()
                }
            },
        ) { ok, msg ->
            if (ok) TakBridgeHolder.setVideoUrl(config.advertiseUrl())
            notifyState()
            onStatus(ok, msg)
        }.also { it.start() }
        notifyState()
    }

    fun start(
        context: Context,
        config: DroneVideoStreamer.VideoConfig,
        onStatus: (Boolean, String) -> Unit,
    ) = launch(context, config, null, onStatus)

    fun stop() {
        streamer?.stop()
        streamer = null
        TakBridgeHolder.setVideoUrl(null)
        // Tear down the screen-capture foreground service + projection if one was running.
        appContext?.let { ScreenCaptureService.stop(it) }
        notifyState()
    }

    val isRunning: Boolean get() = streamer?.isStreaming == true
    val isActive: Boolean get() = streamer != null
    val isReconnecting: Boolean get() = streamer?.isReconnecting == true

    /**
     * Start streaming using the video settings saved by TakConnectActivity, with a
     * MediaProjection (screen-capture transcode). Returns false if no stream is configured.
     */
    fun startScreenCapture(
        context: Context,
        projection: android.media.projection.MediaProjection,
        onStatus: (Boolean, String) -> Unit,
    ): Boolean {
        val cfg = buildConfig(context) ?: return false
        launch(context, cfg, projection, onStatus)
        return true
    }

    /**
     * Start streaming using saved settings, no projection (passthrough, or the decode-transcode
     * fallback). Returns false if no stream is configured.
     */
    fun startFromPrefs(context: Context, onStatus: (Boolean, String) -> Unit): Boolean {
        val cfg = buildConfig(context) ?: return false
        launch(context, cfg, null, onStatus)
        return true
    }
}
