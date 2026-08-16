package com.example.moqandroid.network.lan.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

class MoqNsdDiscovery(context: Context) {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(DiscoveryState())
    private val availableServices = mutableSetOf<String>()
    private val resolvedPeers = linkedMapOf<String, DiscoveredPeer>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private val queuedServices = mutableSetOf<String>()

    private var activeListener: NsdManager.DiscoveryListener? = null
    private var resolvingService: String? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var stopRequested = false
    private var restartAfterStop = false

    val state: StateFlow<DiscoveryState> = mutableState.asStateFlow()

    fun start() = onMainThread {
        if (activeListener != null) {
            if (stopRequested) restartAfterStop = true
            return@onMainThread
        }

        val listener = createDiscoveryListener()
        activeListener = listener
        mutableState.value = DiscoveryState(phase = DiscoveryPhase.Starting)

        runCatching {
            acquireMulticastLock()
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            Log.w(LOG_TAG, "Could not start MoQ service discovery.", error)
            failDiscovery(listener, ERROR_START_EXCEPTION)
        }
    }

    fun restart() = onMainThread {
        requestStop(restart = true)
    }

    fun stop() = onMainThread {
        stopInternal()
    }

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = onMainThread {
                if (activeListener !== this || stopRequested) return@onMainThread
                Log.i(LOG_TAG, "MoQ service discovery started type=$serviceType")
                publishState(DiscoveryPhase.Scanning)
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) = onMainThread {
                if (activeListener !== this || stopRequested) return@onMainThread
                val serviceName = serviceInfo.serviceName
                availableServices += serviceName
                Log.i(LOG_TAG, "LAN discovery event=found service=$serviceName")
                if (
                    serviceName != resolvingService &&
                    serviceName !in resolvedPeers &&
                    queuedServices.add(serviceName)
                ) {
                    resolveQueue.addLast(serviceInfo)
                    resolveNext()
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = onMainThread {
                if (activeListener !== this || stopRequested) return@onMainThread
                val serviceName = serviceInfo.serviceName
                availableServices -= serviceName
                queuedServices -= serviceName
                resolveQueue.removeAll { it.serviceName == serviceName }
                if (resolvedPeers.remove(serviceName) != null) {
                    Log.i(LOG_TAG, "LAN discovery event=lost service=$serviceName")
                    publishState(DiscoveryPhase.Scanning)
                }
            }

            override fun onDiscoveryStopped(serviceType: String) = onMainThread {
                if (activeListener !== this) return@onMainThread
                Log.i(LOG_TAG, "MoQ service discovery stopped type=$serviceType")
                activeListener = null
                stopRequested = false
                clearDiscoveryData()
                releaseMulticastLock()
                if (restartAfterStop) {
                    restartAfterStop = false
                    start()
                } else {
                    mutableState.value = DiscoveryState()
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = onMainThread {
                if (activeListener !== this) return@onMainThread
                Log.w(LOG_TAG, "MoQ service discovery failed to start type=$serviceType code=$errorCode")
                failDiscovery(this, errorCode)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = onMainThread {
                if (activeListener !== this) return@onMainThread
                Log.w(LOG_TAG, "MoQ service discovery failed to stop type=$serviceType code=$errorCode")
                stopRequested = false
                restartAfterStop = false
                clearDiscoveryData()
                mutableState.value = DiscoveryState(phase = DiscoveryPhase.Failed, errorCode = errorCode)
            }
        }
    }

    private fun resolveNext() {
        if (activeListener == null || stopRequested || resolvingService != null) return

        val serviceInfo = generateSequence { resolveQueue.pollFirst() }
            .firstOrNull { it.serviceName in availableServices }
            ?: return
        val serviceName = serviceInfo.serviceName
        queuedServices -= serviceName
        resolvingService = serviceName

        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = onMainThread {
                Log.w(LOG_TAG, "Could not resolve MoQ service name=$serviceName code=$errorCode")
                finishResolve(serviceName)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) = onMainThread {
                if (activeListener != null && serviceName in availableServices) {
                    val peer = serviceInfo.toDiscoveredPeer()
                    resolvedPeers[serviceName] = peer
                    Log.i(
                        LOG_TAG,
                        "LAN discovery event=resolved service=${peer.serviceName} addresses=${peer.addresses.size} " +
                            "port=${peer.port} fingerprint=${peer.fingerprint != null} node=${peer.nodeUrl != null}",
                    )
                    publishState(DiscoveryPhase.Scanning)
                }
                finishResolve(serviceName)
            }
        }

        runCatching {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(serviceInfo, listener)
        }.onFailure { error ->
            Log.w(LOG_TAG, "Could not request MoQ service resolution name=$serviceName", error)
            finishResolve(serviceName)
        }
    }

    private fun finishResolve(serviceName: String) {
        if (resolvingService == serviceName) resolvingService = null
        resolveNext()
    }

    private fun NsdServiceInfo.toDiscoveredPeer(): DiscoveredPeer {
        val txt = attributes.mapValues { (_, value) -> value.toString(StandardCharsets.UTF_8) }
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hostAddresses.map { it.hostAddress.orEmpty() }
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(host?.hostAddress)
        }

        return DiscoveredPeer(
            serviceName = serviceName,
            addresses = addresses.filter(String::isNotBlank).distinct().sorted(),
            port = port,
            fingerprint = txt[TXT_FINGERPRINT]?.takeIf(String::isNotBlank),
            nodeUrl = txt[TXT_NODE]?.takeIf(String::isNotBlank),
            credential = txt[TXT_NONCE]?.takeIf(String::isNotBlank),
        )
    }

    private fun publishState(phase: DiscoveryPhase, errorCode: Int? = null) {
        mutableState.value = DiscoveryState(
            phase = phase,
            peers = resolvedPeers.values.sortedBy { it.serviceName.lowercase() },
            errorCode = errorCode,
        )
    }

    private fun failDiscovery(listener: NsdManager.DiscoveryListener, errorCode: Int) {
        if (activeListener !== listener) return
        runCatching { nsdManager.stopServiceDiscovery(listener) }
            .onFailure { error -> Log.d(LOG_TAG, "MoQ discovery was not active after start failure.", error) }
        activeListener = null
        stopRequested = false
        restartAfterStop = false
        clearDiscoveryData()
        releaseMulticastLock()
        mutableState.value = DiscoveryState(phase = DiscoveryPhase.Failed, errorCode = errorCode)
    }

    private fun stopInternal() {
        requestStop(restart = false)
    }

    private fun requestStop(restart: Boolean) {
        val listener = activeListener
        restartAfterStop = restart
        if (listener != null && stopRequested) {
            mutableState.value = DiscoveryState(phase = if (restart) DiscoveryPhase.Starting else DiscoveryPhase.Idle)
            return
        }
        if (listener == null) {
            clearDiscoveryData()
            releaseMulticastLock()
            mutableState.value = DiscoveryState(phase = if (restart) DiscoveryPhase.Starting else DiscoveryPhase.Idle)
            if (restart) start()
            return
        }

        stopRequested = true
        clearDiscoveryData()
        mutableState.value = DiscoveryState(phase = if (restart) DiscoveryPhase.Starting else DiscoveryPhase.Idle)
        runCatching { nsdManager.stopServiceDiscovery(listener) }
            .onFailure { error ->
                Log.w(LOG_TAG, "Could not stop MoQ service discovery.", error)
                activeListener = null
                stopRequested = false
                restartAfterStop = false
                releaseMulticastLock()
                mutableState.value = DiscoveryState(phase = DiscoveryPhase.Failed, errorCode = ERROR_STOP_EXCEPTION)
            }
    }

    private fun clearDiscoveryData() {
        availableServices.clear()
        resolvedPeers.clear()
        resolveQueue.clear()
        queuedServices.clear()
        resolvingService = null
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        multicastLock = null
    }

    private fun onMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    companion object {
        const val SERVICE_TYPE = "_moq._udp."
        private const val TXT_FINGERPRINT = "fp"
        private const val TXT_NODE = "node"
        private const val TXT_NONCE = "n"
        private const val MULTICAST_LOCK_TAG = "MoqCastNsdDiscovery"
        private const val ERROR_START_EXCEPTION = -1
        private const val ERROR_STOP_EXCEPTION = -2
        private const val LOG_TAG = "MoqAndroid"
    }
}
