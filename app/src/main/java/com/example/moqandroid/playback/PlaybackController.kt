package com.example.moqandroid.playback

import android.util.Log
import android.view.Surface
import com.example.moqandroid.catalog.CodecPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.moq.MoqOriginConsumer

class PlaybackController(
    private val scope: CoroutineScope,
    private val logTag: String,
) {
    private var playbackJob: Job? = null
    private var playbackSessionId = 0

    fun start(
        surface: Surface,
        relayUrl: String,
        broadcastName: String,
        onPlayerState: (PlayerState, String) -> Unit,
    ) {
        startInternal(surface, broadcastName, onPlayerState) { playback ->
            playback.playRelay(surface, relayUrl, broadcastName, CodecPreference.Auto)
        }
    }

    fun startPeer(
        surface: Surface,
        originConsumer: MoqOriginConsumer,
        peerName: String,
        broadcastName: String,
        onPlayerState: (PlayerState, String) -> Unit,
    ) {
        startInternal(surface, broadcastName, onPlayerState) { playback ->
            originConsumer.use {
                playback.playOrigin(it, peerName, surface, broadcastName, CodecPreference.Auto)
            }
        }
    }

    private fun startInternal(
        surface: Surface,
        broadcastName: String,
        onPlayerState: (PlayerState, String) -> Unit,
        play: suspend (MoqPlaybackSession) -> Unit,
    ) {
        playbackJob?.cancel()
        val sessionId = ++playbackSessionId
        playbackJob = scope.launch {
            val playback = MoqPlaybackSession(
                logTag = logTag,
                status = { state -> updatePlayerStatus(state, broadcastName, sessionId, onPlayerState) },
            )
            runCatching {
                play(playback)
                updatePlayerStatus(PlayerState.Disconnected, broadcastName, sessionId, onPlayerState)
            }.onFailure { error ->
                Log.w(logTag, "playback failed", error)
                when {
                    error is CancellationException -> {
                        updatePlayerStatus(PlayerState.Disconnected, broadcastName, sessionId, onPlayerState)
                    }

                    isActive -> {
                        updatePlayerStatus(
                            PlayerState.Failed(error.message ?: error::class.java.name),
                            broadcastName,
                            sessionId,
                            onPlayerState,
                        )
                    }
                }
            }
        }
    }

    fun stop() {
        playbackSessionId += 1
        playbackJob?.cancel()
        playbackJob = null
    }

    private fun updatePlayerStatus(
        state: PlayerState,
        broadcastName: String,
        sessionId: Int,
        onPlayerState: (PlayerState, String) -> Unit,
    ) {
        if (sessionId != playbackSessionId) return
        val message = state.message(broadcastName)
        Log.i(logTag, message)
        scope.launch(Dispatchers.Main.immediate) {
            if (sessionId == playbackSessionId) {
                onPlayerState(state, message)
            }
        }
    }
}
