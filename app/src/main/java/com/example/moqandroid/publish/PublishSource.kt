package com.example.moqandroid.publish

import android.view.Surface
import com.example.moqandroid.protocol.VideoLayoutEvent
import com.example.moqandroid.publish.encoder.H264ProfilePreference
import com.example.moqandroid.publish.encoder.VideoEncoderPolicy

interface VideoPublishSource {
    val label: String

    val presentation: VideoPublishPresentation?
        get() = null

    val layoutTransitions: VideoLayoutTransitionCapability?
        get() = null

    suspend fun attachEncoderSurface(surface: Surface, config: VideoPublishConfig)

    suspend fun detachEncoderSurface()

    fun pollFailure(): Throwable? = null

    suspend fun close()
}

data class VideoPublishPresentation(
    val displayWidth: Int,
    val displayHeight: Int,
    val rotationDegrees: Int,
    val flip: Boolean = false,
)

enum class PublishSourceType(val storageValue: String) {
    Camera("camera"),
    Screen("screen");

    companion object {
        fun fromStorageValue(value: String?): PublishSourceType {
            return entries.firstOrNull { it.storageValue == value } ?: Screen
        }
    }
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
