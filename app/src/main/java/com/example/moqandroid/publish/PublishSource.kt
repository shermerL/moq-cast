package com.example.moqandroid.publish

import android.view.Surface
import com.example.moqandroid.protocol.VideoLayoutEvent
import com.example.moqandroid.publish.encoder.H264ProfilePreference
import com.example.moqandroid.publish.encoder.VideoEncoderPolicy

interface VideoPublishSource {
    val label: String

    val layoutTransitions: VideoLayoutTransitionCapability?
        get() = null

    fun attachEncoderSurface(surface: Surface, config: VideoPublishConfig)

    fun detachEncoderSurface()

    fun close()
}

interface VideoLayoutTransitionCapability {
    fun isOutputSuspended(): Boolean

    fun pollConfigChange(): VideoPublishTransition?

    fun pollLayoutEvent(): VideoLayoutEvent?

    fun onLayoutReady(generation: Long)
}

data class VideoPublishTransition(
    val config: VideoPublishConfig,
    val generation: Long,
)

data class VideoPublishConfig(
    val width: Int,
    val height: Int,
    val bitrate: Int = 4_000_000,
    val frameRate: Int = 30,
    val iFrameIntervalSeconds: Int = 1,
    val encoderPolicy: VideoEncoderPolicy = VideoEncoderPolicy.Default,
    val h264ProfilePreference: H264ProfilePreference = H264ProfilePreference.High,
)
