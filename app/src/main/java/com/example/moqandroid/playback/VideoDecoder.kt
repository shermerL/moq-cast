package com.example.moqandroid.playback

import android.media.MediaCodec
import com.example.moqandroid.catalog.PlayableVideoInfo
import com.example.moqandroid.media.AvcConfig
import com.example.moqandroid.media.payloadForDecoder
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import uniffi.moq.MoqMediaConsumer
import kotlin.coroutines.coroutineContext
import kotlin.math.min

class VideoDecoder(private val status: (PlayerState) -> Unit) {
    suspend fun decodeLoop(
        codec: MediaCodec,
        media: MoqMediaConsumer,
        avcConfig: AvcConfig?,
        videoInfo: PlayableVideoInfo,
        audioClock: AudioPlaybackClock?,
    ) = coroutineScope {
        val info = MediaCodec.BufferInfo()
        val stats = PlaybackStats(videoInfo)

        while (coroutineContext.isActive) {
            val frame = media.next() ?: break
            val payload = frame.payloadForDecoder(avcConfig)
            stats.onFrameReceived(payload.size)

            var queued = false
            while (!queued) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    codec.getInputBuffer(inputIndex)?.apply {
                        clear()
                        put(payload)
                    }
                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        payload.size,
                        frame.timestampUs.toLong(),
                        if (frame.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                    )
                    queued = true
                }
                stats.renderedFrames += drainDecoder(codec, info, audioClock)
            }

            stats.renderedFrames += drainDecoder(codec, info, audioClock)
            stats.flushIfDue { status(PlayerState.Stats(it)) }
        }
    }

    private fun drainDecoder(codec: MediaCodec, info: MediaCodec.BufferInfo, audioClock: AudioPlaybackClock?): Int {
        var rendered = 0
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return rendered
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> return rendered
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> return rendered
                else -> if (outputIndex >= 0) {
                    val decision = videoRenderDecision(info.presentationTimeUs, audioClock)
                    if (info.size > 0 && decision.render) {
                        if (decision.renderTimeNs != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                            codec.releaseOutputBuffer(outputIndex, decision.renderTimeNs)
                        } else {
                            codec.releaseOutputBuffer(outputIndex, true)
                        }
                        rendered += 1
                    } else {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }
    }

    private fun videoRenderDecision(presentationTimeUs: Long, audioClock: AudioPlaybackClock?): VideoRenderDecision {
        val audioTimeUs = audioClock?.positionUs() ?: return VideoRenderDecision(render = true)
        val deltaUs = presentationTimeUs - audioTimeUs
        if (deltaUs < -VIDEO_DROP_LATE_US) return VideoRenderDecision(render = false)

        if (deltaUs > VIDEO_RENDER_EARLY_US) {
            Thread.sleep(min((deltaUs - VIDEO_RENDER_EARLY_US) / 1_000, VIDEO_MAX_SLEEP_MS))
        }

        val refreshedAudioTimeUs = audioClock.positionUs() ?: audioTimeUs
        val refreshedDeltaUs = presentationTimeUs - refreshedAudioTimeUs
        if (refreshedDeltaUs < -VIDEO_DROP_LATE_US) return VideoRenderDecision(render = false)

        val renderTimeNs = audioClock.nanoTimeFor(presentationTimeUs)
        return VideoRenderDecision(render = true, renderTimeNs = renderTimeNs)
    }
}

private data class VideoRenderDecision(
    val render: Boolean,
    val renderTimeNs: Long? = null,
)
