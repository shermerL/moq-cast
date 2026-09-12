package com.example.moqandroid.playback

import com.example.moqandroid.catalog.AudioDecoderBackend
import com.example.moqandroid.catalog.PlayableAudioTrack
import com.example.moqandroid.catalog.decoderOutput
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.moq.MoqAudio
import uniffi.moq.MoqContainer

class PlaybackLatencyPolicyTest {
    @Test
    fun videoOnlyHasNoStaleGroupTolerance() {
        assertEquals(0uL, PlaybackLatencyPolicy.forInitialTracks(false).maxAgeMs)
    }

    @Test
    fun audioAndVideoUseTheSameEightyMillisecondBudget() {
        val policy = PlaybackLatencyPolicy.forInitialTracks(true)
        assertEquals(80uL, policy.maxAgeMs)
        assertEquals(80uL, opus().decoderOutput(policy.maxAgeMs).maxAgeMs)
    }

    @Test
    fun aNewWatchSelectsItsOwnPolicyWithoutMutatingTheCurrentWatch() {
        val currentWatch = PlaybackLatencyPolicy.forInitialTracks(true)
        val nextWatch = PlaybackLatencyPolicy.forInitialTracks(false)

        assertEquals(PlaybackLatencyPolicy.AudioVideo, currentWatch)
        assertEquals(80uL, currentWatch.maxAgeMs)
        assertEquals(PlaybackLatencyPolicy.VideoOnly, nextWatch)
        assertEquals(0uL, nextWatch.maxAgeMs)
    }

    private fun opus(description: ByteArray = byteArrayOf(1, 2)) = PlayableAudioTrack(
        name = "audio",
        audio = MoqAudio(
            codec = "opus",
            sampleRate = 48_000u,
            channelCount = 2u,
            bitrate = null,
            container = MoqContainer.Legacy,
            description = description,
        ),
        decoderBackend = AudioDecoderBackend.MoqNativeOpus,
        sampleRate = 48_000,
        channelCount = 2,
        channelMask = 12,
    )
}
