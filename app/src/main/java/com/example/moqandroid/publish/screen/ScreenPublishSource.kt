package com.example.moqandroid.publish.screen

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import com.example.moqandroid.publish.VideoPublishConfig
import com.example.moqandroid.publish.VideoPublishSource
import java.util.concurrent.atomic.AtomicReference

class ScreenPublishSource(
    context: Context,
    private val projection: MediaProjection,
    initialConfig: ScreenVideoConfig,
) : VideoPublishSource {
    override val label: String = "screen"

    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val densityDpi = initialConfig.densityDpi
    private val baseConfig = initialConfig.encoderConfig()
    private val pendingConfig = AtomicReference<VideoPublishConfig?>()
    private var virtualDisplay: VirtualDisplay? = null
    private var virtualDisplayWidth = initialConfig.width
    private var virtualDisplayHeight = initialConfig.height
    private var lastRequestedConfig = baseConfig
    @Volatile
    private var closed = false
    private val resizeRunnable = Runnable(::evaluateDisplayGeometry)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || closed) return
            handler.removeCallbacks(resizeRunnable)
            handler.postDelayed(resizeRunnable, RESIZE_DEBOUNCE_MS)
        }
    }

    init {
        displayManager.registerDisplayListener(displayListener, handler)
        handler.post(resizeRunnable)
    }

    override fun attachEncoderSurface(surface: Surface, config: VideoPublishConfig) {
        detachEncoderSurface()
        Log.i(
            LOG_TAG,
            "attaching screen source virtualDisplay=${config.width}x${config.height} " +
                "densityDpi=$densityDpi encoderInput=${config.width}x${config.height}",
        )
        val currentDisplay = virtualDisplay
        if (currentDisplay == null) {
            virtualDisplay = projection.createVirtualDisplay(
                "MoqScreenPublish",
                config.width,
                config.height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null,
            )
        } else {
            currentDisplay.resize(config.width, config.height, densityDpi)
            currentDisplay.surface = surface
        }
        virtualDisplayWidth = config.width
        virtualDisplayHeight = config.height
    }

    override fun detachEncoderSurface() {
        if (virtualDisplay != null) {
            Log.i(LOG_TAG, "detaching screen source encoder surface")
        }
        virtualDisplay?.surface = null
    }

    override fun pollConfigChange(): VideoPublishConfig? {
        return pendingConfig.getAndSet(null)
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(resizeRunnable)
        displayManager.unregisterDisplayListener(displayListener)
        virtualDisplay?.release()
        virtualDisplay = null
    }

    @Suppress("DEPRECATION")
    private fun evaluateDisplayGeometry() {
        if (closed) return
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        display?.getRealMetrics(metrics)
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return

        val nextConfig = baseConfig.withScreenSize(metrics.widthPixels, metrics.heightPixels)
        Log.i(
            LOG_TAG,
            "screen capture geometry displayRotation=${display?.rotation.rotationName()} " +
                "screen=${metrics.widthPixels}x${metrics.heightPixels} densityDpi=${metrics.densityDpi} " +
                "virtualDisplay=${virtualDisplayWidth}x$virtualDisplayHeight " +
                "targetEncoder=${nextConfig.width}x${nextConfig.height}",
        )
        if (nextConfig.width == lastRequestedConfig.width && nextConfig.height == lastRequestedConfig.height) return

        lastRequestedConfig = nextConfig
        pendingConfig.set(nextConfig)
        Log.i(LOG_TAG, "screen encoder resize requested target=${nextConfig.width}x${nextConfig.height}")
    }

    private fun Int?.rotationName(): String = when (this) {
        Surface.ROTATION_0 -> "0"
        Surface.ROTATION_90 -> "90"
        Surface.ROTATION_180 -> "180"
        Surface.ROTATION_270 -> "270"
        else -> "unknown"
    }

    companion object {
        private const val LOG_TAG = "MoqAndroid"
        private const val RESIZE_DEBOUNCE_MS = 300L
    }
}
