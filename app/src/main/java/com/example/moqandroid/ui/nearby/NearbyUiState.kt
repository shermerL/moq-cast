package com.example.moqandroid.ui.nearby

import com.example.moqandroid.network.lan.discovery.DiscoveryPhase
import com.example.moqandroid.network.lan.server.PeerServerState

data class NearbyUiState(
    val phase: DiscoveryPhase,
    val peers: List<PeerListItem>,
    val errorCode: Int?,
    val serverState: PeerServerState,
    val mediaState: NearbyMediaState,
    val canShareScreen: Boolean,
)

sealed interface NearbyMediaState {
    data object ConnectedIdle : NearbyMediaState
    data object PublishingScreen : NearbyMediaState
    data class ViewingRemote(val publisherId: String) : NearbyMediaState
}

/** Keeps local screen publishing and remote screen playback mutually exclusive. */
object NearbyMediaStateReducer {
    fun publishingStarted(): NearbyMediaState = NearbyMediaState.PublishingScreen

    fun viewingStarted(current: NearbyMediaState, publisherId: String): NearbyMediaState? {
        return if (current == NearbyMediaState.PublishingScreen) null else NearbyMediaState.ViewingRemote(publisherId)
    }

    fun stopped(): NearbyMediaState = NearbyMediaState.ConnectedIdle
}

data class NearbyActions(
    val onBack: () -> Unit,
    val onStartDiscovery: () -> Unit,
    val onStopDiscovery: () -> Unit,
    val onRefresh: () -> Unit,
    val onShareScreen: () -> Unit,
    val onStopSharing: () -> Unit,
    val onWatch: (PeerListItem) -> Unit,
)
