package com.example.moqandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.moqandroid.catalog.CodecPreference
import com.example.moqandroid.config.AppConfigStore
import com.example.moqandroid.playback.MoqPlaybackSession
import com.example.moqandroid.playback.PlayerState
import com.example.moqandroid.publish.MoqScreenPublishSession
import com.example.moqandroid.publish.PublishState
import com.example.moqandroid.publish.ScreenCaptureService
import com.example.moqandroid.publish.ScreenPublishConfig
import com.example.moqandroid.publish.ScreenVideoConfig
import com.example.moqandroid.publish.SystemAudioConfig
import com.example.moqandroid.ui.app.FirstRunConfig
import com.example.moqandroid.ui.app.MainTabsActions
import com.example.moqandroid.ui.app.MainTabsState
import com.example.moqandroid.ui.app.MainTabs
import com.example.moqandroid.ui.app.PublishPanelActions
import com.example.moqandroid.ui.app.PublishPanelState
import com.example.moqandroid.ui.app.RelayConfigActions
import com.example.moqandroid.ui.app.RelayConfigUiState
import com.example.moqandroid.ui.app.RelaySettings
import com.example.moqandroid.ui.app.RelaySettingsActions
import com.example.moqandroid.ui.app.SubscribePanelActions
import com.example.moqandroid.ui.app.SubscribePanelState
import com.example.moqandroid.ui.PlayerScreen
import java.lang.SecurityException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class MainActivity : ComponentActivity(), SurfaceHolder.Callback {
    private val logTag = "MoqAndroid"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var configStore: AppConfigStore
    private lateinit var projectionManager: MediaProjectionManager
    private var relayUrl by mutableStateOf("")
    private var configRelayUrl by mutableStateOf("")
    private var settingsRelayUrl by mutableStateOf("")
    private var publishBroadcastName by mutableStateOf("bbb.hang")
    private var subscribeBroadcastName by mutableStateOf("bbb.hang")
    private var broadcastName = "bbb.hang"
    private var playbackJob: Job? = null
    private var publishJob: Job? = null
    private var playerBroadcast: String? = null
    private var playbackSessionId = 0
    private var publishSessionId = 0
    private var currentScreen by mutableStateOf(AppScreen.Config)
    private var configStatusMessage by mutableStateOf("Relay URL is required before using MoQScreenCast.")
    private var publishStatusMessage by mutableStateOf("Ready to publish screen.")
    private var subscribeStatusMessage by mutableStateOf("Ready to subscribe.")
    private var settingsStatusMessage by mutableStateOf("Update relay URL.")
    private var includeSystemAudio by mutableStateOf(true)

    private var playerScreen: PlayerScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = AppConfigStore(this)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        relayUrl = configStore.loadRelayUrl()
        configRelayUrl = relayUrl
        settingsRelayUrl = relayUrl
        currentScreen = if (relayUrl.isBlank()) AppScreen.Config else AppScreen.Home
        setComposeContent()
    }

    private fun showConfigUi(message: String = configStatusMessage) {
        exitFullscreen()
        stopPlayback(message)
        playerBroadcast = null
        configStatusMessage = message
        playerScreen = null
        currentScreen = AppScreen.Config
        setComposeContent()
    }

    private fun showMainUi() {
        exitFullscreen()
        stopPlayback("Disconnected from ${playerBroadcast ?: broadcastName}.")
        playerBroadcast = null
        playerScreen = null
        currentScreen = AppScreen.Home
        setComposeContent()
    }

    private fun showSettingsUi(message: String = settingsStatusMessage) {
        exitFullscreen()
        stopPlayback("Disconnected from ${playerBroadcast ?: broadcastName}.")
        playerBroadcast = null
        settingsStatusMessage = message
        playerScreen = null
        settingsRelayUrl = relayUrl
        currentScreen = AppScreen.Settings
        setComposeContent()
    }

    private fun setComposeContent() {
        setContent {
            when (currentScreen) {
                AppScreen.Config -> FirstRunConfig(
                    state = RelayConfigUiState(
                        relayUrl = configRelayUrl,
                        status = configStatusMessage,
                    ),
                    actions = RelayConfigActions(
                        onRelayUrlChange = { configRelayUrl = it },
                        onContinue = ::saveConfigFromInput,
                    ),
                )

                AppScreen.Home -> MainTabs(
                    state = MainTabsState(
                        publish = PublishPanelState(
                            broadcast = publishBroadcastName,
                            includeSystemAudio = includeSystemAudio,
                            status = publishStatusMessage,
                        ),
                        subscribe = SubscribePanelState(
                            broadcast = subscribeBroadcastName,
                            status = subscribeStatusMessage,
                        ),
                    ),
                    actions = MainTabsActions(
                        publish = PublishPanelActions(
                            onBroadcastChange = { publishBroadcastName = it },
                            onIncludeSystemAudioChange = { includeSystemAudio = it },
                            onPublish = ::requestScreenPublish,
                            onStopPublish = { stopPublish("Screen publish stopped.") },
                        ),
                        subscribe = SubscribePanelActions(
                            onBroadcastChange = { subscribeBroadcastName = it },
                            onSubscribe = ::subscribeFromInput,
                        ),
                        onSettings = ::showSettingsUi,
                    ),
                )

                AppScreen.Settings -> RelaySettings(
                    state = RelayConfigUiState(
                        relayUrl = settingsRelayUrl,
                        status = settingsStatusMessage,
                    ),
                    actions = RelaySettingsActions(
                        onRelayUrlChange = { settingsRelayUrl = it },
                        onSave = ::saveSettingsFromInput,
                        onBack = ::showMainUi,
                    ),
                )
            }
        }
    }

    private fun saveConfigFromInput() {
        val nextRelayUrl = configRelayUrl.trim()
        val validationError = validateRelayUrl(nextRelayUrl)
        if (validationError != null) {
            updateConfigStatus(validationError)
            return
        }

        relayUrl = nextRelayUrl
        settingsRelayUrl = nextRelayUrl
        configStore.saveRelayUrl(nextRelayUrl)
        publishStatusMessage = "Relay saved. Ready to publish screen."
        subscribeStatusMessage = "Relay saved. Ready to subscribe."
        showMainUi()
    }

    private fun saveSettingsFromInput() {
        val nextRelayUrl = settingsRelayUrl.trim()
        val validationError = validateRelayUrl(nextRelayUrl)
        if (validationError != null) {
            updateSettingsStatus(validationError)
            return
        }

        relayUrl = nextRelayUrl
        configRelayUrl = nextRelayUrl
        configStore.saveRelayUrl(nextRelayUrl)
        publishStatusMessage = "Relay updated."
        subscribeStatusMessage = "Relay updated."
        showMainUi()
    }

    private fun validateRelayUrl(value: String): String? {
        if (value.isEmpty()) return "Relay URL cannot be empty."

        val uri = runCatching { URI(value) }.getOrNull()
            ?: return "Relay URL is invalid."
        if (uri.scheme != "http" && uri.scheme != "https") return "Relay URL must start with http:// or https://."
        if (uri.host.isNullOrBlank()) return "Relay URL must include a host."

        return null
    }

    private fun subscribeFromInput() {
        val nextBroadcast = subscribeBroadcastName.trim().trim('/')
        if (nextBroadcast.isEmpty()) {
            updateSubscribeStatus("Broadcast name cannot be empty.")
            return
        }

        broadcastName = nextBroadcast
        showPlayerUi(nextBroadcast)
    }

    private fun requestScreenPublish() {
        val nextBroadcast = publishBroadcastName.trim().trim('/')
        if (nextBroadcast.isEmpty()) {
            updatePublishHomeStatus("Broadcast name cannot be empty.")
            return
        }

        broadcastName = nextBroadcast
        if (includeSystemAudio && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            updatePublishHomeStatus("System audio capture requires Android 10+.\nbroadcast=$nextBroadcast")
            return
        }
        if (includeSystemAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updatePublishHomeStatus("Audio permission is required before publishing system audio.\nbroadcast=$nextBroadcast")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            updatePublishHomeStatus("Notification permission is required before screen publish.\nbroadcast=$nextBroadcast")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }

        updatePublishHomeStatus("Requesting screen capture permission ...\nbroadcast=$nextBroadcast")
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_NOTIFICATIONS -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    requestScreenPublish()
                } else {
                    updatePublishHomeStatus("Notification permission denied. Screen publish was not started.")
                }
            }
            REQUEST_RECORD_AUDIO -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    requestScreenPublish()
                } else {
                    updatePublishHomeStatus("Audio permission denied. Screen publish was not started.")
                }
            }
        }
    }

    @Deprecated("Deprecated in Android framework, still fine for this minimal Activity demo.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCREEN_CAPTURE) return

        if (resultCode != RESULT_OK || data == null) {
            updatePublishHomeStatus("Screen capture permission denied.")
            return
        }

        startScreenPublish(resultCode, data)
    }

    private fun startScreenPublish(resultCode: Int, data: Intent) {
        stopPublish("Restarting screen publish ...")
        val publishBroadcast = broadcastName
        val config = screenPublishConfig()
        val sessionId = ++publishSessionId
        publishJob = scope.launch {
            runCatching {
                ScreenCaptureService.start(this@MainActivity, relayUrl, publishBroadcast)
                withTimeout(3_000) { ScreenCaptureService.awaitForeground() }

                val projection = projectionManager.getMediaProjection(resultCode, data)
                    ?: error("Android did not return a MediaProjection.")

                val publisher = MoqScreenPublishSession(
                    relayUrl = relayUrl,
                    status = { state -> updatePublishStatus(state, sessionId) },
                )
                publisher.publish(projection, publishBroadcast, config)
            }.onFailure { error ->
                Log.w(logTag, "screen publish failed", error)
                val reason = when (error) {
                    is SecurityException -> "Screen capture requires a mediaProjection foreground service:\n${error.message}"
                    else -> error.message ?: error::class.java.name
                }
                if (isActive) updatePublishStatus(PublishState.Failed(reason), sessionId)
            }.also {
                ScreenCaptureService.stop(this@MainActivity)
            }
        }
    }

    private fun screenPublishConfig(): ScreenPublishConfig {
        val metrics = resources.displayMetrics
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

    private fun showPlayerUi(nextBroadcast: String) {
        enterFullscreen()
        playerBroadcast = nextBroadcast
        subscribeStatusMessage = "Disconnected from $nextBroadcast."

        val screen = PlayerScreen(
            activity = this,
            broadcastName = nextBroadcast,
            surfaceCallback = this,
        )
        playerScreen = screen
        setContentView(screen.root)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val nextBroadcast = playerBroadcast ?: return
        val surface = holder.surface
        if (!surface.isValid) {
            updatePlayerStatus(PlayerState.SurfaceWaiting)
            return
        }

        playbackJob?.cancel()
        val sessionId = ++playbackSessionId
        playbackJob = scope.launch {
            val playback = MoqPlaybackSession(
                relayUrl = relayUrl,
                logTag = logTag,
                status = { state -> updatePlayerStatus(state, sessionId) },
            )
            runCatching {
                playback.play(surface, nextBroadcast, CodecPreference.Auto)
                updatePlayerStatus(PlayerState.Disconnected, sessionId)
            }.onFailure { error ->
                Log.w(logTag, "playback failed", error)
                when {
                    error is CancellationException -> updatePlayerStatus(PlayerState.Disconnected, sessionId)
                    isActive -> updatePlayerStatus(PlayerState.Failed(error.message ?: error::class.java.name), sessionId)
                }
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopPlayback("Disconnected from ${playerBroadcast ?: broadcastName}.")
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && playerBroadcast != null) {
            subscribeStatusMessage = "Disconnected from ${playerBroadcast ?: broadcastName}."
            showMainUi()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_BACK && currentScreen == AppScreen.Settings) {
            showMainUi()
            return true
        }

        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        stopPublish("Screen publish stopped.")
        scope.cancel()
        super.onDestroy()
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

    private fun updatePublishStatus(state: PublishState, sessionId: Int = publishSessionId) {
        if (sessionId != publishSessionId) return
        val message = state.message()
        Log.i(logTag, message)
        publishStatusMessage = message
    }

    private fun updatePlayerStatus(state: PlayerState, sessionId: Int = playbackSessionId) {
        val broadcast = playerBroadcast ?: return
        if (sessionId != playbackSessionId) return
        val message = state.message(broadcast)
        Log.i(logTag, message)

        runOnUiThread {
            if (sessionId == playbackSessionId && playerBroadcast != null) {
                if (state is PlayerState.Playing) {
                    playerScreen?.setVideoSize(state.videoInfo.displayWidth, state.videoInfo.displayHeight)
                }
                playerScreen?.setStatus(message)
            }
        }
    }

    private fun stopPlayback(message: String) {
        playbackSessionId += 1
        playbackJob?.cancel()
        playbackJob = null
        subscribeStatusMessage = message
    }

    private fun stopPublish(message: String) {
        publishSessionId += 1
        publishJob?.cancel()
        publishJob = null
        ScreenCaptureService.stop(this)
        publishStatusMessage = message
        if (currentScreen == AppScreen.Home) updatePublishHomeStatus(message)
    }

    private fun enterFullscreen() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun exitFullscreen() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun Int.roundDownToEven(): Int = if (this % 2 == 0) this else this - 1

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 1001
        private const val REQUEST_NOTIFICATIONS = 1002
        private const val REQUEST_RECORD_AUDIO = 1003
        private const val MAX_PUBLISH_LONG_EDGE = 1080
    }
}

private enum class AppScreen {
    Config,
    Home,
    Settings,
}
