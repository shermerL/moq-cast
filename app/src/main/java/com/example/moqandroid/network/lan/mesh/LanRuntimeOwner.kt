package com.example.moqandroid.network.lan.mesh

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import uniffi.moq.MoqOriginProducer
import java.util.concurrent.atomic.AtomicBoolean

class LanRuntimeOwner(context: Context) {
    private val lock = Any()
    private val leases = LanRuntimeLeaseBook()
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runtime = MoqLanMeshRuntime(context.applicationContext, runtimeScope)

    val discoveryState = runtime.discoveryState
    val serverState = runtime.serverState
    val broadcasts = runtime.broadcasts
    val peers = runtime.peers
    val peerStates = runtime.peerStates

    fun acquireUi(): UiLease {
        val id = mutate { leases.acquireUi() }
        return UiLease(this, id)
    }

    fun reservePublish(): PublishReservation? {
        val id = mutate { leases.reservePublish() }
        if (LanMeshOriginRegistry.current() == null) {
            cancelReservation(id)
            return null
        }
        return PublishReservation(this, id)
    }

    fun claimPublish(id: Long): PublishLease? {
        val claimed = mutate { leases.claimPublish(id) }
        return if (claimed) PublishLease(this, id) else null
    }

    fun refresh() = synchronized(lock) {
        if (leases.shouldRun) runtime.refresh()
    }

    fun consume() = runtime.consume()

    fun localPeerId(): String? = runtime.localPeerId()

    private fun releaseUi(id: Long) {
        mutate { leases.releaseUi(id) }
    }

    private fun cancelReservation(id: Long) {
        mutate { leases.cancelReservation(id) }
    }

    private fun releasePublish(id: Long) {
        mutate { leases.releasePublish(id) }
    }

    private fun origin(id: Long): MoqOriginProducer? = synchronized(lock) {
        if (leases.isPublishing(id)) LanMeshOriginRegistry.current() else null
    }

    private inline fun <T> mutate(action: () -> T): T = synchronized(lock) {
        val wasRunning = leases.shouldRun
        val result = action()
        when {
            !wasRunning && leases.shouldRun -> runtime.start()
            wasRunning && !leases.shouldRun -> runtime.stop()
        }
        result
    }

    class UiLease internal constructor(
        private val owner: LanRuntimeOwner,
        private val id: Long,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true)) owner.releaseUi(id)
        }
    }

    class PublishReservation internal constructor(
        private val owner: LanRuntimeOwner,
        val id: Long,
    ) : AutoCloseable {
        private val handedOff = AtomicBoolean()

        fun handoff() {
            check(handedOff.compareAndSet(false, true)) { "LAN publish reservation is no longer available." }
        }

        override fun close() {
            if (handedOff.compareAndSet(false, true)) owner.cancelReservation(id)
        }
    }

    class PublishLease internal constructor(
        private val owner: LanRuntimeOwner,
        private val id: Long,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()

        fun origin(): MoqOriginProducer? = if (closed.get()) null else owner.origin(id)

        override fun close() {
            if (closed.compareAndSet(false, true)) owner.releasePublish(id)
        }
    }
}
