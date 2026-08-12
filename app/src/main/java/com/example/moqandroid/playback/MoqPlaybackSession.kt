package com.example.moqandroid.playback

import android.view.Surface
import com.example.moqandroid.catalog.CodecPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.moq.MoqOriginConsumer
import uniffi.moq.MoqClient
import uniffi.moq.MoqOriginOptions
import uniffi.moq.MoqOriginProducer

class MoqPlaybackSession(
    private val logTag: String,
    private val status: (PlayerState) -> Unit,
) {
    private val catalogReader = PlaybackCatalogReader(logTag)
    private val trackSelector = PlaybackTrackSelector(logTag)
    private val pipeline = PlaybackPipeline(logTag, status)

    suspend fun playRelay(
        surface: Surface,
        relayUrl: String,
        broadcastName: String,
        codecPreference: CodecPreference,
    ) = withContext(Dispatchers.IO) {
        status(PlayerState.Connecting(relayUrl))

        MoqOriginProducer(MoqOriginOptions()).use { originProducer ->
            MoqClient().use { client ->
                client.setConsume(originProducer)

                client.connect(relayUrl).use { session ->
                    try {
                        originProducer.consume().use { originConsumer ->
                            playBroadcast(originConsumer, surface, broadcastName, codecPreference)
                        }
                    } finally {
                        session.shutdown()
                    }
                }
            }
        }
    }

    suspend fun playOrigin(
        originConsumer: MoqOriginConsumer,
        label: String,
        surface: Surface,
        broadcastName: String,
        codecPreference: CodecPreference,
    ) = withContext(Dispatchers.IO) {
        status(PlayerState.Connecting(label))
        playBroadcast(originConsumer, surface, broadcastName, codecPreference)
    }

    private suspend fun playBroadcast(
        originConsumer: MoqOriginConsumer,
        surface: Surface,
        broadcastName: String,
        codecPreference: CodecPreference,
    ) {
        status(PlayerState.WaitingBroadcast)
        originConsumer.announcedBroadcast(broadcastName).use { announced ->
            announced.available().use { broadcast ->
                status(PlayerState.ReadingCatalog)
                broadcast.subscribeCatalog().use { catalogConsumer ->
                    val catalog = catalogReader.readFirst(catalogConsumer, broadcastName)
                    val trackInfo = trackSelector.select(catalog, broadcastName, codecPreference)
                    pipeline.play(broadcast, surface, trackInfo, catalogConsumer)
                }
            }
        }
    }
}
