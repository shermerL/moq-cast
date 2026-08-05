package com.example.moqandroid.playback

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.example.moqandroid.catalog.PlayableAudioTrack
import com.example.moqandroid.catalog.aacMediaFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import uniffi.moq.MoqMediaConsumer
import kotlin.coroutines.coroutineContext

class AacAudioPlayer(private val logTag: String) {
    suspend fun play(
        consumer: MoqMediaConsumer,
        audio: PlayableAudioTrack,
        clock: AudioPlaybackClock?,
    ) = withContext(Dispatchers.IO) {
        val codec = MediaCodec.createDecoderByType(AAC_MIME)
        val runtime = AacDecodeRuntime(logTag, codec, audio, clock)
        var codecStarted = false

        try {
            codec.configure(audio.aacMediaFormat(), null, null, 0)
            codec.start()
            codecStarted = true
            Log.i(
                logTag,
                "AAC decoder start track=${audio.name} codec=${audio.audio.codec} " +
                    "sampleRate=${audio.sampleRate} channels=${audio.channelCount} " +
                    "descriptionBytes=${audio.audio.description?.size ?: 0}",
            )

            var lastTimestampUs = 0L
            while (coroutineContext.isActive) {
                val frame = consumer.next() ?: break
                lastTimestampUs = frame.timestampUs.toLong()
                runtime.queueInput(frame.payload, lastTimestampUs) {
                    coroutineContext.isActive
                }
            }

            if (coroutineContext.isActive) {
                runtime.finish(lastTimestampUs) {
                    coroutineContext.isActive
                }
            }
        } catch (error: CancellationException) {
            throw error
        } finally {
            runtime.close()
            if (codecStarted) runCatching { codec.stop() }
            codec.release()
            Log.i(logTag, "AAC decoder released track=${audio.name}")
        }
    }
}

private class AacDecodeRuntime(
    private val logTag: String,
    private val codec: MediaCodec,
    private val audio: PlayableAudioTrack,
    private val clock: AudioPlaybackClock?,
) : AutoCloseable {
    private val info = MediaCodec.BufferInfo()
    private var renderer: PcmAudioRenderer? = null
    private var outputFormat: PcmAudioFormat? = null
    private var outputEnded = false

    fun queueInput(
        payload: ByteArray,
        timestampUs: Long,
        isActive: () -> Boolean,
    ) {
        while (true) {
            ensureActive(isActive)
            val inputIndex = codec.dequeueInputBuffer(CODEC_POLL_TIMEOUT_US)
            if (inputIndex >= 0) {
                val input = requireNotNull(codec.getInputBuffer(inputIndex)) {
                    "AAC decoder input buffer unavailable"
                }
                input.clear()
                require(payload.size <= input.remaining()) {
                    "AAC access unit is too large: ${payload.size} > ${input.remaining()}"
                }
                input.put(payload)
                codec.queueInputBuffer(inputIndex, 0, payload.size, timestampUs, 0)
                break
            }
            drainOutput(0)
        }
        drainOutput(0)
    }

    fun finish(
        timestampUs: Long,
        isActive: () -> Boolean,
    ) {
        while (true) {
            ensureActive(isActive)
            val inputIndex = codec.dequeueInputBuffer(CODEC_POLL_TIMEOUT_US)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    timestampUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                break
            }
            drainOutput(0)
        }

        while (!outputEnded) {
            ensureActive(isActive)
            drainOutput(CODEC_POLL_TIMEOUT_US)
        }
    }

    override fun close() {
        renderer?.close()
        renderer = null
    }

    private fun drainOutput(timeoutUs: Long) {
        var nextTimeoutUs = timeoutUs
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(info, nextTimeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    configureOutput(codec.outputFormat)
                    nextTimeoutUs = 0
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> nextTimeoutUs = 0
                else -> if (outputIndex >= 0) {
                    try {
                        val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && !codecConfig) {
                            val output = requireNotNull(codec.getOutputBuffer(outputIndex)) {
                                "AAC decoder output buffer unavailable"
                            }.duplicate().apply {
                                position(info.offset)
                                limit(info.offset + info.size)
                            }
                            val activeRenderer = requireNotNull(renderer) {
                                "AAC decoder produced PCM before its output format"
                            }
                            activeRenderer.write(output, info.presentationTimeUs)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEnded = true
                        }
                    } finally {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                    if (outputEnded) return
                    nextTimeoutUs = 0
                }
            }
        }
    }

    private fun configureOutput(mediaFormat: MediaFormat) {
        val sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val pcmEncoding = if (mediaFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            mediaFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        require(pcmEncoding == AudioFormat.ENCODING_PCM_16BIT) {
            "AAC decoder returned unsupported PCM encoding: $pcmEncoding"
        }

        val nextFormat = PcmAudioFormat(sampleRate, channelCount)
        val activeFormat = outputFormat
        require(activeFormat == null || activeFormat == nextFormat) {
            "AAC output format changed during playback: $activeFormat -> $nextFormat"
        }
        if (activeFormat != null) return

        outputFormat = nextFormat
        renderer = PcmAudioRenderer(
            logTag = logTag,
            audio = audio,
            format = nextFormat,
            decoderName = "android-mediacodec",
            clock = clock,
        )
        Log.i(
            logTag,
            "AAC decoder output track=${audio.name} sampleRate=$sampleRate " +
                "channels=$channelCount pcmEncoding=$pcmEncoding",
        )
    }

    private fun ensureActive(isActive: () -> Boolean) {
        if (!isActive()) throw CancellationException("AAC playback cancelled")
    }
}

private const val AAC_MIME = "audio/mp4a-latm"
private const val CODEC_POLL_TIMEOUT_US = 10_000L
