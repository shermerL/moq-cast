package com.example.moqandroid.network.lan.mesh

/** Connection state for one discovered LAN peer. */
sealed interface PeerConnectionState {
    data object Discovered : PeerConnectionState
    data object Waiting : PeerConnectionState
    data object Connecting : PeerConnectionState
    data object Connected : PeerConnectionState
    data object Reconnecting : PeerConnectionState
    data class Failed(val reason: String) : PeerConnectionState
    data object Lost : PeerConnectionState
}

/** Mutable peer state table owned by the LAN mesh runtime. */
class PeerConnectionDirectory {
    private val states = linkedMapOf<String, PeerConnectionState>()

    @Synchronized
    fun update(peerId: String, state: PeerConnectionState): Map<String, PeerConnectionState> {
        states[peerId] = state
        return snapshot()
    }

    @Synchronized
    fun reconcile(
        discoveredPeerIds: Set<String>,
        dialedPeerIds: Set<String>,
    ): Map<String, PeerConnectionState> {
        states.entries.removeAll { (peerId, state) ->
            peerId !in discoveredPeerIds && state == PeerConnectionState.Lost
        }
        states.keys.minus(discoveredPeerIds).forEach { peerId -> states[peerId] = PeerConnectionState.Lost }
        discoveredPeerIds.forEach { peerId ->
            val current = states[peerId]
            if (current == null || current == PeerConnectionState.Lost) {
                states[peerId] = if (peerId in dialedPeerIds) {
                    PeerConnectionState.Connecting
                } else {
                    PeerConnectionState.Waiting
                }
            }
        }
        return snapshot()
    }

    @Synchronized
    fun clear(): Map<String, PeerConnectionState> {
        states.clear()
        return emptyMap()
    }

    private fun snapshot(): Map<String, PeerConnectionState> = states.toMap()
}
