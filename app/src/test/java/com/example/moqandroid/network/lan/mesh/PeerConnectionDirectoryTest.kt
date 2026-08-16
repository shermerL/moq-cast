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

        directory.begin("peer-a", generation = 1)
        directory.update("peer-a", generation = 1, PeerConnectionState.Connected)

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
        directory.begin("peer-a", generation = 1)
        directory.update("peer-a", generation = 1, PeerConnectionState.Connected)

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

    @Test
    fun keepsAHealthySessionAcrossTransientDiscoveryLoss() {
        val directory = PeerConnectionDirectory()
        directory.reconcile(setOf("peer-a"), setOf("peer-a"))
        directory.begin("peer-a", generation = 1)
        directory.update("peer-a", generation = 1, PeerConnectionState.Connected)

        assertEquals(
            mapOf("peer-a" to PeerConnectionState.Connected),
            directory.reconcile(emptySet(), emptySet(), retainedPeerIds = setOf("peer-a")),
        )

        assertEquals(
            mapOf("peer-a" to PeerConnectionState.Connected),
            directory.reconcile(setOf("peer-a"), setOf("peer-a")),
        )
    }

    @Test
    fun staleGenerationCannotOverwriteCurrentSession() {
        val directory = PeerConnectionDirectory()

        directory.begin("peer-a", generation = 1)
        directory.begin("peer-a", generation = 2)

        assertEquals(false, directory.update("peer-a", generation = 1, PeerConnectionState.Failed("stale")))
        assertEquals(true, directory.update("peer-a", generation = 2, PeerConnectionState.Connected))
        assertEquals(PeerConnectionState.Connected, directory.snapshot().getValue("peer-a"))
    }

    @Test
    fun followsConnectReconnectAndRecoveryForOneGeneration() {
        val directory = PeerConnectionDirectory()
        directory.begin("peer-a", generation = 7)
        assertEquals(PeerConnectionState.Connecting, directory.snapshot().getValue("peer-a"))

        directory.update("peer-a", generation = 7, PeerConnectionState.Connected)
        assertEquals(PeerConnectionState.Connected, directory.snapshot().getValue("peer-a"))

        directory.update("peer-a", generation = 7, PeerConnectionState.Reconnecting)
        assertEquals(PeerConnectionState.Reconnecting, directory.snapshot().getValue("peer-a"))

        directory.update("peer-a", generation = 7, PeerConnectionState.Connected)
        assertEquals(PeerConnectionState.Connected, directory.snapshot().getValue("peer-a"))
    }

    @Test
    fun startsANewGenerationWhenLostPeerReappears() {
        val directory = PeerConnectionDirectory()
        directory.begin("peer-a", generation = 1)
        directory.update("peer-a", generation = 1, PeerConnectionState.Connected)
        directory.reconcile(emptySet(), emptySet())
        assertEquals(PeerConnectionState.Lost, directory.snapshot().getValue("peer-a"))

        directory.reconcile(setOf("peer-a"), setOf("peer-a"))
        directory.begin("peer-a", generation = 2)

        assertEquals(PeerConnectionState.Connecting, directory.snapshot().getValue("peer-a"))
        assertEquals(false, directory.update("peer-a", generation = 1, PeerConnectionState.Connected))
        assertEquals(true, directory.update("peer-a", generation = 2, PeerConnectionState.Connected))
    }
}
