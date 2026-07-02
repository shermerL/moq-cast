package com.example.moqandroid.playback

import android.view.Surface
import kotlinx.coroutines.coroutineScope
import uniffi.moq.MoqBroadcastConsumer

class PlaybackPipeline(
    private val logTag: String,
    private val status: (PlayerState) -> Unit,
) {
    private val subscriptionManager = PlaybackSubscriptionManager()
    private val audioRunner = AudioPlaybackRunner(logTag)
    private val videoRenderer = VideoPlaybackRenderer(status)

    suspend fun play(
        broadcast: MoqBroadcastConsumer,
        surface: Surface,
        trackInfo: PlaybackTrackInfo,
    ) {
        val video = trackInfo.video

        status(PlayerState.Subscribing(video.name, video.video.codec))

        val subscriptions = subscriptionManager.subscribe(broadcast, trackInfo)

        coroutineScope {
            val audioJob = audioRunner.launchIn(this, trackInfo, subscriptions)

            try {
                videoRenderer.play(surface, trackInfo, subscriptions)
            } finally {
                status(PlayerState.Disconnecting)
                audioJob?.cancel()
                subscriptions.cancel()
            }
        }
    }
}
