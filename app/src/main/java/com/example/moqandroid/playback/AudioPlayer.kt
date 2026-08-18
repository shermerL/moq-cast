package com.example.moqandroid.playback

import android.media.AudioTimestamp
import android.media.AudioTrack
import com.example.moqandroid.catalog.PlayableAudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import uniffi.moq.MoqAudioConsumer
import kotlin.coroutines.coroutineContext

class AudioPlayer(private val logTag: String) {
    suspend fun play(
        consumer: MoqAudioConsumer,
        audio: PlayableAudioTrack,
        clock: AudioPlaybackClock?,
    ) = withContext(Dispatchers.IO) {
        PcmAudioRenderer(
            logTag = logTag,
            audio = audio,
            format = PcmAudioFormat(audio.sampleRate, audio.channelCount),
            decoderName = "moq-native",
            clock = clock,
        ).use { renderer ->
            coroutineScope {
                while (coroutineContext.isActive) {
                    val frame = consumer.next() ?: break
                    renderer.write(frame.data, frame.timestampUs.toLong())
                }
            }
        }
    }
}

class AudioPlaybackClock(sampleRate: Int) {
    private val lock = Any()
    private val timestamp = AudioTimestamp()
    private var sampleRate = sampleRate
    private var track: AudioTrack? = null
    private var anchorStreamUs: Long? = null
    private var anchorSubmittedFrames = 0L
    private var submittedFrames = 0L
    private var lastPlaybackHeadFrames = 0L
    private var playbackHeadWraps = 0L
    private var previousPlaybackHead = 0L
    private var lastTimestampFrames = -1L
    private var lastTimestampNanoTime = -1L

    fun bind(track: AudioTrack, sampleRate: Int = this.sampleRate) = synchronized(lock) {
        require(anchorStreamUs == null || this.sampleRate == sampleRate) {
            "audio output sample rate changed after playback started"
        }
        this.sampleRate = sampleRate
        this.track = track
    }

    fun unbind(track: AudioTrack) = synchronized(lock) {
        if (this.track !== track) return@synchronized
        lastPlaybackHeadFrames = maxOf(lastPlaybackHeadFrames, playbackHeadFramesLocked(track))
        this.track = null
    }

    fun queueFrame(timestampUs: Long, frameSamples: Int) = synchronized(lock) {
        if (anchorStreamUs == null) {
            anchorStreamUs = timestampUs
            anchorSubmittedFrames = submittedFrames
        }
        submittedFrames += frameSamples
    }

    fun reanchor(track: AudioTrack, timestampUs: Long) = synchronized(lock) {
        if (this.track !== track) return@synchronized
        playbackHeadWraps = 0L
        previousPlaybackHead = 0L
        lastPlaybackHeadFrames = 0L
        lastTimestampFrames = -1L
        lastTimestampNanoTime = -1L
        val playbackHeadFrames = playbackHeadFramesLocked(track)
        anchorStreamUs = timestampUs
        anchorSubmittedFrames = playbackHeadFrames
        submittedFrames = playbackHeadFrames
    }

    fun commitFrame(playbackHeadFrames: Long) = synchronized(lock) {
        val extendedPlaybackHead = extendPlaybackHead(playbackHeadFrames)
        lastPlaybackHeadFrames = maxOf(lastPlaybackHeadFrames, extendedPlaybackHead)
    }

    internal fun timingFor(streamTimeUs: Long): AudioPlaybackTiming? = synchronized(lock) {
        val boundTrack = track ?: return null
        val anchor = anchorStreamUs ?: return null
        val nowNs = System.nanoTime()
        val sample = audioTimestampLocked(boundTrack, anchor, nowNs)
            ?: AudioPlaybackSample(
                streamTimeUs = streamTimeUsAt(playbackHeadFramesLocked(boundTrack), anchor),
                nanoTimeNs = nowNs,
            )
        val queuedEndUs = streamTimeUsAt(submittedFrames, anchor)
        sample.timingFor(streamTimeUs, nowNs, queuedEndUs)
    }

    private fun audioTimestampLocked(
        track: AudioTrack,
        anchorStreamUs: Long,
        nowNs: Long,
    ): AudioPlaybackSample? {
        if (!track.getTimestamp(timestamp)) return null
        val framePosition = timestamp.framePosition
        val nanoTime = timestamp.nanoTime
        val ageNs = nowNs - nanoTime

        if (
            framePosition < lastTimestampFrames ||
            nanoTime < lastTimestampNanoTime ||
            ageNs > AUDIO_TIMESTAMP_MAX_AGE_NS ||
            ageNs < -AUDIO_TIMESTAMP_MAX_FUTURE_NS
        ) {
            return null
        }

        lastTimestampFrames = framePosition
        lastTimestampNanoTime = nanoTime
        lastPlaybackHeadFrames = maxOf(lastPlaybackHeadFrames, framePosition)
        return AudioPlaybackSample(
            streamTimeUs = streamTimeUsAt(framePosition, anchorStreamUs),
            nanoTimeNs = nanoTime,
        )
    }

    private fun playbackHeadFramesLocked(track: AudioTrack): Long {
        lastPlaybackHeadFrames = maxOf(
            lastPlaybackHeadFrames,
            extendPlaybackHead(track.playbackHeadPosition.toLong()),
        )
        return lastPlaybackHeadFrames
    }

    private fun streamTimeUsAt(framePosition: Long, anchorStreamUs: Long): Long {
        val playedSinceAnchor = (framePosition - anchorSubmittedFrames)
            .coerceIn(0, (submittedFrames - anchorSubmittedFrames).coerceAtLeast(0))
        return anchorStreamUs + playedSinceAnchor * 1_000_000L / sampleRate
    }

    private fun extendPlaybackHead(playbackHeadFrames: Long): Long {
        val normalized = playbackHeadFrames and PLAYBACK_HEAD_POSITION_MASK
        if (normalized < previousPlaybackHead) playbackHeadWraps += PLAYBACK_HEAD_POSITION_WRAP
        previousPlaybackHead = normalized
        return playbackHeadWraps + normalized
    }
}

internal data class AudioPlaybackTiming(
    val positionUs: Long,
    val targetNanoTimeNs: Long,
)

internal data class AudioPlaybackSample(
    val streamTimeUs: Long,
    val nanoTimeNs: Long,
) {
    fun timingFor(
        targetStreamTimeUs: Long,
        nowNs: Long,
        queuedEndUs: Long,
    ): AudioPlaybackTiming {
        val elapsedUs = ((nowNs - nanoTimeNs).coerceAtLeast(0L) / 1_000L)
        val currentStreamTimeUs = (streamTimeUs + elapsedUs).coerceAtMost(queuedEndUs)
        return AudioPlaybackTiming(
            positionUs = currentStreamTimeUs,
            targetNanoTimeNs = nanoTimeNs + (targetStreamTimeUs - streamTimeUs) * 1_000L,
        )
    }
}

internal data class AudioTimelineDiscontinuity(
    val expectedTimestampUs: Long,
    val actualTimestampUs: Long,
) {
    val deltaUs: Long = actualTimestampUs - expectedTimestampUs
}

internal class AudioFrameTimeline(private val sampleRate: Int) {
    private var nextTimestampUs: Long? = null

    fun advance(timestampUs: Long, frameSamples: Int): AudioTimelineDiscontinuity? {
        require(frameSamples > 0)
        val durationUs = frameSamples * 1_000_000L / sampleRate
        val expectedTimestampUs = nextTimestampUs
        nextTimestampUs = timestampUs + durationUs
        if (expectedTimestampUs == null) return null

        val deltaUs = timestampUs - expectedTimestampUs
        val toleranceUs = maxOf(AUDIO_TIMESTAMP_TOLERANCE_US, durationUs / 2)
        return if (kotlin.math.abs(deltaUs) > toleranceUs) {
            AudioTimelineDiscontinuity(expectedTimestampUs, timestampUs)
        } else {
            null
        }
    }
}

private const val AUDIO_TIMESTAMP_MAX_AGE_NS = 5_000_000_000L
private const val AUDIO_TIMESTAMP_MAX_FUTURE_NS = 1_000_000_000L
private const val AUDIO_TIMESTAMP_TOLERANCE_US = 2_000L
