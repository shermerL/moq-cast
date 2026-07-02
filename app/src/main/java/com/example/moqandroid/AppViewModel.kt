package com.example.moqandroid

import android.app.Application
import android.content.Intent
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.moqandroid.config.AppConfigStore
import com.example.moqandroid.config.RelayConfig
import com.example.moqandroid.config.SettingsState
import com.example.moqandroid.playback.PlaybackController
import com.example.moqandroid.playback.PlayerState
import com.example.moqandroid.publish.PublishController
import com.example.moqandroid.publish.PublishRequest
import com.example.moqandroid.publish.PublishState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val logTag = "MoqAndroid"
    private val configStore = AppConfigStore(application)
    private val initialRelayUrl = configStore.loadRelayUrl()
    private val publishController = PublishController(application)
    private val playbackController = PlaybackController(viewModelScope, logTag)

    var relayConfig by mutableStateOf(RelayConfig(initialRelayUrl))
        private set
    var configState by mutableStateOf(
        SettingsState(
            relayUrl = initialRelayUrl,
            statusMessage = "Relay URL is required before using MoQScreenCast.",
        ),
    )
        private set
    var settingsState by mutableStateOf(
        SettingsState(
            relayUrl = initialRelayUrl,
            statusMessage = "Update relay URL.",
        ),
    )
        private set
    var publishBroadcastName by mutableStateOf("bbb.hang")
        private set
    var subscribeBroadcastName by mutableStateOf("bbb.hang")
        private set
    var currentScreen by mutableStateOf(AppScreen.Config)
        private set
    var publishStatusMessage by mutableStateOf("Ready to publish screen.")
        private set
    var subscribeStatusMessage by mutableStateOf("Ready to subscribe.")
        private set
    var includeSystemAudio by mutableStateOf(false)
        private set
    var playerBroadcast by mutableStateOf<String?>(null)
        private set

    private var activeBroadcastName = "bbb.hang"

    val relayUrl: String
        get() = relayConfig.relayUrl

    val configRelayUrl: String
        get() = configState.relayUrl

    val configStatusMessage: String
        get() = configState.statusMessage

    val settingsRelayUrl: String
        get() = settingsState.relayUrl

    val settingsStatusMessage: String
        get() = settingsState.statusMessage

    init {
        currentScreen = if (relayConfig.relayUrl.isBlank()) AppScreen.Config else AppScreen.Home
        viewModelScope.launch {
            publishController.status.collect { state ->
                updatePublishStatus(state)
            }
        }
    }

    fun updateConfigRelayUrl(value: String) {
        configState = configState.withRelayUrl(value)
    }

    fun updateSettingsRelayUrl(value: String) {
        settingsState = settingsState.withRelayUrl(value)
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
        settingsState = SettingsState(
            relayUrl = relayConfig.relayUrl,
            statusMessage = "Update relay URL.",
        )
        currentScreen = AppScreen.Settings
    }

    fun saveConfigFromInput(): Boolean {
        val nextRelayConfig = relayConfigFromInput(configState.relayUrl, ::updateConfigStatus) ?: return false

        relayConfig = nextRelayConfig
        settingsState = settingsState.withRelayUrl(nextRelayConfig.relayUrl)
        configStore.saveRelayUrl(nextRelayConfig.relayUrl)
        publishStatusMessage = "Relay saved. Ready to publish screen."
        subscribeStatusMessage = "Relay saved. Ready to subscribe."
        showMainUi()
        return true
    }

    fun saveSettingsFromInput(): Boolean {
        val nextRelayConfig = relayConfigFromInput(settingsState.relayUrl, ::updateSettingsStatus) ?: return false

        relayConfig = nextRelayConfig
        configState = configState.withRelayUrl(nextRelayConfig.relayUrl)
        configStore.saveRelayUrl(nextRelayConfig.relayUrl)
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
        val preparation = publishController.prepare(
            broadcastInput = publishBroadcastName,
            includeSystemAudio = includeSystemAudio,
            hasRecordAudioPermission = hasRecordAudioPermission,
            hasNotificationPermission = hasNotificationPermission,
        )
        preparation.broadcastName?.let { activeBroadcastName = it }
        updatePublishHomeStatus(preparation.message)
        return preparation.request
    }

    fun startScreenPublish(
        resultCode: Int,
        resultData: Intent,
        metrics: DisplayMetrics,
    ) {
        publishStatusMessage = "Starting screen publish ..."
        publishController.start(
            relayConfig = relayConfig,
            broadcastName = activeBroadcastName,
            resultCode = resultCode,
            resultData = resultData,
            metrics = metrics,
            includeSystemAudio = includeSystemAudio,
        )
    }

    fun startPlayback(
        surface: Surface,
        onPlayerState: (PlayerState, String) -> Unit,
    ) {
        val nextBroadcast = playerBroadcast ?: return

        playbackController.start(
            surface = surface,
            relayUrl = relayConfig.relayUrl,
            broadcastName = nextBroadcast,
            onPlayerState = onPlayerState,
        )
    }

    fun stopPlayback(message: String) {
        playbackController.stop()
        subscribeStatusMessage = message
    }

    fun stopPublish(message: String) {
        publishController.stop()
        publishStatusMessage = message
        if (currentScreen == AppScreen.Home) updatePublishHomeStatus(message)
    }

    override fun onCleared() {
        Log.i(logTag, "AppViewModel cleared")
        stopPlayback("Disconnected from ${playerBroadcast ?: activeBroadcastName}.")
        super.onCleared()
    }

    private fun relayConfigFromInput(
        value: String,
        onInvalid: (String) -> Unit,
    ): RelayConfig? {
        return RelayConfig.fromInput(value).getOrElse { error ->
            onInvalid(error.message ?: "Relay URL is invalid.")
            null
        }
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
        configState = configState.withStatus(message)
        Log.i(logTag, message)
    }

    private fun updateSettingsStatus(message: String) {
        settingsState = settingsState.withStatus(message)
        Log.i(logTag, message)
    }

    private fun updatePublishStatus(state: PublishState) {
        val message = state.message()
        Log.i(logTag, message)
        viewModelScope.launch(Dispatchers.Main.immediate) {
            publishStatusMessage = message
        }
    }
}

enum class AppScreen {
    Config,
    Home,
    Settings,
}
