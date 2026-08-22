package com.example.moqandroid.network.lan.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.moqandroid.network.lan.discovery.MoqNsdDiscovery

class MoqNsdAdvertiser(context: Context) {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val coordinator = NsdRegistrationCoordinator<RegistrationRequest>()
    private var activeListener: NsdManager.RegistrationListener? = null

    fun register(
        serviceName: String,
        port: Int,
        fingerprint: String,
        credential: String,
        onRegistered: (String) -> Unit,
        onFailure: (Int) -> Unit,
    ) = onMainThread {
        val request = RegistrationRequest(serviceName, port, fingerprint, credential, onRegistered, onFailure)
        applyTransition(coordinator.register(request))
    }

    fun unregister() = onMainThread {
        applyTransition(coordinator.stop())
    }

    private fun startRegistration(request: RegistrationRequest) {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = onMainThread {
                if (activeListener !== this) return@onMainThread
                val transition = coordinator.registered()
                transition.registered?.let { registered ->
                    Log.i(LOG_TAG, "MoQ service registered name=${serviceInfo.serviceName} port=${request.port}")
                    registered.onRegistered(serviceInfo.serviceName)
                }
                applyTransition(transition)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = onMainThread {
                if (activeListener !== this) return@onMainThread
                activeListener = null
                Log.w(LOG_TAG, "MoQ service registration failed code=$errorCode")
                val transition = coordinator.registrationFailed()
                transition.failed?.onFailure?.invoke(errorCode)
                applyTransition(transition)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = onMainThread {
                if (activeListener !== this) return@onMainThread
                activeListener = null
                Log.i(LOG_TAG, "MoQ service unregistered name=${serviceInfo.serviceName}")
                applyTransition(coordinator.unregistered())
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = onMainThread {
                if (activeListener !== this) return@onMainThread
                activeListener = null
                Log.w(LOG_TAG, "MoQ service unregistration failed code=$errorCode")
                applyTransition(coordinator.unregistered())
            }
        }
        activeListener = listener
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = request.serviceName
            serviceType = MoqNsdDiscovery.SERVICE_TYPE
            port = request.port
            setAttribute(TXT_FINGERPRINT, request.fingerprint)
            setAttribute(TXT_NONCE, request.credential)
        }
        runCatching {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            if (activeListener !== listener) return@onFailure
            activeListener = null
            Log.w(LOG_TAG, "Could not register MoQ service.", error)
            val transition = coordinator.registrationFailed()
            transition.failed?.onFailure?.invoke(ERROR_REGISTER_EXCEPTION)
            applyTransition(transition)
        }
    }

    private fun requestUnregister() {
        val listener = activeListener ?: return
        runCatching { nsdManager.unregisterService(listener) }
            .onFailure { error ->
                if (activeListener !== listener) return@onFailure
                activeListener = null
                Log.w(LOG_TAG, "Could not unregister MoQ service.", error)
                applyTransition(coordinator.unregistered())
            }
    }

    private fun applyTransition(transition: Transition<RegistrationRequest>) {
        transition.register?.let(::startRegistration)
        if (transition.unregister) requestUnregister()
    }

    private fun onMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    companion object {
        private const val TXT_FINGERPRINT = "fp"
        private const val TXT_NONCE = "n"
        private const val ERROR_REGISTER_EXCEPTION = -1
        private const val LOG_TAG = "MoqAndroid"
    }

    private data class RegistrationRequest(
        val serviceName: String,
        val port: Int,
        val fingerprint: String,
        val credential: String,
        val onRegistered: (String) -> Unit,
        val onFailure: (Int) -> Unit,
    )
}
