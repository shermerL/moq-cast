package com.example.moqandroid.playback

import android.media.MediaCodec
import android.util.Log
import android.view.Surface
import com.example.moqandroid.catalog.CodecPreference
import com.example.moqandroid.catalog.PlayableVideoInfo
import com.example.moqandroid.catalog.decoderOutput
import com.example.moqandroid.catalog.describe
import com.example.moqandroid.catalog.displayHeightFor
import com.example.moqandroid.catalog.displayWidthFor
import com.example.moqandroid.catalog.mediaFormat
import com.example.moqandroid.catalog.preferenceRank
import com.example.moqandroid.catalog.toPlayableTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.moq.MoqClient
import uniffi.moq.MoqOriginProducer

class MoqPlaybackSession(
    private val relayUrl: String,
    private val logTag: String,
    private val status: (PlayerState) -> Unit,
) {
    suspend fun play(
        surface: Surface,
        broadcastName: String,
        codecPreference: CodecPreference,
    ) = withContext(Dispatchers.IO) {
        status(PlayerState.Connecting(relayUrl))

        MoqOriginProducer().use { originProducer ->
            MoqClient().use { client ->
                client.setConsume(originProducer)

                client.connect(relayUrl).use { session ->
                    status(PlayerState.WaitingBroadcast)

                    val originConsumer = originProducer.consume()
                    val broadcast = originConsumer.announcedBroadcast(broadcastName).available()

                    status(PlayerState.ReadingCatalog)
                    val catalog = broadcast.subscribeCatalog().use { catalogConsumer ->
                        catalogConsumer.next()
                    } ?: error("catalog ended before first update")
                    Log.i(logTag, catalog.describe(broadcastName))

                    val playableTracks = catalog.video.entries
                        .mapNotNull { (name, video) -> video.toPlayableTrack(name) }
                    val selectedVideo = playableTracks
                        .minByOrNull { it.preferenceRank(codecPreference) }
                        ?: error("catalog has no playable video tracks")
                    val audioTrack = catalog.audio.entries
                        .mapNotNull { (name, audio) -> audio.toPlayableTrack(name) }
                        .firstOrNull()

                    Log.i(
                        logTag,
                        "selected video=${selectedVideo.name} codec=${selectedVideo.video.codec} " +
                            "mime=${selectedVideo.mime} audio=${audioTrack?.name ?: "none"}",
                    )

                    status(PlayerState.Subscribing(selectedVideo.name, selectedVideo.video.codec))
                    val media = broadcast.subscribeMedia(selectedVideo.name, selectedVideo.video.container, 250uL)
                    val audioClock = audioTrack?.let { AudioPlaybackClock(it.sampleRate) }
                    val audioConsumer = audioTrack?.let { track ->
                        broadcast.subscribeAudio(track.name, track.audio, track.decoderOutput())
                    }

                    val codec = MediaCodec.createDecoderByType(selectedVideo.mime)
                    val videoInfo = PlayableVideoInfo(
                        broadcastName = broadcastName,
                        trackName = selectedVideo.name,
                        codec = selectedVideo.video.codec,
                        mime = selectedVideo.mime,
                        preference = codecPreference,
                        audioTrackName = audioTrack?.name,
                        displayWidth = catalog.displayWidthFor(selectedVideo.video),
                        displayHeight = catalog.displayHeightFor(selectedVideo.video),
                    )
                    var codecStarted = false

                    coroutineScope {
                        val audioJob = audioConsumer?.let { consumer ->
                            val track = audioTrack ?: error("audio consumer without audio track")
                            launch {
                                runCatching {
                                    AudioPlayer(logTag).play(consumer, track, audioClock)
                                }.onFailure { error ->
                                    Log.w(logTag, "audio playback failed", error)
                                }
                            }
                        }

                        try {
                            codec.configure(selectedVideo.video.mediaFormat(selectedVideo.mime, selectedVideo.avcConfig), surface, null, 0)
                            codec.start()
                            codecStarted = true

                            status(PlayerState.Playing(videoInfo))
                            VideoDecoder(status).decodeLoop(codec, media, selectedVideo.avcConfig, videoInfo, audioClock)
                            status(PlayerState.StreamEnded)
                        } finally {
                            status(PlayerState.Disconnecting)
                            audioJob?.cancel()
                            audioConsumer?.cancel()
                            media.cancel()
                            if (codecStarted) runCatching { codec.stop() }
                            codec.release()
                            session.shutdown()
                        }
                    }
                }
            }
        }
    }
}
