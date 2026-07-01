package com.example.moqandroid

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.moqandroid.catalog.CodecPreference
import com.example.moqandroid.config.AppConfigStore
import com.example.moqandroid.media.codec.CodecSupport
import com.example.moqandroid.playback.MoqPlaybackSession
import com.example.moqandroid.playback.PlayerState
import com.example.moqandroid.publish.PublishState
import com.example.moqandroid.publish.ScreenCaptureService
import com.example.moqandroid.publish.ScreenPublishConfig
import com.example.moqandroid.publish.ScreenVideoConfig
import com.example.moqandroid.publish.SystemAudioConfig
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val logTag = "MoqAndroid"
    private val configStore = AppConfigStore(application)

    var relayUrl by mutableStateOf("")
        private set
    var configRelayUrl by mutableStateOf("")
        private set
    var settingsRelayUrl by mutableStateOf("")
        private set
    var publishBroadcastName by mutableStateOf("bbb.hang")
        private set
    var subscribeBroadcastName by mutableStateOf("bbb.hang")
        private set
    var currentScreen by mutableStateOf(AppScreen.Config)
        private set
    var configStatusMessage by mutableStateOf("Relay URL is required before using MoQScreenCast.")
        private set
    var publishStatusMessage by mutableStateOf("Ready to publish screen.")
        private set
    var subscribeStatusMessage by mutableStateOf("Ready to subscribe.")
        private set
    var settingsStatusMessage by mutableStateOf("Update relay URL.")
        private set
    var includeSystemAudio by mutableStateOf(true)
        private set
    var playerBroadcast by mutableStateOf<String?>(null)
        private set

    private var activeBroadcastName = "bbb.hang"
    private var playbackJob: Job? = null
    private var playbackSessionId = 0

    init {
        relayUrl = configStore.loadRelayUrl()
        configRelayUrl = relayUrl
        settingsRelayUrl = relayUrl
        currentScreen = if (relayUrl.isBlank()) AppScreen.Config else AppScreen.Home
        viewModelScope.launch {
            ScreenCaptureService.status.collect { state ->
                updatePublishStatus(state)
            }
        }
    }

    fun updateConfigRelayUrl(value: String) {
        configRelayUrl = value
    }

    fun updateSettingsRelayUrl(value: String) {
        settingsRelayUrl = value
    }

    fun updatePublishBroadcast(value: String) {
        publishBroadcastName = value
    }

    fun updateSubscribeBroadcast(value: String) {
        subscribeBroadcastName = value
    }

    fun updateIncludeSystemAudio(value: Boolean) {
        includeSystemAudio = value
    }

    fun showMainUi() {
        stopPlayback("Disconnected from ${playerBroadcast ?: activeBroadcastName}.")
        playerBroadcast = null
        currentScreen = AppScreen.Home
    }

    fun showSettingsUi() {
        stopPlayback("Disconnected from ${playerBroadcast ?: activeBroadcastName}.")
        playerBroadcast = null
        settingsRelayUrl = relayUrl
        settingsStatusMessage = "Update relay URL."
        currentScreen = AppScreen.Settings
    }

    fun saveConfigFromInput(): Boolean {
        val nextRelayUrl = configRelayUrl.trim()
        val validationError = validateRelayUrl(nextRelayUrl)
        if (validationError != null) {
            updateConfigStatus(validationError)
            return false
        }

        relayUrl = nextRelayUrl
        settingsRelayUrl = nextRelayUrl
        configStore.saveRelayUrl(nextRelayUrl)
        publishStatusMessage = "Relay saved. Ready to publish screen."
        subscribeStatusMessage = "Relay saved. Ready to subscribe."
        showMainUi()
        return true
    }

    fun saveSettingsFromInput(): Boolean {
        val nextRelayUrl = settingsRelayUrl.trim()
        val validationError = validateRelayUrl(nextRelayUrl)
        if (validationError != null) {
            updateSettingsStatus(validationError)
            return false
        }

        relayUrl = nextRelayUrl
        configRelayUrl = nextRelayUrl
        configStore.saveRelayUrl(nextRelayUrl)
        publishStatusMessage = "Relay updated."
        subscribeStatusMessage = "Relay updated."
        showMainUi()
        return true
    }

    fun prepareSubscribe(): String? {
        val nextBroadcast = subscribeBroadcastName.trim().trim('/')
        if (nextBroadcast.isEmpty()) {
            updateSubscribeStatus("Broadcast name cannot be empty.")
            return null
        }

        activeBroadcastName = nextBroadcast
        playerBroadcast = nextBroadcast
        subscribeStatusMessage = "Disconnected from $nextBroadcast."
        return nextBroadcast
    }

    fun prepareScreenPublish(
        hasRecordAudioPermission: Boolean,
        hasNotificationPermission: Boolean,
    ): PublishRequest {
        val nextBroadcast = publishBroadcastName.trim().trim('/')
        if (nextBroadcast.isEmpty()) {
            updatePublishHomeStatus("Broadcast name cannot be empty.")
            return PublishRequest.None
        }

        activeBroadcastName = nextBroadcast
        if (!CodecSupport.hasEncoderFor(MIME_AVC)) {
            updatePublishHomeStatus("This device has no H.264 encoder.\n${CodecSupport.describeVideoEncoders()}")
            return PublishRequest.None
        }
        if (includeSystemAudio && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            updatePublishHomeStatus("System audio capture requires Android 10+.\nbroadcast=$nextBroadcast")
            return PublishRequest.None
        }
        if (includeSystemAudio && !hasRecordAudioPermission) {
            updatePublishHomeStatus("Audio permission is required before publishing system audio.\nbroadcast=$nextBroadcast")
            return PublishRequest.RequestRecordAudio
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            updatePublishHomeStatus("Notification permission is required before screen publish.\nbroadcast=$nextBroadcast")
            return PublishRequest.RequestNotifications
        }

        updatePublishHomeStatus("Requesting screen capture permission ...\nbroadcast=$nextBroadcast")
        return PublishRequest.RequestScreenCapture
    }

    fun startScreenPublish(
        resultCode: Int,
        resultData: Intent,
        metrics: DisplayMetrics,
    ) {
        publishStatusMessage = "Starting screen publish ..."
        ScreenCaptureService.start(
            context = getApplication(),
            relayUrl = relayUrl,
            broadcastName = activeBroadcastName,
            resultCode = resultCode,
            resultData = resultData,
            config = screenPublishConfig(metrics),
        )
    }

    fun startPlayback(
        surface: Surface,
        onPlayerState: (PlayerState, String) -> Unit,
    ) {
        val nextBroadcast = playerBroadcast ?: return

        playbackJob?.cancel()
        val sessionId = ++playbackSessionId
        playbackJob = viewModelScope.launch {
            val playback = MoqPlaybackSession(
                relayUrl = relayUrl,
                logTag = logTag,
                status = { state -> updatePlayerStatus(state, sessionId, onPlayerState) },
            )
            runCatching {
                playback.play(surface, nextBroadcast, CodecPreference.Auto)
                updatePlayerStatus(PlayerState.Disconnected, sessionId, onPlayerState)
            }.onFailure { error ->
                Log.w(logTag, "playback failed", error)
                when {
                    error is CancellationException -> updatePlayerStatus(PlayerState.Disconnected, sessionId, onPlayerState)
                    isActive -> updatePlayerStatus(PlayerState.Failed(error.message ?: error::class.java.name), sessionId, onPlayerState)
                }
            }
        }
    }

    fun stopPlayback(message: String) {
        playbackSessionId += 1
        playbackJob?.cancel()
        playbackJob = null
        subscribeStatusMessage = message
    }

    fun stopPublish(message: String) {
        ScreenCaptureService.stop(getApplication())
        publishStatusMessage = message
        if (currentScreen == AppScreen.Home) updatePublishHomeStatus(message)
    }

    override fun onCleared() {
        Log.i(logTag, "AppViewModel cleared")
        stopPlayback("Disconnected from ${playerBroadcast ?: activeBroadcastName}.")
        super.onCleared()
    }

    private fun screenPublishConfig(metrics: DisplayMetrics): ScreenPublishConfig {
        val (width, height) = scaledVideoSize(metrics.widthPixels, metrics.heightPixels)
        return ScreenPublishConfig(
            video = ScreenVideoConfig(
                width = width,
                height = height,
                densityDpi = metrics.densityDpi,
            ),
            audio = if (includeSystemAudio) SystemAudioConfig.Enabled() else SystemAudioConfig.Disabled,
        )
    }

    private fun scaledVideoSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val longestEdge = maxOf(sourceWidth, sourceHeight)
        val scale = minOf(MAX_PUBLISH_LONG_EDGE.toFloat() / longestEdge, 1f)
        val width = (sourceWidth * scale).toInt().roundDownToEven().coerceAtLeast(2)
        val height = (sourceHeight * scale).toInt().roundDownToEven().coerceAtLeast(2)
        return width to height
    }

    private fun validateRelayUrl(value: String): String? {
        if (value.isEmpty()) return "Relay URL cannot be empty."

        val uri = runCatching { URI(value) }.getOrNull()
            ?: return "Relay URL is invalid."
        if (uri.scheme != "http" && uri.scheme != "https") return "Relay URL must start with http:// or https://."
        if (uri.host.isNullOrBlank()) return "Relay URL must include a host."

        return null
    }

    private fun updatePublishHomeStatus(message: String) {
        publishStatusMessage = message
        Log.i(logTag, message)
    }

    private fun updateSubscribeStatus(message: String) {
        subscribeStatusMessage = message
        Log.i(logTag, message)
    }

    private fun updateConfigStatus(message: String) {
        configStatusMessage = message
        Log.i(logTag, message)
    }

    private fun updateSettingsStatus(message: String) {
        settingsStatusMessage = message
        Log.i(logTag, message)
    }

    private fun updatePublishStatus(state: PublishState) {
        val message = state.message()
        Log.i(logTag, message)
        viewModelScope.launch(Dispatchers.Main.immediate) {
            publishStatusMessage = message
        }
    }

    private fun updatePlayerStatus(
        state: PlayerState,
        sessionId: Int,
        onPlayerState: (PlayerState, String) -> Unit,
    ) {
        val broadcast = playerBroadcast ?: return
        if (sessionId != playbackSessionId) return
        val message = state.message(broadcast)
        Log.i(logTag, message)
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (sessionId == playbackSessionId && playerBroadcast != null) {
                onPlayerState(state, message)
            }
        }
    }

    private fun Int.roundDownToEven(): Int = if (this % 2 == 0) this else this - 1

    companion object {
        private const val MIME_AVC = "video/avc"
        private const val MAX_PUBLISH_LONG_EDGE = 1080
    }
}

enum class AppScreen {
    Config,
    Home,
    Settings,
}

sealed interface PublishRequest {
    data object None : PublishRequest
    data object RequestRecordAudio : PublishRequest
    data object RequestNotifications : PublishRequest
    data object RequestScreenCapture : PublishRequest
}
