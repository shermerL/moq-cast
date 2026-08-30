package com.example.moqandroid.playback

import android.util.Log
import com.example.moqandroid.catalog.AudioDecoderBackend
import com.example.moqandroid.catalog.decoderOutput
import uniffi.moq.MoqAudioConsumer
import uniffi.moq.MoqBroadcastConsumer
import uniffi.moq.MoqMediaConsumer
import uniffi.moq.MoqSubscription
import uniffi.moq.MoqTrackConsumer

class PlaybackSubscriptionManager(private val logTag: String) {
    suspend fun subscribe(
        broadcast: MoqBroadcastConsumer,
        trackInfo: PlaybackTrackInfo,
    ): PlaybackSubscriptions {
        val video = trackInfo.video
        val audio = trackInfo.audio
        val media = broadcast.subscribeMedia(
            video.name,
            video.video.container,
            MoqSubscription(maxAgeMs = 250uL),
        )
        val audioSubscription = audio?.let { track ->
            val clock = AudioPlaybackClock(track.sampleRate)
            when (track.decoderBackend) {
                AudioDecoderBackend.MoqNativeOpus -> DecodedOpusSubscription(
                    consumer = broadcast.decodeAudio(
                        track.name,
                        track.audio,
                        track.decoderOutput(),
                    ),
                    clock = clock,
                )
                AudioDecoderBackend.AndroidMediaCodecAac -> EncodedAacSubscription(
                    consumer = broadcast.subscribeMedia(
                        track.name,
                        track.audio.container,
                        MoqSubscription(maxAgeMs = 250uL),
                    ),
                    clock = clock,
                )
            }
        }
        val videoLayoutConsumer = trackInfo.videoLayoutTrackName?.let { trackName ->
            runCatching { broadcast.subscribeTrack(trackName, null) }
                .onFailure { error -> Log.w(logTag, "video layout control subscription failed", error) }
                .getOrNull()
        }

        return PlaybackSubscriptions(
            media = media,
            audio = audioSubscription,
            videoLayoutConsumer = videoLayoutConsumer,
        )
    }
}

class PlaybackSubscriptions(
    val media: MoqMediaConsumer,
    val audio: AudioPlaybackSubscription?,
    val videoLayoutConsumer: MoqTrackConsumer?,
) {
    val audioClock: AudioPlaybackClock?
        get() = audio?.clock

    fun cancel() {
        videoLayoutConsumer?.cancel()
        audio?.cancel()
        media.cancel()
    }
}

sealed interface AudioPlaybackSubscription {
    val clock: AudioPlaybackClock

    fun cancel()
}

data class DecodedOpusSubscription(
    val consumer: MoqAudioConsumer,
    override val clock: AudioPlaybackClock,
) : AudioPlaybackSubscription {
    override fun cancel() {
        consumer.cancel()
    }
}

data class EncodedAacSubscription(
    val consumer: MoqMediaConsumer,
    override val clock: AudioPlaybackClock,
) : AudioPlaybackSubscription {
    override fun cancel() {
        consumer.cancel()
    }
}
