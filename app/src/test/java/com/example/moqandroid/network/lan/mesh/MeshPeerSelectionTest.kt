package com.example.moqandroid.network.lan.mesh

import com.example.moqandroid.network.lan.discovery.DiscoveredPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshPeerSelectionTest {
    @Test
    fun twoNodesChooseExactlyOneDialerForEitherIdOrdering() {
        assertTrue(shouldDialPeer(localId = "peer-a", peerId = "peer-b"))
        assertFalse(shouldDialPeer(localId = "peer-b", peerId = "peer-a"))
    }

    @Test
    fun listenerRestartDoesNotReintroduceOldLocalServiceAsRemote() {
        val oldLocal = peer("local-old")
        val remote = peer("peer-b")

        assertEquals(
            listOf(remote),
            remotePeers(
                peers = listOf(oldLocal, remote),
                localPeerIds = setOf("local-old", "local-new"),
            ),
        )
    }

    private fun peer(id: String) = DiscoveredPeer(
        serviceName = id,
        addresses = listOf("192.168.1.20"),
        port = 4443,
        fingerprint = "fingerprint",
        nodeUrl = null,
        credential = "credential",
    )
}
