package com.example.moqandroid.network.lan.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BroadcastDirectoryTest {
    @Test
    fun tracksScreenAvailabilityByPath() {
        val directory = BroadcastDirectory()
        val path = BroadcastDirectory.screenPath("peer-a")

        directory.available(path, localPeerId = "local")
        val current = requireNotNull(directory.available(path, localPeerId = "local"))

        assertEquals(1, directory.state.value.size)
        assertEquals(ScreenBroadcastAvailability.Available, directory.state.value.getValue(path).availability)

        directory.withdrawn(current)

        assertEquals(ScreenBroadcastAvailability.Withdrawn, directory.state.value.getValue(path).availability)
    }

    @Test
    fun staleWithdrawalDoesNotReplaceANewerAnnouncement() {
        val directory = BroadcastDirectory()
        val path = BroadcastDirectory.screenPath("peer-a")
        val stale = requireNotNull(directory.available(path, localPeerId = "local"))
        val current = requireNotNull(directory.available(path, localPeerId = "local"))

        directory.withdrawn(stale)

        assertEquals(current, directory.state.value.getValue(path))
        assertEquals(ScreenBroadcastAvailability.Available, directory.state.value.getValue(path).availability)
    }

    @Test
    fun withdrawnBroadcastCanBecomeAvailableAgain() {
        val directory = BroadcastDirectory()
        val path = BroadcastDirectory.screenPath("peer-a")
        val first = requireNotNull(directory.available(path, localPeerId = "local"))
        directory.withdrawn(first)

        val second = requireNotNull(directory.available(path, localPeerId = "local"))

        assertEquals(ScreenBroadcastAvailability.Available, directory.state.value.getValue(path).availability)
        assertEquals(second, directory.state.value.getValue(path))
    }

    @Test
    fun rejectsLocalAndMalformedScreenPaths() {
        val directory = BroadcastDirectory()

        assertNull(directory.available("moqcast.screen/local", localPeerId = "local"))
        assertNull(directory.available("moqcast.screen/peer/extra", localPeerId = "local"))
        assertNull(directory.available("camera/peer", localPeerId = "local"))
        assertEquals(emptyMap<String, RemoteScreenBroadcast>(), directory.state.value)
    }
}
