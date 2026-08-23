package com.example.moqandroid.network.lan.discovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NsdResolveAttemptPolicyTest {
    @Test
    fun oneTransientFailureCanBeRetriedWithoutAnAdditionalPeer() {
        val policy = NsdResolveAttemptPolicy()
        policy.found("peer-a")
        policy.started("peer-a")

        assertTrue(policy.canRetry("peer-a", available = true))

        policy.started("peer-a")
        assertFalse(policy.canRetry("peer-a", available = true))
    }

    @Test
    fun lostServiceIsNotRetried() {
        val policy = NsdResolveAttemptPolicy()
        policy.found("peer-a")
        policy.started("peer-a")
        policy.remove("peer-a")

        assertFalse(policy.canRetry("peer-a", available = false))
    }

    @Test
    fun aNewFoundEventStartsANewBoundedAttemptSequence() {
        val policy = NsdResolveAttemptPolicy()
        policy.found("peer-a")
        policy.started("peer-a")
        policy.started("peer-a")
        assertFalse(policy.canRetry("peer-a", available = true))

        policy.found("peer-a")
        policy.started("peer-a")
        assertTrue(policy.canRetry("peer-a", available = true))
    }
}
