package com.example.moqandroid.network.lan.mesh

import uniffi.moq.MoqOriginProducer

/** Shares the active LAN mesh origin with the publishing foreground service. */
object LanMeshOriginRegistry {
    private val lock = Any()
    private var origin: MoqOriginProducer? = null

    fun attach(value: MoqOriginProducer) = synchronized(lock) {
        origin = value
    }

    fun detach(value: MoqOriginProducer) = synchronized(lock) {
        if (origin === value) origin = null
    }

    fun current(): MoqOriginProducer? = synchronized(lock) { origin }
}
