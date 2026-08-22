package com.example.moqandroid.network.lan.server

internal class NsdRegistrationCoordinator<T> {
    private var phase = Phase.Idle
    private var active: T? = null
    private var pending: T? = null
    private var stopRequested = false

    fun register(request: T): Transition<T> = when (phase) {
        Phase.Idle -> start(request)
        Phase.Registering -> {
            pending = request
            Transition()
        }
        Phase.Registered -> {
            pending = request
            phase = Phase.Unregistering
            Transition(unregister = true)
        }
        Phase.Unregistering -> {
            pending = request
            Transition()
        }
    }

    fun stop(): Transition<T> {
        pending = null
        return when (phase) {
            Phase.Idle -> Transition()
            Phase.Registering -> {
                stopRequested = true
                Transition()
            }
            Phase.Registered -> {
                phase = Phase.Unregistering
                Transition(unregister = true)
            }
            Phase.Unregistering -> Transition()
        }
    }

    fun registered(): Transition<T> {
        if (phase != Phase.Registering) return Transition()
        return if (stopRequested || pending != null) {
            phase = Phase.Unregistering
            Transition(unregister = true)
        } else {
            phase = Phase.Registered
            Transition(registered = active)
        }
    }

    fun registrationFailed(): Transition<T> {
        val failed = active.takeUnless { stopRequested }
        resetActive()
        return startPending(failed)
    }

    fun unregistered(): Transition<T> {
        resetActive()
        return startPending()
    }

    private fun start(request: T): Transition<T> {
        active = request
        phase = Phase.Registering
        stopRequested = false
        return Transition(register = request)
    }

    private fun startPending(failed: T? = null): Transition<T> {
        val next = pending
        pending = null
        return if (next == null) Transition(failed = failed) else start(next).copy(failed = failed)
    }

    private fun resetActive() {
        phase = Phase.Idle
        active = null
        stopRequested = false
    }

    private enum class Phase {
        Idle,
        Registering,
        Registered,
        Unregistering,
    }
}

internal data class Transition<T>(
    val register: T? = null,
    val unregister: Boolean = false,
    val registered: T? = null,
    val failed: T? = null,
)
