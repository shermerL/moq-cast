package com.example.moqandroid

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.widget.TextView
import com.example.moqandroid.catalog.CodecPreference
import com.example.moqandroid.config.AppConfigStore
import com.example.moqandroid.playback.MoqPlaybackSession
import com.example.moqandroid.playback.PlayerState
import com.example.moqandroid.publish.MoqScreenPublishSession
import com.example.moqandroid.publish.PublishState
import com.example.moqandroid.publish.ScreenCaptureService
import com.example.moqandroid.publish.ScreenVideoConfig
import com.example.moqandroid.ui.ConfigScreen
import com.example.moqandroid.ui.MainScreen
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

class MainActivity : Activity(), SurfaceHolder.Callback {
    private val logTag = "MoqAndroid"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var configStore: AppConfigStore
    private lateinit var projectionManager: MediaProjectionManager
    private var relayUrl = ""
    private var broadcastName = "bbb.hang"
    private var playbackJob: Job? = null
    private var publishJob: Job? = null
    private var playerBroadcast: String? = null
    private var playbackSessionId = 0
    private var publishSessionId = 0
    private var configStatusMessage = "Configure relay before subscribing."
    private var mainStatusMessage = "Enter a broadcast name, then subscribe."

    private lateinit var status: TextView
    private var configScreen: ConfigScreen? = null
    private var mainScreen: MainScreen? = null
    private var playerScreen: PlayerScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = AppConfigStore(this)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        relayUrl = configStore.loadRelayUrl()
        showConfigUi()
    }

    private fun showConfigUi(message: String = configStatusMessage) {
        exitFullscreen()
        stopPlayback(message)
        playerBroadcast = null
        configStatusMessage = message

        val screen = ConfigScreen(
            activity = this,
            initialStatus = message,
            initialRelayUrl = relayUrl,
            onContinue = ::saveConfigFromInput,
        )
        configScreen = screen
        mainScreen = null
        playerScreen = null
        status = screen.status
        setContentView(screen.root)
        screen.requestInitialFocus()
    }

    private fun showMainUi(message: String = mainStatusMessage) {
        exitFullscreen()
        stopPlayback(message)
        playerBroadcast = null
        mainStatusMessage = message

        val screen = MainScreen(
            activity = this,
            initialStatus = message,
            initialBroadcast = broadcastName,
            onSubscribe = ::subscribeFromInput,
            onPublishScreen = ::requestScreenPublish,
            onStopPublish = { stopPublish("Screen publish stopped.") },
            onConfigureRelay = { showConfigUi("Update relay URL.") },
        )
        configScreen = null
        mainScreen = screen
        playerScreen = null
        status = screen.status
        setContentView(screen.root)
        screen.requestInitialFocus()
    }

    private fun saveConfigFromInput() {
        val nextRelayUrl = configScreen?.relayUrl().orEmpty().trim()
        val validationError = validateRelayUrl(nextRelayUrl)
        if (validationError != null) {
            updateConfigStatus(validationError)
            return
        }

        relayUrl = nextRelayUrl
        configStore.saveRelayUrl(nextRelayUrl)
        showMainUi("Relay saved. Enter a broadcast name, then subscribe.")
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
        val nextBroadcast = mainScreen?.broadcastName().orEmpty().trim().trim('/')
        if (nextBroadcast.isEmpty()) {
            updateMainStatus("Broadcast name cannot be empty.")
            return
        }

        broadcastName = nextBroadcast
        showPlayerUi(nextBroadcast)
    }

    private fun requestScreenPublish() {
        val nextBroadcast = mainScreen?.broadcastName().orEmpty().trim().trim('/')
        if (nextBroadcast.isEmpty()) {
            updateMainStatus("Broadcast name cannot be empty.")
            return
        }

        broadcastName = nextBroadcast
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            updateMainStatus("Notification permission is required before screen publish.\nbroadcast=$nextBroadcast")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }

        updateMainStatus("Requesting screen capture permission ...\nbroadcast=$nextBroadcast")
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            requestScreenPublish()
        } else {
            updateMainStatus("Notification permission denied. Screen publish was not started.")
        }
    }

    @Deprecated("Deprecated in Android framework, still fine for this minimal Activity demo.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCREEN_CAPTURE) return

        if (resultCode != RESULT_OK || data == null) {
            updateMainStatus("Screen capture permission denied.")
            return
        }

        startScreenPublish(resultCode, data)
    }

    private fun startScreenPublish(resultCode: Int, data: Intent) {
        stopPublish("Restarting screen publish ...")
        val publishBroadcast = broadcastName
        val config = screenVideoConfig()
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

    private fun screenVideoConfig(): ScreenVideoConfig {
        val metrics = resources.displayMetrics
        val (width, height) = scaledVideoSize(metrics.widthPixels, metrics.heightPixels)
        return ScreenVideoConfig(
            width = width,
            height = height,
            densityDpi = metrics.densityDpi,
        )
    }

    private fun scaledVideoSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val scale = minOf(
            MAX_PUBLISH_WIDTH.toFloat() / sourceWidth,
            MAX_PUBLISH_HEIGHT.toFloat() / sourceHeight,
            1f,
        )
        val width = (sourceWidth * scale).toInt().roundDownToEven().coerceAtLeast(2)
        val height = (sourceHeight * scale).toInt().roundDownToEven().coerceAtLeast(2)
        return width to height
    }

    private fun showPlayerUi(nextBroadcast: String) {
        enterFullscreen()
        playerBroadcast = nextBroadcast
        mainStatusMessage = "Disconnected from $nextBroadcast."

        val screen = PlayerScreen(
            activity = this,
            broadcastName = nextBroadcast,
            surfaceCallback = this,
        )
        playerScreen = screen
        mainScreen = null
        status = screen.status
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
            showMainUi("Disconnected from ${playerBroadcast ?: broadcastName}.")
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_BACK && mainScreen != null) {
            showConfigUi("Update relay URL.")
            return true
        }

        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        stopPublish("Screen publish stopped.")
        scope.cancel()
        super.onDestroy()
    }

    private fun updateMainStatus(message: String) {
        mainStatusMessage = message
        Log.i(logTag, message)
        runOnUiThread { status.text = message }
    }

    private fun updateConfigStatus(message: String) {
        configStatusMessage = message
        Log.i(logTag, message)
        runOnUiThread { configScreen?.setStatus(message) }
    }

    private fun updatePublishStatus(state: PublishState, sessionId: Int = publishSessionId) {
        if (sessionId != publishSessionId) return
        val message = state.message()
        Log.i(logTag, message)
        runOnUiThread {
            if (sessionId == publishSessionId && mainScreen != null) {
                status.text = message
            }
        }
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
        mainStatusMessage = message
    }

    private fun stopPublish(message: String) {
        publishSessionId += 1
        publishJob?.cancel()
        publishJob = null
        ScreenCaptureService.stop(this)
        mainStatusMessage = message
        if (mainScreen != null) updateMainStatus(message)
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
        private const val MAX_PUBLISH_WIDTH = 1280
        private const val MAX_PUBLISH_HEIGHT = 720
    }
}
