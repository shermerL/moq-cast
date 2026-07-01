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
import androidx.lifecycle.ViewModelProvider
import com.example.moqandroid.playback.PlayerState
import com.example.moqandroid.ui.PlayerScreen
import com.example.moqandroid.ui.app.FirstRunConfig
import com.example.moqandroid.ui.app.MainTabs
import com.example.moqandroid.ui.app.MainTabsActions
import com.example.moqandroid.ui.app.MainTabsState
import com.example.moqandroid.ui.app.PublishPanelActions
import com.example.moqandroid.ui.app.PublishPanelState
import com.example.moqandroid.ui.app.RelayConfigActions
import com.example.moqandroid.ui.app.RelayConfigUiState
import com.example.moqandroid.ui.app.RelaySettings
import com.example.moqandroid.ui.app.RelaySettingsActions
import com.example.moqandroid.ui.app.SubscribePanelActions
import com.example.moqandroid.ui.app.SubscribePanelState

class MainActivity : ComponentActivity(), SurfaceHolder.Callback {
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var viewModel: AppViewModel
    private var playerScreen: PlayerScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        viewModel = ViewModelProvider(this)[AppViewModel::class.java]
        setComposeContent()
    }

    private fun setComposeContent() {
        setContent {
            when (viewModel.currentScreen) {
                AppScreen.Config -> FirstRunConfig(
                    state = RelayConfigUiState(
                        relayUrl = viewModel.configRelayUrl,
                        status = viewModel.configStatusMessage,
                    ),
                    actions = RelayConfigActions(
                        onRelayUrlChange = viewModel::updateConfigRelayUrl,
                        onContinue = {
                            if (viewModel.saveConfigFromInput()) exitFullscreen()
                        },
                    ),
                )

                AppScreen.Home -> MainTabs(
                    state = MainTabsState(
                        publish = PublishPanelState(
                            broadcast = viewModel.publishBroadcastName,
                            includeSystemAudio = viewModel.includeSystemAudio,
                            status = viewModel.publishStatusMessage,
                        ),
                        subscribe = SubscribePanelState(
                            broadcast = viewModel.subscribeBroadcastName,
                            status = viewModel.subscribeStatusMessage,
                        ),
                    ),
                    actions = MainTabsActions(
                        publish = PublishPanelActions(
                            onBroadcastChange = viewModel::updatePublishBroadcast,
                            onIncludeSystemAudioChange = viewModel::updateIncludeSystemAudio,
                            onPublish = ::requestScreenPublish,
                            onStopPublish = { viewModel.stopPublish("Screen publish stopped.") },
                        ),
                        subscribe = SubscribePanelActions(
                            onBroadcastChange = viewModel::updateSubscribeBroadcast,
                            onSubscribe = ::showPlayerUi,
                        ),
                        onSettings = {
                            exitFullscreen()
                            viewModel.showSettingsUi()
                        },
                    ),
                )

                AppScreen.Settings -> RelaySettings(
                    state = RelayConfigUiState(
                        relayUrl = viewModel.settingsRelayUrl,
                        status = viewModel.settingsStatusMessage,
                    ),
                    actions = RelaySettingsActions(
                        onRelayUrlChange = viewModel::updateSettingsRelayUrl,
                        onSave = {
                            if (viewModel.saveSettingsFromInput()) exitFullscreen()
                        },
                        onBack = {
                            exitFullscreen()
                            viewModel.showMainUi()
                        },
                    ),
                )
            }
        }
    }

    private fun requestScreenPublish() {
        when (
            viewModel.prepareScreenPublish(
                hasRecordAudioPermission = hasRecordAudioPermission(),
                hasNotificationPermission = hasNotificationPermission(),
            )
        ) {
            PublishRequest.None -> Unit
            PublishRequest.RequestRecordAudio -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            PublishRequest.RequestNotifications -> requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            PublishRequest.RequestScreenCapture -> startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                REQUEST_SCREEN_CAPTURE,
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_NOTIFICATIONS,
            REQUEST_RECORD_AUDIO,
            -> requestScreenPublish()
        }
    }

    @Deprecated("Deprecated in Android framework. Kept until Activity Result API migration.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCREEN_CAPTURE) return

        if (resultCode != RESULT_OK || data == null) {
            viewModel.stopPublish("Screen capture permission denied.")
            return
        }

        viewModel.startScreenPublish(resultCode, data, resources.displayMetrics)
    }

    private fun showPlayerUi() {
        val nextBroadcast = viewModel.prepareSubscribe() ?: return
        enterFullscreen()

        val screen = PlayerScreen(
            activity = this,
            broadcastName = nextBroadcast,
            surfaceCallback = this,
        )
        playerScreen = screen
        setContentView(screen.root)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val surface = holder.surface
        if (!surface.isValid) {
            updatePlayerView(PlayerState.SurfaceWaiting, PlayerState.SurfaceWaiting.message(viewModel.playerBroadcast.orEmpty()))
            return
        }

        viewModel.startPlayback(surface, ::updatePlayerView)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        viewModel.stopPlayback("Disconnected from ${viewModel.playerBroadcast ?: viewModel.subscribeBroadcastName}.")
    }

    override fun onPause() {
        Log.i(LOG_TAG, "MainActivity paused")
        super.onPause()
    }

    override fun onStop() {
        Log.i(LOG_TAG, "MainActivity stopped")
        super.onStop()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && viewModel.playerBroadcast != null) {
            viewModel.showMainUi()
            playerScreen = null
            exitFullscreen()
            setComposeContent()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_BACK && viewModel.currentScreen == AppScreen.Settings) {
            viewModel.showMainUi()
            exitFullscreen()
            return true
        }

        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        Log.i(LOG_TAG, "MainActivity destroyed")
        super.onDestroy()
    }

    private fun updatePlayerView(state: PlayerState, message: String) {
        if (state is PlayerState.Playing) {
            playerScreen?.setVideoSize(state.videoInfo.displayWidth, state.videoInfo.displayHeight)
        }
        playerScreen?.setStatus(message)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
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

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 1001
        private const val REQUEST_NOTIFICATIONS = 1002
        private const val REQUEST_RECORD_AUDIO = 1003
        private const val LOG_TAG = "MoqAndroid"
    }
}
