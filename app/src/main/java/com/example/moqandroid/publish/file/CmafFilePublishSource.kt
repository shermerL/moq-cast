package com.example.moqandroid.publish.file

import android.content.Context
import android.os.SystemClock
import com.example.moqandroid.publish.PublisherEvent
import com.example.moqandroid.publish.PublisherLifecycleEventSink
import com.example.moqandroid.publish.PublisherState
import uniffi.moq.MoqMediaStreamProducer

class CmafFilePublishSource(
    private val context: Context,
    val file: ProbedPublishFile,
) {
    suspend fun publish(
        media: MoqMediaStreamProducer,
        relayUrl: String,
        broadcastName: String,
        lifecycle: PublisherLifecycleEventSink,
    ) {
        require(file.compatibility == PublishFileCompatibility.DirectFmp4) {
            "${file.displayName} is not compatible with direct CMAF publishing."
        }
        val video = file.tracks.single { it.kind == PublishFileTrackKind.Video }
        val audioEnabled = file.tracks.any { it.kind == PublishFileTrackKind.Audio }
        val input = context.contentResolver.openInputStream(file.uri)
            ?: error("Android could not open ${file.displayName}.")
        val pacer = CmafPublishPacer()
        var started = false
        var fragments = 0
        var bytes = 0L
        var lastFragments = 0
        var lastBytes = 0L
        var lastUpdateMs = SystemClock.elapsedRealtime()

        input.use {
            CmafStreamReader(it).stream(
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
        }

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
    }
}
