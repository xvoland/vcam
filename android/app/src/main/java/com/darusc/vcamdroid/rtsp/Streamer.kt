package com.darusc.vcamdroid.rtsp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.ViewOverlay
import com.darusc.vcamdroid.networking.ConnectionManager
import com.darusc.vcamdroid.networking.DeviceDescriptor
import com.darusc.vcamdroid.networking.ErrorReport
import com.darusc.vcamdroid.util.Logger
import com.darusc.vcamdroid.video.filters.FilterAdjuster
import com.darusc.vcamdroid.video.filters.FilterRepository
import com.darusc.vcamdroid.video.filters.custom.HorizontalFlipFilterRender
import com.darusc.vcamdroid.video.filters.custom.VerticalFlipFilterRender
import com.darusc.vcamdroid.video.getSupportedResolutions
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.input.video.CameraOpenException
import com.pedro.library.util.BitrateAdapter
import com.pedro.library.view.OpenGlView
import com.pedro.rtspserver.RtspServerCamera2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.logging.Filter

class Streamer(
    private var options: StreamOptions,
    context: Context,
    private val openGlView: OpenGlView,
) : ConnectChecker {

    companion object {
        const val PORT = 8554
        const val URL = "rtsp://localhost:$PORT/live"
    }

    private val TAG = "VCamdroid"

    private val rtspServerCamera2 = RtspServerCamera2(openGlView, this, PORT)
    private val connectionManager = ConnectionManager.getInstance()

    /**
     * Stream options that arrived before the SurfaceView had a valid surface.
     * Applied by [onSurfaceCreated].
     */
    private var pendingOptions: StreamOptions? = null

    private var bitrateAdapter = BitrateAdapter {
        if (options.adaptiveBitrateEnabled) {
            rtspServerCamera2.setVideoBitrateOnFly(it)
        }
    }

    init {
        val connectionManager = ConnectionManager.getInstance()
        val localIpAddress = connectionManager.localIpAddress

        getSupportedResolutions(context) { back, front ->
            val descriptor = DeviceDescriptor(
                android.os.Build.MODEL,
                URL.replace("localhost", localIpAddress),
                back,
                front,
                FilterRepository.filters
            )

            connectionManager.sendDescriptor(descriptor)
        }
    }

    fun stop() {
        // `stopPreview()` also renders through the OpenGlView, so it needs a
        // surface just like `startPreview()` does.
        if (hasValidSurface()) {
            rtspServerCamera2.stopPreview()
        }
        if (rtspServerCamera2.isStreaming) {
            rtspServerCamera2.stopStream()
        }
        pendingOptions = null
    }

    /**
     * Switch camera front <-> back
     */
    fun switchCamera() {
        try {
            rtspServerCamera2.switchCamera()
            // Make sure local state is synced correctly by manually updating it
            if (options.camera == CameraHelper.Facing.BACK) {
                options.camera = CameraHelper.Facing.FRONT
            } else {
                options.camera = CameraHelper.Facing.BACK
            }
        } catch (e: CameraOpenException) {
            Logger.log("STREAMER", "Can't switch camera. Current resolution ({${options.width}, ${options.height}) not supported")
            connectionManager.sendErrorReport(ErrorReport(
                ErrorReport.Severity.ERROR,
                "Can't switch camera",
                "Resolution ({${options.width}, ${options.height}) not supported"
            ))
        }
    }

    /**
     * Set a new resolution. Requires a stream restart
     */
    fun setResolution(width: Int, height: Int) {
        if (options.width == width && options.height == height)
            return

        Logger.log("STREAMER", "Changing resolution to ${width}x${height}...")
        options.width = width
        options.height = height
        options.bitrate = calculateOptimalBitrate(width, height, options.fps)
        restartStream()
    }

    fun rotate(degress: Int) {
        options.rotation += degress
        rtspServerCamera2.glInterface.setStreamRotation(options.rotation)
        // rtspServerCamera2.glInterface.setRotation(options.rotation)
    }

    /**
     * Apply an effect filter. Only one active at a time
     * @param filterName Filter's name
     */
    fun applyEffectFilter(filterName: String) {
        val category = FilterRepository.getCategory(filterName)
        if (category == FilterRepository.Category.CORRECTION) {
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            // Remove the current filter if there is one
            options.activeEffectFilter?.let { rtspServerCamera2.glInterface.removeFilter(it) }
            // Only apply the new requested effect filter if it is different than the NONE filter
            // Applying the NONE filter would completely remove all filters, including the correction filters
            // This behaviour results in deleting the effect filter but keeping the corrections
            if (category != FilterRepository.Category.NONE) {
                val filter = FilterRepository.create(filterName)
                if (filter != null) {
                    rtspServerCamera2.glInterface.addFilter(filter)
                    options.activeEffectFilter = filter
                }
            } else {
                options.activeEffectFilter = null
            }
        }
    }

    /**
     * Apply a correction filter. Multiple filters can be active at once.
     * If the filter was already applied it is adjusted with new value
     */
    fun applyCorrectionFilter(filterName: String, value: Int) {
        val category = FilterRepository.getCategory(filterName)
        val fclass = FilterRepository.getClass(filterName)

        if (category != FilterRepository.Category.CORRECTION || fclass == null) {
            return
        }

        val existingFilter = options.activeCorrectionFilters.find { it::class.java == fclass }
        if (existingFilter != null) {
            // If the filter already exist just update its adjustment value
            FilterAdjuster.adjust(existingFilter, value)
        } else {
            // Otherwise create a new filter, save it and apply it to the opengl interface
            val filter = FilterRepository.create(filterName)
            CoroutineScope(Dispatchers.Main).launch {
                FilterAdjuster.adjust(filter!!, value)
                options.activeCorrectionFilters.add(filter)
                rtspServerCamera2.glInterface.addFilter(filter)
            }
        }
    }

    /**
     * Set a new target fps. Requires a stream restart
     */
    fun setFps(fps: Int) {
        if (options.fps == fps)
            return

        Logger.log("STREAMER", "Changing fps to ${fps}...")
        options.fps = fps
        restartStream()
    }

    fun setZoom(zoom: Float) {
        rtspServerCamera2.zoom = zoom
    }

    fun flip(axis: StreamOptions.FlipAxis) {
        CoroutineScope(Dispatchers.Main).launch {
            when (axis) {
                StreamOptions.FlipAxis.HORIZONTAL -> options.hFlipFilter = toggleFilter(options.hFlipFilter) { HorizontalFlipFilterRender() } as HorizontalFlipFilterRender?
                StreamOptions.FlipAxis.VERTICAL -> options.vFlipFilter = toggleFilter(options.vFlipFilter) { VerticalFlipFilterRender() } as VerticalFlipFilterRender?
            }
        }
    }

    private fun toggleFilter(
        currentFilter: BaseFilterRender?,
        createFilter: () -> BaseFilterRender
    ): BaseFilterRender? {
        return if (currentFilter != null) {
            // If it exists, remove it and return null (to clear the variable)
            rtspServerCamera2.glInterface.removeFilter(currentFilter)
            null
        } else {
            // If it's null, create new one, add it, and return it
            val newFilter = createFilter()
            rtspServerCamera2.glInterface.addFilter(newFilter)
            newFilter
        }
    }

    /**
     * Set a new bitrate
     */
    fun setBitrate(bitrate: Int) {
        options.adaptiveBitrateEnabled = false
        rtspServerCamera2.setVideoBitrateOnFly(bitrate * 1024)
    }

    fun setAdaptiveBitrate(min: Int, max: Int) {
        options.adaptiveBitrateEnabled = true
        options.adaptiveBitrateMin = min * 1024
        options.adaptiveBitrateMax = max * 1024

        bitrateAdapter.setMaxBitrate(options.adaptiveBitrateMax)
        rtspServerCamera2.setVideoBitrateOnFly(options.adaptiveBitrateMax)
    }

    fun setStabilization(enabled: Boolean) {
        when (enabled) {
            true -> rtspServerCamera2.enableVideoStabilization()
            false -> rtspServerCamera2.disableVideoStabilization()
        }
    }

    fun setFlash(enabled: Boolean) {
        when (enabled) {
            true -> rtspServerCamera2.enableLantern()
            false -> rtspServerCamera2.disableLantern()
        }
    }

    fun setFocus(mode: Int) {
        when (mode) {
            0 -> rtspServerCamera2.enableAutoFocus()
            1 -> rtspServerCamera2.disableAutoFocus()
        }
    }

    fun setH265Codec(enabled: Boolean) {
        // Keep the local state in sync so a later restart re-applies the right
        // codec (the ACTIVATION packet sets this too).
        options.h265Enabled = enabled
        val codec = if (enabled) VideoCodec.H265 else VideoCodec.H264

        if (!rtspServerCamera2.isStreaming) {
            rtspServerCamera2.setVideoCodec(codec)
            return
        }

        // The server is running and `setVideoCodec` would throw. It has to be
        // stopped first, so reuse the restart path that already does that.
        restartStream()
    }

    /**
     * True once the SurfaceView actually has a surface to render into.
     *
     * The view is inflated and measured asynchronously, so an ACTIVATION packet
     * can easily arrive before the surface exists.
     */
    private fun hasValidSurface(): Boolean =
        openGlView.holder.surface?.isValid == true

    /**
     * Starts the preview, or reports that it had to be postponed.
     *
     * `RtspServerCamera2.startPreview()` renders through the OpenGlView and
     * throws `IllegalArgumentException: Make sure the SurfaceView or associated
     * SurfaceHolder has a valid Surface` when it is called too early, which
     * used to crash the whole app.
     */
    fun startPreview(): Boolean {
        if (!hasValidSurface()) {
            Logger.log("STREAMER", "Preview postponed: the surface is not ready yet")
            return false
        }

        if (!rtspServerCamera2.isOnPreview) {
            rtspServerCamera2.startPreview(options.camera, options.width, options.height)
        }
        return true
    }

    /**
     * Called by StreamActivity once the surface is created. This is the only
     * place that starts the preview for a new session; [startStream] never
     * kicks it off on its own.
     */
    fun onSurfaceCreated() {
        Handler(Looper.getMainLooper()).post {
            if (!startPreview()) {
                return@post
            }

            // An ACTIVATION packet that arrived while the surface was missing
            // is applied now.
            val pending = pendingOptions ?: return@post
            pendingOptions = null
            Logger.log("STREAMER", "Applying the stream options that were pending")
            beginStream(options, pending)
        }
    }

    fun startStream(newOptions: StreamOptions) {
        val oldOptions = this.options
        this.options = newOptions

        Handler(Looper.getMainLooper()).post {
            if (!hasValidSurface()) {
                // Nothing can be rendered yet: remember the request and let
                // onSurfaceCreated() finish it.
                pendingOptions = newOptions
                Logger.log("STREAMER", "Stream postponed: the surface is not ready yet")
                return@post
            }
            beginStream(oldOptions, newOptions)
        }
    }

    private fun beginStream(oldOptions: StreamOptions, newOptions: StreamOptions) {
        val needPreviewRestart = (oldOptions.width != newOptions.width) ||
                (oldOptions.height != newOptions.height) ||
                (oldOptions.camera != newOptions.camera)

        // The RTSP server outlives a desktop-client reconnect, so a second
        // ACTIVATION can arrive while we are still streaming. Stop it first:
        // RtspServer.setVideoCodec() throws "Please set VideoCodec before
        // startServer" while the server is running.
        if (needPreviewRestart && rtspServerCamera2.isStreaming) {
            rtspServerCamera2.stopStream()
        }

        if (needPreviewRestart) {
            if (rtspServerCamera2.isOnPreview) {
                rtspServerCamera2.stopPreview()
            }
            // Restart the preview with the new geometry. When nothing changed
            // the preview is already running (started by onSurfaceCreated), so
            // there is nothing to do here.
            startPreview()
        }

        applyOptionsToStream(newOptions)
        startStreamInternal()
    }

    private fun startStreamInternal() {
        if (!rtspServerCamera2.isStreaming) {
            try {
                rtspServerCamera2.prepareVideo(
                    options.width,
                    options.height,
                    options.fps,
                    options.bitrate,
                    0
                )
                rtspServerCamera2.startStream(URL)
                Logger.log("STREAMER", "Stream started")
            } catch (e: Exception) {
                Logger.log("STREAMER", "Error preparing stream")
                connectionManager.sendErrorReport(ErrorReport(
                    ErrorReport.Severity.ERROR,
                    "Can't start stream",
                    "({${options.width}, ${options.height}) @${options.fps}fps not supported"
                ))
            }
        }
    }

    /**
     * Apply initial options
     */
    private fun applyOptionsToStream(options: StreamOptions) {
        if (options.adaptiveBitrateEnabled) {
            bitrateAdapter.setMaxBitrate(options.adaptiveBitrateMax)
        } else {
            rtspServerCamera2.setVideoBitrateOnFly(options.bitrate)
        }

        setStabilization(options.stabilization)
        setFocus(options.focusMode)

        // RtspServer throws "Please set VideoCodec before startServer" while it
        // is running. beginStream() stops the stream when the options require a
        // restart, so this only runs on a stopped server.
        if (!rtspServerCamera2.isStreaming) {
            rtspServerCamera2.setVideoCodec(if (options.h265Enabled) VideoCodec.H265 else VideoCodec.H264)
        }

        options.activeEffectFilter?.let { rtspServerCamera2.glInterface.addFilter(it) }
        options.activeCorrectionFilters.forEach {
            rtspServerCamera2.glInterface.addFilter(it)
        }
    }

    private fun restartStream() {
        Handler(Looper.getMainLooper()).post {
            if (!hasValidSurface()) {
                // Nothing to restart without a surface; onSurfaceCreated()
                // starts the preview and applies this request.
                pendingOptions = options
                Logger.log("STREAMER", "Restart postponed: the surface is not ready yet")
                return@post
            }

            val wasStreaming = rtspServerCamera2.isStreaming

            if (rtspServerCamera2.isStreaming) {
                rtspServerCamera2.stopStream()
            }
            if (rtspServerCamera2.isOnPreview) {
                rtspServerCamera2.stopPreview()
            }

            if (!startPreview()) {
                return@post
            }

            // Re-apply the options on the stopped server: the codec can only be
            // changed while it is stopped, and the resolution/fps changes that
            // lead here also need a fresh bitrate and encoder setup.
            applyOptionsToStream(options)

            if (wasStreaming) {
                startStreamInternal()
            }
        }
    }

    /**
     * Calculate the optimal bitrate for given resolution and fps.
     * Uses 1080p 30fps @ 4000Mbps as a standard reference
     */
    private fun calculateOptimalBitrate(width: Int, height: Int, fps: Int): Int {
        val targetPixelCount = width.toLong() * height.toLong() * fps
        val referencePixelCount = 1920L * 1080L * 30L
        val referenceBitrate = 6000 * 1024L

        val calculatedBitrate =
            (targetPixelCount.toDouble() / referencePixelCount.toDouble()) * referenceBitrate
        val minBitrate = 500 * 1024
        val maxBitrate = 12000 * 1024

        return calculatedBitrate.toInt().coerceIn(minBitrate, maxBitrate)
    }

    override fun onNewBitrate(bitrate: Long) {
        bitrateAdapter.adaptBitrate(bitrate)
    }

    override fun onAuthError() {}

    override fun onAuthSuccess() {}

    override fun onConnectionFailed(reason: String) {}

    override fun onConnectionStarted(url: String) {}

    override fun onConnectionSuccess() {}

    override fun onDisconnect() {}
}