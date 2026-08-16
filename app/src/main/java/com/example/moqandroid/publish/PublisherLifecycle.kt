package com.example.moqandroid.publish

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class PublisherState {
    data object Idle : PublisherState()
    data object Preparing : PublisherState()
    data class Connecting(val relayUrl: String, val broadcastName: String) : PublisherState()
    data class Publishing(
        val relayUrl: String,
        val broadcastName: String,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val frameRate: Int,
        val audioEnabled: Boolean,
    ) : PublisherState()

    data object Stopping : PublisherState()
    data object Stopped : PublisherState()
    data class Error(val reason: String) : PublisherState()
}

sealed class PublisherEvent {
    data class TrackStarted(val name: String) : PublisherEvent()
    data class TrackStopped(val name: String) : PublisherEvent()
    data class TrackError(val name: String, val reason: String) : PublisherEvent()
    data class StatsUpdated(
        val relayUrl: String,
        val broadcastName: String,
        val frames: Int,
        val bytes: Long,
        val bitrateKbps: Double,
    ) : PublisherEvent()
}

class PublisherLifecycleEventSink(
    private val update: (PublisherState) -> Unit,
    private val emit: (PublisherEvent) -> Unit,
) {
    fun update(state: PublisherState) {
        update.invoke(state)
    }

    fun emit(event: PublisherEvent) {
        emit.invoke(event)
    }
}

enum class PublishTarget {
    Relay,
    Lan,
}

data class PublishStatusSnapshot(
    val state: PublishState,
    val target: PublishTarget?,
)

class PublishStatusFacade {
    private data class ActivePublishContext(
        val generation: Long,
        val target: PublishTarget,
    )

    private val mutableSnapshot = MutableStateFlow(
        PublishStatusSnapshot(PublishState.Stopped, target = null),
    )
    private var publisherState: PublisherState = PublisherState.Idle
    private var activeContext: ActivePublishContext? = null
    private var nextGeneration = 0L

    val snapshot: StateFlow<PublishStatusSnapshot> = mutableSnapshot.asStateFlow()

    val isActive: Boolean
        get() = when (publisherState) {
            PublisherState.Idle,
            PublisherState.Stopped,
            is PublisherState.Error,
            -> false

            PublisherState.Preparing,
            is PublisherState.Connecting,
            is PublisherState.Publishing,
            PublisherState.Stopping,
            -> true
        }

    val canReportStopped: Boolean
        get() = publisherState !is PublisherState.Error

    @Synchronized
    fun prepare(target: PublishTarget) {
        replaceContext(target)
        updateState(PublisherState.Preparing)
    }

    @Synchronized
    fun beginPublish(target: PublishTarget): Long {
        val context = activeContext
            ?.takeIf { publisherState == PublisherState.Preparing && it.target == target }
            ?: replaceContext(target)
        updateState(PublisherState.Preparing)
        return context.generation
    }

    @Synchronized
    fun requestStop(): Boolean {
        val generation = activeContext?.generation ?: return false
        return requestStop(generation)
    }

    @Synchronized
    fun requestStop(generation: Long): Boolean {
        if (!isCurrent(generation) || !isActive) return false
        updateState(PublisherState.Stopping)
        return true
    }

    @Synchronized
    fun markStopped() {
        activeContext?.generation?.let(::markStopped) ?: updateState(PublisherState.Stopped)
    }

    @Synchronized
    fun markStopped(generation: Long) {
        if (isCurrent(generation)) updateState(PublisherState.Stopped)
    }

    @Synchronized
    fun fail(reason: String) {
        activeContext?.generation?.let { fail(it, reason) } ?: updateState(PublisherState.Error(reason))
    }

    @Synchronized
    fun fail(generation: Long, reason: String) {
        if (isCurrent(generation)) updateState(PublisherState.Error(reason))
    }

    fun eventSink(generation: Long): PublisherLifecycleEventSink {
        return PublisherLifecycleEventSink(
            update = { updateState(generation, it) },
            emit = { updateEvent(generation, it) },
        )
    }

    private fun replaceContext(target: PublishTarget): ActivePublishContext {
        return ActivePublishContext(++nextGeneration, target).also { activeContext = it }
    }

    private fun isCurrent(generation: Long): Boolean = activeContext?.generation == generation

    private fun updateState(state: PublisherState) {
        publisherState = state
        val target = if (state == PublisherState.Stopped || state is PublisherState.Error) {
            activeContext = null
            null
        } else {
            activeContext?.target
        }
        mutableSnapshot.value = PublishStatusSnapshot(state.toPublishState(), target)
    }

    @Synchronized
    private fun updateState(generation: Long, state: PublisherState) {
        if (isCurrent(generation)) updateState(state)
    }

    @Synchronized
    private fun updateEvent(generation: Long, event: PublisherEvent) {
        if (!isCurrent(generation)) return
        if (!canApply(event)) return
        if (event is PublisherEvent.TrackError && event.name != AUDIO_TRACK_NAME) {
            publisherState = PublisherState.Error(event.reason)
            activeContext = null
        }
        val state = event.toPublishState() ?: return
        mutableSnapshot.value = PublishStatusSnapshot(state, activeContext?.target)
    }

    private fun canApply(event: PublisherEvent): Boolean {
        return when (publisherState) {
            PublisherState.Idle,
            PublisherState.Stopped,
            is PublisherState.Error,
            -> false

            PublisherState.Preparing,
            is PublisherState.Connecting,
            -> event is PublisherEvent.TrackError

            is PublisherState.Publishing -> true

            PublisherState.Stopping -> event is PublisherEvent.TrackError
        }
    }

    private fun PublisherState.toPublishState(): PublishState = when (this) {
        PublisherState.Idle -> PublishState.Stopped
        PublisherState.Preparing -> PublishState.Preparing
        is PublisherState.Connecting -> PublishState.Connecting(relayUrl, broadcastName)
        is PublisherState.Publishing -> PublishState.Publishing(
            relayUrl = relayUrl,
            broadcastName = broadcastName,
            width = width,
            height = height,
            bitrate = bitrate,
            frameRate = frameRate,
            audioEnabled = audioEnabled,
        )
        PublisherState.Stopping -> PublishState.Stopping
        PublisherState.Stopped -> PublishState.Stopped
        is PublisherState.Error -> PublishState.Failed(reason)
    }

    private fun PublisherEvent.toPublishState(): PublishState? = when (this) {
        is PublisherEvent.StatsUpdated -> PublishState.Stats(relayUrl, broadcastName, frames, bytes, bitrateKbps)
        is PublisherEvent.TrackError -> if (name == AUDIO_TRACK_NAME) {
            PublishState.AudioFailed(reason)
        } else {
            PublishState.Failed(reason)
        }
        is PublisherEvent.TrackStarted,
        is PublisherEvent.TrackStopped,
        -> null
    }

    private companion object {
        private const val AUDIO_TRACK_NAME = "audio"
    }
}
