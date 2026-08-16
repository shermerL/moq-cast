package com.example.moqandroid.network.lan.mesh

internal class LanRuntimeLeaseBook {
    private var nextId = 0L
    private val uiLeases = mutableSetOf<Long>()
    private val publishReservations = mutableSetOf<Long>()
    private val publishLeases = mutableSetOf<Long>()

    val shouldRun: Boolean
        get() = uiLeases.isNotEmpty() || publishReservations.isNotEmpty() || publishLeases.isNotEmpty()

    fun acquireUi(): Long = nextId().also(uiLeases::add)

    fun releaseUi(id: Long): Boolean = uiLeases.remove(id)

    fun reservePublish(): Long = nextId().also(publishReservations::add)

    fun cancelReservation(id: Long): Boolean = publishReservations.remove(id)

    fun claimPublish(id: Long): Boolean {
        if (!publishReservations.remove(id)) return false
        return publishLeases.add(id)
    }

    fun releasePublish(id: Long): Boolean = publishLeases.remove(id)

    fun isPublishing(id: Long): Boolean = id in publishLeases

    private fun nextId(): Long {
        do {
            nextId = if (nextId == Long.MAX_VALUE) 1L else nextId + 1L
        } while (nextId in uiLeases || nextId in publishReservations || nextId in publishLeases)
        return nextId
    }
}
