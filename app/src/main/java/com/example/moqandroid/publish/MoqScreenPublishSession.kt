package com.example.moqandroid.publish

import android.media.projection.MediaProjection
import uniffi.moq.MoqBroadcastProducer
import uniffi.moq.MoqClient
import uniffi.moq.MoqOriginProducer

class MoqScreenPublishSession(
    private val relayUrl: String,
    private val status: (PublishState) -> Unit,
) {
    suspend fun publish(
        projection: MediaProjection,
        broadcastName: String,
        config: ScreenVideoConfig,
    ) {
        status(PublishState.Preparing)

        MoqBroadcastProducer().use { broadcast ->
            val media = broadcast.publishMediaStream("avc3")
            MoqOriginProducer().use { origin ->
                origin.publish(broadcastName, broadcast)

                MoqClient().use { client ->
                    client.setPublish(origin)
                    status(PublishState.Connecting(relayUrl, broadcastName))
                    client.connect(relayUrl).use { session ->
                        try {
                            ScreenVideoEncoder(projection, media, relayUrl, status).run(config, broadcastName)
                        } finally {
                            runCatching { broadcast.finish() }
                            session.shutdown()
                        }
                    }
                }
            }
        }

        status(PublishState.Stopped)
    }
}
