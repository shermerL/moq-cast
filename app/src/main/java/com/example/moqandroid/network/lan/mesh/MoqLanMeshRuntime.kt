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
    private var currentLocalPeerId: String? = null
    private var previousLocalPeerId: String? = null
    private var lastIgnoredLocalPeerId: String? = null
    private val mutablePeerStates = MutableStateFlow<Map<String, PeerConnectionState>>(emptyMap())
    private val mutablePeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    private var reconcileJob: Job? = null
    private var nextPeerGeneration = 0L
    private var started = false

    val discoveryState = discovery.state
    val serverState = server.state
    val broadcasts = server.broadcasts
    val peers: StateFlow<List<DiscoveredPeer>> = mutablePeers.asStateFlow()
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
        mutablePeers.value = emptyList()
        discovery.stop()
        server.stop()
    }

    fun consume() = server.consume()

    fun localPeerId(): String? = server.state.value.serviceName()

    private fun reconcile(peers: List<DiscoveredPeer>, localId: String?) {
        if (!started) return
        if (localId != null && localId != currentLocalPeerId) {
            previousLocalPeerId = currentLocalPeerId
            currentLocalPeerId = localId
        }
        val localPeerIds = setOfNotNull(currentLocalPeerId, previousLocalPeerId)
        val remotePeers = remotePeers(peers, localPeerIds)
        val ignoredLocalPeerId = peers.asSequence()
            .map(DiscoveredPeer::id)
            .firstOrNull { it in localPeerIds }
        if (ignoredLocalPeerId != null && ignoredLocalPeerId != lastIgnoredLocalPeerId) {
            lastIgnoredLocalPeerId = ignoredLocalPeerId
            Log.i(LOG_TAG, "LAN discovery event=ignored-local service=$ignoredLocalPeerId")
        } else if (ignoredLocalPeerId == null) {
            lastIgnoredLocalPeerId = null
        }
        if (localId == null) {
            synchronized(peerJobs) {
                peerJobs.values.forEach { it.job.cancel() }
                peerJobs.clear()
            }
            publishPeerStates {
                peerDirectory.reconcile(remotePeers.mapTo(mutableSetOf(), DiscoveredPeer::id), emptySet())
            }
            mutablePeers.value = remotePeers
            return
        }

        val discovered = remotePeers.associateBy(DiscoveredPeer::id)
        val wanted = discovered.values
            .filter { peer -> shouldDialPeer(localId, peer.id) }
            .associateBy(DiscoveredPeer::id)
        val retained = retainedPeerIds(discovered.keys)

        synchronized(peerJobs) {
            peerJobs.keys.minus(wanted.keys + retained).forEach { id ->
                peerJobs.remove(id)?.job?.cancel()
                Log.i(LOG_TAG, "LAN peer event=removed id=$id reason=not-discovered")
            }
        }
        publishPeerStates { peerDirectory.reconcile(discovered.keys, wanted.keys, retained) }
        publishVisiblePeers(discovered, retained)

        wanted.forEach { (id, peer) ->
            synchronized(peerJobs) {
                val current = peerJobs[id]
                if (current?.peer == peer) return@synchronized
                current?.job?.cancel()
                val generation = ++nextPeerGeneration
                publishPeerStates { peerDirectory.begin(id, generation) }
                Log.i(LOG_TAG, "LAN peer event=connecting id=$id generation=$generation")
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
        val origins = server.sessionOrigins()
        if (origins == null) {
            updatePeerState(peer.id, generation, PeerConnectionState.Failed("LAN media origins are not ready."))
            return
        }

        try {
            MoqClient().use { client ->
                client.setTlsFingerprints(listOf(target.fingerprint))
                client.setPublish(origins.publishOrigin)
                client.setConsume(origins.consumeOrigin)
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
        } finally {
            if (discovery.state.value.peers.none { it.id == peer.id }) {
                synchronized(peerJobs) {
                    if (peerJobs[peer.id]?.generation == generation) peerJobs.remove(peer.id)
                }
                publishVisiblePeers(
                    discovery.state.value.peers.associateBy(DiscoveredPeer::id),
                    retainedPeerIds = retainedPeerIds(
                        discovery.state.value.peers.mapTo(mutableSetOf(), DiscoveredPeer::id),
                    ),
                )
            }
        }
    }

    private fun updatePeerState(peerId: String, generation: Long, state: PeerConnectionState) {
        val changed = synchronized(peerJobs) {
            if (peerJobs[peerId]?.generation != generation) return
            synchronized(peerDirectory) {
                val previous = peerDirectory.snapshot()[peerId]
                if (previous == state) return
                if (!peerDirectory.update(peerId, generation, state)) return
                mutablePeerStates.value = peerDirectory.snapshot()
                true
            }
        }
        if (changed) {
            Log.i(LOG_TAG, "LAN peer event=state id=$peerId generation=$generation state=${state.logName()}")
        }
    }

    private fun publishPeerStates(update: () -> Map<String, PeerConnectionState>) {
        synchronized(peerDirectory) {
            mutablePeerStates.value = update()
        }
    }

    private fun publishVisiblePeers(
        discovered: Map<String, DiscoveredPeer>,
        retainedPeerIds: Set<String>,
    ) {
        val retainedPeers = synchronized(peerJobs) {
            retainedPeerIds.mapNotNull { peerJobs[it]?.peer }
        }
        mutablePeers.value = (discovered.values + retainedPeers)
            .distinctBy(DiscoveredPeer::id)
            .sortedBy { it.serviceName.lowercase() }
    }

    private fun retainedPeerIds(discoveredPeerIds: Set<String>): Set<String> = synchronized(peerJobs) {
        val states = peerDirectory.snapshot()
        peerJobs.keys.filterTo(mutableSetOf()) { id ->
            id !in discoveredPeerIds &&
                peerJobs[id]?.job?.isActive == true &&
                states[id].keepsSessionAcrossDiscoveryLoss()
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

private fun PeerConnectionState?.keepsSessionAcrossDiscoveryLoss(): Boolean =
    this == PeerConnectionState.Connected || this == PeerConnectionState.Reconnecting

internal fun remotePeers(
    peers: List<DiscoveredPeer>,
    localPeerIds: Set<String>,
): List<DiscoveredPeer> = peers.filterNot { peer -> peer.id in localPeerIds }

internal fun shouldDialPeer(localId: String, peerId: String): Boolean = localId < peerId

private fun PeerConnectionState.logName(): String = when (this) {
    PeerConnectionState.Discovered -> "discovered"
    PeerConnectionState.Waiting -> "waiting"
    PeerConnectionState.Connecting -> "connecting"
    PeerConnectionState.Connected -> "connected"
    PeerConnectionState.Reconnecting -> "reconnecting"
    is PeerConnectionState.Failed -> "failed"
    PeerConnectionState.Lost -> "lost"
}

private fun PeerServerState.serviceName(): String? =
    (lifecycle as? PeerListenerState.Listening)?.serviceName
