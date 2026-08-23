package com.example.moqandroid.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackJobChainTest {
    @Test
    fun newJobWaitsForPreviousTeardown() = runBlocking {
        val order = mutableListOf<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirstTeardown = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        val first = launch {
            firstStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    order += "first teardown started"
                    releaseFirstTeardown.await()
                    order += "first teardown complete"
                }
            }
        }
        firstStarted.await()
        first.cancel()

        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitPreviousPlayback(first)
            order += "second started"
            secondStarted.complete(Unit)
            awaitCancellation()
        }
        yield()

        assertFalse(secondStarted.isCompleted)
        releaseFirstTeardown.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) { secondStarted.await() }
        assertTrue(order.indexOf("first teardown complete") < order.indexOf("second started"))

        second.cancel()
        withTimeout(TEST_TIMEOUT_MS) { second.join() }
    }

    @Test
    fun cancelledIntermediateJobStillWaitsForEarlierTeardown() = runBlocking {
        val order = mutableListOf<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirstTeardown = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val thirdStarted = CompletableDeferred<Unit>()
        var resourceFactoryCalls = 0

        val first = launch {
            firstStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    order += "first teardown started"
                    releaseFirstTeardown.await()
                    order += "first teardown complete"
                }
            }
        }
        firstStarted.await()
        first.cancel()

        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitPreviousPlayback(first)
            resourceFactoryCalls += 1
            secondStarted.complete(Unit)
            awaitCancellation()
        }
        second.cancel()

        val third = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitPreviousPlayback(second)
            order += "third started"
            thirdStarted.complete(Unit)
            awaitCancellation()
        }
        yield()

        assertFalse(secondStarted.isCompleted)
        assertFalse(thirdStarted.isCompleted)
        releaseFirstTeardown.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) { thirdStarted.await() }
        assertFalse(secondStarted.isCompleted)
        assertEquals(0, resourceFactoryCalls)
        assertTrue(order.indexOf("first teardown complete") < order.indexOf("third started"))

        third.cancel()
        withTimeout(TEST_TIMEOUT_MS) { third.join() }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 2_000L
    }
}
