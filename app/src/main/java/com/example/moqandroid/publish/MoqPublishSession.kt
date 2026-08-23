package com.example.moqandroid.publish

import android.util.Log
import com.example.moqandroid.publish.audio.AudioPublishSource
import com.example.moqandroid.publish.encoder.SurfaceVideoEncoder
import com.example.moqandroid.protocol.MOQCAST_CATALOG_SECTION_NAME
import com.example.moqandroid.protocol.VIDEO_LAYOUT_TRACK_NAME
import com.example.moqandroid.protocol.videoLayoutCatalogSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.moq.MoqAudioProducer
import uniffi.moq.MoqBroadcastProducer
import uniffi.moq.MoqClient
import uniffi.moq.MoqDimensions
import uniffi.moq.MoqInit
import uniffi.moq.MoqOriginOptions
import uniffi.moq.MoqOriginProducer
import uniffi.moq.MoqTrackProducer
import uniffi.moq.MoqVideoProperties

internal class MoqPublishSession(
    private val relayUrl: String,
    private val tlsFingerprints: List<String> = emptyList(),
    private val connectionLabel: String = relayUrl,
    private val sharedOrigin: MoqOriginProducer? = null,
    private val lifecycle: PublisherLifecycleEventSink,
) {
    suspend fun publish(
        source: VideoPublishSource,
        broadcastName: String,
        config: PublishSessionConfig,
        audioSource: AudioPublishSource? = null,
    ) {
        try {
            withBroadcast(broadcastName) { broadcast ->
                publishBroadcast(broadcast, source, broadcastName, config, audioSource)
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { source.close() }
                    .onFailure { Log.w(LOG_TAG, "failed to close ${source.label} publish source", it) }
            }
        }
    }

    private suspend fun withBroadcast(
        broadcastName: String,
        publish: suspend (MoqBroadcastProducer) -> Unit,
    ) {
        lifecycle.update(PublisherState.Preparing)
        sharedOrigin?.let { origin ->
            lifecycle.update(PublisherState.Connecting(connectionLabel, broadcastName))
            origin.createBroadcast(broadcastName).use { broadcast -> publish(broadcast) }
            lifecycle.update(PublisherState.Stopped)
            return
        }

        MoqOriginProducer(MoqOriginOptions()).use { origin ->
            MoqClient().use { client ->
                if (tlsFingerprints.isNotEmpty()) client.setTlsFingerprints(tlsFingerprints)
                client.setPublish(origin)
                lifecycle.update(PublisherState.Connecting(connectionLabel, broadcastName))
                client.connect(relayUrl).use { session ->
                    try {
                        origin.createBroadcast(broadcastName).use { broadcast -> publish(broadcast) }
                    } finally {
                        session.shutdown()
                    }
                }
            }
        }
        lifecycle.update(PublisherState.Stopped)
    }

    private suspend fun publishBroadcast(
        broadcast: MoqBroadcastProducer,
        source: VideoPublishSource,
        broadcastName: String,
        config: PublishSessionConfig,
        audioSource: AudioPublishSource?,
    ) {
        val timeline = PublishTimeline()
        val media = broadcast.publishMedia(
            MoqInit(format = "avc3", data = byteArrayOf(), video = null),
        )
        var videoLayout: MoqTrackProducer? = null
        var audio: MoqAudioProducer? = null
        try {
            source.presentation?.let { presentation ->
                val display = MoqDimensions(
                    width = presentation.displayWidth.toUInt(),
                    height = presentation.displayHeight.toUInt(),
                )
                val videoProperties = MoqVideoProperties(
                    display = display,
                    rotation = presentation.rotationDegrees.toDouble(),
                    flip = presentation.flip,
                )
                broadcast.setVideoProperties(videoProperties)
                Log.i(
                    LOG_TAG,
                    "publishing video format=avc3 " +
                        "display=${presentation.displayWidth}x${presentation.displayHeight} " +
                        "rotation=${presentation.rotationDegrees} flip=${presentation.flip}",
                )
            } ?: Log.i(LOG_TAG, "publishing video format=avc3 catalogRotation=unset")

            videoLayout = source.layoutTransitions?.let {
                broadcast.publishTrack(VIDEO_LAYOUT_TRACK_NAME, null).also {
                    broadcast.setCatalogSection(MOQCAST_CATALOG_SECTION_NAME, videoLayoutCatalogSection())
                }
            }
            audio = audioSource?.config?.let { audioConfig ->
                Log.i(
                    LOG_TAG,
                    "publishing audio track=0 codec=opus encoder=moq-native input=s16 " +
                        "sampleRate=${audioConfig.sampleRate} channels=${audioConfig.channelCount} " +
                        "bitrate=${audioConfig.bitrate} frameDurationMs=${audioConfig.frameDurationMs}",
                )
                broadcast.publishAudio("0", audioConfig.encoderInput(), audioConfig.encoderOutput())
            }

            coroutineScope {
                val audioJob = audioSource?.let { source ->
                    audio?.let { producer ->
                        launch {
                            runCatching {
                                source.capture(producer, timeline)
                            }.onFailure { error ->
                                if (error !is CancellationException) {
                                    Log.w(LOG_TAG, "audio capture failed", error)
                                    lifecycle.emit(
                                        PublisherEvent.TrackError(
                                            name = AUDIO_TRACK_NAME,
                                            reason = error.message ?: error::class.java.name,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                try {
                    SurfaceVideoEncoder(
                        source = source,
                        media = media,
                        videoLayout = videoLayout,
                        timeline = timeline,
                        connectionLabel = connectionLabel,
                        lifecycle = lifecycle,
                    ).run(config.video, broadcastName, audioSource?.config)
                } finally {
                    withContext(NonCancellable) {
                        audioJob?.cancelAndJoin()
                    }
                }
            }
        } finally {
            audio?.let { finishAndClose("audio", it, it::finish) }
            videoLayout?.let { finishAndClose("video layout", it, it::finish) }
            finishAndClose("video", media, media::finish)
            runCatching { broadcast.finish() }
        }
    }

    private inline fun finishAndClose(
        label: String,
        producer: AutoCloseable,
        finish: () -> Unit,
    ) {
        runCatching(finish)
            .onFailure { Log.w(LOG_TAG, "failed to finish $label producer", it) }
        runCatching { producer.close() }
            .onFailure { Log.w(LOG_TAG, "failed to close $label producer", it) }
    }

    companion object {
        private const val LOG_TAG = "MoqAndroid"
        private const val AUDIO_TRACK_NAME = "audio"
    }
}

data class PublishSessionConfig(
    val video: VideoPublishConfig,
)
