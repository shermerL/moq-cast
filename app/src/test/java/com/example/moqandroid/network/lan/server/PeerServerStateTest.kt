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

    @Test
    fun staleSessionCannotChangeANewerListenerCount() {
        val tracker = PeerServerStateTracker()
        val first = tracker.begin()
        tracker.listener(first.generation, PeerListenerState.Listening("first", 4443))
        tracker.session(first.generation, 1)

        val second = tracker.begin()
        tracker.listener(second.generation, PeerListenerState.Listening("second", 4444))
        tracker.session(second.generation, 1)
        tracker.session(first.generation, -1)

        assertEquals(PeerListenerState.Listening("second", 4444), tracker.state.lifecycle)
        assertEquals(1, tracker.state.activeSessionCount)
    }

    @Test
    fun stoppingListenerClearsInboundSessions() {
        val tracker = PeerServerStateTracker()
        val listener = tracker.begin()
        tracker.listener(listener.generation, PeerListenerState.Listening("local", 4443))
        tracker.session(listener.generation, 2)

        tracker.stop()

        assertEquals(PeerListenerState.Idle, tracker.state.lifecycle)
        assertEquals(0, tracker.state.activeSessionCount)
    }
}
