package com.example.moqandroid.publish.service

import com.example.moqandroid.publish.PublishState
import com.example.moqandroid.publish.PublishStatusFacade
import com.example.moqandroid.publish.PublishTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublishJobLifecycleTest {
    @Test
    fun stoppedIsReportedOnceAfterCleanupCompletes() = runBlocking {
        val facade = PublishStatusFacade()
        val generation = facade.beginPublish(PublishTarget.Lan)
        val lifecycle = PublishJobLifecycle()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var completionCount = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                }
                facade.markStopped(generation)
            }
        }
        lifecycle.replace(job)

        facade.requestStop(generation)
        lifecycle.cancelAndNotify {
            completionCount += 1
            facade.markStopped(generation)
        }
        lifecycle.cancelAndNotify {
            completionCount += 1
            facade.markStopped(generation)
        }
        cleanupStarted.await()

        assertEquals(PublishState.Stopping, facade.snapshot.value.state)
        assertEquals(PublishTarget.Lan, facade.snapshot.value.target)
        assertEquals(0, completionCount)

        releaseCleanup.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) { job.join() }

        assertEquals(PublishState.Stopped, facade.snapshot.value.state)
        assertNull(facade.snapshot.value.target)
        assertEquals(1, completionCount)
    }

    @Test
    fun oldTeardownCannotClearNewPublishGeneration() = runBlocking {
        val facade = PublishStatusFacade()
        val oldGeneration = facade.beginPublish(PublishTarget.Lan)
        val lifecycle = PublishJobLifecycle()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                }
                facade.markStopped(oldGeneration)
            }
        }
        lifecycle.replace(job)

        facade.requestStop(oldGeneration)
        lifecycle.cancelAndNotify { facade.markStopped(oldGeneration) }
        cleanupStarted.await()
        facade.beginPublish(PublishTarget.Relay)

        releaseCleanup.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) { job.join() }

        assertEquals(PublishState.Preparing, facade.snapshot.value.state)
        assertEquals(PublishTarget.Relay, facade.snapshot.value.target)
    }

    @Test
    fun repeatedStopWithoutAJobNotifiesOnce() {
        val lifecycle = PublishJobLifecycle()
        var completionCount = 0

        lifecycle.cancelAndNotify { completionCount += 1 }
        lifecycle.cancelAndNotify { completionCount += 1 }

        assertEquals(1, completionCount)
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 2_000L
    }
}
