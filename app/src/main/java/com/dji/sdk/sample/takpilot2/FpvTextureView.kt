package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import com.taklite.util.AppLog
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager

/**
 * Live FPV view — MSDK v5 edition.
 *
 * The v4 original carried a full custom MediaCodec pipeline (Annex-B reassembly, slice
 * parsing, decoder health, an IDR-request lever) because the Mini 2's VideoFeeder handed
 * out raw non-aligned NAL chunks and no periodic SPS/PPS. v5 decodes on the SDK side:
 * [ICameraStreamManager.putCameraStreamSurface] renders the live feed straight into this
 * view's SurfaceTexture, scaled CENTER_INSIDE. All of the v4 decoder machinery is gone —
 * and with it the static-scene artifacting problem it existed to manage.
 *
 * What this file still owns:
 * - The surface lifecycle (register on available, re-register on size change, remove on
 *   destroy).
 * - [onFirstFrame], for the "NO VIDEO" cover — driven by the raw stream listener, which
 *   fires per encoded frame without touching the decode path.
 * - [onVideoRectChanged], the on-screen rectangle the video actually occupies after
 *   CENTER_INSIDE letterboxing — the AR overlay and crosshair align to it.
 * - [requestResync] — re-registers the surface, the v5 equivalent of the v4 decoder
 *   rebuild behind the Video Re-Sync button.
 */
class FpvTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private companion object {
        const val TAG = "TP2Fpv"
        val CAMERA = ComponentIndexType.LEFT_OR_MAIN
    }

    /** Fired once when the first frame of a (re)started stream arrives. */
    var onFirstFrame: (() -> Unit)? = null

    /** Fired (on the UI thread) when the letterboxed video rectangle changes. */
    var onVideoRectChanged: ((RectF) -> Unit)? = null

    private var surface: Surface? = null
    @Volatile private var streamW = 0
    @Volatile private var streamH = 0
    @Volatile private var sawFrame = false

    private val streamManager: ICameraStreamManager
        get() = MediaDataCenter.getInstance().cameraStreamManager

    /**
     * Raw encoded-stream listener: cheap per-frame signal carrying the stream dimensions.
     * Used for first-frame detection and the letterbox rect; the pixels themselves go
     * through the surface DJI renders into.
     */
    private val receiveListener = ICameraStreamManager.ReceiveStreamListener { _, _, _, info ->
        if (!sawFrame) {
            sawFrame = true
            AppLog.i(TAG, "first frame: ${info.width}x${info.height} ${info.mimeType}")
            runCatching { onFirstFrame?.invoke() }
        }
        if (info.width != streamW || info.height != streamH) {
            streamW = info.width
            streamH = info.height
            post { recomputeVideoRect() }
        }
    }

    init {
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        AppLog.i(TAG, "surface available ${width}x$height — registering with camera stream manager")
        val s = Surface(st)
        surface = s
        runCatching {
            streamManager.putCameraStreamSurface(
                CAMERA, s, width, height, ICameraStreamManager.ScaleType.CENTER_INSIDE)
            streamManager.addReceiveStreamListener(CAMERA, receiveListener)
        }.onFailure { AppLog.w(TAG, "camera stream registration failed: ${it.message}") }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        // DJI scales to the registered dimensions, so a resized surface must re-register.
        AppLog.i(TAG, "surface resized ${width}x$height — re-registering")
        val s = surface ?: return
        runCatching {
            streamManager.removeCameraStreamSurface(s)
            streamManager.putCameraStreamSurface(
                CAMERA, s, width, height, ICameraStreamManager.ScaleType.CENTER_INSIDE)
        }.onFailure { AppLog.w(TAG, "camera stream re-registration failed: ${it.message}") }
        recomputeVideoRect()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        AppLog.i(TAG, "surface destroyed — removing from camera stream manager")
        runCatching {
            surface?.let { streamManager.removeCameraStreamSurface(it) }
            streamManager.removeReceiveStreamListener(receiveListener)
        }
        surface?.release()
        surface = null
        sawFrame = false
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    /**
     * Video Re-Sync: drop and re-register the surface and stream listener. In v5 the
     * decoder is DJI's; a fresh registration is the whole reset.
     */
    fun requestResync() {
        AppLog.i(TAG, "resync requested")
        val s = surface ?: return
        sawFrame = false
        runCatching {
            streamManager.removeCameraStreamSurface(s)
            streamManager.removeReceiveStreamListener(receiveListener)
            streamManager.putCameraStreamSurface(
                CAMERA, s, width, height, ICameraStreamManager.ScaleType.CENTER_INSIDE)
            streamManager.addReceiveStreamListener(CAMERA, receiveListener)
        }.onFailure { AppLog.w(TAG, "resync failed: ${it.message}") }
    }

    /**
     * The rectangle the video occupies inside this view after CENTER_INSIDE scaling.
     * Same aspect-fit math the v4 view used for its transform matrix; here it feeds the
     * overlay alignment AND the downward shift below, since DJI does the scaling itself.
     *
     * ⚠ THE IMAGE IS PUSHED DOWN TO THE BOTTOM OF THE SCREEN (operator, 2026-08-19).
     * A 16:9 stream in the RC Plus 2's 16:10 screen letterboxes by 60px at each end.
     * CENTER_INSIDE puts half of that at the top, where the 56dp action bar already covers
     * it, and half at the bottom, where it is a black band of wasted screen. Moving the
     * whole image down by the top letterbox hides ALL of the dead space behind the action
     * bar and gives the pilot the full remaining height of live picture.
     *
     * This is a TRANSLATION, never a scale or a crop. The image keeps its aspect ratio and
     * its field of view, which the AR projection depends on — filling the screen by cropping
     * the sides would silently change the FOV and put every projected marker in the wrong
     * place.
     *
     * The same offset goes to the texture and to the rectangle the overlays receive. They
     * MUST move together: the crosshair is the aiming reference for a marker drop, so if the
     * picture moves and the rectangle does not, a marker dropped at the crosshair no longer
     * lands under it.
     */
    private fun recomputeVideoRect() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f || streamW <= 0 || streamH <= 0) return
        val viewAspect = vw / vh
        val videoAspect = streamW.toFloat() / streamH.toFloat()
        val rect = if (videoAspect >= viewAspect) {
            // Video is wider: full width, letterboxed top/bottom.
            val h = vw / videoAspect
            RectF(0f, (vh - h) / 2f, vw, (vh + h) / 2f)
        } else {
            // Video is taller: full height, pillarboxed left/right.
            val w = vh * videoAspect
            RectF((vw - w) / 2f, 0f, (vw + w) / 2f, vh)
        }

        // Drop the image onto the bottom edge. Zero when the stream is pillarboxed instead,
        // and zero when it already fills the height, thus this is a no-op on any screen that
        // matches the stream's aspect ratio.
        val dy = vh - rect.bottom
        if (dy > 0.5f) rect.offset(0f, dy)

        // setTransform touches the view, thus it goes to the UI thread — this method also
        // runs from the stream listener, which does not.
        post {
            setTransform(Matrix().apply { if (dy > 0.5f) setTranslate(0f, dy) })
        }

        AppLog.d(TAG, "video rect: $rect (stream ${streamW}x$streamH in view ${width}x$height," +
            " shifted down ${dy}px)")
        runCatching { onVideoRectChanged?.invoke(rect) }
    }
}
