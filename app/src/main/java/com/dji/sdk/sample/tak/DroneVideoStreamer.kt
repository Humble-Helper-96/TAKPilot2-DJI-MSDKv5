package com.dji.sdk.sample.tak

import android.content.Context
import android.media.MediaCodec
import com.taklite.util.AppLog
import com.pedro.rtsp.rtsp.Protocol
import com.pedro.rtsp.rtsp.RtspClient
import com.pedro.rtsp.utils.ConnectCheckerRtsp
import com.pedro.srt.srt.SrtClient
import com.pedro.srt.srt.packets.control.handshake.EncryptionType
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
 * encoder's resolution/fps/bitrate via [StreamProfile]. "original"
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
) : ConnectCheckerRtsp, com.pedro.common.ConnectChecker {

    data class VideoConfig(
        val host: String,
        val streamId: String,

        // ONE LOGIN FOR THE SERVER, both protocols. SRT has no login of its own — the SRT
        // handshake carries a stream id, which is an opaque string, and the media server reads
        // a user and a password out of it by its own convention.
        val username: String,
        val password: String,

        // ONLY THE PORT DIFFERS BETWEEN THE PROTOCOLS, and SRT adds a passphrase. Each keeps
        // its own value, so a pilot who toggles back and forth finds what they last entered.
        //
        // ⚠ [rtspPort] IS USED WHICHEVER TRANSPORT IS SELECTED, and the screen hides its field
        // under SRT. [advertiseUrl] is an RTSP address because no TAK client plays SRT, so this
        // is the port the team connects to even while the video leaves over SRT.
        val rtspPort: Int = VideoTransport.RTSP.defaultPort,
        val srtPort: Int = VideoTransport.SRT.defaultPort,

        /** How the video leaves the controller. See [VideoTransport]. */
        val transport: VideoTransport = VideoTransport.RTSP,
        /** SRT only, MILLISECONDS. See [VideoTransport.SRT_LATENCY_DEFAULT_MS] for the
         *  evidence, the units trap and the field override. */
        val srtLatencyMs: Int = VideoTransport.SRT_LATENCY_DEFAULT_MS,
        /**
         * SRT only. The media server's `srtPublishPassphrase`, or empty when it has none. It
         * ENCRYPTS THE STREAM and is not a credential.
         *
         * ⚠ A server that sets a passphrase refuses an unencrypted publisher outright, before
         * it reads the stream id, and answers `SRT_REJ_PEER` — the same refusal it sends for a
         * wrong login. A missing passphrase looks exactly like bad credentials.
         *
         * ⚠ NEVER LOG IT and never put it in a url.
         */
        val srtPassphrase: String = "",

        // ---- What the CoT advertises, which is NOT always where the video is pushed ----
        //
        // A media server can be unreachable from where the team is and still be the right
        // place to push: an internal server that forwards to an external one. The screen
        // resolves this — this server, the other configured server, or nothing — so this class
        // never has to know a server slot exists.
        val advertiseEnabled: Boolean = true,
        val advertiseHost: String = "",
        val advertisePort: Int = VideoTransport.RTSP.defaultPort,
        val advertiseUser: String = "",
        val advertisePass: String = "",
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
        /**
         * Where the video goes OUT.
         *
         *  - RTSP: the credentials are NOT here — they go to the client with setAuthorization.
         *  - SRT: `srt://host:port/publish:<path>:<user>:<password>`. Everything after the port
         *    is the STREAM ID, one opaque string, and the media server reads the publisher, the
         *    path and the credentials out of it.
         *
         * ⚠ A colon in the video password breaks the SRT form — the colon separates the parts
         * of the stream id and there is no escape. [start] logs a warning. RTSP is unaffected.
         */
        fun pushUrl(): String = when (transport) {
            VideoTransport.RTSP -> "rtsp://$host:$rtspPort/${path()}"
            VideoTransport.SRT ->
                if (username.isEmpty()) "srt://$host:$srtPort/publish:${path()}"
                else "srt://$host:$srtPort/publish:${path()}:$username:$password"
        }

        /** The port the PUSH uses. The login is [username] either way. */
        val pushPort: Int get() =
            if (transport == VideoTransport.SRT) srtPort else rtspPort

        /**
         * The address that goes in the CoT, for the team to play.
         *
         * ⚠ ALWAYS RTSP. An SRT push is not playable by any TAK client, so advertising
         * `srt://…` would put an address on the wire that every viewer fails to open, and the
         * failure would look like a dead feed rather than a wrong address. It is built from the
         * ADVERTISE fields, which are not necessarily this server's own. An empty string means
         * the pilot turned the advertisement off and no video block goes in the CoT.
         */
        fun advertiseUrl(): String {
            if (!advertiseEnabled) return ""
            val h = advertiseHost.ifEmpty { host }
            val cred = if (advertiseUser.isNotEmpty())
                "${enc(advertiseUser)}:${enc(advertisePass)}@" else ""
            return "rtsp://$cred$h:$advertisePort/${path()}?tcp"
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
            val secret = when {
                username.isEmpty() -> ""
                password.isEmpty() -> "(NO PASSWORD)"
                else -> "***"
            }
            val who = if (username.isEmpty()) "" else "$username:$secret@"
            return when (transport) {
                VideoTransport.RTSP -> "rtsp://$who$host:$rtspPort/${path()}?tcp"
                VideoTransport.SRT ->
                    if (username.isEmpty()) "srt://$host:$srtPort/publish:${path()}"
                    else "srt://$host:$srtPort/publish:${path()}:$username:$secret"
            }
        }
        private fun enc(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }

    /**
     * The two push clients behind one set of calls. Only [start] knows which is in use;
     * everything after it is transport-blind, because the transport is a property of the link
     * and not of the video.
     *
     * [droppedVideoFrames] is the SEND QUEUE overflow count — the one number that separates
     * "this link is lossy", which SRT repairs, from "this link is too slow", which only a lower
     * video quality fixes. See [countFrame].
     */
    private interface PushClient {
        fun connect(url: String)
        fun disconnect()
        fun reConnect(delayMs: Long)
        fun setVideoInfo(sps: java.nio.ByteBuffer, pps: java.nio.ByteBuffer, vps: java.nio.ByteBuffer?)
        fun sendVideo(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo)
        val droppedVideoFrames: Long
    }

    private class RtspPushClient(private val c: RtspClient) : PushClient {
        override fun connect(url: String) = c.connect(url)
        override fun disconnect() = c.disconnect()
        override fun reConnect(delayMs: Long) = c.reConnect(delayMs)
        override fun setVideoInfo(sps: java.nio.ByteBuffer, pps: java.nio.ByteBuffer, vps: java.nio.ByteBuffer?) =
            c.setVideoInfo(sps, pps, vps)
        override fun sendVideo(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) =
            c.sendVideo(buffer, info)
        override val droppedVideoFrames: Long get() = c.droppedVideoFrames
    }

    private class SrtPushClient(private val c: SrtClient) : PushClient {
        override fun connect(url: String) = c.connect(url)
        override fun disconnect() = c.disconnect()
        override fun reConnect(delayMs: Long) = c.reConnect(delayMs)
        override fun setVideoInfo(sps: java.nio.ByteBuffer, pps: java.nio.ByteBuffer, vps: java.nio.ByteBuffer?) =
            c.setVideoInfo(sps, pps, vps)
        override fun sendVideo(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) =
            c.sendVideo(buffer, info)
        override val droppedVideoFrames: Long get() = c.droppedVideoFrames
    }

    private var client: PushClient? = null

    // ---- What the link is actually doing (see countFrame) ----
    //
    // ⚠ The wire figure is ACCUMULATED, not snapshot: the library reports about once a second
    // and the line covers ten. Written on the main thread, drained on the encoder thread.
    private val wireBitsSum = java.util.concurrent.atomic.AtomicLong(0)
    private val wireSamples = java.util.concurrent.atomic.AtomicInteger(0)
    private var screenEncoder: ScreenCaptureEncoder? = null

    @Volatile private var streaming = false
    @Volatile private var paramsSet = false
    @Volatile private var stopped = false
    private var frameCount = 0
    private var frameBytesSinceLog = 0L
    private var lastRateLogMs = 0L
    private var framesAtLastLog = 0

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

    /**
     * @return true when screen capture actually began. R22: this used to return Unit, so
     * [VideoStreamerHolder] retained a streamer that had failed at the first line and reported
     * `isActive = true` for it — which the flight screen reads as "already streaming, so this
     * LIVE tap means STOP". The pilot then alternated between a permission toast and a
     * "stopped" toast and could never start the stream at all.
     */
    fun start(): Boolean {
        stopped = false
        paramsSet = false

        // ⚠ SRT carries the credentials inside the stream id, and the colon separates its
        // parts. A password with a colon produces a stream id the server reads wrongly.
        if (config.transport == VideoTransport.SRT && config.password.contains(':')) {
            AppLog.w(TAG, "the video password contains a colon — SRT cannot carry it, " +
                    "the server will refuse this stream. Change the password or use RTSP.")
        }
        // ⚠ SRT refuses a passphrase outside 10–79 characters BY THROWING. Caught here so the
        // pilot gets a reason instead of a dead LIVE tap.
        if (config.transport == VideoTransport.SRT && config.srtPassphrase.isNotEmpty() &&
            config.srtPassphrase.length !in SRT_PASSPHRASE_MIN..SRT_PASSPHRASE_MAX) {
            onStatus(false, "SRT passphrase must be $SRT_PASSPHRASE_MIN–$SRT_PASSPHRASE_MAX characters")
            return false
        }

        val push: PushClient = when (config.transport) {
            VideoTransport.RTSP -> RtspPushClient(RtspClient(this).apply {
                setLogs(false)
                // TCP always. The UDP option went with the checkbox — see [VideoTransport.RTSP].
                setProtocol(Protocol.TCP)
                if (config.username.isNotEmpty()) setAuthorization(config.username, config.password)
                setOnlyVideo(true)
        // Our own handleConnectionDropped() backoff loop is authoritative on when to give up
        // (RECONNECT_MAX_MS wall-clock, not attempt count) — set this high so the library's own
        // internal reTries counter (decremented by every client.reConnect() call) never becomes
        // the limiting factor first.
                setReTries(1000)
            })
            VideoTransport.SRT -> SrtPushClient(SrtClient(this).apply {
                setLogs(false)
                // ⚠ NO setAuthorization CALL — it throws in this client. The credentials are
                // already in the url that pushUrl() built.
                setOnlyVideo(true)
                setReTries(1000)
                latencyMs = VideoTransport.clampLatencyMs(config.srtLatencyMs)
                // The MPEG-TS mux must be told the codec; the default is H.264, so an H.265
                // stream sent without this is muxed under the wrong stream type and no viewer
                // decodes it. RTSP learns it from the VPS instead.
                setVideoCodec(
                    if (VideoCodec.fromPref(config.codec).isHevc) com.pedro.common.VideoCodec.H265
                    else com.pedro.common.VideoCodec.H264)
                if (config.srtPassphrase.isNotEmpty()) {
                    setPassphrase(config.srtPassphrase, EncryptionType.AES128)
                }
            })
        }
        client = push

        val projection = mediaProjection
        if (projection == null) {
            // The v5 port streams the screen only; there is no aircraft-feed fallback.
            onStatus(false, "Screen-capture permission required — start LIVE from the flight screen")
            return false
        }
        // Screen-capture: the encoder produces frames from the composited screen
        // immediately; params-ready connects, and the encoder's own sync frame arms the
        // packetizer.
        val enc = ScreenCaptureEncoder(
            context, projection,
            StreamProfile.fromPref(config.profile),
            VideoCodec.fromPref(config.codec),
            onEncoded = { buf, info -> onEncodedFrame(buf, info) },
            onParamsReady = { s, p, v -> onEncoderParamsReady(s, p, v) },
            // R14: the encoder used to die silently (drain-thread codec error, or the system
            // revoking the projection) — the RTSP link, the LIVE pill and the notification all
            // stayed "healthy" with no frames actually moving. giveUp() is the one hook already
            // built for "the streaming machinery is dead, tear it all down and tell the pilot".
            onGone = { reason -> onScreenCaptureGone(reason) },
        )
        if (!enc.start()) {
            onStatus(false, "Screen capture failed to start")
            return false
        }
        screenEncoder = enc
        AppLog.i(TAG, "start [${config.profile}, ${config.codec}, screen] push=${config.pushUrl()}")
        onStatus(true, "Capturing screen → ${config.urlSafe()}")
        return true
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
        try { client?.disconnect() } catch (t: Throwable) { AppLog.w(TAG, "disconnect: ${t.message}") }
        client = null
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
            client?.setVideoInfo(s, p, v)
            AppLog.i(TAG, "encoder params ready — connecting")
            client?.connect(config.pushUrl())
        } catch (t: Throwable) {
            AppLog.w(TAG, "transcode connect failed: ${t.message}")
        }
    }

    private fun onEncodedFrame(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (stopped) return
        try {
            client?.sendVideo(buf, info)
            countFrame(info.size)
        } catch (t: Throwable) {
            AppLog.w(TAG, "encoded frame push failed: ${t.message}")
        }
    }

    /**
     * WHAT THE LINK IS DOING, every [RATE_LOG_INTERVAL_MS], at INFO so it reaches `app.log`
     * without Detailed logging on. Only while CONNECTED: the encoder runs from the moment LIVE
     * is tapped, so a push that never connected would otherwise report a healthy rate for a
     * stream the server had refused.
     *
     * ⚠ Two numbers, because a stream that looks bad fails in one of two ways that need
     * opposite fixes. `drops` climbing means the SEND QUEUE overflowed — the encoder is making
     * more video than the link can carry, which is BANDWIDTH and no transport fixes it.
     * `drops` at zero with a torn picture at the far end means packets are being LOST, which is
     * what SRT recovers and RTSP does not.
     *
     * `wire` is the library's own count of what reaches the socket — headers, the MPEG-TS
     * packing under SRT, and any retransmission — averaged over the SAME window as the payload
     * rate beside it. Well above the payload rate means the link is repairing itself.
     */
    private fun countFrame(size: Int) {
        if (!streaming) return
        frameCount++
        frameBytesSinceLog += size
        val now = System.currentTimeMillis()
        if (lastRateLogMs == 0L) { lastRateLogMs = now; framesAtLastLog = frameCount; return }
        val elapsed = now - lastRateLogMs
        if (elapsed < RATE_LOG_INTERVAL_MS) return

        val frames = frameCount - framesAtLastLog
        val kbps = (frameBytesSinceLog * 8L) / elapsed        // bytes/ms*8 == kbit/s
        val fps = frames * 1000L / elapsed
        val wireBits = wireBitsSum.getAndSet(0)
        val wireN = wireSamples.getAndSet(0)
        val wire = if (wireN > 0) " wire=${wireBits / wireN / 1000}kbps" else ""
        AppLog.i(TAG, "link [${config.transport.label}]: ${kbps}kbps$wire  ${fps}fps  " +
                "drops=${client?.droppedVideoFrames ?: 0}  frames=$frameCount")

        lastRateLogMs = now
        framesAtLastLog = frameCount
        frameBytesSinceLog = 0
    }

    /** One per-second sample from whichever client is running. Averaged in [countFrame]. */
    private fun recordWireBitrate(bitrate: Long) {
        wireBitsSum.addAndGet(bitrate)
        wireSamples.incrementAndGet()
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
        // The pilot stopped the push, and the server then closed its side. That is the normal
        // end of a stream and not a fault; handleConnectionDropped already ignores it, but the
        // line above it logged "connection failed" after every stop (from the Autel tree,
        // 2026-09-10).
        if (stopped) {
            AppLog.i(TAG, "push closed after stop: $reason")
            return
        }
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
        // ⚠ A REFUSAL IS NOT A DROPPED LINK. SRT_REJ_PEER is the server declining the publish
        // — a missing or wrong passphrase, a credential it does not accept, or a path it will
        // not take. All three are fixed on a screen, never by waiting.
        if (reason.contains("Endpoint malformed") || reason.contains("access denied") ||
            reason.contains("SRT_REJ_PEER")) {
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
        client?.reConnect(reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    /** The screen-capture encoder reported itself dead (codec error, or the system revoked the
     *  projection). Streaming cannot continue without it — give up the same way a non-retryable
     *  RTSP failure does, rather than leaving `streaming` true with nothing behind it. */
    private fun onScreenCaptureGone(reason: String) {
        if (stopped) return
        AppLog.w(TAG, "screen capture ended ($reason) — stopping stream")
        giveUp("Video capture stopped: $reason")
    }

    private fun giveUp(statusMsg: String) {
        stopped = true
        isReconnecting = false
        releaseInternal()
        onStatus(false, statusMsg)
        onGiveUp()
    }
    // R15: unlike onConnectionFailedRtsp right above, this used to only flip `streaming` and
    // report status — it never called releaseInternal()/onGiveUp(), so a wrong user/pass left
    // the encoder and VirtualDisplay capturing, the foreground service and notification up, and
    // onEncodedFrame feeding a dead sender forever. Wrong credentials are not transient — same
    // non-retryable category as "Endpoint malformed"/"access denied" in handleConnectionDropped.
    override fun onAuthErrorRtsp() {
        if (stopped) return
        AppLog.w(TAG, "auth error — giving up immediately")
        giveUp("Stream auth error (check user/pass)")
    }
    override fun onAuthSuccessRtsp() { AppLog.i(TAG, "auth ok") }
    override fun onNewBitrateRtsp(bitrate: Long) = recordWireBitrate(bitrate)

    // ---- ConnectChecker (the SRT client) ----
    //
    // The two clients report the same events under different names. Each override is one line
    // into the handler the RTSP side already uses, so the state machine exists once.

    override fun onConnectionStarted(url: String) { AppLog.i(TAG, "connecting ${config.urlSafe()}") }
    override fun onConnectionSuccess() = onConnectionSuccessRtsp()
    override fun onConnectionFailed(reason: String) = onConnectionFailedRtsp(reason)
    override fun onDisconnect() = onDisconnectRtsp()
    /** SRT has no credential exchange of its own — a refused login comes back as a failed
     *  connection, not as this. Kept because the interface has it. */
    override fun onAuthError() = onAuthErrorRtsp()
    override fun onAuthSuccess() { AppLog.i(TAG, "auth ok") }
    override fun onNewBitrate(bitrate: Long) = recordWireBitrate(bitrate)

    companion object {
        private const val TAG = "DroneVideoStreamer"
        private const val RATE_LOG_INTERVAL_MS = 10_000L

        /** The library's own limits — outside these [SrtClient.setPassphrase] throws. */
        private const val SRT_PASSPHRASE_MIN = 10
        private const val SRT_PASSPHRASE_MAX = 79
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

    /**
     * R22: "original" (passthrough of the aircraft feed) was a v4-era profile. This port
     * streams the SCREEN and has no passthrough path, so a saved "original" — which no current
     * UI can write, but which survives untouched through Pre-Flight migration — used to route
     * the pilot into a dead start with no projection. It is treated as "standard", which is
     * what [StreamProfile.fromPref] already resolves it to; normalising
     * here as well keeps `isTranscode` (and with it the "-Low" publish path) in agreement,
     * instead of silently publishing a legacy install to a different URL.
     */
    private fun normalizeProfile(saved: String?): String =
        if (saved.isNullOrEmpty() || saved == "original") "standard" else saved

    private fun buildConfig(context: Context): DroneVideoStreamer.VideoConfig? {
        val p = context.getSharedPreferences("takpilot2_tak", Context.MODE_PRIVATE)
        val host = p.getString("video_host", "") ?: ""
        val streamId = p.getString("video_streamid", "") ?: ""
        if (host.isEmpty() || streamId.isEmpty()) return null
        return DroneVideoStreamer.VideoConfig(
            host = host,
            streamId = streamId,
            // ⚠ Keys must match the KEY_V_* constants in TakConnectActivity. They are literals
            // here only because this file has no access to those private constants.
            //
            // The RTSP set is read whichever transport is selected — it is what the CoT
            // advertises. The advertise set is RESOLVED by the screen, so this class never has
            // to know a server slot exists.
            username = p.getString("video_user", "") ?: "",
            password = p.getString("video_pass", "") ?: "",
            rtspPort = p.getInt("video_rtsp_port", VideoTransport.RTSP.defaultPort),
            srtPort = p.getInt("video_srt_port", VideoTransport.SRT.defaultPort),
            transport = VideoTransport.fromPref(p.getString("video_transport", null)),
            // Read on every start, so a change on the Debug screen takes effect at the next
            // LIVE and not at the next application launch.
            srtLatencyMs = VideoTransport.srtLatencyMs(p),
            srtPassphrase = p.getString("video_srt_passphrase", "") ?: "",
            advertiseEnabled = p.getBoolean("video_adv_on", true),
            advertiseHost = p.getString("video_adv_host", "") ?: "",
            advertisePort = p.getInt("video_adv_port", VideoTransport.RTSP.defaultPort),
            advertiseUser = p.getString("video_adv_user", "") ?: "",
            advertisePass = p.getString("video_adv_pass", "") ?: "",
            profile = normalizeProfile(p.getString("video_profile", "standard")),
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
        // R21: `self` is this streamer's identity, and every callback below checks it against
        // the field before touching shared state. Both callbacks reach us through a
        // main-looper post, so an OLD streamer's message can be sitting in the queue when a
        // restart (long-press LIVE -> pick a tier -> ACTION_RESTART) installs a NEW one. Then
        // the stale message runs and, with no identity check, nulls the live streamer AND
        // calls ScreenCaptureService.stop() -> projection.stop(): the fresh stream dies and
        // the pilot has to grant screen capture again. The R14 encoder-death path widened this
        // (it adds a second queue hop, and it fires exactly during a restart, when the shared
        // MediaProjection's display is being released), so the guard matters more now.
        lateinit var self: DroneVideoStreamer
        self = DroneVideoStreamer(
            context.applicationContext, config, projection,
            onGiveUp = {
                // Reconnect window expired — DroneVideoStreamer already released its own
                // encoder/transcoder/client; our job is to drop the reference and tear down
                // the foreground service + projection it doesn't own.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (streamer !== self) {
                        AppLog.i("VideoStreamerHolder", "stale give-up from a replaced streamer — ignoring")
                        return@post
                    }
                    AppLog.w("VideoStreamerHolder", "reconnect window expired — stopping capture")
                    streamer = null
                    TakBridgeHolder.setVideoUrl(null)
                    appContext?.let { ScreenCaptureService.stop(it) }
                    notifyState()
                }
            },
        ) { ok, msg ->
            // Same identity gate: a stale success must not re-advertise a dead stream's URL
            // over the live one's, and a stale failure must not report over it either.
            if (streamer !== self) {
                AppLog.i("VideoStreamerHolder", "stale status from a replaced streamer — ignoring: $msg")
            } else {
                // An empty url means the pilot turned the advertisement off. null is the
                // state the rest of the app already understands as "no video address".
                if (ok) TakBridgeHolder.setVideoUrl(config.advertiseUrl().ifEmpty { null })
                notifyState()
                onStatus(ok, msg)
            }
        }
        // Assigned BEFORE start() so the identity gate above passes for the status callbacks
        // start() raises synchronously. If capture never began, drop it again rather than
        // leave a dead streamer behind reporting isActive = true (R22).
        streamer = self
        if (!self.start() && streamer === self) {
            AppLog.w("VideoStreamerHolder", "capture did not start — not retaining the streamer")
            streamer = null
        }
        notifyState()
    }

    // R22: `start(context, config, onStatus)` and `startFromPrefs(context, onStatus)` used to
    // live here. Both called launch() with projection = null, which this port cannot stream
    // from — they were the trap the legacy "original" profile fell into. start() had no call
    // sites at all; startFromPrefs had exactly one, the dead branch now removed from the
    // flight screen. Screen capture is the only way in: use startScreenCapture below.

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
}
