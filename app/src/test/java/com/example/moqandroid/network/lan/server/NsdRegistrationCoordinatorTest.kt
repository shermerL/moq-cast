package com.example.moqandroid.network.lan.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NsdRegistrationCoordinatorTest {
    @Test
    fun nextRegistrationWaitsForOldUnregistrationCallback() {
        val coordinator = NsdRegistrationCoordinator<String>()

        assertEquals("old", coordinator.register("old").register)
        assertNull(coordinator.register("new").register)
        assertTrue(coordinator.registered().unregister)
        assertEquals("new", coordinator.unregistered().register)
    }

    @Test
    fun repeatedRegistrationKeepsOnlyLatestPendingRequest() {
        val coordinator = NsdRegistrationCoordinator<String>()

        coordinator.register("old")
        coordinator.register("middle")
        coordinator.register("new")
        coordinator.registered()

        assertEquals("new", coordinator.unregistered().register)
    }

    @Test
    fun stopWhileRegisteringCannotRestartPendingRegistration() {
        val coordinator = NsdRegistrationCoordinator<String>()

        coordinator.register("old")
        coordinator.register("new")
        coordinator.stop()
        assertTrue(coordinator.registered().unregister)

        val stopped = coordinator.unregistered()
        assertNull(stopped.register)
        assertFalse(stopped.unregister)
    }

    @Test
    fun unregistrationFailureStillReleasesLatestPendingRegistration() {
        val coordinator = NsdRegistrationCoordinator<String>()

        coordinator.register("old")
        coordinator.registered()
        assertTrue(coordinator.register("new").unregister)

        assertEquals("new", coordinator.unregistered().register)
    }
}
