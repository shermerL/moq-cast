package com.example.moqandroid.network.lan.server

import android.content.Context
import android.util.Log
import com.example.moqandroid.network.lan.mesh.BroadcastDirectory
import com.example.moqandroid.network.lan.mesh.LanMeshOriginRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.moq.MoqOriginConsumer
import uniffi.moq.MoqOriginOptions
import uniffi.moq.MoqOriginProducer
import uniffi.moq.MoqRequest
import uniffi.moq.MoqServer
import java.security.MessageDigest
import java.security.SecureRandom

class MoqPeerServer(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val advertiser = MoqNsdAdvertiser(context.applicationContext)
    private val mutableState = MutableStateFlow(PeerServerState())
    private val broadcastDirectory = BroadcastDirectory()
    private val resourceLock = Any()

    private var serverJob: Job? = null
    private var restartAfterStop = false
    private var server: MoqServer? = null
    private var receiveOrigin: MoqOriginProducer? = null
    private val stateTracker = PeerServerStateTracker()

    val state: StateFlow<PeerServerState> = mutableState.asStateFlow()
    val broadcasts = broadcastDirectory.state

    fun start() {
        val job = synchronized(resourceLock) {
            val current = serverJob
            if (current != null && !current.isCompleted) {
                if (!current.isActive) restartAfterStop = true
                return
            }

            restartAfterStop = false
            scope.launch(Dispatchers.IO) { runServer() }.also { serverJob = it }
        }
        job.invokeOnCompletion {
            val shouldRestart = synchronized(resourceLock) {
                if (serverJob !== job) return@synchronized false
                serverJob = null
                restartAfterStop.also { restartAfterStop = false }
            }
            if (shouldRestart && scope.isActive) start()
        }
    }

    fun stop() {
        val (job, listener) = synchronized(resourceLock) {
            restartAfterStop = false
            serverJob to server
        }
        job?.cancel(CancellationException("Nearby receive stopped."))
        listener?.cancel()
        advertiser.unregister()
        broadcastDirectory.reset()
        publishState(stateTracker.stop())
    }

    fun consume(): MoqOriginConsumer? = synchronized(resourceLock) { receiveOrigin?.consume() }

    fun origin(): MoqOriginProducer? = synchronized(resourceLock) { receiveOrigin }

    private suspend fun runServer() {
        currentCoroutineContext().ensureActive()
        val instance = randomHex(INSTANCE_BYTES)
        val credential = randomHex(CREDENTIAL_BYTES)
        val listenerGeneration = stateTracker.begin()
        publishState(listenerGeneration.state)
        Log.i(LOG_TAG, "LAN listener event=starting generation=${listenerGeneration.generation}")
        try {
            MoqOriginProducer(MoqOriginOptions()).use { origin ->
                LanMeshOriginRegistry.attach(origin)
                MoqServer().use { listener ->
                    synchronized(resourceLock) {
                        receiveOrigin = origin
                        server = listener
                    }
                    listener.setBind("[::]:0")
                    listener.setTlsGenerate(listOf("moqcast-lan"))
                    listener.setPublish(origin)
                    listener.setConsume(origin)
                    val localAddress = listener.listen()
                    val port = localAddress.substringAfterLast(':').toInt()
                    val fingerprint = listener.certFingerprints().firstOrNull()
                        ?: error("The MoQ server did not generate a TLS fingerprint.")

                    withContext(Dispatchers.Main.immediate) {
                        advertiser.register(
                            serviceName = instance,
                            port = port,
                            fingerprint = fingerprint,
                            credential = credential,
                            onRegistered = { registeredName ->
                                val registered = stateTracker.listener(
                                    listenerGeneration.generation,
                                    PeerListenerState.Listening(registeredName, port),
                                )
                                if (registered != null) {
                                    publishState(registered)
                                    Log.i(
                                        LOG_TAG,
                                        "LAN listener event=listening generation=${listenerGeneration.generation} " +
                                            "service=$registeredName port=$port",
                                    )
                                }
                            },
                            onFailure = { code ->
                                val failed = stateTracker.listener(
                                    listenerGeneration.generation,
                                    PeerListenerState.Failed("mDNS registration failed ($code)."),
                                )
                                publishState(failed)
                                listener.cancel()
                            },
                        )
                    }

                    val broadcastJob = scope.launch(Dispatchers.IO) {
                        val routeJobs = linkedMapOf<String, Job>()
                        origin.consume().use { consumer ->
                            consumer.announced("").use { announcements ->
                                try {
                                    while (isActive) {
                                        val announcement = announcements.next() ?: break
                                        announcement.use {
                                            val path = announcement.path()
                                            val screen = broadcastDirectory.available(path, mutableState.value.serviceName())
                                                ?: return@use
                                            routeJobs.remove(path)?.cancel()
                                            val broadcast = announcement.broadcast()
                                            routeJobs[path] = launch {
                                                broadcast.use {
                                                    try {
                                                        val routes = broadcast.routeUpdates()
                                                        routes.use {
                                                            while (isActive) {
                                                                val route = routes.next() ?: break
                                                                if (!route.announce) break
                                                            }
                                                        }
                                                    } finally {
                                                        broadcastDirectory.withdrawn(screen)
                                                        Log.i(
                                                            LOG_TAG,
                                                            "LAN broadcast event=withdrawn path=$path revision=${screen.revision}",
                                                        )
                                                    }
                                                }
                                            }
                                            Log.i(
                                                LOG_TAG,
                                                "LAN broadcast event=available path=${screen.path} " +
                                                    "publisher=${screen.publisherId} revision=${screen.revision}",
                                            )
                                        }
                                    }
                                } finally {
                                    routeJobs.values.forEach(Job::cancel)
                                    routeJobs.clear()
                                }
                            }
                        }
                    }

                    try {
                        acceptLoop(listener, credential, listenerGeneration.generation)
                    } finally {
                        broadcastJob.cancel()
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "Nearby MoQ server failed.", error)
            publishState(
                stateTracker.listener(
                    listenerGeneration.generation,
                    PeerListenerState.Failed(error.message ?: error::class.java.simpleName),
                ),
            )
        } finally {
            advertiser.unregister()
            broadcastDirectory.reset()
            synchronized(resourceLock) {
                receiveOrigin?.let(LanMeshOriginRegistry::detach)
                server = null
                receiveOrigin = null
            }
            publishState(stateTracker.finish(listenerGeneration.generation))
        }
    }

    private suspend fun acceptLoop(
        listener: MoqServer,
        credential: String,
        listenerGeneration: Long,
    ) = coroutineScope {
        while (scope.isActive) {
            val request = listener.accept() ?: break
            launch {
                runCatching {
                    request.use {
                        if (!request.authorized(credential)) {
                            Log.w(LOG_TAG, "Rejected nearby MoQ request transport=${request.transport()}")
                            request.reject(403u.toUShort())
                        } else {
                            request.accept().use { session ->
                                val accepted = stateTracker.session(listenerGeneration, 1)
                                publishState(accepted)
                                if (accepted != null) {
                                    Log.i(
                                        LOG_TAG,
                                        "LAN inbound event=connected generation=$listenerGeneration " +
                                            "transport=${request.transport()} count=${accepted.activeSessionCount}",
                                    )
                                }
                                try {
                                    session.closed()
                                } finally {
                                    val closed = stateTracker.session(listenerGeneration, -1)
                                    publishState(closed)
                                    if (closed != null) {
                                        Log.i(
                                            LOG_TAG,
                                            "LAN inbound event=closed generation=$listenerGeneration " +
                                                "count=${closed.activeSessionCount}",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }.onFailure { error ->
                    if (error !is CancellationException) Log.w(LOG_TAG, "Nearby peer session failed.", error)
                }
            }
        }
    }

    private fun MoqRequest.authorized(credential: String): Boolean {
        val requestPath = path()
        val expected = "/.cluster/$credential"
        return MessageDigest.isEqual(
            requestPath.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount).also(SecureRandom()::nextBytes)
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    companion object {
        fun screenBroadcast(publisherId: String): String = BroadcastDirectory.screenPath(publisherId)

        private const val INSTANCE_BYTES = 8
        private const val CREDENTIAL_BYTES = 16
        private const val LOG_TAG = "MoqAndroid"
    }

    private fun publishState(state: PeerServerState?) {
        if (state != null) mutableState.value = state
    }
}

private fun PeerServerState.serviceName(): String? =
    (lifecycle as? PeerListenerState.Listening)?.serviceName

data class PeerServerState(
    val lifecycle: PeerListenerState = PeerListenerState.Idle,
    val activeSessionCount: Int = 0,
)

internal fun PeerServerState.withActiveSessionDelta(delta: Int): PeerServerState =
    copy(activeSessionCount = (activeSessionCount + delta).coerceAtLeast(0))

internal class PeerServerStateTracker {
    private var generation = 0L

    var state = PeerServerState()
        private set

    @Synchronized
    fun begin(): ListenerGeneration {
        generation += 1
        state = PeerServerState(PeerListenerState.Starting)
        return ListenerGeneration(generation, state)
    }

    @Synchronized
    fun listener(expectedGeneration: Long, lifecycle: PeerListenerState): PeerServerState? {
        if (expectedGeneration != generation) return null
        state = PeerServerState(lifecycle)
        return state
    }

    @Synchronized
    fun session(expectedGeneration: Long, delta: Int): PeerServerState? {
        if (expectedGeneration != generation || state.lifecycle !is PeerListenerState.Listening) return null
        state = state.withActiveSessionDelta(delta)
        return state
    }

    @Synchronized
    fun finish(expectedGeneration: Long): PeerServerState? {
        if (expectedGeneration != generation || state.lifecycle is PeerListenerState.Failed) return null
        state = PeerServerState(PeerListenerState.Idle)
        return state
    }

    @Synchronized
    fun stop(): PeerServerState {
        generation += 1
        state = PeerServerState(PeerListenerState.Idle)
        return state
    }
}

internal data class ListenerGeneration(
    val generation: Long,
    val state: PeerServerState,
)

sealed interface PeerListenerState {
    data object Idle : PeerListenerState
    data object Starting : PeerListenerState
    data class Listening(val serviceName: String, val port: Int) : PeerListenerState
    data class Failed(val reason: String) : PeerListenerState
}
