package com.example.moqandroid.publish.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import com.example.moqandroid.publish.PublisherEvent
import com.example.moqandroid.publish.PublisherLifecycleEventSink
import com.example.moqandroid.publish.PublisherState
import com.example.moqandroid.publish.VideoPublishConfig
import com.example.moqandroid.publish.VideoPublishSource
import com.example.moqandroid.publish.screen.SystemAudioConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import uniffi.moq.MoqMediaStreamProducer
import kotlin.coroutines.coroutineContext

class SurfaceVideoEncoder(
    private val source: VideoPublishSource,
    private val media: MoqMediaStreamProducer,
    private val relayUrl: String,
    private val lifecycle: PublisherLifecycleEventSink,
) {
    suspend fun run(
        config: VideoPublishConfig,
        broadcastName: String,
        audioConfig: SystemAudioConfig,
    ) = withContext(Dispatchers.Default) {
        val stats = PublishStatsTracker(relayUrl, broadcastName)

        try {
            runAttempts(config, broadcastName, audioConfig, stats)
        } finally {
            lifecycle.update(PublisherState.Stopping)
            runCatching { media.finish() }
        }
    }

    private suspend fun runAttempts(
        config: VideoPublishConfig,
        broadcastName: String,
        audioConfig: SystemAudioConfig,
        stats: PublishStatsTracker,
    ) {
        var lastError: Throwable? = null
        val attempts = config.encoderAttempts()
        attempts.forEachIndexed { index, attempt ->
            val isFallbackAvailable = index < attempts.lastIndex
            var codec: MediaCodec? = null
            var codecStarted = false
            var sourceAttached = false
            var encodingStarted = false
            var trackStarted = false

            try {
                codec = MediaCodec.createEncoderByType(MIME_AVC)
                codec.configure(attempt.mediaFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = codec.createInputSurface()
                codec.start()
                codecStarted = true

                source.attachEncoderSurface(inputSurface, attempt.config)
                sourceAttached = true
                codec.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })

                lifecycle.emit(PublisherEvent.TrackStarted(VIDEO_TRACK_NAME))
                trackStarted = true
                lifecycle.update(
                    PublisherState.Publishing(
                        relayUrl = relayUrl,
                        broadcastName = broadcastName,
                        width = attempt.config.width,
                        height = attempt.config.height,
                        bitrate = attempt.config.bitrate,
                        frameRate = attempt.config.frameRate,
                        audioEnabled = audioConfig is SystemAudioConfig.Enabled,
                    ),
                )
                encodingStarted = true
                drain(codec, media, stats, lifecycle)
                return
            } catch (error: Throwable) {
                if (encodingStarted && error !is CancellationException) {
                    lifecycle.emit(PublisherEvent.TrackError(VIDEO_TRACK_NAME, error.message ?: error::class.java.name))
                }
                if (error is CancellationException || encodingStarted || !isFallbackAvailable) throw error

                lastError = error

                Log.w(
                    LOG_TAG,
                    "H.264 encoder configure failed in compatibility mode, retrying with fallback " +
                        "width=${attempt.config.width} height=${attempt.config.height} " +
                        "fps=${attempt.config.frameRate} bitrate=${attempt.config.bitrate} " +
                        "profile=${attempt.profileName}",
                    error,
                )
            } finally {
                if (trackStarted) lifecycle.emit(PublisherEvent.TrackStopped(VIDEO_TRACK_NAME))
                if (sourceAttached) source.detachEncoderSurface()
                if (codecStarted) runCatching { codec?.stop() }
                codec?.release()
            }
        }

        throw lastError ?: IllegalStateException("H.264 encoder did not start.")
    }

    private fun VideoPublishConfig.encoderAttempts(): List<EncoderAttempt> {
        if (!compatibilityMode) return listOf(EncoderAttempt(this, profile = null, profileName = "default"))

        return listOf(
            EncoderAttempt(this, profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, profileName = "baseline"),
            EncoderAttempt(alignForFallback(), profile = null, profileName = "default"),
        )
    }

    private fun VideoPublishConfig.alignForFallback(): VideoPublishConfig {
        return copy(
            width = width.roundDownTo(FALLBACK_ALIGNMENT).coerceAtLeast(FALLBACK_ALIGNMENT),
            height = height.roundDownTo(FALLBACK_ALIGNMENT).coerceAtLeast(FALLBACK_ALIGNMENT),
        )
    }

    private fun EncoderAttempt.mediaFormat(): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_AVC, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSeconds)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            profile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
    }

    private fun Int.roundDownTo(alignment: Int): Int = this - (this % alignment)

    private suspend fun drain(
        codec: MediaCodec,
        media: MoqMediaStreamProducer,
        stats: PublishStatsTracker,
        lifecycle: PublisherLifecycleEventSink,
    ) {
        val info = MediaCodec.BufferInfo()
        var codecConfig: ByteArray? = null
        while (coroutineContext.isActive) {
            when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    codecConfig = codec.outputFormat.h264CodecConfig()
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    val payload = codec.getOutputBuffer(outputIndex)?.readBytes(info) ?: ByteArray(0)
                    val flags = info.flags
                    codec.releaseOutputBuffer(outputIndex, false)

                    if (payload.isNotEmpty()) {
                        val annexB = payload.toAnnexB()
                        if (flags.hasCodecConfig()) {
                            codecConfig = annexB
                            continue
                        }

                        val frame = if (flags.hasKeyFrame()) annexB.withParameterSets(codecConfig) else annexB
                        media.write(frame)
                        stats.onFrame(frame.size, lifecycle::emit)
                    }
                }
            }
        }
    }

    private fun java.nio.ByteBuffer.readBytes(info: MediaCodec.BufferInfo): ByteArray {
        position(info.offset)
        limit(info.offset + info.size)
        return ByteArray(info.size).also { get(it) }
    }

    private fun MediaFormat.h264CodecConfig(): ByteArray? {
        val sps = getByteBuffer("csd-0")?.readRemainingBytes()?.toAnnexB()
        val pps = getByteBuffer("csd-1")?.readRemainingBytes()?.toAnnexB()
        return when {
            sps != null && pps != null -> sps + pps
            sps != null -> sps
            pps != null -> pps
            else -> null
        }
    }

    private fun java.nio.ByteBuffer.readRemainingBytes(): ByteArray {
        val duplicate = duplicate()
        return ByteArray(duplicate.remaining()).also { duplicate.get(it) }
    }

    private fun Int.hasCodecConfig(): Boolean {
        return this and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
    }

    private fun Int.hasKeyFrame(): Boolean {
        return this and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
    }

    companion object {
        private const val LOG_TAG = "MoqAndroid"
        private const val MIME_AVC = "video/avc"
        private const val FALLBACK_ALIGNMENT = 32
        private const val VIDEO_TRACK_NAME = "video"
    }
}

private data class EncoderAttempt(
    val config: VideoPublishConfig,
    val profile: Int?,
    val profileName: String,
)
