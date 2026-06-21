package com.example.moqandroid.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log
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
        val minBuffer = AudioTrack.getMinBufferSize(audio.sampleRate, audio.channelMask, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuffer > 0) { "Android cannot create audio buffer for ${audio.sampleRate}Hz/${audio.channelCount}ch" }

        val bufferSize = maxOf(minBuffer, audio.bytesPerSecond / 5)
        val track = createTrack(audio, bufferSize)

        try {
            clock?.bind(track)
            track.play()
            coroutineScope {
                while (coroutineContext.isActive) {
                    val frame = consumer.next() ?: break
                    val data = frame.data
                    val frameSamples = data.size / audio.bytesPerSampleFrame
                    clock?.queueFrame(frame.timestampUs.toLong(), frameSamples)

                    var offset = 0
                    while (offset < data.size && coroutineContext.isActive) {
                        val written = track.write(data, offset, data.size - offset)
                        if (written < 0) error("AudioTrack.write failed: $written")
                        offset += written
                    }
                    clock?.commitFrame(track.playbackHeadPosition.toLong())
                    Log.d(logTag, "audio frame ${audio.name} bytes=${data.size} ts=${frame.timestampUs}")
                }
            }
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
        }
    }

    private fun createTrack(audio: PlayableAudioTrack, bufferSize: Int): AudioTrack {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(audio.sampleRate)
                        .setChannelMask(audio.channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                audio.sampleRate,
                audio.channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM,
            )
        }
    }
}

class AudioPlaybackClock(private val sampleRate: Int) {
    private val lock = Any()
    private val timestamp = AudioTimestamp()
    private var track: AudioTrack? = null
    private var anchorStreamUs: Long? = null
    private var anchorSubmittedFrames = 0L
    private var submittedFrames = 0L
    private var lastPlaybackHeadFrames = 0L
    private var playbackHeadWraps = 0L
    private var previousPlaybackHead = 0L

    fun bind(track: AudioTrack) = synchronized(lock) {
        this.track = track
    }

    fun queueFrame(timestampUs: Long, frameSamples: Int) = synchronized(lock) {
        if (anchorStreamUs == null) {
            anchorStreamUs = timestampUs
            anchorSubmittedFrames = submittedFrames
        }
        submittedFrames += frameSamples
    }

    fun commitFrame(playbackHeadFrames: Long) = synchronized(lock) {
        val extendedPlaybackHead = extendPlaybackHead(playbackHeadFrames)
        lastPlaybackHeadFrames = maxOf(lastPlaybackHeadFrames, extendedPlaybackHead)
    }

    fun positionUs(): Long? = synchronized(lock) {
        val anchor = anchorStreamUs ?: return null
        val playedSinceAnchor = (currentPlaybackFramesLocked() - anchorSubmittedFrames)
            .coerceIn(0, (submittedFrames - anchorSubmittedFrames).coerceAtLeast(0))
        anchor + playedSinceAnchor * 1_000_000L / sampleRate
    }

    fun nanoTimeFor(streamTimeUs: Long): Long? = synchronized(lock) {
        val anchor = anchorStreamUs ?: return null
        val currentFrames = currentPlaybackFramesLocked()
        val currentStreamUs = anchor + (currentFrames - anchorSubmittedFrames).coerceAtLeast(0) * 1_000_000L / sampleRate
        return System.nanoTime() + (streamTimeUs - currentStreamUs) * 1_000L
    }

    private fun currentPlaybackFramesLocked(): Long {
        val boundTrack = track
        if (boundTrack != null && boundTrack.getTimestamp(timestamp)) {
            lastPlaybackHeadFrames = maxOf(lastPlaybackHeadFrames, timestamp.framePosition)
            return lastPlaybackHeadFrames
        }

        boundTrack?.let {
            lastPlaybackHeadFrames = maxOf(lastPlaybackHeadFrames, extendPlaybackHead(it.playbackHeadPosition.toLong()))
        }

        return lastPlaybackHeadFrames
    }

    private fun extendPlaybackHead(playbackHeadFrames: Long): Long {
        val normalized = playbackHeadFrames and PLAYBACK_HEAD_POSITION_MASK
        if (normalized < previousPlaybackHead) playbackHeadWraps += PLAYBACK_HEAD_POSITION_WRAP
        previousPlaybackHead = normalized
        return playbackHeadWraps + normalized
    }
}
