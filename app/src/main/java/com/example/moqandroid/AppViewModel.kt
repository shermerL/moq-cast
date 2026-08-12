package com.example.moqandroid

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.moqandroid.config.AppConfigStore
import com.example.moqandroid.config.AppLanguage
import com.example.moqandroid.config.RelayConfig
import com.example.moqandroid.config.SettingsState
import com.example.moqandroid.config.withAppLanguage
import com.example.moqandroid.network.lan.mesh.ScreenBroadcastAvailability
import com.example.moqandroid.network.lan.mesh.MoqLanMeshRuntime
import com.example.moqandroid.network.lan.server.MoqPeerServer
import com.example.moqandroid.network.lan.server.PeerListenerState
import com.example.moqandroid.network.lan.server.PeerServerState
import com.example.moqandroid.playback.PlaybackController
import com.example.moqandroid.playback.PlayerState
import com.example.moqandroid.publish.CameraPublishStartRequest
import com.example.moqandroid.publish.PublishController
import com.example.moqandroid.publish.PublishPermissions
import com.example.moqandroid.publish.PublishPreparationInput
import com.example.moqandroid.publish.FilePublishStartRequest
import com.example.moqandroid.publish.PublishRequest
import com.example.moqandroid.publish.PublishSourceType
import com.example.moqandroid.publish.PublishState
import com.example.moqandroid.publish.PublishStatusFormatter
import com.example.moqandroid.publish.ScreenPublishStartRequest
import com.example.moqandroid.publish.camera.CameraLensFacing
import com.example.moqandroid.publish.camera.CameraQualityPreset
import com.example.moqandroid.publish.encoder.H264ProfilePreference
import com.example.moqandroid.publish.encoder.VideoEncoderPolicy
import com.example.moqandroid.publish.file.PublishFileProbe
import com.example.moqandroid.publish.file.PublishFileState
import com.example.moqandroid.ui.app.PublishPanelMode
import com.example.moqandroid.ui.nearby.NearbyMediaState
import com.example.moqandroid.ui.nearby.NearbyMediaStateReducer
import com.example.moqandroid.ui.nearby.PeerListItem
import com.example.moqandroid.ui.nearby.PeerListProjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val logTag = "MoqAndroid"
    private val configStore = AppConfigStore(application)
    private val initialRelayUrl = configStore.loadRelayUrl()
    private val initialLanguage = configStore.loadLanguage()
    private val initialPublishCompatibilityMode = configStore.loadPublishCompatibilityMode()
    private val initialH264ProfilePreference = configStore.loadH264ProfilePreference()
    private val initialShowPlaybackStats = configStore.loadShowPlaybackStats()
    private val initialLanMeshEnabled = configStore.loadLanMeshEnabled()
    private var appLanguage = initialLanguage
    private var localizedResources = application.withAppLanguage(initialLanguage)
    private val publishController = PublishController(application)
    private val publishFileProbe = PublishFileProbe(application)
    private val playbackController = PlaybackController(viewModelScope, logTag)
    private val lanMesh = MoqLanMeshRuntime(application, viewModelScope)
    private var publishFileProbeJob: Job? = null
    private var nearbyScreenPublishPending = false
    private var playbackTarget = PlaybackTarget.Relay

    var relayConfig by mutableStateOf(RelayConfig(initialRelayUrl))
        private set
    var configState by mutableStateOf(
        SettingsState(
            relayUrl = initialRelayUrl,
            statusMessage = text(R.string.relay_required),
            language = initialLanguage,
            publishCompatibilityMode = initialPublishCompatibilityMode,
            h264ProfilePreference = initialH264ProfilePreference,
            showPlaybackStats = initialShowPlaybackStats,
            lanMeshEnabled = initialLanMeshEnabled,
        ),
    )
        private set
    var settingsState by mutableStateOf(
        SettingsState(
            relayUrl = initialRelayUrl,
            statusMessage = text(R.string.update_relay_url),
            language = initialLanguage,
            publishCompatibilityMode = initialPublishCompatibilityMode,
            h264ProfilePreference = initialH264ProfilePreference,
            showPlaybackStats = initialShowPlaybackStats,
            lanMeshEnabled = initialLanMeshEnabled,
        ),
    )
        private set
    var homeRelayUrl by mutableStateOf(initialRelayUrl)
        private set
    var publishBroadcastName by mutableStateOf("bbb.hang")
        private set
    var subscribeBroadcastName by mutableStateOf("bbb.hang")
        private set
    var currentScreen by mutableStateOf(AppScreen.Config)
        private set
    var publishStatusMessage by mutableStateOf(text(R.string.ready_publish_screen))
        private set
    var publishPanelMode by mutableStateOf(PublishPanelMode.Ready)
        private set
    var subscribeStatusMessage by mutableStateOf(text(R.string.ready_subscribe))
        private set
    var includeSystemAudio by mutableStateOf(false)
        private set
    var includeMicrophone by mutableStateOf(false)
        private set
    var cameraLensFacing by mutableStateOf(CameraLensFacing.Back)
        private set
    var cameraQualityPreset by mutableStateOf(CameraQualityPreset.Auto)
        private set
    var publishSource by mutableStateOf(PublishSourceType.Screen)
        private set
    var publishFileState by mutableStateOf<PublishFileState>(PublishFileState.NotSelected)
        private set
    var playerBroadcast by mutableStateOf<String?>(null)
        private set
    var nearbyMediaState by mutableStateOf<NearbyMediaState>(NearbyMediaState.ConnectedIdle)
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

    val settingsLanguage: AppLanguage
        get() = settingsState.language

    val settingsPublishCompatibilityMode: Boolean
        get() = settingsState.publishCompatibilityMode

    val settingsH264ProfilePreference: H264ProfilePreference
        get() = settingsState.h264ProfilePreference

    val settingsShowPlaybackStats: Boolean
        get() = settingsState.showPlaybackStats

    val settingsLanMeshEnabled: Boolean
        get() = settingsState.lanMeshEnabled

    val languageOptions: List<AppLanguage>
        get() = AppLanguage.entries

    val h264ProfileOptions: List<H264ProfilePreference>
        get() = H264ProfilePreference.entries

    val nearbyDiscoveryState = lanMesh.discoveryState
    val nearbyServerState = lanMesh.serverState
    val nearbyPeerItems = combine(
        lanMesh.peers,
        lanMesh.peerStates,
        lanMesh.broadcasts,
        lanMesh.serverState,
    ) { peers, connections, screens, server ->
        PeerListProjector.project(
            peers = peers,
            connections = connections,
            screens = screens,
            localPeerId = server.serviceName(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        currentScreen = if (relayConfig.relayUrl.isBlank()) AppScreen.Config else AppScreen.Home
        if (initialLanMeshEnabled) lanMesh.start()
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

    fun updateHomeRelayUrl(value: String) {
        homeRelayUrl = value
    }

    fun updateSettingsLanguage(value: AppLanguage) {
        appLanguage = value
        localizedResources = getApplication<Application>().withAppLanguage(value)
        configStore.saveLanguage(value)
        settingsState = settingsState
            .withLanguage(value)
            .withStatus(text(R.string.language_set, text(value.labelRes)))
    }

    fun updateSettingsPublishCompatibilityMode(value: Boolean) {
        settingsState = settingsState.withPublishCompatibilityMode(value)
    }

    fun updateSettingsH264ProfilePreference(value: H264ProfilePreference) {
        settingsState = settingsState.withH264ProfilePreference(value)
    }

    fun updateSettingsShowPlaybackStats(value: Boolean) {
        settingsState = settingsState.withShowPlaybackStats(value)
    }

    fun updateSettingsLanMeshEnabled(value: Boolean) {
        settingsState = settingsState.withLanMeshEnabled(value)
        configState = configState.withLanMeshEnabled(value)
        configStore.saveLanMeshEnabled(value)
        if (value) lanMesh.start() else lanMesh.stop()
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

    fun updateIncludeMicrophone(value: Boolean) {
        includeMicrophone = value
    }

    fun updateCameraLensFacing(value: CameraLensFacing) {
        cameraLensFacing = value
    }

    fun updateCameraQualityPreset(value: CameraQualityPreset) {
        cameraQualityPreset = value
    }

    fun updatePublishSource(value: PublishSourceType) {
        publishSource = value
    }

    fun selectPublishFile(uri: Uri) {
        val displayName = uri.lastPathSegment
        publishFileProbeJob?.cancel()
        publishFileState = PublishFileState.Probing(displayName)
        publishFileProbeJob = viewModelScope.launch {
            publishFileState = try {
                withContext(Dispatchers.IO) { publishFileProbe.probe(uri) }
                    .let(PublishFileState::Ready)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                PublishFileState.Failed(
                    displayName = displayName,
                    reason = error.message ?: error::class.java.simpleName,
                )
            }
        }
    }

    fun showMainUi() {
        stopPlayback("Disconnected from ${playerBroadcast ?: activeBroadcastName}.")
        playerBroadcast = null
        playbackTarget = PlaybackTarget.Relay
        currentScreen = AppScreen.Home
    }

    fun showNearbyUi() {
        stopPlayback("Disconnected from ${playerBroadcast ?: activeBroadcastName}.")
        playerBroadcast = null
        currentScreen = AppScreen.Nearby
    }

    fun startNearbyDiscovery() {
        if (settingsState.lanMeshEnabled) lanMesh.start()
    }

    fun stopNearbyDiscovery() {
        if (!settingsState.lanMeshEnabled) lanMesh.stop()
    }

    fun refreshNearbyPeers() {
        if (settingsState.lanMeshEnabled) lanMesh.refresh()
    }

    fun showSettingsUi() {
        stopPlayback("Disconnected from ${playerBroadcast ?: activeBroadcastName}.")
        playerBroadcast = null
        currentScreen = AppScreen.Home
    }

    fun saveConfigFromInput(): Boolean {
        val nextRelayConfig = relayConfigFromInput(configState.relayUrl, ::updateConfigStatus) ?: return false

        applyRelayConfig(nextRelayConfig)
        configStore.saveLanguage(configState.language)
        publishStatusMessage = text(R.string.relay_saved)
        subscribeStatusMessage = text(R.string.relay_saved)
        showMainUi()
        return true
    }

    fun saveSettingsFromInput(): Boolean {
        val nextRelayConfig = relayConfigFromInput(settingsState.relayUrl, ::updateSettingsStatus) ?: return false

        applyRelayConfig(nextRelayConfig)
        configState = configState
            .withRelayUrl(nextRelayConfig.relayUrl)
            .withLanguage(settingsState.language)
            .withPublishCompatibilityMode(settingsState.publishCompatibilityMode)
            .withH264ProfilePreference(settingsState.h264ProfilePreference)
            .withShowPlaybackStats(settingsState.showPlaybackStats)
            .withLanMeshEnabled(settingsState.lanMeshEnabled)
            .withStatus(text(R.string.relay_required))
        configStore.saveLanguage(settingsState.language)
        configStore.savePublishCompatibilityMode(settingsState.publishCompatibilityMode)
        configStore.saveH264ProfilePreference(settingsState.h264ProfilePreference)
        configStore.saveShowPlaybackStats(settingsState.showPlaybackStats)
        configStore.saveLanMeshEnabled(settingsState.lanMeshEnabled)
        publishStatusMessage = text(R.string.relay_updated)
        subscribeStatusMessage = text(R.string.relay_updated)
        showMainUi()
        return true
    }

    fun prepareSubscribe(): String? {
        val nextRelayConfig = relayConfigFromInput(homeRelayUrl, ::updateSubscribeStatus) ?: return null
        applyRelayConfig(nextRelayConfig)

        val nextBroadcast = subscribeBroadcastName.trim().trim('/')
        if (nextBroadcast.isEmpty()) {
            updateSubscribeStatus(text(R.string.broadcast_empty))
            return null
        }

        activeBroadcastName = nextBroadcast
        playerBroadcast = nextBroadcast
        subscribeStatusMessage = "Disconnected from $nextBroadcast."
        return nextBroadcast
    }

    fun preparePublish(
        hasCameraPermission: Boolean,
        hasRecordAudioPermission: Boolean,
        hasNotificationPermission: Boolean,
    ): PublishRequest {
        nearbyScreenPublishPending = false
        val nextRelayConfig = relayConfigFromInput(homeRelayUrl, ::updatePublishHomeStatus) ?: return PublishRequest.None
        applyRelayConfig(nextRelayConfig)

        val preparation = publishController.prepare(
            PublishPreparationInput(
                source = publishSource,
                broadcastInput = publishBroadcastName,
                includeSystemAudio = includeSystemAudio,
                includeMicrophone = includeMicrophone,
                cameraLensFacing = cameraLensFacing,
                cameraQualityPreset = cameraQualityPreset,
                publishFile = (publishFileState as? PublishFileState.Ready)?.file,
                permissions = PublishPermissions(
                    camera = hasCameraPermission,
                    notifications = hasNotificationPermission,
                    recordAudio = hasRecordAudioPermission,
                ),
            ),
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
        publishStatusMessage = text(R.string.publish_status_starting)
        val useLanMesh = nearbyScreenPublishPending
        publishController.startScreen(
            ScreenPublishStartRequest(
                endpointUrl = relayConfig.relayUrl,
                tlsFingerprint = null,
                connectionLabel = if (useLanMesh) text(R.string.nearby_title) else relayConfig.relayUrl,
                broadcastName = activeBroadcastName,
                resultCode = resultCode,
                resultData = resultData,
                metrics = metrics,
                includeSystemAudio = if (useLanMesh) false else includeSystemAudio,
                encoderPolicy = VideoEncoderPolicy.fromCompatibilityMode(configState.publishCompatibilityMode),
                h264ProfilePreference = configState.h264ProfilePreference,
                useLanMesh = useLanMesh,
            ),
        )
        if (useLanMesh) {
            nearbyMediaState = NearbyMediaStateReducer.publishingStarted()
            nearbyScreenPublishPending = false
        }
    }

    fun prepareNearbyScreenPublish(
        hasNotificationPermission: Boolean,
    ): PublishRequest {
        if (!settingsState.lanMeshEnabled) {
            updatePublishHomeStatus(text(R.string.nearby_mesh_disabled))
            return PublishRequest.None
        }
        if (nearbyMediaState is NearbyMediaState.ViewingRemote) {
            stopPlayback(text(R.string.nearby_playback_stopped_for_share))
            playerBroadcast = null
        }
        val localPeerId = lanMesh.localPeerId()
        if (localPeerId == null) {
            updatePublishHomeStatus(text(R.string.nearby_receiver_not_ready))
            return PublishRequest.None
        }
        activeBroadcastName = MoqPeerServer.screenBroadcast(localPeerId)
        nearbyScreenPublishPending = true
        val preparation = publishController.prepare(
            PublishPreparationInput(
                source = PublishSourceType.Screen,
                broadcastInput = activeBroadcastName,
                includeSystemAudio = false,
                includeMicrophone = false,
                cameraLensFacing = cameraLensFacing,
                cameraQualityPreset = cameraQualityPreset,
                publishFile = null,
                permissions = PublishPermissions(
                    camera = true,
                    notifications = hasNotificationPermission,
                    recordAudio = true,
                ),
            ),
        )
        preparation.broadcastName?.let { activeBroadcastName = it }
        updatePublishHomeStatus(preparation.message)
        if (preparation.request == PublishRequest.None) {
            nearbyScreenPublishPending = false
        } else {
            lanMesh.start()
        }
        return preparation.request
    }

    fun cancelNearbyScreenPublish(message: String) {
        nearbyScreenPublishPending = false
        failPublish(message)
        if (currentScreen == AppScreen.Nearby) {
            lanMesh.start()
        }
    }

    fun prepareNearbyPlayback(item: PeerListItem): String? {
        if (nearbyMediaState == NearbyMediaState.PublishingScreen) {
            updateSubscribeStatus(text(R.string.nearby_stop_sharing_first))
            return null
        }
        val path = item.screenBroadcastPath ?: return null
        val screen = lanMesh.broadcasts.value[path]
        if (screen?.availability != ScreenBroadcastAvailability.Available) return null
        stopPlayback("Disconnected from ${playerBroadcast ?: activeBroadcastName}.")
        activeBroadcastName = path
        playerBroadcast = path
        playbackTarget = PlaybackTarget.Nearby
        nearbyMediaState = NearbyMediaStateReducer.viewingStarted(nearbyMediaState, item.peerId) ?: return null
        return path
    }

    fun startCameraPublish() {
        publishStatusMessage = text(R.string.publish_status_starting_camera)
        publishController.startCamera(
            CameraPublishStartRequest(
                relayConfig = relayConfig,
                broadcastName = activeBroadcastName,
                encoderPolicy = VideoEncoderPolicy.fromCompatibilityMode(configState.publishCompatibilityMode),
                h264ProfilePreference = configState.h264ProfilePreference,
                includeMicrophone = includeMicrophone,
                lensFacing = cameraLensFacing,
                qualityPreset = cameraQualityPreset,
            ),
        )
    }

    fun startFilePublish() {
        val file = (publishFileState as? PublishFileState.Ready)?.file
        if (file == null) {
            failPublish("Choose a local video first.")
            return
        }
        publishStatusMessage = text(R.string.publish_status_starting_file)
        publishController.startFile(
            FilePublishStartRequest(
                relayConfig = relayConfig,
                broadcastName = activeBroadcastName,
                file = file,
            ),
        )
    }

    fun startPlayback(
        surface: Surface,
        onPlayerState: (PlayerState, String) -> Unit,
    ) {
        val nextBroadcast = playerBroadcast ?: return

        when (playbackTarget) {
            PlaybackTarget.Relay -> playbackController.start(
                surface = surface,
                relayUrl = relayConfig.relayUrl,
                broadcastName = nextBroadcast,
                onPlayerState = onPlayerState,
            )
            PlaybackTarget.Nearby -> {
                val consumer = lanMesh.consume()
                if (consumer == null) {
                    onPlayerState(PlayerState.Failed("Nearby receiver is not running."), "Nearby receiver is not running.")
                    return
                }
                playbackController.startPeer(
                    surface = surface,
                    originConsumer = consumer,
                    peerName = text(R.string.nearby_device),
                    broadcastName = nextBroadcast,
                    onPlayerState = onPlayerState,
                )
            }
        }
    }

    fun stopPlayback(message: String) {
        playbackController.stop()
        subscribeStatusMessage = message
        if (playbackTarget == PlaybackTarget.Nearby) nearbyMediaState = NearbyMediaStateReducer.stopped()
    }

    fun stopPublish(message: String) {
        publishController.stop()
        publishPanelMode = PublishPanelMode.Ready
        publishStatusMessage = message
        if (nearbyMediaState == NearbyMediaState.PublishingScreen) {
            nearbyMediaState = NearbyMediaStateReducer.stopped()
        }
        if (currentScreen == AppScreen.Home) updatePublishHomeStatus(message)
    }

    fun failPublish(message: String) {
        publishController.fail(message)
    }

    override fun onCleared() {
        Log.i(logTag, "AppViewModel cleared")
        lanMesh.stop()
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

    private fun applyRelayConfig(nextRelayConfig: RelayConfig) {
        relayConfig = nextRelayConfig
        homeRelayUrl = nextRelayConfig.relayUrl
        configState = configState.withRelayUrl(nextRelayConfig.relayUrl)
        settingsState = settingsState.withRelayUrl(nextRelayConfig.relayUrl)
        configStore.saveRelayUrl(nextRelayConfig.relayUrl)
    }

    private fun updatePublishStatus(state: PublishState) {
        val message = PublishStatusFormatter(localizedResources).format(state)
        Log.i(logTag, message)
        viewModelScope.launch(Dispatchers.Main.immediate) {
            publishPanelMode = state.toPublishPanelMode()
            publishStatusMessage = message
            if (
                nearbyMediaState == NearbyMediaState.PublishingScreen &&
                (state is PublishState.Stopped || state is PublishState.Failed)
            ) {
                nearbyMediaState = NearbyMediaStateReducer.stopped()
            }
        }
    }

    private fun PublishState.toPublishPanelMode(): PublishPanelMode = when (this) {
        PublishState.Preparing,
        is PublishState.Connecting,
        -> PublishPanelMode.Preparing
        is PublishState.Publishing,
        is PublishState.Stats,
        is PublishState.AudioFailed,
        -> PublishPanelMode.Publishing
        PublishState.Stopping,
        -> PublishPanelMode.Stopping
        is PublishState.Failed,
        -> PublishPanelMode.Error
        PublishState.Stopped,
        -> PublishPanelMode.Ready
    }

    private fun text(@StringRes resId: Int, vararg args: Any): String {
        return localizedResources.getString(resId, *args)
    }
}

private fun PeerServerState.serviceName(): String? =
    (lifecycle as? PeerListenerState.Listening)?.serviceName

private enum class PlaybackTarget {
    Relay,
    Nearby,
}

enum class AppScreen {
    Config,
    Home,
    Nearby,
}
