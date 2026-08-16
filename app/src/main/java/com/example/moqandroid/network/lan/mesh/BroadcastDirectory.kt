package com.example.moqandroid.network.lan.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScreenBroadcastAvailability {
    Available,
    Withdrawn,
}

data class RemoteScreenBroadcast(
    val publisherId: String,
    val path: String,
    val availability: ScreenBroadcastAvailability,
    internal val revision: Long = 0,
)

/** Tracks remote screen announcements by broadcast path. */
class BroadcastDirectory {
    private val mutableState = MutableStateFlow<Map<String, RemoteScreenBroadcast>>(emptyMap())
    private var nextRevision = 0L

    val state: StateFlow<Map<String, RemoteScreenBroadcast>> = mutableState.asStateFlow()

    @Synchronized
    fun available(path: String, localPeerId: String?): RemoteScreenBroadcast? {
        val publisherId = publisherId(path) ?: return null
        if (publisherId == localPeerId) return null
        val screen = RemoteScreenBroadcast(
            publisherId = publisherId,
            path = path,
            availability = ScreenBroadcastAvailability.Available,
            revision = ++nextRevision,
        )
        mutableState.value = mutableState.value + (path to screen)
        return screen
    }

    @Synchronized
    fun withdrawn(screen: RemoteScreenBroadcast) {
        val current = mutableState.value[screen.path] ?: return
        if (current !== screen) return
        mutableState.value = mutableState.value + (
            screen.path to current.copy(availability = ScreenBroadcastAvailability.Withdrawn)
        )
    }

    @Synchronized
    fun reset() {
        mutableState.value = emptyMap()
    }

    companion object {
        private const val SCREEN_PREFIX = "moqcast.screen/"

        fun screenPath(publisherId: String): String = SCREEN_PREFIX + publisherId

        fun publisherId(path: String): String? {
            if (!path.startsWith(SCREEN_PREFIX)) return null
            return path.removePrefix(SCREEN_PREFIX).takeIf { it.isNotBlank() && '/' !in it }
        }
    }
}
