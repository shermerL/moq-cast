package com.example.moqandroid.ui.nearby

import com.example.moqandroid.network.lan.discovery.DiscoveredPeer
import com.example.moqandroid.network.lan.mesh.PeerConnectionState
import com.example.moqandroid.network.lan.mesh.RemoteScreenBroadcast
import com.example.moqandroid.network.lan.mesh.ScreenBroadcastAvailability
import com.example.moqandroid.publish.PublishState
import com.example.moqandroid.publish.PublishTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyStateTest {
    @Test
    fun activeLanPublishRestoresAfterViewModelRecreation() {
        val restored = NearbyMediaStateReducer.fromPublishStatus(
            current = NearbyMediaState.ConnectedIdle,
            state = PublishState.Publishing(
                relayUrl = "moqt://192.168.1.20:4443",
                broadcastName = "moqcast.screen/local",
                width = 1280,
                height = 720,
                bitrate = 4_000_000,
                frameRate = 30,
                audioEnabled = false,
            ),
            target = PublishTarget.Lan,
        )

        assertEquals(NearbyMediaState.PublishingScreen, restored)
    }

    @Test
    fun activeRelayPublishDoesNotRestoreLanSession() {
        val restored = NearbyMediaStateReducer.fromPublishStatus(
            current = NearbyMediaState.ConnectedIdle,
            state = PublishState.Publishing(
                relayUrl = "https://relay.example/anon",
                broadcastName = "screen.hang",
                width = 1280,
                height = 720,
                bitrate = 4_000_000,
                frameRate = 30,
                audioEnabled = false,
            ),
            target = PublishTarget.Relay,
        )

        assertEquals(NearbyMediaState.ConnectedIdle, restored)
    }

    @Test
    fun combinesDiscoveryTransportAndScreenState() {
        val peer = DiscoveredPeer(
            serviceName = "peer-a",
            addresses = listOf("192.168.1.20"),
            port = 4443,
            fingerprint = "fingerprint",
            nodeUrl = "moqt://192.168.1.20:4443/.cluster/token",
            credential = "token",
        )
        val path = "moqcast.screen/peer-a"

        val item = PeerListProjector.project(
            peers = listOf(peer),
            connections = mapOf("peer-a" to PeerConnectionState.Connected),
            screens = mapOf(
                path to RemoteScreenBroadcast("peer-a", path, ScreenBroadcastAvailability.Available),
            ),
            localPeerId = "local",
        ).single()

        assertEquals("peer-a", item.peerId)
        assertEquals(PeerConnectionState.Connected, item.connectionState)
        assertEquals(path, item.screenBroadcastPath)
        assertTrue(item.canWatch)
    }

    @Test
    fun withdrawnScreenIsNotWatchable() {
        val peer = DiscoveredPeer("peer-a", listOf("192.168.1.20"), 4443, null, null, "token")
        val path = "moqcast.screen/peer-a"

        val item = PeerListProjector.project(
            peers = listOf(peer),
            connections = emptyMap(),
            screens = mapOf(
                path to RemoteScreenBroadcast("peer-a", path, ScreenBroadcastAvailability.Withdrawn),
            ),
            localPeerId = "local",
        ).single()

        assertFalse(item.canWatch)
    }

    @Test
    fun waitingPeerIsNotPresentedAsConnectedByInboundAggregateState() {
        val peer = DiscoveredPeer("peer-a", listOf("192.168.1.20"), 4443, null, null, "token")

        val item = PeerListProjector.project(
            peers = listOf(peer),
            connections = mapOf("peer-a" to PeerConnectionState.Waiting),
            screens = emptyMap(),
            localPeerId = "local",
        ).single()

        assertEquals(PeerConnectionState.Waiting, item.connectionState)
        assertFalse(item.canWatch)
    }

    @Test
    fun publishingBlocksRemotePlayback() {
        assertNull(
            NearbyMediaStateReducer.viewingStarted(
                NearbyMediaState.PublishingScreen,
                publisherId = "peer-a",
            ),
        )
        assertEquals(
            NearbyMediaState.ViewingRemote("peer-a"),
            NearbyMediaStateReducer.viewingStarted(NearbyMediaState.ConnectedIdle, "peer-a"),
        )
        assertEquals(NearbyMediaState.ConnectedIdle, NearbyMediaStateReducer.stopped())
    }

    @Test
    fun mediaLifecycleExposesOneValidActionSetPerPhase() {
        val idle = NearbyActionPolicy.project(
            mediaState = NearbyMediaState.ConnectedIdle,
            canReachPeer = true,
            screenAvailable = true,
        )
        assertTrue(idle.canShareScreen)
        assertTrue(idle.canWatch)
        assertFalse(idle.canOpenActiveSession)
        assertFalse(idle.canStop)

        val preparing = NearbyActionPolicy.project(
            mediaState = NearbyMediaState.PreparingScreen,
            canReachPeer = true,
            screenAvailable = true,
        )
        assertFalse(preparing.canShareScreen)
        assertFalse(preparing.canWatch)
        assertFalse(preparing.canOpenActiveSession)
        assertTrue(preparing.canStop)

        val publishing = NearbyActionPolicy.project(
            mediaState = NearbyMediaState.PublishingScreen,
            canReachPeer = true,
            screenAvailable = true,
        )
        assertFalse(publishing.canShareScreen)
        assertFalse(publishing.canWatch)
        assertFalse(publishing.canOpenActiveSession)
        assertTrue(publishing.canStop)

        val viewing = NearbyActionPolicy.project(
            mediaState = NearbyMediaState.ViewingRemote("peer-a"),
            canReachPeer = true,
            screenAvailable = true,
        )
        assertFalse(viewing.canShareScreen)
        assertFalse(viewing.canWatch)
        assertTrue(viewing.canOpenActiveSession)
        assertTrue(viewing.canStop)

        val stopping = NearbyActionPolicy.project(
            mediaState = NearbyMediaState.StoppingScreen,
            canReachPeer = true,
            screenAvailable = true,
        )
        assertFalse(stopping.canShareScreen)
        assertFalse(stopping.canWatch)
        assertFalse(stopping.canOpenActiveSession)
        assertFalse(stopping.canStop)
    }

    @Test
    fun preparingAndStoppingKeepMeshStateIndependentFromMedia() {
        assertEquals(
            NearbyMediaState.PreparingScreen,
            NearbyMediaStateReducer.preparingScreenStarted(NearbyMediaState.ConnectedIdle),
        )
        assertEquals(
            NearbyMediaState.StoppingScreen,
            NearbyMediaStateReducer.stopping(NearbyMediaState.PreparingScreen),
        )
        assertNull(NearbyMediaStateReducer.stopping(NearbyMediaState.StoppingScreen))
        assertNull(NearbyMediaStateReducer.viewingStarted(NearbyMediaState.PreparingScreen, "peer-a"))
        assertEquals(NearbyMediaState.ConnectedIdle, NearbyMediaStateReducer.stopped())
    }

    @Test
    fun preparingProjectionIsIdempotentWhilePermissionFlowContinues() {
        assertEquals(
            NearbyMediaState.PreparingScreen,
            NearbyMediaStateReducer.preparingScreenStarted(NearbyMediaState.PreparingScreen),
        )
    }

    @Test
    fun unavailableTransportDisablesGlobalShareButNotDirectoryState() {
        val actions = NearbyActionPolicy.project(
            mediaState = NearbyMediaState.ConnectedIdle,
            canReachPeer = false,
            screenAvailable = true,
        )

        assertFalse(actions.canShareScreen)
        assertTrue(actions.canWatch)
    }
}
