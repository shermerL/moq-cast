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
    private val generations = linkedMapOf<String, Long>()

    @Synchronized
    fun begin(peerId: String, generation: Long): Map<String, PeerConnectionState> {
        generations[peerId] = generation
        states[peerId] = PeerConnectionState.Connecting
        return snapshot()
    }

    @Synchronized
    fun update(peerId: String, generation: Long, state: PeerConnectionState): Boolean {
        if (generations[peerId] != generation) return false
        states[peerId] = state
        return true
    }

    @Synchronized
    fun reconcile(
        discoveredPeerIds: Set<String>,
        dialedPeerIds: Set<String>,
        retainedPeerIds: Set<String> = emptySet(),
    ): Map<String, PeerConnectionState> {
        states.entries.removeAll { (peerId, state) ->
            peerId !in discoveredPeerIds && peerId !in retainedPeerIds && state == PeerConnectionState.Lost
        }
        generations.keys.retainAll(discoveredPeerIds + retainedPeerIds)
        states.keys.minus(discoveredPeerIds + retainedPeerIds).forEach { peerId ->
            states[peerId] = PeerConnectionState.Lost
        }
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
        generations.clear()
        return emptyMap()
    }

    @Synchronized
    fun snapshot(): Map<String, PeerConnectionState> = states.toMap()
}
