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
    private var listenerState: PeerListenerState = PeerListenerState.Idle
    private var activeSessionCount = 0

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
        updateListenerState(PeerListenerState.Idle)
    }

    fun consume(): MoqOriginConsumer? = synchronized(resourceLock) { receiveOrigin?.consume() }

    fun origin(): MoqOriginProducer? = synchronized(resourceLock) { receiveOrigin }

    private suspend fun runServer() {
        val instance = randomHex(INSTANCE_BYTES)
        val credential = randomHex(CREDENTIAL_BYTES)
        updateListenerState(PeerListenerState.Starting)
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
                                updateListenerState(PeerListenerState.Listening(registeredName, port))
                            },
                            onFailure = { code ->
                                updateListenerState(PeerListenerState.Failed("mDNS registration failed ($code)."))
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
                                                        Log.i(LOG_TAG, "Nearby screen broadcast withdrawn name=$path")
                                                    }
                                                }
                                            }
                                            Log.i(
                                                LOG_TAG,
                                                "Nearby screen broadcast available name=${screen.path} publisher=${screen.publisherId}",
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
                        acceptLoop(listener, credential)
                    } finally {
                        broadcastJob.cancel()
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "Nearby MoQ server failed.", error)
            updateListenerState(PeerListenerState.Failed(error.message ?: error::class.java.simpleName))
        } finally {
            advertiser.unregister()
            broadcastDirectory.reset()
            synchronized(resourceLock) {
                receiveOrigin?.let(LanMeshOriginRegistry::detach)
                server = null
                receiveOrigin = null
            }
            if (scope.isActive && mutableState.value.lifecycle !is PeerListenerState.Failed) {
                updateListenerState(PeerListenerState.Idle)
            }
        }
    }

    private suspend fun acceptLoop(listener: MoqServer, credential: String) = coroutineScope {
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
                                updateActiveSessions(1)
                                Log.i(LOG_TAG, "Accepted nearby MoQ peer transport=${request.transport()}")
                                try {
                                    session.closed()
                                } finally {
                                    updateActiveSessions(-1)
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

    private fun updateListenerState(state: PeerListenerState) {
        synchronized(resourceLock) {
            listenerState = state
            if (state == PeerListenerState.Idle || state is PeerListenerState.Failed) activeSessionCount = 0
            mutableState.value = PeerServerState(listenerState, activeSessionCount)
        }
    }

    private fun updateActiveSessions(delta: Int) {
        synchronized(resourceLock) {
            val next = PeerServerState(listenerState, activeSessionCount).withActiveSessionDelta(delta)
            activeSessionCount = next.activeSessionCount
            mutableState.value = next
        }
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

sealed interface PeerListenerState {
    data object Idle : PeerListenerState
    data object Starting : PeerListenerState
    data class Listening(val serviceName: String, val port: Int) : PeerListenerState
    data class Failed(val reason: String) : PeerListenerState
}
