package com.example.moqandroid.publish.screen

import com.example.moqandroid.publish.VideoPublishConfig
import com.example.moqandroid.publish.encoder.H264ProfilePreference
import com.example.moqandroid.publish.encoder.VideoEncoderPolicy

data class ScreenPublishConfig(
    val video: ScreenVideoConfig,
    val audio: SystemAudioConfig = SystemAudioConfig.Disabled,
)

data class ScreenVideoConfig(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val bitrate: Int = 4_000_000,
    val frameRate: Int = 30,
    val iFrameIntervalSeconds: Int = 1,
    val encoderPolicy: VideoEncoderPolicy = VideoEncoderPolicy.Default,
    val h264ProfilePreference: H264ProfilePreference = H264ProfilePreference.High,
)

fun ScreenVideoConfig.encoderConfig(): VideoPublishConfig {
    return VideoPublishConfig(
        width = width,
        height = height,
        bitrate = bitrate,
        frameRate = frameRate,
        iFrameIntervalSeconds = iFrameIntervalSeconds,
        encoderPolicy = encoderPolicy,
        h264ProfilePreference = h264ProfilePreference,
    )
}

sealed class SystemAudioConfig {
    data object Disabled : SystemAudioConfig()

    data class Enabled(
        val sampleRate: Int = 48_000,
        val channelCount: Int = 2,
        val bitrate: Int = 96_000,
        val frameDurationMs: Int = 20,
    ) : SystemAudioConfig()
}
