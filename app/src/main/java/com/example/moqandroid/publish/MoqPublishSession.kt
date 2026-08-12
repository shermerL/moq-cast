package com.example.moqandroid.publish

import android.util.Log
import com.example.moqandroid.publish.audio.AudioPublishSource
import com.example.moqandroid.publish.encoder.SurfaceVideoEncoder
import com.example.moqandroid.publish.file.CmafFilePublishSource
import com.example.moqandroid.protocol.MOQCAST_CATALOG_SECTION_NAME
import com.example.moqandroid.protocol.VIDEO_LAYOUT_TRACK_NAME
import com.example.moqandroid.protocol.videoLayoutCatalogSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import uniffi.moq.MoqBroadcastProducer
import uniffi.moq.MoqClient
import uniffi.moq.MoqDimensions
import uniffi.moq.MoqInit
import uniffi.moq.MoqOriginOptions
import uniffi.moq.MoqOriginProducer
import uniffi.moq.MoqVideoProperties

class MoqPublishSession(
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
            source.close()
        }
    }

    suspend fun publishFile(
        source: CmafFilePublishSource,
        broadcastName: String,
    ) {
        withBroadcast(broadcastName) { broadcast ->
            publishFileBroadcast(broadcast, source, broadcastName)
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
        val media = broadcast.publishMediaStream(
            MoqInit(format = "avc3", data = byteArrayOf(), video = null),
        )
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

            // Manual omission checks: comment out the default declaration above and enable one block at a time.
            // Test 1: omit video.display from the catalog.

//            val videoProperties = MoqVideoProperties(
//                display = null,
//                rotation = presentation.rotationDegrees.toDouble(),
//                flip = presentation.flip,
//            )


            // Test 2: omit video.rotation from the catalog.

//            val videoProperties = MoqVideoProperties(
//                display = display,
//                rotation = null,
//                flip = presentation.flip,
//            )


            // Test 3: omit video.flip from the catalog.

//            val videoProperties = MoqVideoProperties(
//                display = display,
//                rotation = presentation.rotationDegrees.toDouble(),
//                flip = null,
//            )

            broadcast.setVideoProperties(videoProperties)
            Log.i(
                LOG_TAG,
                "publishing video format=avc3 " +
                    "display=${presentation.displayWidth}x${presentation.displayHeight} " +
                    "rotation=${presentation.rotationDegrees} flip=${presentation.flip}",
            )
        } ?: Log.i(LOG_TAG, "publishing video format=avc3 catalogRotation=unset")

        val videoLayout = source.layoutTransitions?.let {
            broadcast.publishTrack(VIDEO_LAYOUT_TRACK_NAME, null).also {
                broadcast.setCatalogSection(MOQCAST_CATALOG_SECTION_NAME, videoLayoutCatalogSection())
            }
        }
        val audio = audioSource?.config?.let { audioConfig ->
            Log.i(
                LOG_TAG,
                "publishing audio track=0 codec=opus encoder=moq-native input=s16 " +
                    "sampleRate=${audioConfig.sampleRate} channels=${audioConfig.channelCount} " +
                    "bitrate=${audioConfig.bitrate} frameDurationMs=${audioConfig.frameDurationMs}",
            )
            broadcast.publishAudio("0", audioConfig.encoderInput(), audioConfig.encoderOutput())
        }

        try {
            coroutineScope {
                val audioJob = audio?.let { producer ->
                    launch {
                        runCatching {
                            audioSource.capture(producer)
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
                        }.also {
                            runCatching { producer.finish() }
                        }
                    }
                }

                try {
                    SurfaceVideoEncoder(
                        source = source,
                        media = media,
                        videoLayout = videoLayout,
                        connectionLabel = connectionLabel,
                        lifecycle = lifecycle,
                    ).run(config.video, broadcastName, audioSource?.config)
                } finally {
                    audioJob?.cancel()
                }
            }
        } finally {
            videoLayout?.let { runCatching { it.finish() } }
            runCatching { broadcast.finish() }
        }
    }

    private suspend fun publishFileBroadcast(
        broadcast: MoqBroadcastProducer,
        source: CmafFilePublishSource,
        broadcastName: String,
    ) {
        Log.i(LOG_TAG, "publishing file=${source.file.displayName} format=fmp4")
        val media = broadcast.publishMediaStream(
            MoqInit(format = "fmp4", data = byteArrayOf(), video = null),
        )
        source.presentation?.let { presentation ->
            broadcast.setVideoProperties(
                MoqVideoProperties(
                    display = MoqDimensions(
                        width = presentation.displayWidth.toUInt(),
                        height = presentation.displayHeight.toUInt(),
                    ),
                    rotation = presentation.rotationDegrees.toDouble(),
                    flip = presentation.flip,
                ),
            )
        }
        var mediaFinished = false
        try {
            source.publish(
                media = media,
                relayUrl = relayUrl,
                broadcastName = broadcastName,
                lifecycle = lifecycle,
            )
            media.finish()
            mediaFinished = true
        } finally {
            if (!mediaFinished) runCatching { media.finish() }
            media.close()
            runCatching { broadcast.finish() }
        }
    }

    companion object {
        private const val LOG_TAG = "MoqAndroid"
        private const val AUDIO_TRACK_NAME = "audio"
    }
}

data class PublishSessionConfig(
    val video: VideoPublishConfig,
)
