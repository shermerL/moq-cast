package com.example.moqandroid.ui.nearby

import com.example.moqandroid.network.lan.discovery.DiscoveredPeer
import com.example.moqandroid.network.lan.mesh.PeerConnectionState
import com.example.moqandroid.network.lan.mesh.RemoteScreenBroadcast
import com.example.moqandroid.network.lan.mesh.ScreenBroadcastAvailability

data class PeerListItem(
    val peerId: String,
    val displayName: String,
    val endpoint: String,
    val connectionState: PeerConnectionState,
    val screenBroadcastPath: String?,
) {
    val canWatch: Boolean
        get() = screenBroadcastPath != null
}

/** Combines discovery, transport and screen availability for the Nearby UI. */
object PeerListProjector {
    fun project(
        peers: List<DiscoveredPeer>,
        connections: Map<String, PeerConnectionState>,
        screens: Map<String, RemoteScreenBroadcast>,
        localPeerId: String?,
    ): List<PeerListItem> {
        val availableScreens = screens.values
            .filter { it.availability == ScreenBroadcastAvailability.Available }
            .associateBy(RemoteScreenBroadcast::publisherId)

        return peers
            .asSequence()
            .filterNot { it.id == localPeerId || it.serviceName == localPeerId }
            .map { peer ->
                PeerListItem(
                    peerId = peer.id,
                    displayName = peer.serviceName,
                    endpoint = peer.endpointText(),
                    connectionState = connections[peer.id] ?: PeerConnectionState.Discovered,
                    screenBroadcastPath = availableScreens[peer.id]?.path
                        ?: availableScreens[peer.serviceName]?.path,
                )
            }
            .sortedBy { it.displayName.lowercase() }
            .toList()
    }
}

private fun DiscoveredPeer.endpointText(): String {
    val address = addresses.firstOrNull() ?: "?"
    return if (address.contains(':')) "[$address]:$port" else "$address:$port"
}
