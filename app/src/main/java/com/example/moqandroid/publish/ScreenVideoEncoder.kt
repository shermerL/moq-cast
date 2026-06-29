package com.example.moqandroid.publish

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import uniffi.moq.MoqMediaStreamProducer
import kotlin.coroutines.coroutineContext

class ScreenVideoEncoder(
    private val projection: MediaProjection,
    private val media: MoqMediaStreamProducer,
    private val relayUrl: String,
    private val status: (PublishState) -> Unit,
) {
    suspend fun run(
        config: ScreenVideoConfig,
        broadcastName: String,
        audioConfig: SystemAudioConfig,
    ) = withContext(Dispatchers.Default) {
        val codec = MediaCodec.createEncoderByType(MIME_AVC)
        var virtualDisplay: VirtualDisplay? = null
        var codecStarted = false
        val stats = PublishStats(relayUrl, broadcastName)

        try {
            val format = MediaFormat.createVideoFormat(MIME_AVC, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSeconds)
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()
            codec.start()
            codecStarted = true

            virtualDisplay = projection.createVirtualDisplay(
                "MoqScreenPublish",
                config.width,
                config.height,
                config.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null,
            )
            codec.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })

            status(
                PublishState.Publishing(
                    relayUrl = relayUrl,
                    broadcastName = broadcastName,
                    width = config.width,
                    height = config.height,
                    bitrate = config.bitrate,
                    frameRate = config.frameRate,
                    audioEnabled = audioConfig is SystemAudioConfig.Enabled,
                ),
            )
            drain(codec, media, stats, status)
        } finally {
            status(PublishState.Stopping)
            virtualDisplay?.release()
            if (codecStarted) runCatching { codec.stop() }
            codec.release()
            runCatching { media.finish() }
        }
    }

    private suspend fun drain(
        codec: MediaCodec,
        media: MoqMediaStreamProducer,
        stats: PublishStats,
        status: (PublishState) -> Unit,
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
                        stats.onFrame(frame.size) { status(it) }
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
        private const val MIME_AVC = "video/avc"
    }
}

data class ScreenVideoConfig(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val bitrate: Int = 4_000_000,
    val frameRate: Int = 30,
    val iFrameIntervalSeconds: Int = 1,
)

private class PublishStats(
    private val relayUrl: String,
    private val broadcastName: String,
) {
    private var frames = 0
    private var bytes = 0L
    private var lastFrames = 0
    private var lastBytes = 0L
    private var lastUpdateMs = SystemClock.elapsedRealtime()

    fun onFrame(size: Int, emit: (PublishState) -> Unit) {
        frames += 1
        bytes += size

        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - lastUpdateMs
        if (elapsedMs < 1_000) return

        val seconds = elapsedMs / 1_000.0
        val kbps = ((bytes - lastBytes) * 8.0 / 1_000.0) / seconds
        emit(PublishState.Stats(relayUrl, broadcastName, frames - lastFrames, bytes, kbps))

        lastUpdateMs = now
        lastFrames = frames
        lastBytes = bytes
    }
}
