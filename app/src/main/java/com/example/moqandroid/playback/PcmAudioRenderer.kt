package com.example.moqandroid.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.example.moqandroid.catalog.PlayableAudioTrack
import java.nio.ByteBuffer

internal data class PcmAudioFormat(
    val sampleRate: Int,
    val channelCount: Int,
) {
    val channelMask: Int = when (channelCount) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        else -> error("Android PCM playback only supports mono or stereo, got $channelCount channels")
    }
    val bytesPerSampleFrame: Int = channelCount * 2
    val bytesPerSecond: Int = sampleRate * bytesPerSampleFrame
}

internal class PcmAudioRenderer(
    private val logTag: String,
    private val audio: PlayableAudioTrack,
    private val format: PcmAudioFormat,
    private val decoderName: String,
    private val clock: AudioPlaybackClock?,
) : AutoCloseable {
    private val track: AudioTrack
    private val stats = AudioRenderStats(logTag, audio)
    private val timeline = AudioFrameTimeline(format.sampleRate)

    init {
        val minBuffer = AudioTrack.getMinBufferSize(
            format.sampleRate,
            format.channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) {
            "Android cannot create audio buffer for ${format.sampleRate}Hz/${format.channelCount}ch"
        }

        val bufferSize = maxOf(minBuffer, format.bytesPerSecond / 5)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(format.channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        clock?.bind(track, format.sampleRate)
        Log.i(
            logTag,
            "audio playback start track=${audio.name} codec=${audio.audio.codec} " +
                "decoder=$decoderName output=s16 renderer=AudioTrack " +
                "sampleRate=${format.sampleRate} channels=${format.channelCount} bufferSize=$bufferSize",
        )
        track.play()
    }

    fun write(data: ByteArray, timestampUs: Long) {
        if (data.isEmpty()) return
        require(data.size % format.bytesPerSampleFrame == 0) {
            "PCM buffer is not aligned to ${format.bytesPerSampleFrame}-byte sample frames"
        }
        val frameSamples = data.size / format.bytesPerSampleFrame
        handleTimeline(timestampUs, frameSamples)
        clock?.queueFrame(timestampUs, frameSamples)

        var offset = 0
        while (offset < data.size) {
            val written = track.write(data, offset, data.size - offset, AudioTrack.WRITE_BLOCKING)
            checkAudioWrite(written)
            offset += written
        }
        onFrameWritten(data.size, timestampUs)
    }

    fun write(data: ByteBuffer, timestampUs: Long) {
        val size = data.remaining()
        if (size == 0) return
        require(size % format.bytesPerSampleFrame == 0) {
            "PCM buffer is not aligned to ${format.bytesPerSampleFrame}-byte sample frames"
        }
        val frameSamples = size / format.bytesPerSampleFrame
        handleTimeline(timestampUs, frameSamples)
        clock?.queueFrame(timestampUs, frameSamples)

        while (data.hasRemaining()) {
            val written = track.write(data, data.remaining(), AudioTrack.WRITE_BLOCKING)
            checkAudioWrite(written)
        }
        onFrameWritten(size, timestampUs)
    }

    override fun close() {
        clock?.unbind(track)
        runCatching { track.pause() }
        runCatching { track.flush() }
        track.release()
    }

    private fun onFrameWritten(size: Int, timestampUs: Long) {
        val playbackHeadFrames = track.playbackHeadPosition.toLong()
        clock?.commitFrame(playbackHeadFrames)
        stats.onFrame(size, timestampUs, playbackHeadFrames)
    }

    private fun handleTimeline(timestampUs: Long, frameSamples: Int) {
        val discontinuity = timeline.advance(timestampUs, frameSamples) ?: return
        Log.w(
            logTag,
            "audio timeline discontinuity track=${audio.name} " +
                "expectedTsUs=${discontinuity.expectedTimestampUs} actualTsUs=${discontinuity.actualTimestampUs} " +
                "deltaUs=${discontinuity.deltaUs} action=flush-reanchor",
        )
        track.pause()
        track.flush()
        clock?.reanchor(track, timestampUs)
        track.play()
    }

    private fun checkAudioWrite(written: Int) {
        if (written < 0) error("AudioTrack.write failed: $written")
        if (written == 0) error("AudioTrack.write made no progress")
    }
}

private class AudioRenderStats(
    private val logTag: String,
    private val audio: PlayableAudioTrack,
) {
    private var frames = 0
    private var bytes = 0L
    private var lastTimestampUs = 0L
    private var lastPlaybackHeadFrames = 0L
    private var lastUpdateMs = SystemClock.elapsedRealtime()

    fun onFrame(size: Int, timestampUs: Long, playbackHeadFrames: Long) {
        frames += 1
        bytes += size
        lastTimestampUs = timestampUs
        lastPlaybackHeadFrames = playbackHeadFrames

        val now = SystemClock.elapsedRealtime()
        if (now - lastUpdateMs < 1_000) return

        Log.i(
            logTag,
            "audio render track=${audio.name} frames=$frames bytes=$bytes " +
                "streamTsUs=$lastTimestampUs playbackHeadFrames=$lastPlaybackHeadFrames",
        )

        frames = 0
        bytes = 0
        lastUpdateMs = now
    }
}
