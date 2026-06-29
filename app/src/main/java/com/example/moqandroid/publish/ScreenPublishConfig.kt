package com.example.moqandroid.publish

data class ScreenPublishConfig(
    val video: ScreenVideoConfig,
    val audio: SystemAudioConfig = SystemAudioConfig.Disabled,
)

sealed class SystemAudioConfig {
    data object Disabled : SystemAudioConfig()

    data class Enabled(
        val sampleRate: Int = 48_000,
        val channelCount: Int = 2,
        val bitrate: Int = 96_000,
        val frameDurationMs: Int = 20,
    ) : SystemAudioConfig()
}
