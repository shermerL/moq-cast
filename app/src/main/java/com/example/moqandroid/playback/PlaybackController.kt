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
        playbackJob?.cancel()
        val sessionId = ++playbackSessionId
        playbackJob = scope.launch {
            val playback = MoqPlaybackSession(
                relayUrl = relayUrl,
                logTag = logTag,
                status = { state -> updatePlayerStatus(state, broadcastName, sessionId, onPlayerState) },
            )
            runCatching {
                playback.play(surface, broadcastName, CodecPreference.Auto)
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

