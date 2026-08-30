package com.example.moqandroid.network.lan.mesh

import uniffi.moq.MoqOriginProducer

/** Shares only the active local LAN publish origin with the foreground service. */
object LanMeshOriginRegistry {
    private val lock = Any()
    private var localPublishOrigin: MoqOriginProducer? = null

    fun attachLocalPublishOrigin(value: MoqOriginProducer) = synchronized(lock) {
        localPublishOrigin = value
    }

    fun detachLocalPublishOrigin(value: MoqOriginProducer) = synchronized(lock) {
        if (localPublishOrigin === value) localPublishOrigin = null
    }

    fun currentLocalPublishOrigin(): MoqOriginProducer? = synchronized(lock) { localPublishOrigin }
}
