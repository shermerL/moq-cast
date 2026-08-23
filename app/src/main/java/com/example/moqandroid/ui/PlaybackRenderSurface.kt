package com.example.moqandroid.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import com.example.moqandroid.playback.PlaybackRendererMode

internal interface PlaybackRenderSurface {
    val view: View
    val pixelCopySource: SurfaceView?
    val isValid: Boolean

    fun surfaceSize(): Pair<Int, Int>

    fun release()
}

internal interface PlaybackRenderSurfaceEvents {
    fun onAvailable(surface: Surface)

    fun onChanged(format: Int?, width: Int, height: Int)

    fun onRedrawNeeded()

    fun onDestroyed()
}

internal fun createPlaybackRenderSurface(
    context: Context,
    mode: PlaybackRendererMode,
    events: PlaybackRenderSurfaceEvents,
): PlaybackRenderSurface = when (mode) {
    PlaybackRendererMode.SurfaceView -> SurfaceViewRenderSurface(context, events)
    PlaybackRendererMode.TextureView -> TextureViewRenderSurface(context, events)
}

private class SurfaceViewRenderSurface(
    context: Context,
    private val events: PlaybackRenderSurfaceEvents,
) : PlaybackRenderSurface {
    private var available = false
    private val callback = object : SurfaceHolder.Callback2 {
        override fun surfaceCreated(holder: SurfaceHolder) {
            available = true
            events.onAvailable(holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            events.onChanged(format, width, height)
        }

        override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
            events.onRedrawNeeded()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            dispatchDestroyed()
        }
    }

    override val view: SurfaceView = SurfaceView(context).apply {
        isFocusable = false
        holder.addCallback(callback)
    }
    override val pixelCopySource: SurfaceView = view
    override val isValid: Boolean
        get() = view.holder.surface.isValid

    override fun surfaceSize(): Pair<Int, Int> {
        val frame = view.holder.surfaceFrame
        val width = frame.width().takeIf { it > 0 } ?: view.width
        val height = frame.height().takeIf { it > 0 } ?: view.height
        return width to height
    }

    override fun release() {
        dispatchDestroyed()
        view.holder.removeCallback(callback)
    }

    private fun dispatchDestroyed() {
        if (!available) return
        available = false
        events.onDestroyed()
    }
}

private class TextureViewRenderSurface(
    context: Context,
    private val events: PlaybackRenderSurfaceEvents,
) : PlaybackRenderSurface {
    private var outputSurface: Surface? = null
    private var available = false
    private val listener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
            outputSurface?.release()
            val surface = Surface(surfaceTexture)
            outputSurface = surface
            available = true
            events.onAvailable(surface)
            events.onChanged(null, width, height)
        }

        override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
            events.onChanged(null, width, height)
        }

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
            dispatchDestroyed()
            releaseOutputSurface()
            return true
        }
    }

    override val view: TextureView = TextureView(context).apply {
        isFocusable = false
        surfaceTextureListener = listener
    }
    override val pixelCopySource: SurfaceView? = null
    override val isValid: Boolean
        get() = outputSurface?.isValid == true

    override fun surfaceSize(): Pair<Int, Int> = view.width to view.height

    override fun release() {
        view.surfaceTextureListener = null
        dispatchDestroyed()
        releaseOutputSurface()
    }

    private fun dispatchDestroyed() {
        if (!available) return
        available = false
        events.onDestroyed()
    }

    private fun releaseOutputSurface() {
        outputSurface?.release()
        outputSurface = null
    }
}
