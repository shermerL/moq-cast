package com.example.moqandroid.publish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublishStatusFacadeTest {
    @Test
    fun preparingSnapshotRetainsLanTargetForPermissionContinuation() {
        val facade = PublishStatusFacade()

        facade.prepare(PublishTarget.Lan)

        assertEquals(PublishState.Preparing, facade.snapshot.value.state)
        assertEquals(PublishTarget.Lan, facade.snapshot.value.target)
    }

    @Test
    fun terminalStateClearsPublishTarget() {
        val facade = PublishStatusFacade()
        facade.prepare(PublishTarget.Lan)
        val generation = facade.beginPublish(PublishTarget.Lan)

        facade.markStopped(generation)

        assertEquals(PublishState.Stopped, facade.snapshot.value.state)
        assertNull(facade.snapshot.value.target)
    }

    @Test
    fun oldGenerationCannotClearNewPublishContext() {
        val facade = PublishStatusFacade()
        val oldGeneration = facade.beginPublish(PublishTarget.Relay)
        val newGeneration = facade.beginPublish(PublishTarget.Lan)

        assertNotEquals(oldGeneration, newGeneration)
        facade.fail(oldGeneration, "old publish failed")
        facade.markStopped(oldGeneration)

        assertEquals(PublishState.Preparing, facade.snapshot.value.state)
        assertEquals(PublishTarget.Lan, facade.snapshot.value.target)
    }

    @Test
    fun newPublishReplacesOldEventSink() {
        val facade = PublishStatusFacade()
        val oldGeneration = facade.beginPublish(PublishTarget.Lan)
        val oldSink = facade.eventSink(oldGeneration)
        val newGeneration = facade.beginPublish(PublishTarget.Relay)
        val newSink = facade.eventSink(newGeneration)

        oldSink.update(
            PublisherState.Publishing("old", "old", 640, 480, 1, 30, false),
        )
        newSink.update(PublisherState.Connecting("new", "new"))

        assertEquals(PublishState.Connecting("new", "new"), facade.snapshot.value.state)
        assertEquals(PublishTarget.Relay, facade.snapshot.value.target)
    }

    @Test
    fun failureClearsPublishTarget() {
        val facade = PublishStatusFacade()
        val generation = facade.beginPublish(PublishTarget.Lan)

        facade.fail(generation, "failed")

        assertEquals(PublishState.Failed("failed"), facade.snapshot.value.state)
        assertNull(facade.snapshot.value.target)
    }
}
