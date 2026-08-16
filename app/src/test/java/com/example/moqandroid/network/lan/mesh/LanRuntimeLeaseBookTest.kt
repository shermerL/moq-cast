package com.example.moqandroid.network.lan.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanRuntimeLeaseBookTest {
    @Test
    fun publishReservationKeepsRuntimeAfterUiReleasesIt() {
        val leases = LanRuntimeLeaseBook()
        val uiLease = leases.acquireUi()
        val reservation = leases.reservePublish()

        assertTrue(leases.releaseUi(uiLease))
        assertTrue(leases.shouldRun)
        assertTrue(leases.claimPublish(reservation))
        assertTrue(leases.shouldRun)
        assertTrue(leases.releasePublish(reservation))
        assertFalse(leases.shouldRun)
    }

    @Test
    fun cancelledReservationStopsTheLastRuntimeOwner() {
        val leases = LanRuntimeLeaseBook()
        val reservation = leases.reservePublish()

        assertTrue(leases.shouldRun)
        assertTrue(leases.cancelReservation(reservation))
        assertFalse(leases.shouldRun)
    }

    @Test
    fun reservationCanOnlyBeClaimedAndReleasedOnce() {
        val leases = LanRuntimeLeaseBook()
        val reservation = leases.reservePublish()

        assertTrue(leases.claimPublish(reservation))
        assertFalse(leases.claimPublish(reservation))
        assertTrue(leases.releasePublish(reservation))
        assertFalse(leases.releasePublish(reservation))
    }

    @Test
    fun releasingPublishKeepsRuntimeWhileUiLeaseIsActive() {
        val leases = LanRuntimeLeaseBook()
        val uiLease = leases.acquireUi()
        val reservation = leases.reservePublish()

        assertTrue(leases.claimPublish(reservation))
        assertTrue(leases.releasePublish(reservation))
        assertTrue(leases.shouldRun)
        assertTrue(leases.releaseUi(uiLease))
        assertFalse(leases.shouldRun)
    }

    @Test
    fun claimedReservationCannotReleaseThePublishLease() {
        val leases = LanRuntimeLeaseBook()
        val reservation = leases.reservePublish()

        assertTrue(leases.claimPublish(reservation))
        assertFalse(leases.cancelReservation(reservation))
        assertTrue(leases.shouldRun)
        assertTrue(leases.releasePublish(reservation))
        assertFalse(leases.shouldRun)
    }

    @Test
    fun stalePublishLeaseCannotStopANewerOwner() {
        val leases = LanRuntimeLeaseBook()
        val oldReservation = leases.reservePublish()
        assertTrue(leases.claimPublish(oldReservation))
        assertTrue(leases.releasePublish(oldReservation))

        val currentReservation = leases.reservePublish()
        assertTrue(leases.claimPublish(currentReservation))
        assertFalse(leases.releasePublish(oldReservation))
        assertTrue(leases.shouldRun)
        assertTrue(leases.releasePublish(currentReservation))
        assertFalse(leases.shouldRun)
    }

    @Test
    fun staleUiLeaseCannotStopANewerOwner() {
        val leases = LanRuntimeLeaseBook()
        val oldLease = leases.acquireUi()
        assertTrue(leases.releaseUi(oldLease))

        val currentLease = leases.acquireUi()
        assertFalse(leases.releaseUi(oldLease))
        assertTrue(leases.shouldRun)
        assertTrue(leases.releaseUi(currentLease))
        assertFalse(leases.shouldRun)
    }
}
