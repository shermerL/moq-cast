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
    private var activeListener: NsdManager.RegistrationListener? = null

    fun register(
        serviceName: String,
        port: Int,
        fingerprint: String,
        credential: String,
        onRegistered: (String) -> Unit,
        onFailure: (Int) -> Unit,
    ) = onMainThread {
        unregister()
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                if (activeListener !== this) return
                Log.i(LOG_TAG, "MoQ service registered name=${serviceInfo.serviceName} port=$port")
                onRegistered(serviceInfo.serviceName)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (activeListener !== this) return
                activeListener = null
                Log.w(LOG_TAG, "MoQ service registration failed code=$errorCode")
                onFailure(errorCode)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                if (activeListener === this) activeListener = null
                Log.i(LOG_TAG, "MoQ service unregistered name=${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (activeListener === this) activeListener = null
                Log.w(LOG_TAG, "MoQ service unregistration failed code=$errorCode")
            }
        }
        activeListener = listener
        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = MoqNsdDiscovery.SERVICE_TYPE
            this.port = port
            setAttribute(TXT_FINGERPRINT, fingerprint)
            setAttribute(TXT_NONCE, credential)
        }
        runCatching {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            activeListener = null
            Log.w(LOG_TAG, "Could not register MoQ service.", error)
            onFailure(ERROR_REGISTER_EXCEPTION)
        }
    }

    fun unregister() = onMainThread {
        val listener = activeListener ?: return@onMainThread
        activeListener = null
        runCatching { nsdManager.unregisterService(listener) }
            .onFailure { error -> Log.w(LOG_TAG, "Could not unregister MoQ service.", error) }
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
}
