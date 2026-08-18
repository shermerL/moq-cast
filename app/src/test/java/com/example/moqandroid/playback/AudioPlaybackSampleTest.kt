package com.example.moqandroid.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlaybackSampleTest {
    @Test
    fun mapsVideoTargetFromThePairedAudioTimestamp() {
        val sample = AudioPlaybackSample(
            streamTimeUs = 120_000L,
            nanoTimeNs = 5_000_000_000L,
        )

        val timing = sample.timingFor(
            targetStreamTimeUs = 420_000L,
            nowNs = 5_100_000_000L,
            queuedEndUs = 1_000_000L,
        )

        assertEquals(220_000L, timing.positionUs)
        assertEquals(5_300_000_000L, timing.targetNanoTimeNs)
    }

    @Test
    fun currentPositionDoesNotAdvancePastQueuedAudio() {
        val sample = AudioPlaybackSample(
            streamTimeUs = 120_000L,
            nanoTimeNs = 5_000_000_000L,
        )

        val timing = sample.timingFor(
            targetStreamTimeUs = 420_000L,
            nowNs = 6_000_000_000L,
            queuedEndUs = 300_000L,
        )

        assertEquals(300_000L, timing.positionUs)
        assertEquals(5_300_000_000L, timing.targetNanoTimeNs)
    }

    @Test
    fun continuousAudioFramesStayOnTheSameTimeline() {
        val timeline = AudioFrameTimeline(sampleRate = 48_000)

        assertEquals(null, timeline.advance(timestampUs = 1_000_000L, frameSamples = 960))
        assertEquals(null, timeline.advance(timestampUs = 1_020_000L, frameSamples = 960))
    }

    @Test
    fun skippedAudioFrameReportsTimelineDiscontinuity() {
        val timeline = AudioFrameTimeline(sampleRate = 48_000)

        timeline.advance(timestampUs = 1_000_000L, frameSamples = 960)
        val discontinuity = timeline.advance(timestampUs = 1_040_000L, frameSamples = 960)

        assertEquals(1_020_000L, discontinuity?.expectedTimestampUs)
        assertEquals(1_040_000L, discontinuity?.actualTimestampUs)
        assertEquals(20_000L, discontinuity?.deltaUs)
    }

    @Test
    fun smallTimestampJitterDoesNotResetPlayback() {
        val timeline = AudioFrameTimeline(sampleRate = 48_000)

        timeline.advance(timestampUs = 1_000_000L, frameSamples = 960)

        assertEquals(null, timeline.advance(timestampUs = 1_022_000L, frameSamples = 960))
    }
}
