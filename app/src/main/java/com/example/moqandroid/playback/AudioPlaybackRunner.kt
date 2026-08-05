package com.example.moqandroid.playback

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AudioPlaybackRunner(private val logTag: String) {
    fun launchIn(
        scope: CoroutineScope,
        trackInfo: PlaybackTrackInfo,
        subscriptions: PlaybackSubscriptions,
    ): Job? {
        val subscription = subscriptions.audio ?: return null
        val audioTrack = trackInfo.audio ?: error("audio consumer without audio track")

        return scope.launch {
            try {
                when (subscription) {
                    is DecodedOpusSubscription -> AudioPlayer(logTag).play(
                        subscription.consumer,
                        audioTrack,
                        subscription.clock,
                    )
                    is EncodedAacSubscription -> AacAudioPlayer(logTag).play(
                        subscription.consumer,
                        audioTrack,
                        subscription.clock,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(logTag, "audio playback failed", error)
                throw error
            }
        }
    }
}
