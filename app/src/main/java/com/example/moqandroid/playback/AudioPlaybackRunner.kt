package com.example.moqandroid.playback

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AudioPlaybackRunner(private val logTag: String) {
    fun launchIn(
        scope: CoroutineScope,
        trackInfo: PlaybackTrackInfo,
        subscriptions: PlaybackSubscriptions,
    ): Job? {
        val consumer = subscriptions.audioConsumer ?: return null
        val audioTrack = trackInfo.audio ?: error("audio consumer without audio track")

        return scope.launch {
            runCatching {
                AudioPlayer(logTag).play(consumer, audioTrack, subscriptions.audioClock)
            }.onFailure { error ->
                Log.w(logTag, "audio playback failed", error)
            }
        }
    }
}
