package com.example.moqandroid.ui

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.example.moqandroid.playback.PlayerState

class PlayerScreen(
    activity: Activity,
    broadcastName: String,
    surfaceCallback: SurfaceHolder.Callback,
) {
    private var videoWidth: Int? = null
    private var videoHeight: Int? = null

    val surfaceView: SurfaceView = SurfaceView(activity).apply {
        isFocusable = false
        holder.addCallback(surfaceCallback)
    }

    val status: TextView = TextView(activity).apply {
        text = PlayerState.SurfaceWaiting.message(broadcastName)
        textSize = 14f
        setTextColor(Color.WHITE)
        setBackgroundColor(0x88000000.toInt())
        setPadding(18, 12, 18, 12)
    }

    private val rootFrame = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
        addView(surfaceView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        addView(
            status,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSurfaceLayout() }
    }

    val root: View = rootFrame

    fun setStatus(message: String) {
        status.text = message
    }

    fun setVideoSize(width: Int?, height: Int?) {
        videoWidth = width?.takeIf { it > 0 }
        videoHeight = height?.takeIf { it > 0 }
        updateSurfaceLayout()
    }

    private fun updateSurfaceLayout() {
        val containerWidth = rootFrame.width
        val containerHeight = rootFrame.height
        val sourceWidth = videoWidth
        val sourceHeight = videoHeight
        if (containerWidth <= 0 || containerHeight <= 0 || sourceWidth == null || sourceHeight == null) return

        val containerRatio = containerWidth.toFloat() / containerHeight
        val sourceRatio = sourceWidth.toFloat() / sourceHeight
        val targetWidth: Int
        val targetHeight: Int

        if (sourceRatio > containerRatio) {
            targetWidth = containerWidth
            targetHeight = (containerWidth / sourceRatio).toInt()
        } else {
            targetHeight = containerHeight
            targetWidth = (containerHeight * sourceRatio).toInt()
        }

        val current = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (current.width == targetWidth && current.height == targetHeight && current.gravity == Gravity.CENTER) return

        surfaceView.layoutParams = FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
    }
}
