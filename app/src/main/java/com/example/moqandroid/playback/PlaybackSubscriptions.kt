package com.example.moqandroid.playback

import uniffi.moq.MoqAudioConsumer
import uniffi.moq.MoqBroadcastConsumer
import uniffi.moq.MoqMediaConsumer

class PlaybackSubscriptionManager {
    fun subscribe(
        broadcast: MoqBroadcastConsumer,
        trackInfo: PlaybackTrackInfo,
    ): PlaybackSubscriptions {
        val video = trackInfo.video
        val audio = trackInfo.audio
        val media = broadcast.subscribeMedia(video.name, video.video.container, 250uL)
        val audioClock = audio?.let { AudioPlaybackClock(it.sampleRate) }
        val audioConsumer = audio?.let { track ->
            val output = trackInfo.audioDecoderOutput
                ?: error("audio decoder output unavailable for ${track.name}")
            broadcast.subscribeAudio(track.name, track.audio, output)
        }

        return PlaybackSubscriptions(
            media = media,
            audioConsumer = audioConsumer,
            audioClock = audioClock,
        )
    }
}

class PlaybackSubscriptions(
    val media: MoqMediaConsumer,
    val audioConsumer: MoqAudioConsumer?,
    val audioClock: AudioPlaybackClock?,
) {
    fun cancel() {
        audioConsumer?.cancel()
        media.cancel()
    }
}
