package com.example.moqandroid.publish.file

import android.content.Context
import android.os.SystemClock
import com.example.moqandroid.publish.PublisherEvent
import com.example.moqandroid.publish.PublisherLifecycleEventSink
import com.example.moqandroid.publish.PublisherState
import com.example.moqandroid.publish.VideoPublishPresentation
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.channels.Channels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import uniffi.moq.MoqMediaStreamProducer

class CmafFilePublishSource(
    private val context: Context,
    val file: ProbedPublishFile,
) {
    val presentation: VideoPublishPresentation?
        get() = file.videoPresentation()

    suspend fun publish(
        media: MoqMediaStreamProducer,
        relayUrl: String,
        broadcastName: String,
        lifecycle: PublisherLifecycleEventSink,
    ) {
        require(file.compatibility != PublishFileCompatibility.Unsupported) {
            "${file.displayName} is not compatible with CMAF publishing."
        }
        when (file.compatibility) {
            PublishFileCompatibility.DirectFmp4 -> {
                val input = context.contentResolver.openInputStream(file.uri)
                    ?: error("Android could not open ${file.displayName}.")
                input.use {
                    publishStream(
                        input = it,
                        media = media,
                        relayUrl = relayUrl,
                        broadcastName = broadcastName,
                        lifecycle = lifecycle,
                        fragmentMode = CmafFragmentMode.Preserve,
                    )
                }
            }
            PublishFileCompatibility.NeedsRemux -> publishRemuxed(
                media,
                relayUrl,
                broadcastName,
                lifecycle,
            )
            PublishFileCompatibility.Unsupported -> error("Unsupported file publish path.")
        }
    }

    private suspend fun publishRemuxed(
        media: MoqMediaStreamProducer,
        relayUrl: String,
        broadcastName: String,
        lifecycle: PublisherLifecycleEventSink,
    ) = coroutineScope {
        val input = PipedInputStream(PIPE_BUFFER_BYTES)
        val output = PipedOutputStream(input)
        val transmux = async(Dispatchers.IO) {
            output.use {
                Mp4FileTransmuxer(context).transmux(file, Channels.newChannel(it))
            }
        }
        transmux.invokeOnCompletion { error ->
            if (error != null) runCatching { input.close() }
        }
        try {
            input.use {
                publishStream(
                    input = it,
                    media = media,
                    relayUrl = relayUrl,
                    broadcastName = broadcastName,
                    lifecycle = lifecycle,
                    fragmentMode = CmafFragmentMode.RefragmentSamples,
                )
            }
            transmux.await()
        } catch (error: Throwable) {
            val remuxError = if (transmux.isCompleted) {
                runCatching { transmux.await() }.exceptionOrNull()
            } else {
                null
            }
            throw remuxError ?: error
        } finally {
            input.close()
            transmux.cancel()
        }
    }

    private suspend fun publishStream(
        input: InputStream,
        media: MoqMediaStreamProducer,
        relayUrl: String,
        broadcastName: String,
        lifecycle: PublisherLifecycleEventSink,
        fragmentMode: CmafFragmentMode,
    ) {
        val video = file.tracks.single { it.kind == PublishFileTrackKind.Video }
        val audioEnabled = file.tracks.any { it.kind == PublishFileTrackKind.Audio }
        val pacer = CmafPublishPacer()
        var started = false
        var fragments = 0
        var bytes = 0L
        var lastFragments = 0
        var lastBytes = 0L
        var lastUpdateMs = SystemClock.elapsedRealtime()

        CmafStreamReader(input, fragmentMode).stream(
            beforeFragment = { timestampUs ->
                pacer.await(timestampUs)
                fragments += 1
                if (!started) {
                    lifecycle.update(
                        PublisherState.Publishing(
                            relayUrl = relayUrl,
                            broadcastName = broadcastName,
                            width = video.width ?: 0,
                            height = video.height ?: 0,
                            bitrate = video.bitrate ?: file.estimatedBitrate(),
                            frameRate = video.frameRate ?: 0,
                            audioEnabled = audioEnabled,
                        ),
                    )
                    lifecycle.emit(PublisherEvent.TrackStarted(VIDEO_TRACK_NAME))
                    if (audioEnabled) lifecycle.emit(PublisherEvent.TrackStarted(AUDIO_TRACK_NAME))
                    started = true
                }
            },
            write = { payload ->
                media.write(payload)
                bytes += payload.size
                val now = SystemClock.elapsedRealtime()
                val elapsedMs = now - lastUpdateMs
                if (elapsedMs >= 1_000) {
                    val kbps = ((bytes - lastBytes) * 8.0) / elapsedMs
                    lifecycle.emit(
                        PublisherEvent.StatsUpdated(
                            relayUrl = relayUrl,
                            broadcastName = broadcastName,
                            frames = fragments - lastFragments,
                            bytes = bytes,
                            bitrateKbps = kbps,
                        ),
                    )
                    lastUpdateMs = now
                    lastFragments = fragments
                    lastBytes = bytes
                }
            },
        )

        lifecycle.emit(PublisherEvent.TrackStopped(VIDEO_TRACK_NAME))
        if (audioEnabled) lifecycle.emit(PublisherEvent.TrackStopped(AUDIO_TRACK_NAME))
    }

    private fun ProbedPublishFile.estimatedBitrate(): Int {
        val duration = durationUs?.takeIf { it > 0 } ?: return 0
        val size = sizeBytes?.takeIf { it > 0 } ?: return 0
        return ((size.toDouble() * 8 * 1_000_000 / duration).coerceAtMost(Int.MAX_VALUE.toDouble())).toInt()
    }

    private companion object {
        private const val VIDEO_TRACK_NAME = "video"
        private const val AUDIO_TRACK_NAME = "audio"
        private const val PIPE_BUFFER_BYTES = 512 * 1024
    }
}

internal fun ProbedPublishFile.videoPresentation(): VideoPublishPresentation? {
    return tracks.singleOrNull { it.kind == PublishFileTrackKind.Video }?.videoPresentation()
}

internal fun PublishFileTrack.videoPresentation(): VideoPublishPresentation? {
    val width = width ?: return null
    val height = height ?: return null
    val rotation = normalizedRotation(rotationDegrees ?: 0)
    val swapDimensions = rotation == 90 || rotation == 270
    return VideoPublishPresentation(
        displayWidth = if (swapDimensions) height else width,
        displayHeight = if (swapDimensions) width else height,
        rotationDegrees = rotation,
    )
}

internal fun normalizedRotation(rotation: Int): Int {
    val normalized = ((rotation % 360) + 360) % 360
    return ((normalized + 45) / 90 % 4) * 90
}
