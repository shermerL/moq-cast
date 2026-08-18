package com.example.moqandroid.publish.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import java.nio.ByteBuffer

internal sealed interface H264CodecOutput {
    data object TryAgain : H264CodecOutput

    data class FormatChanged(
        val codecConfig: ByteArray?,
        val width: Int?,
        val height: Int?,
        val spsBytes: Int,
        val ppsBytes: Int,
    ) : H264CodecOutput

    data class AccessUnit(
        val payload: ByteArray,
        val presentationTimeUs: Long,
        val keyFrame: Boolean,
    ) : H264CodecOutput
}

internal class H264CodecSession private constructor(
    private val codec: MediaCodec,
    val inputSurface: Surface,
) : AutoCloseable {
    private val bufferInfo = MediaCodec.BufferInfo()
    private var closed = false

    fun requestKeyFrame() {
        codec.setParameters(Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        })
    }

    fun dequeueOutput(timeoutUs: Long): H264CodecOutput {
        return when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)) {
            MediaCodec.INFO_TRY_AGAIN_LATER -> H264CodecOutput.TryAgain
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> codec.outputFormat.toOutputFormat()
            else -> {
                if (outputIndex < 0) return H264CodecOutput.TryAgain
                try {
                    H264CodecOutput.AccessUnit(
                        payload = codec.getOutputBuffer(outputIndex)?.readBytes(bufferInfo) ?: ByteArray(0),
                        presentationTimeUs = bufferInfo.presentationTimeUs,
                        keyFrame = bufferInfo.flags.hasFlag(MediaCodec.BUFFER_FLAG_KEY_FRAME),
                    )
                } finally {
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
    }

    companion object {
        fun open(attempt: EncoderAttempt): H264CodecSession {
            val codec = if (attempt.encoderName == UNKNOWN_ENCODER_NAME) {
                MediaCodec.createEncoderByType(MIME_AVC)
            } else {
                MediaCodec.createByCodecName(attempt.encoderName)
            }
            var inputSurface: Surface? = null
            try {
                codec.configure(attempt.mediaFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = codec.createInputSurface()
                codec.start()
                return H264CodecSession(codec, inputSurface)
            } catch (error: Throwable) {
                runCatching { inputSurface?.release() }
                runCatching { codec.release() }
                throw error
            }
        }

        private fun EncoderAttempt.mediaFormat(): MediaFormat {
            return MediaFormat.createVideoFormat(MIME_AVC, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSeconds)
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                profile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
            }
        }

        private fun MediaFormat.toOutputFormat(): H264CodecOutput.FormatChanged {
            val sps = getByteBuffer("csd-0")?.readRemainingBytes()
            val pps = getByteBuffer("csd-1")?.readRemainingBytes()
            val codecConfig = when {
                sps != null && pps != null -> sps.toAnnexB() + pps.toAnnexB()
                sps != null -> sps.toAnnexB()
                pps != null -> pps.toAnnexB()
                else -> null
            }
            return H264CodecOutput.FormatChanged(
                codecConfig = codecConfig,
                width = integerOrNull(MediaFormat.KEY_WIDTH),
                height = integerOrNull(MediaFormat.KEY_HEIGHT),
                spsBytes = sps?.size ?: 0,
                ppsBytes = pps?.size ?: 0,
            )
        }

        private fun MediaFormat.integerOrNull(key: String): Int? {
            return if (containsKey(key)) getInteger(key) else null
        }

        private fun ByteBuffer.readRemainingBytes(): ByteArray {
            val source = duplicate()
            return ByteArray(source.remaining()).also { source.get(it) }
        }

        private fun ByteBuffer.readBytes(info: MediaCodec.BufferInfo): ByteArray {
            val source = duplicate()
            source.position(info.offset)
            source.limit(info.offset + info.size)
            return ByteArray(info.size).also { source.get(it) }
        }

        private fun Int.hasFlag(flag: Int): Boolean = this and flag != 0

        private const val MIME_AVC = "video/avc"
        private const val UNKNOWN_ENCODER_NAME = "unknown"
    }
}
