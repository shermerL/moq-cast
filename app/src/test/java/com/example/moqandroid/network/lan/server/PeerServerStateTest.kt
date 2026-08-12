package com.example.moqandroid.network.lan.server

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerServerStateTest {
    @Test
    fun closingOneInboundSessionDoesNotHideTheOthers() {
        val listening = PeerListenerState.Listening(serviceName = "local", port = 4443)
        val connected = PeerServerState(listening)
            .withActiveSessionDelta(1)
            .withActiveSessionDelta(1)

        val oneRemaining = connected.withActiveSessionDelta(-1)

        assertEquals(listening, oneRemaining.lifecycle)
        assertEquals(1, oneRemaining.activeSessionCount)
    }

    @Test
    fun activeSessionCountCannotBecomeNegative() {
        assertEquals(0, PeerServerState().withActiveSessionDelta(-1).activeSessionCount)
    }
}
