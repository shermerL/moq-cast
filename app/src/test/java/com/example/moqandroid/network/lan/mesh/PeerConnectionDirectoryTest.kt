package com.example.moqandroid.network.lan.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerConnectionDirectoryTest {
    @Test
    fun keepsIndependentStateForEveryDiscoveredPeer() {
        val directory = PeerConnectionDirectory()

        assertEquals(
            mapOf(
                "peer-a" to PeerConnectionState.Connecting,
                "peer-b" to PeerConnectionState.Waiting,
            ),
            directory.reconcile(setOf("peer-a", "peer-b"), setOf("peer-a")),
        )

        directory.update("peer-a", PeerConnectionState.Connected)

        assertEquals(
            mapOf(
                "peer-a" to PeerConnectionState.Connected,
                "peer-b" to PeerConnectionState.Waiting,
            ),
            directory.reconcile(setOf("peer-a", "peer-b"), setOf("peer-a")),
        )
    }

    @Test
    fun marksOnlyTheRemovedPeerLost() {
        val directory = PeerConnectionDirectory()
        directory.reconcile(setOf("peer-a", "peer-b"), setOf("peer-a"))
        directory.update("peer-a", PeerConnectionState.Connected)

        assertEquals(
            mapOf(
                "peer-a" to PeerConnectionState.Connected,
                "peer-b" to PeerConnectionState.Lost,
            ),
            directory.reconcile(setOf("peer-a"), setOf("peer-a")),
        )

        assertEquals(
            mapOf("peer-a" to PeerConnectionState.Connected),
            directory.reconcile(setOf("peer-a"), setOf("peer-a")),
        )
    }
}
