package com.example.moqandroid.publish.screen

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import com.example.moqandroid.publish.VideoPublishConfig
import com.example.moqandroid.publish.VideoPublishSource

class ScreenPublishSource(
    private val projection: MediaProjection,
    private val densityDpi: Int,
) : VideoPublishSource {
    override val label: String = "screen"

    private var virtualDisplay: VirtualDisplay? = null

    override fun attachEncoderSurface(surface: Surface, config: VideoPublishConfig) {
        detachEncoderSurface()
        Log.i(
            LOG_TAG,
            "attaching screen source virtualDisplay=${config.width}x${config.height} " +
                "densityDpi=$densityDpi encoderInput=${config.width}x${config.height}",
        )
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
    }

    override fun detachEncoderSurface() {
        if (virtualDisplay != null) {
            Log.i(LOG_TAG, "releasing screen source virtual display")
        }
        virtualDisplay?.release()
        virtualDisplay = null
    }

    override fun close() {
        detachEncoderSurface()
    }

    companion object {
        private const val LOG_TAG = "MoqAndroid"
    }
}
