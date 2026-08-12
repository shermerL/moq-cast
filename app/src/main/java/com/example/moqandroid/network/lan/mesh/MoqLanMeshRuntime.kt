package com.example.moqandroid.network.lan.mesh

import android.content.Context
import android.util.Log
import com.example.moqandroid.network.lan.discovery.DiscoveredPeer
import com.example.moqandroid.network.lan.discovery.MoqNsdDiscovery
import com.example.moqandroid.network.lan.peer.toPublishTarget
import com.example.moqandroid.network.lan.server.MoqPeerServer
import com.example.moqandroid.network.lan.server.PeerListenerState
import com.example.moqandroid.network.lan.server.PeerServerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.moq.MoqClient
import uniffi.moq.MoqConnectionStatus

/** Keeps one bidirectional MoQ session to every discovered LAN peer. */
class MoqLanMeshRuntime(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val discovery = MoqNsdDiscovery(context.applicationContext)
    private val server = MoqPeerServer(context.applicationContext, scope)
    private val peerJobs = linkedMapOf<String, PeerJob>()
    private val peerDirectory = PeerConnectionDirectory()
    private val mutablePeerStates = MutableStateFlow<Map<String, PeerConnectionState>>(emptyMap())
    private var reconcileJob: Job? = null
    private var nextPeerGeneration = 0L
    private var started = false

    val discoveryState = discovery.state
    val serverState = server.state
    val broadcasts = server.broadcasts
    val peerStates: StateFlow<Map<String, PeerConnectionState>> = mutablePeerStates.asStateFlow()

    fun start() {
        if (started) return
        started = true
        server.start()
        discovery.start()
        reconcileJob = scope.launch {
            combine(discovery.state, server.state) { discovered, listener -> discovered.peers to listener }
                .collect { (peers, listener) ->
                    reconcile(peers, listener.serviceName())
                }
        }
    }

    fun refresh() {
        if (started) discovery.restart() else start()
    }

    fun stop() {
        if (!started) return
        started = false
        reconcileJob?.cancel()
        reconcileJob = null
        synchronized(peerJobs) {
            peerJobs.values.forEach { it.job.cancel() }
            peerJobs.clear()
        }
        publishPeerStates(peerDirectory::clear)
        discovery.stop()
        server.stop()
    }

    fun consume() = server.consume()

    fun localPeerId(): String? = server.state.value.serviceName()

    private fun reconcile(peers: List<DiscoveredPeer>, localId: String?) {
        if (!started) return
        if (localId == null) {
            synchronized(peerJobs) {
                peerJobs.values.forEach { it.job.cancel() }
                peerJobs.clear()
            }
            publishPeerStates {
                peerDirectory.reconcile(peers.mapTo(mutableSetOf(), DiscoveredPeer::id), emptySet())
            }
            return
        }

        val discovered = peers.associateBy(DiscoveredPeer::id)
        val wanted = discovered.values
            .filter { peer -> localId < peer.id }
            .associateBy(DiscoveredPeer::id)

        synchronized(peerJobs) {
            peerJobs.keys.minus(wanted.keys).forEach { id ->
                peerJobs.remove(id)?.job?.cancel()
                Log.i(LOG_TAG, "LAN mesh peer removed id=$id")
            }
        }
        publishPeerStates { peerDirectory.reconcile(discovered.keys, wanted.keys) }

        wanted.forEach { (id, peer) ->
            synchronized(peerJobs) {
                val current = peerJobs[id]
                if (current?.peer == peer) return@synchronized
                current?.job?.cancel()
                updatePeerState(id, PeerConnectionState.Connecting)
                val generation = ++nextPeerGeneration
                val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { connect(peer, generation) }
                peerJobs[id] = PeerJob(peer, generation, job)
                job.start()
            }
        }
    }

    private suspend fun connect(peer: DiscoveredPeer, generation: Long) {
        val target = peer.toPublishTarget().getOrElse { error ->
            Log.w(LOG_TAG, "LAN mesh peer cannot be dialed id=${peer.id}: ${error.message}")
            updatePeerState(peer.id, generation, PeerConnectionState.Failed(error.message ?: "Invalid peer endpoint."))
            return
        }
        val origin = server.origin() ?: return

        try {
            MoqClient().use { client ->
                client.setTlsFingerprints(listOf(target.fingerprint))
                client.setPublish(origin)
                client.setConsume(origin)
                client.setReconnect(true)
                client.connect(target.url).use { session ->
                    while (currentCoroutineContext().isActive) {
                        val state = when (session.status()) {
                            MoqConnectionStatus.CONNECTED -> PeerConnectionState.Connected
                            MoqConnectionStatus.DISCONNECTED,
                            MoqConnectionStatus.MIGRATING,
                            -> PeerConnectionState.Reconnecting
                        }
                        updatePeerState(peer.id, generation, state)
                        Log.i(LOG_TAG, "LAN mesh peer status id=${peer.id} state=${state::class.simpleName}")
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "LAN mesh peer ended id=${peer.id}", error)
            if (discovery.state.value.peers.any { it.id == peer.id }) {
                updatePeerState(
                    peer.id,
                    generation,
                    PeerConnectionState.Failed(error.message ?: error::class.java.simpleName),
                )
            } else {
                updatePeerState(peer.id, generation, PeerConnectionState.Lost)
            }
        }
    }

    private fun updatePeerState(peerId: String, state: PeerConnectionState) {
        publishPeerStates { peerDirectory.update(peerId, state) }
    }

    private fun updatePeerState(peerId: String, generation: Long, state: PeerConnectionState) {
        synchronized(peerJobs) {
            if (peerJobs[peerId]?.generation != generation) return
            updatePeerState(peerId, state)
        }
    }

    private fun publishPeerStates(update: () -> Map<String, PeerConnectionState>) {
        synchronized(peerDirectory) {
            mutablePeerStates.value = update()
        }
    }

    private data class PeerJob(
        val peer: DiscoveredPeer,
        val generation: Long,
        val job: Job,
    )

    private companion object {
        private const val LOG_TAG = "MoqAndroid"
    }
}

private fun PeerServerState.serviceName(): String? =
    (lifecycle as? PeerListenerState.Listening)?.serviceName
