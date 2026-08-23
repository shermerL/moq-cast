package com.example.moqandroid.publish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishTimelineTest {
    @Test
    fun videoUsesTheSharedMonotonicClock() {
        val clock = FakeNanoClock(10_000_000_000L)
        val timeline = PublishTimeline(clock::now)
        val video = timeline.video()
        video.beginGeneration()
        clock.advanceNs(100_000_000L)

        val timestamp = video.map(sourceTimestampUs = 10_080_000L)

        assertEquals(80_000L, timestamp.timestampUs)
        assertEquals(100_000L, timestamp.sessionElapsedUs)
        assertTrue(timestamp.sourceClockMatched)
    }

    @Test
    fun videoKeepsCaptureCadenceWhenEncoderOutputIsBursty() {
        val clock = FakeNanoClock(10_000_000_000L)
        val timeline = PublishTimeline(clock::now)
        val video = timeline.video()
        video.beginGeneration()
        clock.advanceNs(100_000_000L)

        val first = video.map(sourceTimestampUs = 10_080_000L)
        clock.advanceNs(400_000_000L)
        val second = video.map(sourceTimestampUs = 10_113_333L)

        assertEquals(80_000L, first.timestampUs)
        assertEquals(113_333L, second.timestampUs)
        assertEquals(500_000L, second.sessionElapsedUs)
    }

    @Test
    fun videoFallsBackToArrivalTimeForAnUnknownSourceClock() {
        val clock = FakeNanoClock(10_000_000_000L)
        val timeline = PublishTimeline(clock::now)
        val video = timeline.video()
        video.beginGeneration()
        clock.advanceNs(200_000_000L)

        val first = video.map(sourceTimestampUs = 0L)
        clock.advanceNs(300_000_000L)
        val second = video.map(sourceTimestampUs = 33_333L)

        assertEquals(200_000L, first.timestampUs)
        assertEquals(233_333L, second.timestampUs)
        assertFalse(first.sourceClockMatched)
    }

    @Test
    fun restartedVideoGenerationDoesNotRewindAfterSourceClockReset() {
        val clock = FakeNanoClock(10_000_000_000L)
        val timeline = PublishTimeline(clock::now)
        val video = timeline.video()
        video.beginGeneration()
        clock.advanceNs(200_000_000L)
        video.map(sourceTimestampUs = 0L)
        val beforeRestart = video.map(sourceTimestampUs = 33_333L)

        clock.advanceNs(200_000_000L)
        video.beginGeneration()
        val afterRestart = video.map(sourceTimestampUs = 0L)

        assertEquals(233_333L, beforeRestart.timestampUs)
        assertEquals(400_000L, afterRestart.timestampUs)
    }

    @Test
    fun audioPreservesStartOffsetAndSampleCadence() {
        val clock = FakeNanoClock(10_000_000_000L)
        val timeline = PublishTimeline(clock::now)
        val audio = timeline.audio(sampleRate = 48_000)
        clock.advanceNs(120_000_000L)

        val first = audio.timestampUs(submittedSampleFrames = 0L, capturedSampleFrames = 960)
        clock.advanceNs(380_000_000L)
        val second = audio.timestampUs(submittedSampleFrames = 960L, capturedSampleFrames = 960)

        assertEquals(100_000L, first)
        assertEquals(120_000L, second)
    }

    @Test
    fun audioAndVideoShareTheSessionEpoch() {
        val clock = FakeNanoClock(10_000_000_000L)
        val timeline = PublishTimeline(clock::now)
        val video = timeline.video()
        val audio = timeline.audio(sampleRate = 48_000)
        video.beginGeneration()
        clock.advanceNs(120_000_000L)

        val audioTimestampUs = audio.timestampUs(submittedSampleFrames = 0L, capturedSampleFrames = 960)
        val videoTimestamp = video.map(sourceTimestampUs = 10_100_000L)

        assertEquals(100_000L, audioTimestampUs)
        assertEquals(100_000L, videoTimestamp.timestampUs)
        assertTrue(videoTimestamp.sourceClockMatched)
    }
}

private class FakeNanoClock(
    private var value: Long,
) {
    fun now(): Long = value

    fun advanceNs(delta: Long) {
        value += delta
    }
}
