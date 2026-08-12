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
    data object PreparingScreen : NearbyMediaState
    data object PublishingScreen : NearbyMediaState
    data object StoppingScreen : NearbyMediaState
    data class ViewingRemote(val publisherId: String) : NearbyMediaState
}

/** Keeps local screen publishing and remote screen playback mutually exclusive. */
object NearbyMediaStateReducer {
    fun preparingScreenStarted(current: NearbyMediaState): NearbyMediaState? {
        return NearbyMediaState.PreparingScreen.takeIf { current == NearbyMediaState.ConnectedIdle }
    }

    fun viewingStarted(current: NearbyMediaState, publisherId: String): NearbyMediaState? {
        return NearbyMediaState.ViewingRemote(publisherId).takeIf { current == NearbyMediaState.ConnectedIdle }
    }

    fun stopping(current: NearbyMediaState): NearbyMediaState? {
        return NearbyMediaState.StoppingScreen.takeIf {
            current == NearbyMediaState.PreparingScreen || current == NearbyMediaState.PublishingScreen
        }
    }

    fun stopped(): NearbyMediaState = NearbyMediaState.ConnectedIdle
}

data class NearbyActionAvailability(
    val canShareScreen: Boolean,
    val canWatch: Boolean,
    val canOpenActiveSession: Boolean,
    val canStop: Boolean,
)

/** Projects media lifecycle and transport facts into actions the Nearby UI may expose. */
object NearbyActionPolicy {
    fun project(
        mediaState: NearbyMediaState,
        canReachPeer: Boolean,
        screenAvailable: Boolean,
    ): NearbyActionAvailability = NearbyActionAvailability(
        canShareScreen = mediaState == NearbyMediaState.ConnectedIdle && canReachPeer,
        canWatch = mediaState == NearbyMediaState.ConnectedIdle && screenAvailable,
        canOpenActiveSession = mediaState is NearbyMediaState.ViewingRemote,
        canStop = mediaState == NearbyMediaState.PreparingScreen ||
            mediaState == NearbyMediaState.PublishingScreen ||
            mediaState is NearbyMediaState.ViewingRemote,
    )
}

data class NearbyActions(
    val onBack: () -> Unit,
    val onStartDiscovery: () -> Unit,
    val onStopDiscovery: () -> Unit,
    val onRefresh: () -> Unit,
    val onShareScreen: () -> Unit,
    val onOpenActiveSession: () -> Unit,
    val onStopMedia: () -> Unit,
    val onWatch: (PeerListItem) -> Unit,
)
