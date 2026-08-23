package com.example.moqandroid.publish

internal class PublishTimeline(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val epochNs = nanoTime()
    private val epochUs = epochNs / NANOS_PER_MICROSECOND

    fun elapsedUs(): Long {
        return (nanoTime() - epochNs).coerceAtLeast(0L) / NANOS_PER_MICROSECOND
    }

    fun video(): VideoPublishTimeline = VideoPublishTimeline(this)

    fun audio(sampleRate: Int): AudioPublishTimeline = AudioPublishTimeline(this, sampleRate)

    internal fun sample(): PublishClockSample {
        val nowNs = nanoTime()
        return PublishClockSample(
            absoluteUs = nowNs / NANOS_PER_MICROSECOND,
            elapsedUs = (nowNs - epochNs).coerceAtLeast(0L) / NANOS_PER_MICROSECOND,
        )
    }

    internal fun toSessionTimeUs(absoluteUs: Long): Long? {
        if (absoluteUs < epochUs) return null
        return absoluteUs - epochUs
    }

    companion object {
        private const val NANOS_PER_MICROSECOND = 1_000L
    }
}

internal class VideoPublishTimeline(
    private val clock: PublishTimeline,
) {
    private var anchor: VideoTimestampAnchor? = null
    private var latestTimestampUs: Long? = null

    fun beginGeneration() {
        anchor = null
    }

    fun map(sourceTimestampUs: Long): VideoPublishTimestamp {
        require(sourceTimestampUs >= 0L) { "Video presentation timestamp must be non-negative." }
        val sample = clock.sample()
        val currentAnchor = anchor ?: createAnchor(sourceTimestampUs, sample).also { anchor = it }
        val timestampUs = currentAnchor.mediaTimestampUs + (sourceTimestampUs - currentAnchor.sourceTimestampUs)
        require(timestampUs >= 0L) { "Mapped video presentation timestamp must be non-negative." }
        latestTimestampUs = maxOf(latestTimestampUs ?: timestampUs, timestampUs)
        return VideoPublishTimestamp(
            timestampUs = timestampUs,
            sessionElapsedUs = sample.elapsedUs,
            sourceClockMatched = currentAnchor.sourceClockMatched,
        )
    }

    private fun createAnchor(
        sourceTimestampUs: Long,
        sample: PublishClockSample,
    ): VideoTimestampAnchor {
        val sourceClockMatched = timestampDistance(sourceTimestampUs, sample.absoluteUs) <= SOURCE_CLOCK_TOLERANCE_US
        val directTimestampUs = if (sourceClockMatched) clock.toSessionTimeUs(sourceTimestampUs) else null
        val previousFloor = latestTimestampUs?.let { if (it == Long.MAX_VALUE) it else it + 1L } ?: 0L
        val mediaTimestampUs = maxOf(directTimestampUs ?: sample.elapsedUs, previousFloor)
        return VideoTimestampAnchor(sourceTimestampUs, mediaTimestampUs, directTimestampUs != null)
    }

    private fun timestampDistance(first: Long, second: Long): Long {
        return if (first >= second) first - second else second - first
    }

    companion object {
        private const val SOURCE_CLOCK_TOLERANCE_US = 5_000_000L
    }
}

internal class AudioPublishTimeline(
    private val clock: PublishTimeline,
    private val sampleRate: Int,
) {
    private var firstSampleTimestampUs: Long? = null

    init {
        require(sampleRate > 0) { "Audio sample rate must be positive." }
    }

    fun timestampUs(
        submittedSampleFrames: Long,
        capturedSampleFrames: Int,
    ): Long {
        require(submittedSampleFrames >= 0L) { "Submitted audio sample count must be non-negative." }
        require(capturedSampleFrames > 0) { "Captured audio frame must contain samples." }
        val firstTimestampUs = firstSampleTimestampUs ?: run {
            val capturedDurationUs = samplesToUs(capturedSampleFrames.toLong())
            (clock.elapsedUs() - capturedDurationUs).coerceAtLeast(0L).also {
                firstSampleTimestampUs = it
            }
        }
        return firstTimestampUs + samplesToUs(submittedSampleFrames)
    }

    private fun samplesToUs(samples: Long): Long = samples * MICROS_PER_SECOND / sampleRate

    companion object {
        private const val MICROS_PER_SECOND = 1_000_000L
    }
}

internal data class VideoPublishTimestamp(
    val timestampUs: Long,
    val sessionElapsedUs: Long,
    val sourceClockMatched: Boolean,
)

internal data class PublishClockSample(
    val absoluteUs: Long,
    val elapsedUs: Long,
)

private data class VideoTimestampAnchor(
    val sourceTimestampUs: Long,
    val mediaTimestampUs: Long,
    val sourceClockMatched: Boolean,
)
